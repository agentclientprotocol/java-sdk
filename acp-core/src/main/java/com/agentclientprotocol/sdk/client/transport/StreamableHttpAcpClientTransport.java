/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Client-side ACP transport for the Streamable HTTP profile.
 *
 * <p>
 * Streamable HTTP maps ACP's logical duplex conversation onto HTTP POST requests plus
 * long-lived Server-Sent Event (SSE) streams. The transport keeps all HTTP-specific
 * routing state internal so the higher-level ACP session can continue to operate only on
 * JSON-RPC messages.
 * </p>
 *
 * @author Kaiser Dandangi
 */
public class StreamableHttpAcpClientTransport implements AcpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpAcpClientTransport.class);

	/** Default ACP path used by the remote transport RFD. */
	public static final String DEFAULT_ACP_PATH = "/acp";

	private static final String HEADER_CONNECTION_ID = "Acp-Connection-Id";

	private static final String HEADER_SESSION_ID = "Acp-Session-Id";

	private static final String CONTENT_TYPE_JSON = "application/json";

	private static final String CONTENT_TYPE_EVENT_STREAM = "text/event-stream";

	private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Controls how unknown outbound request / notification methods are classified.
	 */
	public enum RoutingMode {

		/**
		 * Prefer explicit ACP routing, but fall back to session-id shape inference for
		 * unknown methods so clients can remain forward-compatible with extensions.
		 */
		COMPATIBLE,

		/**
		 * Require every outbound request / notification method to have an explicit routing
		 * rule.
		 */
		STRICT

	}

	private enum ScopeKind {

		BOOTSTRAP,

		CONNECTION,

		SESSION

	}

	private enum RequestKind {

		INITIALIZE,

		SESSION_NEW,

		SESSION_LOAD,

		GENERIC

	}

	private record RouteScope(ScopeKind kind, String sessionId) {

		static RouteScope bootstrap() {
			return new RouteScope(ScopeKind.BOOTSTRAP, null);
		}

		static RouteScope connection() {
			return new RouteScope(ScopeKind.CONNECTION, null);
		}

		static RouteScope session(String sessionId) {
			return new RouteScope(ScopeKind.SESSION, sessionId);
		}

		boolean isSession() {
			return kind == ScopeKind.SESSION;
		}

	}

	private record OutboundRequestRoute(RequestKind kind, RouteScope requestScope, RouteScope responseScope) {
	}

	private record HttpClientBundle(HttpClient httpClient, ExecutorService ownedExecutor) {
	}

	private final URI endpointUri;

	private final AcpJsonMapper jsonMapper;

	private final HttpClient httpClient;

	private final ExecutorService ownedHttpExecutor;

	private final ExecutorService httpSignalExecutor;

	private final ExecutorService sseExecutor;

	private final Sinks.Many<JSONRPCMessage> inboundSink;

	/*
	 * A streamable HTTP client may have one connection SSE reader and multiple session
	 * SSE readers active at the same time. Reactor unicast sinks require serialized
	 * producers, so every SSE reader emits through this monitor.
	 */
	private final Object inboundEmitMonitor = new Object();

	private final AtomicBoolean connected = new AtomicBoolean(false);

	private final AtomicBoolean initialized = new AtomicBoolean(false);

	private final AtomicBoolean closing = new AtomicBoolean(false);

	// Client-originated request id -> where the eventual SSE response is expected.
	private final Map<Object, OutboundRequestRoute> outboundRequestRoutes = new ConcurrentHashMap<>();

	// Agent-originated request id -> HTTP scope required for the later client POST response.
	private final Map<Object, RouteScope> inboundRequestRoutes = new ConcurrentHashMap<>();

	private final Map<String, SseStream> sessionStreams = new ConcurrentHashMap<>();

	// Session id -> shared open operation so callers reuse one GET while opening.
	private final Map<String, Mono<Void>> sessionStreamOpenOperations = new ConcurrentHashMap<>();

	private volatile SseStream connectionStream;

	private volatile String connectionId;

	private volatile RoutingMode routingMode = RoutingMode.COMPATIBLE;

	private volatile Consumer<Throwable> exceptionHandler = t -> logger.error("Transport error", t);

	/**
	 * Creates a new Streamable HTTP client transport using a default JDK {@link HttpClient}
	 * configured with an internal {@link CookieManager}.
	 * @param endpointUri the remote ACP endpoint URI
	 * @param jsonMapper JSON mapper used for message serialization
	 */
	public StreamableHttpAcpClientTransport(URI endpointUri, AcpJsonMapper jsonMapper) {
		this(endpointUri, jsonMapper, createDefaultHttpClient());
	}

	/**
	 * Creates a new Streamable HTTP client transport using a caller-provided
	 * {@link HttpClient}. This allows advanced callers to customize cookies, TLS,
	 * executors, or proxy behavior.
	 * @param endpointUri the remote ACP endpoint URI
	 * @param jsonMapper JSON mapper used for message serialization
	 * @param httpClient HTTP client to use for requests
	 */
	public StreamableHttpAcpClientTransport(URI endpointUri, AcpJsonMapper jsonMapper, HttpClient httpClient) {
		this(endpointUri, jsonMapper, new HttpClientBundle(httpClient, null));
	}

	private StreamableHttpAcpClientTransport(URI endpointUri, AcpJsonMapper jsonMapper, HttpClientBundle bundle) {
		Assert.notNull(endpointUri, "The endpointUri can not be null");
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");
		Assert.notNull(bundle, "The HttpClient bundle can not be null");
		Assert.notNull(bundle.httpClient(), "The HttpClient can not be null");
		Assert.isTrue("http".equalsIgnoreCase(endpointUri.getScheme())
				|| "https".equalsIgnoreCase(endpointUri.getScheme()),
				"The endpointUri must use http or https");

		this.endpointUri = endpointUri;
		this.jsonMapper = jsonMapper;
		this.httpClient = bundle.httpClient();
		this.ownedHttpExecutor = bundle.ownedExecutor();
		this.httpSignalExecutor = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "acp-streamable-http-signal");
			t.setDaemon(true);
			return t;
		});
		this.sseExecutor = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "acp-streamable-http-sse");
			t.setDaemon(true);
			return t;
		});
		this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
	}

	private static HttpClientBundle createDefaultHttpClient() {
		ExecutorService executor = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "acp-streamable-http-client");
			t.setDaemon(true);
			return t;
		});
		HttpClient client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_2)
			.cookieHandler(new CookieManager())
			.executor(executor)
			.build();
		return new HttpClientBundle(client, executor);
	}

	/**
	 * Sets the routing mode for outbound request / notification classification.
	 * @param routingMode routing mode to apply
	 * @return this transport
	 */
	public StreamableHttpAcpClientTransport routingMode(RoutingMode routingMode) {
		Assert.notNull(routingMode, "The routingMode can not be null");
		this.routingMode = routingMode;
		return this;
	}

	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		Assert.notNull(handler, "The handler can not be null");
		if (!connected.compareAndSet(false, true)) {
			return Mono.error(new IllegalStateException("Already connected"));
		}

		handleIncomingMessages(handler);
		return Mono.empty();
	}

	private void handleIncomingMessages(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		this.inboundSink.asFlux()
			.flatMap(message -> Mono.just(message).transform(handler))
			.doOnNext(this::forwardHandlerEmissionForCompatibility)
			.subscribe();
	}

	private void forwardHandlerEmissionForCompatibility(JSONRPCMessage emittedMessage) {
		/*
		 * Compatibility note:
		 * WebSocketAcpClientTransport currently forwards any message emitted by the
		 * registered client handler back onto the transport. AcpClientSession also sends
		 * client responses explicitly via sendMessage(...), so the client-side contract is
		 * still ambiguous. Preserve parity for now and keep this path isolated so it can be
		 * removed cheaply if the client transport contract is later made receive-only.
		 */
		if (emittedMessage != null && !closing.get()) {
			routeAndPost(emittedMessage).subscribe(v -> {
			}, exceptionHandler);
		}
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		Assert.notNull(message, "The message can not be null");
		if (closing.get()) {
			return Mono.error(new AcpConnectionException("Transport is closing"));
		}

		if (message instanceof AcpSchema.JSONRPCRequest request
				&& AcpSchema.METHOD_INITIALIZE.equals(request.method())) {
			return initialize(request);
		}

		return routeAndPost(message);
	}

	private Mono<Void> initialize(AcpSchema.JSONRPCRequest request) {
		if (!initialized.compareAndSet(false, true)) {
			return Mono.error(new IllegalStateException("Transport is already initialized"));
		}

		HttpRequest httpRequest;
		try {
			httpRequest = jsonPostBuilder(RouteScope.bootstrap())
				.POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(request), StandardCharsets.UTF_8))
				.build();
		}
		catch (IOException e) {
			initialized.set(false);
			return Mono.error(new AcpConnectionException("Failed to serialize initialize request", e));
		}

		return sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
			.flatMap(response -> {
				if (response.statusCode() != 200) {
					return Mono.error(new AcpConnectionException(
							"Expected 200 for initialize, got " + response.statusCode()));
				}
				String contentType = response.headers().firstValue("Content-Type").orElse("");
				if (!contentType.toLowerCase().contains(CONTENT_TYPE_JSON)) {
					return Mono.error(new AcpConnectionException(
							"Expected " + CONTENT_TYPE_JSON + " initialize response, got " + contentType));
				}
				this.connectionId = response.headers()
					.firstValue(HEADER_CONNECTION_ID)
					.orElseThrow(() -> new AcpConnectionException(
							"Initialize response missing " + HEADER_CONNECTION_ID));
				JSONRPCMessage responseMessage;
				try {
					responseMessage = AcpSchema.deserializeJsonRpcMessage(jsonMapper, response.body());
				}
				catch (Exception e) {
					return Mono.error(new AcpConnectionException("Failed to deserialize initialize response", e));
				}
				return openConnectionStream().then(emitInbound(responseMessage));
			})
			.doOnError(error -> {
				initialized.set(false);
				exceptionHandler.accept(error);
			});
	}

	private Mono<Void> routeAndPost(JSONRPCMessage message) {
		return Mono.defer(() -> {
			ResolvedOutboundRoute resolved = resolveOutboundRoute(message);
			Mono<Void> preparation = prepareRoute(resolved);
			return preparation.then(postAccepted(message, resolved.scope()))
				.doOnSuccess(ignored -> {
					if (message instanceof AcpSchema.JSONRPCResponse response) {
						inboundRequestRoutes.remove(response.id());
					}
				})
				.doOnError(error -> {
					if (message instanceof AcpSchema.JSONRPCRequest request) {
						outboundRequestRoutes.remove(request.id());
					}
				});
		});
	}

	private Mono<Void> prepareRoute(ResolvedOutboundRoute resolved) {
		if (resolved.message() instanceof AcpSchema.JSONRPCRequest request
				&& AcpSchema.METHOD_SESSION_LOAD.equals(request.method())) {
			return openSessionStream(resolved.scope().sessionId());
		}
		if (resolved.scope().isSession() && !sessionStreams.containsKey(resolved.scope().sessionId())) {
			return Mono.error(new AcpConnectionException(
					"No open session stream for session " + resolved.scope().sessionId()));
		}
		return Mono.empty();
	}

	private Mono<Void> postAccepted(JSONRPCMessage message, RouteScope scope) {
		HttpRequest request;
		try {
			request = jsonPostBuilder(scope)
				.POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(message), StandardCharsets.UTF_8))
				.build();
		}
		catch (IOException e) {
			return Mono.error(new AcpConnectionException("Failed to serialize outbound message", e));
		}

		return sendAsync(request, HttpResponse.BodyHandlers.discarding())
			.flatMap(response -> {
				if (response.statusCode() != 202) {
					return Mono.error(new AcpConnectionException(
							"Expected 202 for POST, got " + response.statusCode()));
				}
				return Mono.empty();
			});
	}

	private HttpRequest.Builder jsonPostBuilder(RouteScope scope) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpointUri)
			.header("Content-Type", CONTENT_TYPE_JSON)
			.header("Accept", CONTENT_TYPE_JSON);
		addScopeHeaders(builder, scope);
		return builder;
	}

	private Mono<Void> openConnectionStream() {
		return openSseStream(RouteScope.connection()).doOnSuccess(stream -> this.connectionStream = stream).then();
	}

	private Mono<Void> openSessionStream(String sessionId) {
		return sessionStreamOpenOperations.computeIfAbsent(sessionId, this::createSessionStreamOpenMono);
	}

	private Mono<Void> createSessionStreamOpenMono(String sessionId) {
		return openSseStream(RouteScope.session(sessionId))
			.doOnSuccess(stream -> sessionStreams.putIfAbsent(sessionId, stream))
			.then()
			.doFinally(signal -> sessionStreamOpenOperations.remove(sessionId))
			.cache();
	}

	private Mono<SseStream> openSseStream(RouteScope scope) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpointUri).GET().header("Accept", CONTENT_TYPE_EVENT_STREAM);
		addScopeHeaders(builder, scope);
		HttpRequest request = builder.build();

		return sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
			.flatMap(response -> {
				if (response.statusCode() != 200) {
					return Mono.error(new AcpConnectionException(
							"Expected 200 when opening SSE stream, got " + response.statusCode()));
				}
				String contentType = response.headers().firstValue("Content-Type").orElse("");
				if (!contentType.toLowerCase().contains(CONTENT_TYPE_EVENT_STREAM)) {
					return Mono.error(new AcpConnectionException(
							"Expected " + CONTENT_TYPE_EVENT_STREAM + " response, got " + contentType));
				}
				SseStream stream = new SseStream(scope, response.body());
				stream.start();
				return Mono.just(stream);
			});
	}

	private void addScopeHeaders(HttpRequest.Builder builder, RouteScope scope) {
		if (scope.kind() != ScopeKind.BOOTSTRAP) {
			String currentConnectionId = requireConnectionId();
			builder.header(HEADER_CONNECTION_ID, currentConnectionId);
		}
		if (scope.isSession()) {
			builder.header(HEADER_SESSION_ID, scope.sessionId());
		}
	}

	private String requireConnectionId() {
		String currentConnectionId = this.connectionId;
		if (currentConnectionId == null || currentConnectionId.isBlank()) {
			throw new AcpConnectionException("Missing " + HEADER_CONNECTION_ID);
		}
		return currentConnectionId;
	}

	private ResolvedOutboundRoute resolveOutboundRoute(JSONRPCMessage message) {
		if (message instanceof AcpSchema.JSONRPCResponse response) {
			RouteScope scope = inboundRequestRoutes.get(response.id());
			if (scope == null) {
				throw new AcpConnectionException("Cannot route outbound response with unknown id " + response.id());
			}
			return new ResolvedOutboundRoute(message, scope, null);
		}

		if (message instanceof AcpSchema.JSONRPCRequest request) {
			ResolvedOutboundRoute resolved = resolveRequestOrNotificationRoute(message, request.method(), request.params());
			if (resolved.requestRoute() != null && request.id() != null) {
				outboundRequestRoutes.put(request.id(), resolved.requestRoute());
			}
			return resolved;
		}

		if (message instanceof AcpSchema.JSONRPCNotification notification) {
			return resolveRequestOrNotificationRoute(message, notification.method(), notification.params());
		}

		throw new AcpConnectionException("Unsupported outbound JSON-RPC message type: " + message);
	}

	private ResolvedOutboundRoute resolveRequestOrNotificationRoute(JSONRPCMessage message, String method, Object params) {
		RouteScope requestScope;
		RequestKind requestKind = RequestKind.GENERIC;
		RouteScope responseScope;

		switch (method) {
			case AcpSchema.METHOD_INITIALIZE:
				requestScope = RouteScope.bootstrap();
				requestKind = RequestKind.INITIALIZE;
				responseScope = RouteScope.bootstrap();
				break;
			case AcpSchema.METHOD_AUTHENTICATE:
			case AcpSchema.METHOD_SESSION_NEW:
				requestScope = RouteScope.connection();
				requestKind = AcpSchema.METHOD_SESSION_NEW.equals(method) ? RequestKind.SESSION_NEW : RequestKind.GENERIC;
				responseScope = RouteScope.connection();
				break;
			case AcpSchema.METHOD_SESSION_LOAD:
				requestScope = RouteScope.session(requireSessionId(params, method));
				requestKind = RequestKind.SESSION_LOAD;
				responseScope = RouteScope.connection();
				break;
			case AcpSchema.METHOD_SESSION_PROMPT:
			case AcpSchema.METHOD_SESSION_SET_MODE:
			case AcpSchema.METHOD_SESSION_SET_MODEL:
			case AcpSchema.METHOD_SESSION_CANCEL:
				requestScope = RouteScope.session(requireSessionId(params, method));
				responseScope = requestScope;
				break;
			default:
				Optional<String> sessionId = extractSessionId(params);
				if (routingMode == RoutingMode.STRICT) {
					throw new AcpConnectionException("No explicit routing rule for outbound method " + method);
				}
				if (sessionId.isPresent()) {
					logger.warn("Falling back to inferred session routing for unknown method '{}'", method);
					requestScope = RouteScope.session(sessionId.get());
				}
				else {
					logger.warn("Falling back to inferred connection routing for unknown method '{}'", method);
					requestScope = RouteScope.connection();
				}
				responseScope = requestScope;
		}

		OutboundRequestRoute requestRoute = null;
		if (message instanceof AcpSchema.JSONRPCRequest) {
			requestRoute = new OutboundRequestRoute(requestKind, requestScope, responseScope);
		}
		return new ResolvedOutboundRoute(message, requestScope, requestRoute);
	}

	private Optional<String> extractSessionId(Object params) {
		if (params == null) {
			return Optional.empty();
		}
		Map<?, ?> paramsMap = jsonMapper.convertValue(params, Map.class);
		Object sessionId = paramsMap.get("sessionId");
		return sessionId == null ? Optional.empty() : Optional.of(sessionId.toString());
	}

	private String requireSessionId(Object params, String method) {
		return extractSessionId(params)
			.filter(sessionId -> !sessionId.isBlank())
			.orElseThrow(() -> new AcpConnectionException("Missing sessionId for outbound method " + method));
	}

	private Mono<Void> processInbound(RouteScope actualScope, JSONRPCMessage message) {
		if (message instanceof AcpSchema.JSONRPCResponse response) {
			OutboundRequestRoute expectedRoute = outboundRequestRoutes.get(response.id());
			if (expectedRoute != null) {
				Mono<Void> processedResponse;
				if (!Objects.equals(expectedRoute.responseScope(), actualScope)) {
					processedResponse = emitInbound(errorResponse(response.id(), "Response id " + response.id()
							+ " arrived on " + actualScope + " but expected " + expectedRoute.responseScope(), null));
				}
				else if (expectedRoute.kind() == RequestKind.SESSION_NEW) {
					processedResponse = processNewSessionResponse(response);
				}
				else {
					processedResponse = emitInbound(message);
				}
				return processedResponse.doFinally(signal -> outboundRequestRoutes.remove(response.id()));
			}
			return emitInbound(message);
		}

		if (message instanceof AcpSchema.JSONRPCRequest request) {
			if (request.id() != null) {
				inboundRequestRoutes.put(request.id(), actualScope);
			}
			return emitInbound(message);
		}

		return emitInbound(message);
	}

	private Mono<Void> processNewSessionResponse(AcpSchema.JSONRPCResponse response) {
		if (response.error() != null) {
			return emitInbound(response);
		}

		String sessionId;
		try {
			AcpSchema.NewSessionResponse sessionResponse = jsonMapper.convertValue(response.result(),
					new TypeRef<AcpSchema.NewSessionResponse>() {
					});
			sessionId = sessionResponse.sessionId();
		}
		catch (Exception e) {
			return emitInbound(errorResponse(response.id(), "Failed to read session/new response", e));
		}
		if (sessionId == null || sessionId.isBlank()) {
			return emitInbound(errorResponse(response.id(), "session/new response missing sessionId", null));
		}
		return openSessionStream(sessionId)
			.then(emitInbound(response))
			.onErrorResume(error -> emitInbound(errorResponse(response.id(),
					"Failed to open session SSE stream for session " + sessionId, error)));
	}

	private AcpSchema.JSONRPCResponse errorResponse(Object id, String message, Throwable error) {
		Object data = error == null ? null : error.getMessage();
		return new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, id, null,
				new AcpSchema.JSONRPCError(AcpErrorCodes.INTERNAL_ERROR, message, data));
	}

	private Mono<Void> emitInbound(JSONRPCMessage message) {
		return Mono.fromRunnable(() -> {
			synchronized (inboundEmitMonitor) {
				Sinks.EmitResult result = inboundSink.tryEmitNext(message);
				if (result.isFailure()) {
					throw new AcpConnectionException("Failed to enqueue inbound message: " + result);
				}
			}
		});
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			closing.set(true);
			Optional.ofNullable(connectionStream).ifPresent(SseStream::close);
			sessionStreams.values().forEach(SseStream::close);

			Mono<Void> deleteRequest = Mono.empty();
			if (connectionId != null) {
				HttpRequest request = HttpRequest.newBuilder(endpointUri)
					.DELETE()
					.header(HEADER_CONNECTION_ID, connectionId)
					.build();
				deleteRequest = sendAsync(request, HttpResponse.BodyHandlers.discarding())
					.flatMap(response -> {
						if (response.statusCode() != 202) {
							return Mono.error(new AcpConnectionException(
									"Expected 202 for DELETE, got " + response.statusCode()));
						}
						return Mono.empty();
					});
			}

			return deleteRequest.doFinally(signal -> clearState());
		});
	}

	@Override
	public void close() {
		closeGracefully().block(CLOSE_TIMEOUT);
	}

	private void clearState() {
		connectionStream = null;
		sessionStreams.clear();
		sessionStreamOpenOperations.clear();
		inboundRequestRoutes.clear();
		outboundRequestRoutes.clear();
		connectionId = null;
		inboundSink.tryEmitComplete();
		sseExecutor.shutdownNow();
		httpSignalExecutor.shutdownNow();
		if (ownedHttpExecutor != null) {
			ownedHttpExecutor.shutdownNow();
		}
	}

	@Override
	public void setExceptionHandler(Consumer<Throwable> handler) {
		this.exceptionHandler = handler;
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return jsonMapper.convertValue(data, typeRef);
	}

	private record ResolvedOutboundRoute(JSONRPCMessage message, RouteScope scope, OutboundRequestRoute requestRoute) {
	}

	private <T> Mono<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
		return Mono.create(sink -> httpClient.sendAsync(request, bodyHandler).whenCompleteAsync((response, error) -> {
			if (error != null) {
				sink.error(error);
			}
			else {
				sink.success(response);
			}
		}, httpSignalExecutor));
	}

	private class SseStream {

		private final RouteScope scope;

		private final InputStream body;

		private final AtomicBoolean closed = new AtomicBoolean(false);

		private Future<?> readerTask;

		SseStream(RouteScope scope, InputStream body) {
			this.scope = scope;
			this.body = body;
		}

		void start() {
			this.readerTask = sseExecutor.submit(this::readLoop);
		}

		void close() {
			if (closed.compareAndSet(false, true)) {
				try {
					body.close();
				}
				catch (IOException ignored) {
				}
				if (readerTask != null) {
					readerTask.cancel(true);
				}
			}
		}

		private void readLoop() {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
				StringBuilder dataBuffer = new StringBuilder();
				String line;
				while (!closed.get() && (line = reader.readLine()) != null) {
					if (line.isEmpty()) {
						dispatchEvent(dataBuffer);
						dataBuffer.setLength(0);
						continue;
					}
					if (line.startsWith(":")) {
						continue;
					}
					if (line.startsWith("data:")) {
						if (!dataBuffer.isEmpty()) {
							dataBuffer.append('\n');
						}
						dataBuffer.append(line.substring(5).stripLeading());
					}
				}
				dispatchEvent(dataBuffer);
				if (!closed.get() && !closing.get()) {
					throw new AcpConnectionException("SSE stream closed unexpectedly: " + scope);
				}
			}
			catch (Exception e) {
				if (!closed.get() && !closing.get()) {
					logger.warn("SSE reader stopped for {}", scope, e);
				}
			}
		}

		private void dispatchEvent(StringBuilder dataBuffer) {
			if (dataBuffer.isEmpty()) {
				return;
			}
			try {
				JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, dataBuffer.toString());
				processInbound(scope, message).subscribe(v -> {
				}, error -> {
					if (!closed.get() && !closing.get()) {
						logger.warn("Failed to process SSE event from {}", scope, error);
					}
				});
			}
			catch (Exception e) {
				if (!closed.get() && !closing.get()) {
					logger.warn("Failed to deserialize SSE event from {}", scope, e);
				}
			}
		}

	}

}
