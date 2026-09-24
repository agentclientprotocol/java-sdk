/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpConnection.UnknownSessionException;
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
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Listener-backed ACP Streamable HTTP transport for agents.
 *
 * <p>
 * This transport hosts the ACP Streamable HTTP endpoint on Jetty, including POST/SSE
 * request handling and WebSocket upgrades on the same path. It creates one fresh agent
 * runtime per remote ACP connection through {@link AcpAgentFactory}. The accepted
 * connection then owns its own per-connection {@link RemoteAcpConnection}, while the
 * listener remains responsible only for wire-level concerns such as headers, SSE
 * streams, WebSocket frames, and request routing.
 * </p>
 *
 * <p>
 * WebSocket support is intentionally hosted here instead of as a separate public
 * listener so one {@code /acp} endpoint can behave like the RFD and the Rust
 * {@code AcpHttpServer}: HTTP requests fall through to the servlet, while valid
 * WebSocket upgrade requests are accepted by Jetty's {@link WebSocketUpgradeHandler}.
 * </p>
 *
 * @author Kaiser Dandangi
 */
public class StreamableHttpAcpAgentTransport {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpAcpAgentTransport.class);

	public static final String DEFAULT_ACP_PATH = "/acp";

	static final String HEADER_CONNECTION_ID = "Acp-Connection-Id";

	static final String HEADER_SESSION_ID = "Acp-Session-Id";

	private static final String CONTENT_TYPE_JSON = "application/json";

	static final String CONTENT_TYPE_EVENT_STREAM = "text/event-stream";

	private static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(30);

	private final int configuredPort;

	private final String path;

	private final AcpJsonMapper jsonMapper;

	private final AcpAgentFactory agentFactory;

	private final StreamableHttpAcpAgentTransportOptions options;

	private final StreamableHttpRouting routing;

	private volatile Scheduler keepAliveScheduler;

	private volatile Disposable keepAliveTask;

	private final ConcurrentMap<String, StreamableHttpConnection> connections = new ConcurrentHashMap<>();

	private final ConcurrentMap<String, WebSocketConnectionState> webSocketConnections = new ConcurrentHashMap<>();

	private final AtomicBoolean started = new AtomicBoolean(false);

	private final AtomicBoolean closing = new AtomicBoolean(false);

	private final Sinks.One<Void> terminationSink = Sinks.one();

	private volatile Server server;

	private volatile ServerConnector connector;

	/**
	 * Creates a new Streamable HTTP listener on the default ACP path.
	 * @param port port to listen on
	 * @param jsonMapper JSON mapper used for serialization
	 * @param agentFactory factory used to create one agent runtime per connection
	 */
	public StreamableHttpAcpAgentTransport(int port, AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory) {
		this(port, DEFAULT_ACP_PATH, jsonMapper, agentFactory);
	}

	/**
	 * Creates a new Streamable HTTP listener.
	 * @param port port to listen on
	 * @param path endpoint path
	 * @param jsonMapper JSON mapper used for serialization
	 * @param agentFactory factory used to create one agent runtime per connection
	 */
	public StreamableHttpAcpAgentTransport(int port, String path, AcpJsonMapper jsonMapper,
			AcpAgentFactory agentFactory) {
		this(port, path, jsonMapper, agentFactory, StreamableHttpAcpAgentTransportOptions.defaults());
	}

	/**
	 * Creates a new Streamable HTTP listener with explicit limits.
	 * @param port port to listen on
	 * @param path endpoint path
	 * @param jsonMapper JSON mapper used for serialization
	 * @param agentFactory factory used to create one agent runtime per connection
	 * @param options bounds and timings
	 */
	public StreamableHttpAcpAgentTransport(int port, String path, AcpJsonMapper jsonMapper,
			AcpAgentFactory agentFactory, StreamableHttpAcpAgentTransportOptions options) {
		Assert.isTrue(port > 0, "Port must be positive");
		Assert.hasText(path, "Path must not be empty");
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");
		Assert.notNull(agentFactory, "The agentFactory can not be null");
		Assert.notNull(options, "The options can not be null");
		this.configuredPort = port;
		this.path = path;
		this.jsonMapper = jsonMapper;
		this.agentFactory = agentFactory;
		this.options = options;
		this.routing = new StreamableHttpRouting(jsonMapper);
	}

	/**
	 * Starts the embedded Jetty server.
	 * @return a mono that completes when the listener is ready
	 */
	public Mono<Void> start() {
		return Mono.fromCallable(() -> {
			// Guard at subscribe time: a start publisher subscribed twice is refused the
			// second time instead of starting a second Jetty server.
			if (!started.compareAndSet(false, true)) {
				throw new IllegalStateException("Already started");
			}
			Server jettyServer = new Server();
			HttpConfiguration httpConfig = new HttpConfiguration();
			ServerConnector jettyConnector = new ServerConnector(jettyServer,
					new HttpConnectionFactory(httpConfig), new HTTP2CServerConnectionFactory(httpConfig));
			jettyConnector.setPort(configuredPort);
			jettyServer.addConnector(jettyConnector);

			ServletContextHandler context = new ServletContextHandler();
			context.setContextPath("/");
			context.addServlet(new ServletHolder(new AcpServlet()), path);

			WebSocketUpgradeHandler webSocketHandler = WebSocketUpgradeHandler.from(jettyServer, context, container -> {
				container.setIdleTimeout(Duration.ofMinutes(30));
				container.addMapping(path, (request, response, callback) -> {
					WebSocketConnectionState connection = createWebSocketConnection();
					try {
						connection.start();
						webSocketConnections.put(connection.id(), connection);
						response.getHeaders().put(HEADER_CONNECTION_ID, connection.id());
						return new AcpWebSocketEndpoint(connection);
					}
					catch (Exception e) {
						connection.close();
						callback.failed(e);
						return null;
					}
				});
			});
			context.insertHandler(webSocketHandler);
			jettyServer.setHandler(context);

			jettyServer.start();
			this.server = jettyServer;
			this.connector = jettyConnector;
			startKeepAlive();
			logger.info("Streamable HTTP agent listener started on port {} at path {}", getPort(), path);
			return null;
		}).then();
	}

	/**
	 * Returns the bound port.
	 * @return listener port
	 */
	public int getPort() {
		ServerConnector currentConnector = this.connector;
		return currentConnector != null ? currentConnector.getLocalPort() : configuredPort;
	}

	/**
	 * Closes all active connections and stops the listener.
	 * @return a mono that completes when shutdown finishes
	 */
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			if (!closing.compareAndSet(false, true)) {
				return Mono.<Void>empty();
			}
			List<Mono<Void>> connectionClosures = new ArrayList<>();
			connections.values().forEach(connection -> connectionClosures.add(connection.closeGracefully()));
			connections.clear();
			webSocketConnections.values().forEach(connection -> connectionClosures.add(connection.closeGracefully()));
			webSocketConnections.clear();

			return Mono.whenDelayError(connectionClosures)
				.then(Mono.<Void>fromRunnable(this::stopServer))
				.doOnSuccess(ignored -> {
					terminationSink.tryEmitValue(null);
				});
		});
	}

	private void startKeepAlive() {
		Duration interval = options.keepAliveInterval();
		if (interval.isZero()) {
			return;
		}
		Scheduler scheduler = Schedulers.newSingle("acp-streamable-http-keepalive", true);
		this.keepAliveScheduler = scheduler;
		// A comment every interval keeps proxies from cutting idle streams and surfaces
		// dead subscribers (the write fails) without waiting for the next real event.
		this.keepAliveTask = Flux.interval(interval, interval, scheduler)
			.subscribe(tick -> connections.values().forEach(StreamableHttpConnection::keepAlive));
	}

	private void stopKeepAlive() {
		Disposable task = this.keepAliveTask;
		if (task != null) {
			task.dispose();
		}
		Scheduler scheduler = this.keepAliveScheduler;
		if (scheduler != null) {
			scheduler.dispose();
		}
	}

	private void stopServer() {
		stopKeepAlive();
		Server currentServer = this.server;
		if (currentServer != null) {
			try {
				currentServer.stop();
			}
			catch (Exception e) {
				throw new AcpConnectionException("Failed to stop Streamable HTTP listener", e);
			}
		}
	}

	/**
	 * Returns a mono that completes once the listener terminates.
	 * @return termination mono
	 */
	public Mono<Void> awaitTermination() {
		return terminationSink.asMono();
	}

	int activeConnectionCount() {
		return connections.size() + webSocketConnections.size();
	}

	private StreamableHttpConnection createConnection() {
		String connectionId = UUID.randomUUID().toString();
		return new StreamableHttpConnection(connectionId, jsonMapper, agentFactory, routing, options,
				connection -> connections.remove(connection.id(), connection));
	}

	private WebSocketConnectionState createWebSocketConnection() {
		String connectionId = UUID.randomUUID().toString();
		return new WebSocketConnectionState(connectionId);
	}

	private final class AcpServlet extends HttpServlet {

		@Override
		protected void doPost(HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {
			if (!hasContentType(request, CONTENT_TYPE_JSON)) {
				writeText(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
						"Content-Type must be application/json");
				return;
			}

			long declaredLength = request.getContentLengthLong();
			if (declaredLength > options.maxPostBodyBytes()) {
				writeText(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
						"POST body exceeds " + options.maxPostBodyBytes() + " bytes");
				return;
			}
			byte[] bodyBytes = request.getInputStream().readNBytes((int) Math.min(Integer.MAX_VALUE,
					options.maxPostBodyBytes() + 1));
			if (bodyBytes.length > options.maxPostBodyBytes()) {
				writeText(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
						"POST body exceeds " + options.maxPostBodyBytes() + " bytes");
				return;
			}
			String body = new String(bodyBytes, StandardCharsets.UTF_8);
			if (body.stripLeading().startsWith("[")) {
				writeText(response, HttpServletResponse.SC_NOT_IMPLEMENTED, "JSON-RPC batches are not supported");
				return;
			}

			JSONRPCMessage message;
			try {
				message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, body);
			}
			catch (Exception e) {
				writeText(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON-RPC");
				return;
			}

			if (StreamableHttpRouting.isInitialize(message)) {
				handleInitialize(request, response, (AcpSchema.JSONRPCRequest) message);
				return;
			}

			String connectionId = header(request, HEADER_CONNECTION_ID).orElse(null);
			if (connectionId == null) {
				writeText(response, HttpServletResponse.SC_BAD_REQUEST, HEADER_CONNECTION_ID + " header required");
				return;
			}
			StreamableHttpConnection connection = connections.get(connectionId);
			if (connection == null) {
				response.setStatus(HttpServletResponse.SC_NOT_FOUND);
				return;
			}

			try {
				connection.acceptClientPost(message, header(request, HEADER_SESSION_ID).orElse(null));
				response.setStatus(HttpServletResponse.SC_ACCEPTED);
			}
			catch (UnknownSessionException e) {
				writeText(response, HttpServletResponse.SC_NOT_FOUND, e.getMessage());
			}
			catch (AcpConnectionException | IllegalArgumentException e) {
				writeText(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
			}
		}

		@Override
		protected void doGet(HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {
			if (!accepts(request, CONTENT_TYPE_EVENT_STREAM)) {
				writeText(response, HttpServletResponse.SC_NOT_ACCEPTABLE, "client must accept text/event-stream");
				return;
			}

			String connectionId = header(request, HEADER_CONNECTION_ID).orElse(null);
			if (connectionId == null) {
				writeText(response, HttpServletResponse.SC_BAD_REQUEST, HEADER_CONNECTION_ID + " header required");
				return;
			}
			StreamableHttpConnection connection = connections.get(connectionId);
			if (connection == null) {
				response.setStatus(HttpServletResponse.SC_NOT_FOUND);
				return;
			}

			try {
				connection.openStream(request, response, header(request, HEADER_SESSION_ID).orElse(null));
			}
			catch (UnknownSessionException e) {
				writeText(response, HttpServletResponse.SC_NOT_FOUND, e.getMessage());
			}
		}

		@Override
		protected void doDelete(HttpServletRequest request, HttpServletResponse response)
				throws ServletException, IOException {
			String connectionId = header(request, HEADER_CONNECTION_ID).orElse(null);
			if (connectionId == null) {
				writeText(response, HttpServletResponse.SC_BAD_REQUEST, HEADER_CONNECTION_ID + " header required");
				return;
			}
			StreamableHttpConnection connection = connections.remove(connectionId);
			if (connection == null) {
				response.setStatus(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			connection.close();
			response.setStatus(HttpServletResponse.SC_ACCEPTED);
		}

		private void handleInitialize(HttpServletRequest request, HttpServletResponse response,
				AcpSchema.JSONRPCRequest initializeRequest) throws IOException {
			if (header(request, HEADER_CONNECTION_ID).isPresent()) {
				writeText(response, HttpServletResponse.SC_BAD_REQUEST,
						"initialize must not include " + HEADER_CONNECTION_ID);
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
			try {
				if (!(initializeResponse instanceof AcpSchema.JSONRPCResponse)) {
					throw new AcpConnectionException("initialize did not produce a JSON-RPC response");
				}
				connections.put(connection.id(), connection);
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
			if (!completed.compareAndSet(false, true)) {
				return;
			}
			connections.remove(connection.id(), connection);
			connection.close();
			try {
				writeText(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "initialize failed");
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
			}
		}

	}

	private boolean hasContentType(HttpServletRequest request, String expected) {
		return Optional.ofNullable(request.getContentType())
			.map(String::toLowerCase)
			.map(contentType -> contentType.split(";", 2)[0].trim())
			.filter(contentType -> contentType.equals(expected))
			.isPresent();
	}

	private boolean accepts(HttpServletRequest request, String expected) {
		return Optional.ofNullable(request.getHeader("Accept"))
			.map(String::toLowerCase)
			.filter(accept -> accept.contains(expected))
			.isPresent();
	}

	private Optional<String> header(HttpServletRequest request, String name) {
		return Optional.ofNullable(request.getHeader(name)).filter(value -> !value.isBlank());
	}

	private void writeText(HttpServletResponse response, int status, String body) throws IOException {
		response.setStatus(status);
		response.setContentType("text/plain");
		response.getWriter().write(body);
	}

	private final class WebSocketConnectionState {

		private final String id;

		private final RemoteAcpConnection remoteConnection;

		private final AtomicBoolean initialized = new AtomicBoolean(false);

		private final AtomicBoolean closed = new AtomicBoolean(false);

		private final SerializedWebSocketSender outboundSender = new SerializedWebSocketSender();

		private volatile Session session;

		WebSocketConnectionState(String id) {
			this.id = id;
			this.remoteConnection = new RemoteAcpConnection(id, jsonMapper, this::sendToClient);
		}

		String id() {
			return id;
		}

		void start() {
			this.remoteConnection.start(agentFactory).block(INITIALIZE_TIMEOUT);
		}

		void open(Session session) {
			this.session = session;
		}

		void acceptFromClient(JSONRPCMessage message) {
			if (!initialized.get()) {
				// The WebSocket branch of the streamable endpoint has no POST
				// initialize response that can create the connection first, so the first
				// client-originated JSON-RPC message on the socket must be initialize.
				if (!StreamableHttpRouting.isInitializeRequest(message)) {
					close(StatusCode.PROTOCOL, "first ACP WebSocket message must be initialize");
					return;
				}
				initialized.set(true);
			}
			else if (message instanceof AcpSchema.JSONRPCRequest request
					&& AcpSchema.METHOD_INITIALIZE.equals(request.method())) {
				sendToClient(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), null,
						new AcpSchema.JSONRPCError(AcpErrorCodes.INVALID_REQUEST,
								"Initialize not allowed on existing connection", null)));
				return;
			}
			remoteConnection.acceptInbound(message);
		}

		void sendToClient(JSONRPCMessage message) {
			try {
				String payload = jsonMapper.writeValueAsString(message);
				logger.debug("Sending streamable ACP WebSocket message: {}", payload);
				outboundSender.send(payload);
			}
			catch (Exception e) {
				remoteConnection.signalException(e);
				close(StatusCode.SERVER_ERROR, "failed to send ACP message");
			}
		}

		Mono<Void> closeGracefully() {
			return closeGracefully(StatusCode.NORMAL, "server closing");
		}

		private Mono<Void> closeGracefully(int statusCode, String reason) {
			if (!closed.compareAndSet(false, true)) {
				return Mono.empty();
			}
			outboundSender.close();
			webSocketConnections.remove(id, this);
			Session currentSession = this.session;
			if (currentSession != null && currentSession.isOpen()) {
				currentSession.close(statusCode, reason, Callback.NOOP);
			}
			return remoteConnection.closeGracefully();
		}

		void close() {
			closeGracefully().subscribe(v -> {
			}, error -> logger.warn("Error closing Streamable ACP WebSocket connection {}", id, error));
		}

		void close(int statusCode, String reason) {
			closeGracefully(statusCode, reason).subscribe(v -> {
			}, error -> logger.warn("Error closing Streamable ACP WebSocket connection {}", id, error));
		}

		private final class SerializedWebSocketSender {

			private final Object lock = new Object();

			private final ArrayDeque<String> queue = new ArrayDeque<>();

			private boolean sendInProgress = false;

			void send(String payload) {
				boolean shouldDrain;
				synchronized (lock) {
					if (closed.get()) {
						throw new AcpConnectionException("Streamable ACP WebSocket connection is closed");
					}
					if (queue.size() >= options.maxWebSocketPendingFrames()) {
						throw new AcpConnectionException("WebSocket send queue exceeded "
								+ options.maxWebSocketPendingFrames() + " pending frames");
					}
					queue.addLast(payload);
					shouldDrain = !sendInProgress;
					if (shouldDrain) {
						sendInProgress = true;
					}
				}
				if (shouldDrain) {
					drain();
				}
			}

			/*
			 * Jetty WebSocket sessions do not allow overlapping writes. Agent messages can
			 * be produced by concurrent prompt handlers, so this per-connection queue sends
			 * exactly one frame at a time and advances only after Jetty completes the
			 * callback for the previous frame.
			 */
			private void drain() {
				String payload;
				Session currentSession;
				synchronized (lock) {
					if (closed.get()) {
						clear();
						return;
					}
					payload = queue.pollFirst();
					if (payload == null) {
						sendInProgress = false;
						return;
					}
					currentSession = session;
				}

				if (currentSession == null || !currentSession.isOpen()) {
					fail(new AcpConnectionException("Streamable ACP WebSocket connection is closed"));
					return;
				}

				try {
					currentSession.sendText(payload, Callback.from(this::drain, this::fail));
				}
				catch (Exception e) {
					fail(e);
				}
			}

			private void fail(Throwable error) {
				if (!closed.get()) {
					remoteConnection.signalException(error);
					WebSocketConnectionState.this.close(StatusCode.SERVER_ERROR, "failed to send ACP message");
				}
			}

			void close() {
				clear();
			}

			private void clear() {
				synchronized (lock) {
					queue.clear();
					sendInProgress = false;
				}
			}

		}

	}

	/**
	 * Jetty WebSocket endpoint for one WebSocket-upgraded ACP connection.
	 */
	@WebSocket
	public class AcpWebSocketEndpoint {

		private final WebSocketConnectionState connection;

		AcpWebSocketEndpoint(WebSocketConnectionState connection) {
			this.connection = connection;
		}

		@OnWebSocketOpen
		public void onOpen(Session session) {
			logger.info("Streamable ACP WebSocket client connected from {}", session.getRemoteSocketAddress());
			connection.open(session);
		}

		@OnWebSocketMessage
		public void onMessage(Session session, String message) {
			logger.debug("Received streamable ACP WebSocket message: {}", message);

			try {
				JSONRPCMessage jsonRpcMessage = AcpSchema.deserializeJsonRpcMessage(jsonMapper, message);
				connection.acceptFromClient(jsonRpcMessage);
			}
			catch (Exception e) {
				logger.warn("Closing streamable ACP WebSocket connection after invalid JSON-RPC frame", e);
				connection.close(StatusCode.PROTOCOL, "invalid JSON-RPC frame");
			}
		}

		@OnWebSocketClose
		public void onClose(Session session, int statusCode, String reason) {
			logger.info("Streamable ACP WebSocket client disconnected: {} - {}", statusCode, reason);
			connection.close(statusCode, reason);
		}

		@OnWebSocketError
		public void onError(Session session, Throwable error) {
			if (error instanceof ClosedChannelException) {
				logger.debug("Streamable ACP WebSocket channel closed");
				connection.close(StatusCode.NORMAL, "WebSocket channel closed");
				return;
			}
			logger.error("Streamable ACP WebSocket error", error);
			connection.remoteConnection.signalException(error);
			connection.close(StatusCode.SERVER_ERROR, "WebSocket error");
		}

	}

}
