/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import com.agentclientprotocol.sdk.util.Assert;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static com.agentclientprotocol.sdk.http.server.StreamableHttpRouting.HEADER_CONNECTION_ID;
import static com.agentclientprotocol.sdk.http.server.StreamableHttpRouting.HEADER_ORIGIN;
import static com.agentclientprotocol.sdk.http.server.StreamableHttpRouting.HEADER_SESSION_ID;

/**
 * The endpoint: the status table of the Streamable HTTP profile, the WebSocket handshake, the
 * connections on both, keep-alive and shutdown.
 *
 * <p>Status table. POST: a body that is not {@code application/json} 415, one over the size
 * limit 413, a JSON-RPC batch 501, other invalid input 400; {@code initialize} without
 * Acp-Connection-Id opens a connection (200 with the agent's answer and the new id, 400 with the
 * header, 503 while closing, 500 if the agent fails or does not answer within the
 * initialize timeout); any other message
 * names its connection (400 without the header, 404 for an unknown connection or session) and is
 * answered 202. GET: 406 unless the client accepts {@code text/event-stream}, else the SSE
 * stream of the connection or of a session. DELETE: 202, or 400/404 as for POST. Any method:
 * 403 for a foreign {@code Origin}; 405 for another method.
 *
 * @author Kaiser Dandangi
 * @author Mark Pollack
 */
final class DefaultAcpHttpEndpoint implements AcpHttpEndpoint {

	private static final Logger logger = LoggerFactory.getLogger(DefaultAcpHttpEndpoint.class);

	private static final String CONTENT_TYPE_JSON = "application/json";

	private static final String CONTENT_TYPE_TEXT = "text/plain";

	private final AcpJsonMapper jsonMapper;

	private final AcpAgentFactory agentFactory;

	private final StreamableHttpAcpAgentTransportOptions options;

	private final StreamableHttpRouting routing;

	private final ConcurrentMap<String, StreamableHttpConnection> connections = new ConcurrentHashMap<>();

	private final ConcurrentMap<String, WebSocketConnection> webSocketConnections = new ConcurrentHashMap<>();

	/**
	 * Connections whose {@code initialize} is still being answered, each with the action that
	 * refuses it; they join {@link #connections} once answered.
	 */
	private final ConcurrentMap<StreamableHttpConnection, Runnable> initializing = new ConcurrentHashMap<>();

	private final AtomicBoolean closing = new AtomicBoolean(false);

	private volatile Consumer<Throwable> exceptionHandler = error -> logger
		.error("Streamable HTTP ACP connection error", error);

	private volatile @Nullable Disposable keepAliveTask;

	DefaultAcpHttpEndpoint(AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory,
			StreamableHttpAcpAgentTransportOptions options) {
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");
		Assert.notNull(agentFactory, "The agentFactory can not be null");
		Assert.notNull(options, "The options can not be null");
		this.jsonMapper = jsonMapper;
		this.agentFactory = agentFactory;
		this.options = options;
		this.routing = new StreamableHttpRouting(jsonMapper);
	}

	@Override
	public StreamableHttpAcpAgentTransportOptions options() {
		return options;
	}

	@Override
	public synchronized void start() {
		Duration interval = options.keepAliveInterval();
		if (interval.isZero() || keepAliveTask != null || closing.get()) {
			return;
		}
		// A comment every interval keeps proxies from cutting idle streams and surfaces dead
		// subscribers (the write fails) without waiting for the next real event. Timed on the
		// SDK's shared timer and handed over off it, so a slow host cannot delay a timeout.
		this.keepAliveTask = Flux.interval(interval, interval, AcpSchedulers.timeouts())
			.onBackpressureDrop()
			.publishOn(AcpSchedulers.timeoutDelivery(), 1)
			.subscribe(tick -> connections.values().forEach(connection -> {
				try {
					connection.keepAlive();
				}
				catch (RuntimeException e) {
					// One broken connection must not stop keep-alives for every other one.
					logger.debug("Keep-alive failed for connection {}: {}", connection.id(), e.getMessage());
				}
			}));
	}

	@Override
	public void setExceptionHandler(Consumer<Throwable> handler) {
		Assert.notNull(handler, "The handler can not be null");
		this.exceptionHandler = handler;
	}

	private void reportException(Throwable error) {
		this.exceptionHandler.accept(error);
	}

	@Override
	public int activeConnectionCount() {
		return connections.size() + webSocketConnections.size();
	}

	// HTTP

	@Override
	public Mono<AcpHttpReply> handle(AcpHttpExchange exchange) {
		return Mono.defer(() -> {
			if (!options.isOriginAllowed(exchange.header(HEADER_ORIGIN))) {
				return Mono.just(text(403, "Origin not allowed"));
			}
			try {
				return switch (exchange.method().toUpperCase(Locale.ROOT)) {
					case "POST" -> post(exchange);
					case "GET" -> Mono.just(get(exchange));
					case "DELETE" -> Mono.just(delete(exchange));
					default -> Mono.just(new AcpHttpReply.Empty(405, Map.of("Allow", "GET, POST, DELETE")));
				};
			}
			catch (Rejection rejection) {
				return Mono.just(rejection.reply());
			}
		}).onErrorResume(error -> {
			reportException(error);
			return Mono.just(text(500, "internal error"));
		});
	}

	private Mono<AcpHttpReply> post(AcpHttpExchange exchange) throws Rejection {
		if (!hasContentType(exchange, CONTENT_TYPE_JSON)) {
			throw new Rejection(text(415, "Content-Type must be application/json"));
		}
		long maxBytes = options.maxPostBodyBytes();
		String contentLength = exchange.header("Content-Length");
		if (contentLength != null && parseLength(contentLength) > maxBytes) {
			throw bodyTooLarge();
		}
		return exchange.body(maxBytes).defaultIfEmpty(new byte[0]).flatMap(bytes -> {
			try {
				if (bytes.length > maxBytes) {
					throw bodyTooLarge();
				}
				return accept(exchange, new String(bytes, StandardCharsets.UTF_8));
			}
			catch (Rejection rejection) {
				return Mono.just(rejection.reply());
			}
		});
	}

	private static long parseLength(String value) {
		try {
			return Long.parseLong(value.trim());
		}
		catch (NumberFormatException e) {
			return -1;
		}
	}

	private Mono<AcpHttpReply> accept(AcpHttpExchange exchange, String body) throws Rejection {
		JSONRPCMessage message;
		try {
			message = parseMessage(body);
		}
		catch (Rejection rejection) {
			if (answerInvalidRequest(exchange, body)) {
				return Mono.just(accepted());
			}
			throw rejection;
		}
		if (StreamableHttpRouting.isInitialize(message)) {
			return initialize(exchange, (AcpSchema.JSONRPCRequest) message);
		}
		StreamableHttpConnection connection = requireConnection(exchange, connections::get);
		try {
			connection.acceptClientPost(message, header(exchange, HEADER_SESSION_ID));
		}
		catch (UnknownSessionException e) {
			throw new Rejection(text(404, e.getMessage()));
		}
		catch (AcpConnectionException | IllegalArgumentException e) {
			throw new Rejection(text(400, e.getMessage()));
		}
		return Mono.just(accepted());
	}

	/**
	 * A JSON object posted on a connection that is no valid JSON-RPC request (JSON-RPC 2.0
	 * section 4) is answered as JSON-RPC prescribes, -32600 Invalid Request, on the connection
	 * stream, as the TypeScript server leaves an object-shaped body to the connection to
	 * validate. A body that is not JSON, or not an object, or posted without a connection, is
	 * still refused with 400.
	 * @return whether the body was answered
	 */
	private boolean answerInvalidRequest(AcpHttpExchange exchange, String body) throws Rejection {
		if (!body.stripLeading().startsWith("{") || header(exchange, HEADER_CONNECTION_ID) == null) {
			return false;
		}
		AcpSchema.JSONRPCResponse answer = AcpSchema.unreadableMessageResponse(jsonMapper, body);
		AcpSchema.JSONRPCError error = answer.error();
		if (error == null || error.code() != AcpErrorCodes.INVALID_REQUEST) {
			return false;
		}
		requireConnection(exchange, connections::get).answerInvalid(answer);
		return true;
	}

	/** One JSON-RPC message; a batch and anything that is not JSON-RPC are rejected. */
	private JSONRPCMessage parseMessage(String body) throws Rejection {
		if (body.stripLeading().startsWith("[")) {
			throw new Rejection(text(501, "JSON-RPC batches are not supported"));
		}
		try {
			return AcpSchema.deserializeJsonRpcMessage(jsonMapper, body);
		}
		catch (Exception e) {
			throw new Rejection(text(400, "Invalid JSON-RPC"));
		}
	}

	private Rejection bodyTooLarge() {
		return new Rejection(text(413, "POST body exceeds " + options.maxPostBodyBytes() + " bytes"));
	}

	private AcpHttpReply get(AcpHttpExchange exchange) throws Rejection {
		if (!accepts(exchange, AcpHttpReply.EVENT_STREAM)) {
			throw new Rejection(text(406, "client must accept text/event-stream"));
		}
		StreamableHttpConnection connection = requireConnection(exchange, connections::get);
		try {
			return connection.openStream(header(exchange, HEADER_SESSION_ID));
		}
		catch (UnknownSessionException e) {
			return text(404, e.getMessage());
		}
	}

	private AcpHttpReply delete(AcpHttpExchange exchange) throws Rejection {
		StreamableHttpConnection connection = requireConnection(exchange, connections::remove);
		connection.close();
		return accepted();
	}

	/**
	 * The connection the request's connection header names, looked up (or removed) by
	 * {@code lookup}: refused with 400 when the header is missing, 404 when no such connection.
	 */
	private StreamableHttpConnection requireConnection(AcpHttpExchange exchange,
			Function<String, @Nullable StreamableHttpConnection> lookup) throws Rejection {
		String connectionId = header(exchange, HEADER_CONNECTION_ID);
		if (connectionId == null) {
			throw new Rejection(text(400, HEADER_CONNECTION_ID + " header required"));
		}
		StreamableHttpConnection connection = lookup.apply(connectionId);
		if (connection == null) {
			throw new Rejection(new AcpHttpReply.Empty(404, Map.of()));
		}
		return connection;
	}

	private Mono<AcpHttpReply> initialize(AcpHttpExchange exchange, AcpSchema.JSONRPCRequest request) {
		if (header(exchange, HEADER_CONNECTION_ID) != null) {
			return Mono.just(text(400, "initialize must not include " + HEADER_CONNECTION_ID));
		}
		if (closing.get()) {
			return Mono.just(shuttingDown());
		}
		StreamableHttpConnection connection = createConnection();
		InitializeAttempt attempt = new InitializeAttempt(connection);
		initializing.put(connection, attempt::refuse);
		if (closing.get()) {
			// closeGracefully() ran between the check above and the registration.
			attempt.refuse();
		}
		Mono<AcpHttpReply> answer = connection.start()
			.then(Mono.defer(() -> connection.initialize(request)))
			.timeout(options.initializeTimeout(), AcpSchedulers.timeouts())
			.map(attempt::succeeded)
			.onErrorResume(error -> Mono.just(attempt.failed(500, "initialize failed")));
		// Agent creation is cheap and the handler runs on the agent's own scheduler, so this
		// runs on the host's thread; the SDK does not use the global boundedElastic scheduler.
		return Mono.firstWithSignal(attempt.refusal.asMono(), answer)
			.doOnCancel(() -> attempt.failed(503, "initialize abandoned"));
	}

	/** One {@code initialize} being answered: exactly one outcome wins. */
	private final class InitializeAttempt {

		private final StreamableHttpConnection connection;

		private final AtomicBoolean completed = new AtomicBoolean(false);

		private final Sinks.One<AcpHttpReply> refusal = Sinks.one();

		InitializeAttempt(StreamableHttpConnection connection) {
			this.connection = connection;
		}

		void refuse() {
			refusal.tryEmitValue(failed(503, "ACP endpoint is shutting down"));
		}

		AcpHttpReply succeeded(JSONRPCMessage response) {
			if (!completed.compareAndSet(false, true)) {
				return shuttingDown();
			}
			initializing.remove(connection);
			try {
				if (!(response instanceof AcpSchema.JSONRPCResponse)) {
					throw new AcpConnectionException("initialize did not produce a JSON-RPC response");
				}
				connections.put(connection.id(), connection);
				if (closing.get()) {
					// Closing began while the agent answered: closeGracefully() may already have
					// gone through the connections, so this one is refused and closed here.
					connections.remove(connection.id(), connection);
					connection.close();
					return shuttingDown();
				}
				return new AcpHttpReply.Body(200, Map.of(HEADER_CONNECTION_ID, connection.id()), CONTENT_TYPE_JSON,
						jsonMapper.writeValueAsString(response));
			}
			catch (Exception e) {
				connections.remove(connection.id(), connection);
				connection.close();
				return text(500, "initialize failed");
			}
		}

		AcpHttpReply failed(int status, String body) {
			if (completed.compareAndSet(false, true)) {
				initializing.remove(connection);
				connections.remove(connection.id(), connection);
				connection.close();
			}
			return text(status, body);
		}

	}

	private StreamableHttpConnection createConnection() {
		return new StreamableHttpConnection(UUID.randomUUID().toString(), jsonMapper, agentFactory, routing, options,
				new StreamableHttpConnection.Owner(connection -> connections.remove(connection.id(), connection),
						this::reportException));
	}

	// WebSocket

	@Override
	public AcpWsHandshake webSocketHandshake(AcpHttpExchange handshake) {
		if (!options.isOriginAllowed(handshake.header(HEADER_ORIGIN))) {
			return new AcpWsHandshake.Refused(text(403, "Origin not allowed"));
		}
		if (closing.get()) {
			return new AcpWsHandshake.Refused(shuttingDown());
		}
		return new Acceptance(UUID.randomUUID().toString());
	}

	/** An accepted upgrade; the connection exists from {@link #open} on. */
	private final class Acceptance implements AcpWsHandshake.Accepted {

		private final String connectionId;

		private final AtomicBoolean opened = new AtomicBoolean(false);

		Acceptance(String connectionId) {
			this.connectionId = connectionId;
		}

		@Override
		public Map<String, String> headers() {
			return Map.of(HEADER_CONNECTION_ID, connectionId);
		}

		@Override
		public long maxTextMessageBytes() {
			return options.maxPostBodyBytes();
		}

		@Override
		public Duration idleTimeout() {
			return options.webSocketIdleTimeout();
		}

		@Override
		public AcpWsHandler open(AcpWsOutbound outbound) {
			Assert.notNull(outbound, "The outbound can not be null");
			if (!opened.compareAndSet(false, true)) {
				throw new IllegalStateException("Already opened");
			}
			WebSocketConnection connection = new WebSocketConnection(connectionId, jsonMapper, options, outbound,
					new WebSocketConnection.Owner(closed -> webSocketConnections.remove(closed.id(), closed),
							DefaultAcpHttpEndpoint.this::reportException),
					WebSocketConnection.Timing.SHARED);
			webSocketConnections.put(connectionId, connection);
			if (closing.get()) {
				// Shutdown began during the upgrade: closeGracefully() may have gone through
				// the connections already.
				connection.closeForShutdown().subscribe(v -> {
				}, error -> logger.debug("Error closing ACP WebSocket connection {}", connectionId, error));
				return connection;
			}
			logger.debug("ACP WebSocket connection {} opened", connectionId);
			connection.start(agentFactory);
			return connection;
		}

	}

	// Lifecycle

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			if (!closing.compareAndSet(false, true)) {
				return Mono.empty();
			}
			List.copyOf(initializing.values()).forEach(Runnable::run);
			Disposable task = this.keepAliveTask;
			if (task != null) {
				task.dispose();
			}
			List<StreamableHttpConnection> closingConnections = List.copyOf(connections.values());
			connections.clear();
			List<WebSocketConnection> closingWebSockets = List.copyOf(webSocketConnections.values());
			webSocketConnections.clear();
			List<Mono<Void>> closures = new ArrayList<>();
			closingConnections.forEach(connection -> closures.add(connection.closeForShutdown()));
			closingWebSockets.forEach(connection -> closures.add(connection.closeForShutdown()));
			Duration timeout = options.shutdownTimeout();
			return Mono.whenDelayError(closures)
				.timeout(timeout, AcpSchedulers.timeouts())
				.onErrorResume(TimeoutException.class, timedOut -> {
					logger.warn("ACP connections did not close within {}; closing them now", timeout);
					closingConnections.forEach(StreamableHttpConnection::closeNow);
					closingWebSockets.forEach(WebSocketConnection::closeNow);
					return Mono.empty();
				})
				.onErrorResume(error -> {
					reportException(error);
					return Mono.empty();
				});
		});
	}

	// Helpers

	private static AcpHttpReply.Body text(int status, @Nullable String body) {
		return new AcpHttpReply.Body(status, Map.of(), CONTENT_TYPE_TEXT, body != null ? body : "");
	}

	private static AcpHttpReply accepted() {
		return new AcpHttpReply.Empty(202, Map.of());
	}

	private static AcpHttpReply shuttingDown() {
		return text(503, "ACP endpoint is shutting down");
	}

	private static boolean hasContentType(AcpHttpExchange exchange, String expected) {
		String contentType = exchange.header("Content-Type");
		return contentType != null
				&& contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim().equals(expected);
	}

	private static boolean accepts(AcpHttpExchange exchange, String expected) {
		String accept = exchange.header("Accept");
		return accept != null && accept.toLowerCase(Locale.ROOT).contains(expected);
	}

	private static @Nullable String header(AcpHttpExchange exchange, String name) {
		String value = exchange.header(name);
		return (value == null || value.isBlank()) ? null : value;
	}

	/** A request refused with a reply; thrown so each method states the happy path once. */
	private static final class Rejection extends Exception {

		private static final long serialVersionUID = 1L;

		private final transient AcpHttpReply reply;

		Rejection(AcpHttpReply reply) {
			super(null, null, false, false);
			this.reply = reply;
		}

		AcpHttpReply reply() {
			return reply;
		}

	}

}
