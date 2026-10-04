/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import com.agentclientprotocol.sdk.util.Assert;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.CONTENT_TYPE_EVENT_STREAM;
import static com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.HEADER_CONNECTION_ID;
import static com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.HEADER_SESSION_ID;
import static com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.INITIALIZE_TIMEOUT;

/**
 * The ACP Streamable HTTP endpoint as a servlet, for serving remote clients from a Servlet 6
 * container you already run, such as Spring Boot, Tomcat, Jetty or Undertow. It creates a
 * fresh agent for each client connection with an {@link AcpAgentFactory}: a POST of
 * {@code initialize} opens a connection, later POSTs carry the client's messages, a GET opens
 * the connection's or a session's SSE stream for the agent's messages, and a DELETE closes
 * the connection. Use it when you have a container; for a stand-alone
 * agent use {@link StreamableHttpAcpAgentTransport}, which mounts this servlet on a Jetty
 * server of its own and also accepts WebSocket upgrades, which this servlet does not.
 *
 * <p>Register it at the path of your choice, with async support on:
 *
 * <pre>{@code
 * void register(jakarta.servlet.ServletContext servletContext, AcpAgentFactory agentFactory) {
 *     var servlet = new StreamableHttpAcpServlet(AcpJsonMapper.createDefault(), agentFactory);
 *     jakarta.servlet.ServletRegistration.Dynamic registration =
 *         servletContext.addServlet("acp", servlet);
 *     registration.addMapping("/acp");
 *     registration.setAsyncSupported(true);
 * }
 * }</pre>
 *
 * <p>The container's lifecycle drives the servlet: {@link #init()} starts the SSE keep-alive
 * and {@link #destroy()} closes every connection, cancelling in-flight prompts. HTTP/2 and TLS
 * are the container's to configure. Agents from one factory run concurrently, so whatever they
 * share must be thread-safe. The servlet has no authentication of its own: protect its path
 * as you would any other endpoint.
 *
 * <p><b>Shutting down.</b> Closing ({@link #closeGracefully()}, or {@link #destroy()} when the
 * container takes the servlet out of service) refuses new connections, answers an
 * {@code initialize} still in flight with 503, cancels in-flight prompts and completes every
 * open SSE response; it never waits for a client to read or answer anything. It finishes
 * within the {@linkplain StreamableHttpAcpAgentTransportOptions#shutdownTimeout() shutdown
 * timeout} (5 seconds by default): a connection whose agent has not closed by then is closed
 * at once.
 *
 * <p><b>Close before a graceful container shutdown.</b> Every SSE stream a client holds open
 * is an asynchronous request in flight, and a container that shuts down gracefully waits for
 * those before it destroys servlets. Spring Boot shuts down gracefully by default
 * ({@code server.shutdown=graceful}), so with a client connected it waits its whole
 * {@code spring.lifecycle.timeout-per-shutdown-phase} (30 seconds) before {@code destroy()} is
 * even called. Call {@link #closeGracefully()} before the server stops instead, for instance
 * from a {@code SmartLifecycle} in the default phase, which Spring stops before the web
 * server's graceful shutdown:
 *
 * <pre>{@code
 * private StreamableHttpAcpServlet servlet; // the servlet registered above
 *
 * // SmartLifecycle.stop(), in the default phase
 * public void stop() {
 *     servlet.closeGracefully().block();
 * }
 * }</pre>
 *
 * @author Kaiser Dandangi
 */
public class StreamableHttpAcpServlet extends HttpServlet {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpAcpServlet.class);

	private static final String CONTENT_TYPE_JSON = "application/json";

	private final transient AcpJsonMapper jsonMapper;

	private final transient StreamableHttpAcpAgentTransportOptions options;

	private final transient ConcurrentMap<String, StreamableHttpConnection> connections = new ConcurrentHashMap<>();

	private final transient AcpAgentFactory agentFactory;

	private final transient StreamableHttpRouting routing;

	private final transient AtomicBoolean closing = new AtomicBoolean(false);

	/**
	 * Connections whose {@code initialize} is still being answered, each with the action
	 * that refuses it; they join {@link #connections} once answered.
	 */
	private final transient ConcurrentMap<StreamableHttpConnection, Runnable> initializing = new ConcurrentHashMap<>();

	private transient volatile Consumer<Throwable> exceptionHandler = error -> logger
		.error("Streamable HTTP ACP connection error", error);

	private transient volatile @Nullable Disposable keepAliveTask;

	/**
	 * Creates a servlet with the default limits and timings and the default JSON mapper
	 * ({@link AcpJsonMapper#createDefault()}).
	 * @param agentFactory creates the agent for each connection
	 * @throws IllegalArgumentException if {@code agentFactory} is null
	 */
	public StreamableHttpAcpServlet(AcpAgentFactory agentFactory) {
		this(AcpJsonMapper.createDefault(), agentFactory);
	}

	/**
	 * Creates a servlet with the default limits and timings.
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @param agentFactory creates the agent for each connection
	 * @throws IllegalArgumentException if an argument is null
	 */
	public StreamableHttpAcpServlet(AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory) {
		this(jsonMapper, agentFactory, StreamableHttpAcpAgentTransportOptions.defaults());
	}

	/**
	 * Creates a servlet with the limits and timings of {@code options}.
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @param agentFactory creates the agent for each connection
	 * @param options the endpoint's limits and timings;
	 * {@code maxConcurrentStreamsPerConnection} is the container's to configure when this
	 * servlet is mounted in a container
	 * @throws IllegalArgumentException if an argument is null
	 */
	public StreamableHttpAcpServlet(AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory,
			StreamableHttpAcpAgentTransportOptions options) {
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");
		Assert.notNull(agentFactory, "The agentFactory can not be null");
		Assert.notNull(options, "The options can not be null");
		this.jsonMapper = jsonMapper;
		this.agentFactory = agentFactory;
		this.options = options;
		this.routing = new StreamableHttpRouting(jsonMapper);
	}

	/**
	 * Starts the SSE keep-alive, unless its interval is zero. The container calls it when it
	 * puts the servlet into service.
	 */
	@Override
	public void init() throws ServletException {
		super.init();
		Duration interval = options.keepAliveInterval();
		if (interval.isZero() || keepAliveTask != null) {
			return;
		}
		// A comment every interval keeps proxies from cutting idle streams and surfaces
		// dead subscribers (the write fails) without waiting for the next real event. Timed
		// on the SDK's shared timer, and written off it, so a slow write cannot delay a
		// timeout; no thread of the servlet's own.
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

	/**
	 * Closes every connection and stops the keep-alive, as {@link #closeGracefully()} does,
	 * within the shutdown timeout. The container calls it when it takes the servlet out of
	 * service.
	 */
	@Override
	public void destroy() {
		try {
			// closeGracefully() is itself bounded by the shutdown timeout; the margin only
			// guards against a close that does not even get to start.
			closeGracefully().block(options.shutdownTimeout().plusSeconds(1));
		}
		catch (RuntimeException e) {
			logger.warn("Streamable HTTP servlet did not close within {}: {}", options.shutdownTimeout(),
					e.getMessage());
		}
		super.destroy();
	}

	/**
	 * Closes every connection this servlet holds, cancelling in-flight prompts and completing
	 * their SSE responses, answers an {@code initialize} still in flight with 503, and stops
	 * the keep-alive; new connections are refused from then on. A connection whose agent has
	 * not closed within the
	 * {@linkplain StreamableHttpAcpAgentTransportOptions#shutdownTimeout() shutdown timeout}
	 * is closed at once. Nothing here waits for a client. Only the first call has an effect.
	 * @return a Mono that completes when every connection has closed, at the latest after the
	 * shutdown timeout
	 */
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
			List<Mono<Void>> closures = new ArrayList<>();
			closingConnections.forEach(connection -> closures.add(connection.closeGracefully()));
			Duration timeout = options.shutdownTimeout();
			return Mono.whenDelayError(closures)
				.timeout(timeout, AcpSchedulers.timeouts())
				.onErrorResume(TimeoutException.class, timedOut -> {
					logger.warn("Streamable HTTP connections did not close within {}; closing them now", timeout);
					closingConnections.forEach(StreamableHttpConnection::closeNow);
					return Mono.empty();
				});
		});
	}

	/**
	 * Sets the handler for the transport errors of every connection this servlet holds,
	 * including those opened before the call. The default logs them. An agent factory may
	 * still install its own handler on the transport it is given.
	 * @param handler receives the connections' transport errors
	 */
	public void setExceptionHandler(Consumer<Throwable> handler) {
		Assert.notNull(handler, "The handler can not be null");
		this.exceptionHandler = handler;
	}

	/** Reports a connection's transport error to the current exception handler. */
	void reportException(Throwable error) {
		this.exceptionHandler.accept(error);
	}

	/**
	 * Returns the number of client connections this servlet holds, not counting those whose
	 * {@code initialize} is still being answered.
	 * @return the number of open connections
	 */
	public int activeConnectionCount() {
		return connections.size();
	}

	private StreamableHttpConnection createConnection() {
		return new StreamableHttpConnection(UUID.randomUUID().toString(), jsonMapper, agentFactory, routing, options,
				new StreamableHttpConnection.Owner(connection -> connections.remove(connection.id(), connection),
						this::reportException));
	}

	/**
	 * Accepts one JSON-RPC message from a client. An {@code initialize} request without an
	 * Acp-Connection-Id header opens a connection: it is answered 200 with the agent's
	 * response and the new connection's id in that header, 503 while the servlet shuts down,
	 * or 500 if the agent fails or takes more than 30 seconds to answer. Any other message
	 * names its connection in the Acp-Connection-Id header and is answered 202; the agent's
	 * answer comes on an SSE stream, as does the -32600 error for a JSON object that is no
	 * valid JSON-RPC request. A body that is not {@code application/json} gets 415, one over
	 * the size limit 413, a JSON-RPC batch 501, other invalid input 400, and an unknown
	 * connection or session 404.
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		try {
			String body = readJsonBody(request);
			JSONRPCMessage message;
			try {
				message = parseMessage(body);
			}
			catch (Rejection rejection) {
				if (answerInvalidRequest(request, body)) {
					response.setStatus(HttpServletResponse.SC_ACCEPTED);
					return;
				}
				throw rejection;
			}
			if (StreamableHttpRouting.isInitialize(message)) {
				handleInitialize(request, response, (AcpSchema.JSONRPCRequest) message);
				return;
			}
			StreamableHttpConnection connection = requireConnection(request, connections::get);
			acceptClientPost(connection, message, header(request, HEADER_SESSION_ID).orElse(null));
			response.setStatus(HttpServletResponse.SC_ACCEPTED);
		}
		catch (Rejection rejection) {
			rejection.writeTo(response);
		}
	}

	/**
	 * Opens an SSE stream that carries the agent's messages: the connection's stream, or, with
	 * an Acp-Session-Id header, that session's. The request must accept
	 * {@code text/event-stream} (406 otherwise) and name its connection in the
	 * Acp-Connection-Id header (400 without it, 404 for an unknown one); an unknown session
	 * is answered 404.
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		try {
			if (!accepts(request, CONTENT_TYPE_EVENT_STREAM)) {
				throw new Rejection(HttpServletResponse.SC_NOT_ACCEPTABLE, "client must accept text/event-stream");
			}
			StreamableHttpConnection connection = requireConnection(request, connections::get);
			connection.openStream(request, response, header(request, HEADER_SESSION_ID).orElse(null));
		}
		catch (UnknownSessionException e) {
			writeText(response, HttpServletResponse.SC_NOT_FOUND, e.getMessage());
		}
		catch (Rejection rejection) {
			rejection.writeTo(response);
		}
	}

	/**
	 * Closes the connection named in the Acp-Connection-Id header, and its agent, and answers
	 * 202; 400 without the header, 404 for an unknown connection.
	 */
	@Override
	protected void doDelete(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		try {
			StreamableHttpConnection connection = requireConnection(request, connections::remove);
			connection.close();
			response.setStatus(HttpServletResponse.SC_ACCEPTED);
		}
		catch (Rejection rejection) {
			rejection.writeTo(response);
		}
	}

	/**
	 * A JSON object posted on a connection that is no valid JSON-RPC request (JSON-RPC 2.0
	 * section 4: a method that is not a string, an id that is not a string, number or null,
	 * jsonrpc other than "2.0") is answered as JSON-RPC prescribes, -32600 Invalid Request,
	 * on the connection stream, as the TypeScript server leaves an object-shaped body to the
	 * connection to validate. A body that is not JSON, or not an object, or posted without a
	 * connection, is still refused with 400.
	 * @return whether the body was answered
	 */
	private boolean answerInvalidRequest(HttpServletRequest request, String body) throws Rejection {
		if (!body.stripLeading().startsWith("{") || header(request, HEADER_CONNECTION_ID).isEmpty()) {
			return false;
		}
		AcpSchema.JSONRPCResponse answer = AcpSchema.unreadableMessageResponse(jsonMapper, body);
		AcpSchema.JSONRPCError error = answer.error();
		if (error == null || error.code() != AcpErrorCodes.INVALID_REQUEST) {
			return false;
		}
		requireConnection(request, connections::get).answerInvalid(answer);
		return true;
	}

	/** The body of a JSON POST, rejecting a wrong content type and an oversized body, declared or actual. */
	private String readJsonBody(HttpServletRequest request) throws IOException, Rejection {
		if (!hasContentType(request, CONTENT_TYPE_JSON)) {
			throw new Rejection(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json");
		}
		long maxBytes = options.maxPostBodyBytes();
		if (request.getContentLengthLong() > maxBytes) {
			throw bodyTooLarge();
		}
		byte[] bodyBytes = request.getInputStream().readNBytes((int) Math.min(Integer.MAX_VALUE, maxBytes + 1));
		if (bodyBytes.length > maxBytes) {
			throw bodyTooLarge();
		}
		return new String(bodyBytes, StandardCharsets.UTF_8);
	}

	/** One JSON-RPC message; a batch and anything that is not JSON-RPC are rejected. */
	private JSONRPCMessage parseMessage(String body) throws Rejection {
		if (body.stripLeading().startsWith("[")) {
			throw new Rejection(HttpServletResponse.SC_NOT_IMPLEMENTED, "JSON-RPC batches are not supported");
		}
		try {
			return AcpSchema.deserializeJsonRpcMessage(jsonMapper, body);
		}
		catch (Exception e) {
			throw new Rejection(HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON-RPC");
		}
	}

	private Rejection bodyTooLarge() {
		return new Rejection(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
				"POST body exceeds " + options.maxPostBodyBytes() + " bytes");
	}

	/**
	 * The connection the request's connection header names, looked up (or removed) by
	 * {@code lookup}: rejected with 400 when the header is missing, 404 when no such connection.
	 */
	private StreamableHttpConnection requireConnection(HttpServletRequest request,
			Function<String, @Nullable StreamableHttpConnection> lookup) throws Rejection {
		String connectionId = header(request, HEADER_CONNECTION_ID)
			.orElseThrow(() -> new Rejection(HttpServletResponse.SC_BAD_REQUEST,
					HEADER_CONNECTION_ID + " header required"));
		StreamableHttpConnection connection = lookup.apply(connectionId);
		if (connection == null) {
			throw Rejection.statusOnly(HttpServletResponse.SC_NOT_FOUND);
		}
		return connection;
	}

	private static void acceptClientPost(StreamableHttpConnection connection, JSONRPCMessage message,
			@Nullable String sessionId) throws Rejection {
		try {
			connection.acceptClientPost(message, sessionId);
		}
		catch (UnknownSessionException e) {
			throw new Rejection(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
		}
		catch (AcpConnectionException | IllegalArgumentException e) {
			throw new Rejection(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
		}
	}

	private void handleInitialize(HttpServletRequest request, HttpServletResponse response,
			AcpSchema.JSONRPCRequest initializeRequest) throws IOException {
		if (header(request, HEADER_CONNECTION_ID).isPresent()) {
			writeText(response, HttpServletResponse.SC_BAD_REQUEST,
					"initialize must not include " + HEADER_CONNECTION_ID);
			return;
		}

		if (closing.get()) {
			writeText(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ACP endpoint is shutting down");
			return;
		}
		StreamableHttpConnection connection = createConnection();
		AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(INITIALIZE_TIMEOUT.toMillis());
		AtomicBoolean completed = new AtomicBoolean(false);
		asyncContext.addListener(new AsyncListener() {

			@Override
			public void onComplete(AsyncEvent event) {
			}

			@Override
			public void onTimeout(AsyncEvent event) {
				completeInitializeFailure(asyncContext, response, connection, completed);
			}

			@Override
			public void onError(AsyncEvent event) {
				completeInitializeFailure(asyncContext, response, connection, completed);
			}

			@Override
			public void onStartAsync(AsyncEvent event) {
				event.getAsyncContext().addListener(this);
			}

		});

		initializing.put(connection, () -> completeInitializeFailure(asyncContext, response, connection, completed,
				HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ACP endpoint is shutting down"));
		if (closing.get()) {
			// closeGracefully() ran between the check above and the registration.
			completeInitializeFailure(asyncContext, response, connection, completed,
					HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ACP endpoint is shutting down");
			return;
		}
		connection.start()
			.then(Mono.defer(() -> connection.initialize(initializeRequest)))
			.timeout(INITIALIZE_TIMEOUT, AcpSchedulers.timeouts())
			// Runs on the servlet thread: the request is already async, agent creation
			// is cheap, and the handler runs on the agent's own scheduler. The SDK does
			// not use the global boundedElastic scheduler.
			.subscribe(initializeResponse -> completeInitializeSuccess(asyncContext, response, connection,
					completed, initializeResponse),
				error -> completeInitializeFailure(asyncContext, response, connection, completed));
	}

	private void completeInitializeSuccess(AsyncContext asyncContext, HttpServletResponse response,
			StreamableHttpConnection connection, AtomicBoolean completed, JSONRPCMessage initializeResponse) {
		if (!completed.compareAndSet(false, true)) {
			return;
		}
		initializing.remove(connection);
		try {
			if (!(initializeResponse instanceof AcpSchema.JSONRPCResponse)) {
				throw new AcpConnectionException("initialize did not produce a JSON-RPC response");
			}
			connections.put(connection.id(), connection);
			if (closing.get()) {
				// Closing began while the agent answered: closeGracefully() may already have
				// gone through the connections, so this one is refused and closed here.
				connections.remove(connection.id(), connection);
				connection.close();
				writeText(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ACP endpoint is shutting down");
				return;
			}
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType(CONTENT_TYPE_JSON);
			response.setHeader(HEADER_CONNECTION_ID, connection.id());
			response.getWriter().write(jsonMapper.writeValueAsString(initializeResponse));
		}
		catch (Exception e) {
			connections.remove(connection.id(), connection);
			connection.close();
			try {
				writeText(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "initialize failed");
			}
			catch (IOException writeError) {
				logger.warn("Failed to write Streamable HTTP initialize failure", writeError);
			}
		}
		finally {
			completeAsyncContext(asyncContext);
		}
	}

	private void completeInitializeFailure(AsyncContext asyncContext, HttpServletResponse response,
			StreamableHttpConnection connection, AtomicBoolean completed) {
		completeInitializeFailure(asyncContext, response, connection, completed,
				HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "initialize failed");
	}

	private void completeInitializeFailure(AsyncContext asyncContext, HttpServletResponse response,
			StreamableHttpConnection connection, AtomicBoolean completed, int status, String body) {
		if (!completed.compareAndSet(false, true)) {
			return;
		}
		initializing.remove(connection);
		connections.remove(connection.id(), connection);
		connection.close();
		try {
			writeText(response, status, body);
		}
		catch (IOException writeError) {
			logger.warn("Failed to write Streamable HTTP initialize failure", writeError);
		}
		finally {
			completeAsyncContext(asyncContext);
		}
	}

	private void completeAsyncContext(AsyncContext asyncContext) {
		try {
			asyncContext.complete();
		}
		catch (IllegalStateException ignored) {
			// Already completed, or completed by the container after a timeout or a
			// client disconnect: there is nothing left to complete.
		}
	}

	private boolean hasContentType(HttpServletRequest request, String expected) {
		return Optional.ofNullable(request.getContentType())
			.map(value -> value.toLowerCase(Locale.ROOT))
			.map(contentType -> contentType.split(";", 2)[0].trim())
			.filter(contentType -> contentType.equals(expected))
			.isPresent();
	}

	private boolean accepts(HttpServletRequest request, String expected) {
		return Optional.ofNullable(request.getHeader("Accept"))
			.map(value -> value.toLowerCase(Locale.ROOT))
			.filter(accept -> accept.contains(expected))
			.isPresent();
	}

	private Optional<String> header(HttpServletRequest request, String name) {
		return Optional.ofNullable(request.getHeader(name)).filter(value -> !value.isBlank());
	}

	private static void writeText(HttpServletResponse response, int status, @Nullable String body) throws IOException {
		response.setStatus(status);
		response.setContentType("text/plain");
		if (body != null) {
			response.getWriter().write(body);
		}
	}

	/**
	 * A request refused with an HTTP status and an optional plain-text body. Thrown by the
	 * request-reading helpers so each {@code do*} method states the happy path once.
	 */
	private static final class Rejection extends Exception {

		private static final long serialVersionUID = 1L;

		private final int status;

		private final @Nullable String body;

		private final boolean hasBody;

		/** A refusal with a plain-text body (none written when {@code body} is null). */
		Rejection(int status, @Nullable String body) {
			this(status, body, true);
		}

		private Rejection(int status, @Nullable String body, boolean hasBody) {
			super(body, null, false, false);
			this.status = status;
			this.body = body;
			this.hasBody = hasBody;
		}

		/** A refusal that sets the status only. */
		static Rejection statusOnly(int status) {
			return new Rejection(status, null, false);
		}

		void writeTo(HttpServletResponse response) throws IOException {
			if (hasBody) {
				writeText(response, status, body);
			}
			else {
				response.setStatus(status);
			}
		}

	}

}
