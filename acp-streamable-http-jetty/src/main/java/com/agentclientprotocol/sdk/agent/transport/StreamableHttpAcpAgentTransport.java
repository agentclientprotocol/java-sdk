/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.Assert;
import jakarta.servlet.AsyncContext;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Listener-backed ACP Streamable HTTP transport for agents.
 *
 * <p>
 * This transport hosts a Jetty HTTP endpoint and creates one fresh agent runtime per
 * remote ACP connection through {@link AcpAgentFactory}. The accepted connection then
 * owns its own per-connection {@link RemoteAcpConnection}, while the listener remains
 * responsible only for HTTP concerns such as headers, SSE streams, and request routing.
 * </p>
 *
 * <p>
 * Streamable HTTP and the RFD-compliant remote WebSocket listener share
 * {@link RemoteAcpConnection}; this class keeps HTTP-specific routing, headers, SSE
 * stream ownership, and replay behavior local to the HTTP adapter.
 * </p>
 *
 * @author Kaiser Dandangi
 */
public class StreamableHttpAcpAgentTransport {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpAcpAgentTransport.class);

	public static final String DEFAULT_ACP_PATH = "/acp";

	private static final String HEADER_CONNECTION_ID = "Acp-Connection-Id";

	private static final String HEADER_SESSION_ID = "Acp-Session-Id";

	private static final String CONTENT_TYPE_JSON = "application/json";

	private static final String CONTENT_TYPE_EVENT_STREAM = "text/event-stream";

	private static final int MAX_REPLAY_EVENTS = 1024;

	private static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(30);

	/**
	 * Controls whether unknown message methods may fall back to shape-based routing.
	 */
	public enum RoutingMode {

		/**
		 * Prefer explicit ACP routing and fall back to session-id shape inference for
		 * extension methods. Also permits provisional session streams before
		 * {@code session/load} so the currently ambiguous resume flow can work.
		 */
		COMPATIBLE,

		/**
		 * Require explicit routing rules and reject unknown session streams.
		 */
		STRICT

	}

	private enum ScopeKind {

		CONNECTION,

		SESSION

	}

	private enum RequestKind {

		INITIALIZE,

		SESSION_NEW,

		SESSION_LOAD,

		GENERIC

	}

	private enum SessionState {

		PENDING_LOAD,

		KNOWN

	}

	private record RouteScope(ScopeKind kind, String sessionId) {

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

	private record ClientRequestRoute(RequestKind kind, RouteScope requestScope, RouteScope responseScope) {
	}

	private record ResolvedInboundRoute(JSONRPCMessage message, RouteScope requestScope,
			ClientRequestRoute requestRoute) {
	}

	private final int configuredPort;

	private final String path;

	private final AcpJsonMapper jsonMapper;

	private final AcpAgentFactory agentFactory;

	private final ConcurrentMap<String, ConnectionState> connections = new ConcurrentHashMap<>();

	private final AtomicBoolean started = new AtomicBoolean(false);

	private final AtomicBoolean closing = new AtomicBoolean(false);

	private final Sinks.One<Void> terminationSink = Sinks.one();

	private volatile RoutingMode routingMode = RoutingMode.COMPATIBLE;

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
		Assert.isTrue(port > 0, "Port must be positive");
		Assert.hasText(path, "Path must not be empty");
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");
		Assert.notNull(agentFactory, "The agentFactory can not be null");
		this.configuredPort = port;
		this.path = path;
		this.jsonMapper = jsonMapper;
		this.agentFactory = agentFactory;
	}

	/**
	 * Sets the routing mode used by the listener.
	 * @param routingMode routing mode to use
	 * @return this transport
	 */
	public StreamableHttpAcpAgentTransport routingMode(RoutingMode routingMode) {
		Assert.notNull(routingMode, "The routingMode can not be null");
		this.routingMode = routingMode;
		return this;
	}

	/**
	 * Starts the embedded Jetty server.
	 * @return a mono that completes when the listener is ready
	 */
	public Mono<Void> start() {
		if (!started.compareAndSet(false, true)) {
			return Mono.error(new IllegalStateException("Already started"));
		}

		return Mono.fromCallable(() -> {
			Server jettyServer = new Server();
			HttpConfiguration httpConfig = new HttpConfiguration();
			ServerConnector jettyConnector = new ServerConnector(jettyServer,
					new HttpConnectionFactory(httpConfig), new HTTP2CServerConnectionFactory(httpConfig));
			jettyConnector.setPort(configuredPort);
			jettyServer.addConnector(jettyConnector);

			ServletContextHandler context = new ServletContextHandler();
			context.setContextPath("/");
			context.addServlet(new ServletHolder(new AcpServlet()), path);
			jettyServer.setHandler(context);

			jettyServer.start();
			this.server = jettyServer;
			this.connector = jettyConnector;
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
		return Mono.fromRunnable(() -> {
			if (!closing.compareAndSet(false, true)) {
				return;
			}
			connections.values().forEach(ConnectionState::close);
			connections.clear();
			Server currentServer = this.server;
			if (currentServer != null) {
				try {
					currentServer.stop();
				}
				catch (Exception e) {
					throw new AcpConnectionException("Failed to stop Streamable HTTP listener", e);
				}
			}
			terminationSink.tryEmitValue(null);
		});
	}

	/**
	 * Returns a mono that completes once the listener terminates.
	 * @return termination mono
	 */
	public Mono<Void> awaitTermination() {
		return terminationSink.asMono();
	}

	private ConnectionState createConnection() {
		String connectionId = UUID.randomUUID().toString();
		ConnectionState connection = new ConnectionState(connectionId);
		connection.start();
		return connection;
	}

	private Optional<ConnectionState> connection(String connectionId) {
		return Optional.ofNullable(connections.get(connectionId));
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

			String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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

			if (isInitialize(message)) {
				handleInitialize(request, response, (AcpSchema.JSONRPCRequest) message);
				return;
			}

			String connectionId = header(request, HEADER_CONNECTION_ID).orElse(null);
			if (connectionId == null) {
				writeText(response, HttpServletResponse.SC_BAD_REQUEST, HEADER_CONNECTION_ID + " header required");
				return;
			}
			ConnectionState connection = connections.get(connectionId);
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
			ConnectionState connection = connections.get(connectionId);
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
			ConnectionState connection = connections.remove(connectionId);
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

			ConnectionState connection = createConnection();
			try {
				JSONRPCMessage initializeResponse = connection.initialize(initializeRequest)
					.block(INITIALIZE_TIMEOUT);
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
				connection.close();
				writeText(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "initialize failed");
			}
		}

	}

	private boolean isInitialize(JSONRPCMessage message) {
		return message instanceof AcpSchema.JSONRPCRequest request
				&& AcpSchema.METHOD_INITIALIZE.equals(request.method());
	}

	private boolean hasContentType(HttpServletRequest request, String expected) {
		return Optional.ofNullable(request.getContentType())
			.map(String::toLowerCase)
			.filter(contentType -> contentType.contains(expected))
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

	private final class ConnectionState {

		private final String id;

		private final RemoteAcpConnection connection;

		private final OutboundStream connectionStream = new OutboundStream();

		private final ConcurrentMap<String, OutboundStream> sessionStreams = new ConcurrentHashMap<>();

		private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

		// Client-originated request id -> route expected for the later agent response.
		private final ConcurrentMap<Object, ClientRequestRoute> clientRequestRoutes = new ConcurrentHashMap<>();

		// Agent-originated request id -> route required for the later client response.
		private final ConcurrentMap<Object, RouteScope> agentRequestRoutes = new ConcurrentHashMap<>();

		private final Sinks.One<JSONRPCMessage> initializeResponse = Sinks.one();

		private final AtomicBoolean initialized = new AtomicBoolean(false);

		private volatile Object initializeRequestId;

		ConnectionState(String id) {
			this.id = id;
			this.connection = new RemoteAcpConnection(id, jsonMapper, this::routeAgentMessage);
		}

		String id() {
			return id;
		}

		void start() {
			this.connection.start(agentFactory).block(INITIALIZE_TIMEOUT);
		}

		Mono<JSONRPCMessage> initialize(AcpSchema.JSONRPCRequest request) {
			this.initializeRequestId = request.id();
			connection.acceptInbound(request);
			return initializeResponse.asMono().doOnSuccess(ignored -> initialized.set(true));
		}

		void acceptClientPost(JSONRPCMessage message, String sessionHeader) {
			if (message instanceof AcpSchema.JSONRPCResponse response) {
				validateClientResponseScope(response, sessionHeader);
				connection.acceptInbound(message);
				return;
			}

			ResolvedInboundRoute resolved = resolveInboundRoute(message, sessionHeader);
			if (resolved.requestScope().isSession()) {
				prepareSessionForInbound(resolved.requestScope().sessionId(), resolved.requestRoute());
			}
			if (message instanceof AcpSchema.JSONRPCRequest request && request.id() != null
					&& resolved.requestRoute() != null) {
				clientRequestRoutes.put(request.id(), resolved.requestRoute());
			}
			connection.acceptInbound(message);
		}

		void openStream(HttpServletRequest request, HttpServletResponse response, String sessionId)
				throws IOException {
			RouteScope scope = sessionId == null ? RouteScope.connection() : RouteScope.session(sessionId);
			OutboundStream stream;
			if (scope.isSession()) {
				stream = openSessionStream(scope.sessionId());
			}
			else {
				stream = connectionStream;
			}

			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType(CONTENT_TYPE_EVENT_STREAM);
			response.setHeader("Cache-Control", "no-cache");
			response.setHeader(HEADER_CONNECTION_ID, id);
			if (scope.isSession()) {
				response.setHeader(HEADER_SESSION_ID, scope.sessionId());
			}
			AsyncContext asyncContext = request.startAsync();
			asyncContext.setTimeout(0);
			stream.subscribe(asyncContext, response);
		}

		void close() {
			connectionStream.close();
			sessionStreams.values().forEach(OutboundStream::close);
			connection.closeGracefully().subscribe();
		}

		private void routeAgentMessage(JSONRPCMessage message) {
			try {
				if (message instanceof AcpSchema.JSONRPCResponse response
						&& Objects.equals(response.id(), initializeRequestId) && !initialized.get()) {
					initializeResponse.tryEmitValue(message);
					return;
				}

				RouteScope scope = resolveAgentOutboundScope(message);
				String payload = jsonMapper.writeValueAsString(message);
				if (scope.isSession()) {
					sessionStream(scope.sessionId()).push(payload);
				}
				else {
					connectionStream.push(payload);
				}
			}
			catch (Exception e) {
				connection.signalException(e);
			}
		}

		private RouteScope resolveAgentOutboundScope(JSONRPCMessage message) {
			if (message instanceof AcpSchema.JSONRPCResponse response) {
				ClientRequestRoute route = clientRequestRoutes.remove(response.id());
				if (route == null) {
					logger.warn("Agent emitted response for unknown client request id {}; routing to connection stream",
							response.id());
					return RouteScope.connection();
				}
				if (route.kind() == RequestKind.SESSION_NEW && response.error() == null) {
					String sessionId = extractSessionIdFromNewSessionResponse(response);
					markSessionKnown(sessionId);
				}
				if (route.kind() == RequestKind.SESSION_LOAD && response.error() == null) {
					markSessionKnown(route.requestScope().sessionId());
				}
				return route.responseScope();
			}

			String method;
			Object params;
			Object id = null;
			if (message instanceof AcpSchema.JSONRPCRequest request) {
				method = request.method();
				params = request.params();
				id = request.id();
			}
			else if (message instanceof AcpSchema.JSONRPCNotification notification) {
				method = notification.method();
				params = notification.params();
			}
			else {
				throw new AcpConnectionException("Unsupported outbound JSON-RPC message type: " + message);
			}

			RouteScope scope = resolveAgentRequestOrNotificationScope(method, params);
			if (id != null) {
				agentRequestRoutes.put(id, scope);
			}
			return scope;
		}

		private RouteScope resolveAgentRequestOrNotificationScope(String method, Object params) {
			switch (method) {
				case AcpSchema.METHOD_SESSION_REQUEST_PERMISSION:
				case AcpSchema.METHOD_SESSION_UPDATE:
				case AcpSchema.METHOD_FS_READ_TEXT_FILE:
				case AcpSchema.METHOD_FS_WRITE_TEXT_FILE:
				case AcpSchema.METHOD_TERMINAL_CREATE:
				case AcpSchema.METHOD_TERMINAL_OUTPUT:
				case AcpSchema.METHOD_TERMINAL_RELEASE:
				case AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT:
				case AcpSchema.METHOD_TERMINAL_KILL:
					return RouteScope.session(requireSessionId(params, method));
				default:
					Optional<String> sessionId = extractSessionId(params);
					if (routingMode == RoutingMode.STRICT) {
						throw new AcpConnectionException("No explicit routing rule for outbound method " + method);
					}
					return sessionId.map(RouteScope::session).orElseGet(RouteScope::connection);
			}
		}

		private ResolvedInboundRoute resolveInboundRoute(JSONRPCMessage message, String sessionHeader) {
			String method;
			Object params;
			if (message instanceof AcpSchema.JSONRPCRequest request) {
				method = request.method();
				params = request.params();
			}
			else if (message instanceof AcpSchema.JSONRPCNotification notification) {
				method = notification.method();
				params = notification.params();
			}
			else {
				throw new AcpConnectionException("Unsupported inbound JSON-RPC message type: " + message);
			}

			RouteScope requestScope;
			RequestKind kind = RequestKind.GENERIC;
			RouteScope responseScope;

			switch (method) {
				case AcpSchema.METHOD_AUTHENTICATE:
				case AcpSchema.METHOD_SESSION_NEW:
					requestScope = RouteScope.connection();
					kind = AcpSchema.METHOD_SESSION_NEW.equals(method) ? RequestKind.SESSION_NEW : RequestKind.GENERIC;
					responseScope = RouteScope.connection();
					break;
				case AcpSchema.METHOD_SESSION_LOAD:
					requestScope = requireSessionScope(method, params, sessionHeader);
					kind = RequestKind.SESSION_LOAD;
					responseScope = RouteScope.connection();
					break;
				case AcpSchema.METHOD_SESSION_PROMPT:
				case AcpSchema.METHOD_SESSION_SET_MODE:
				case AcpSchema.METHOD_SESSION_SET_MODEL:
				case AcpSchema.METHOD_SESSION_CANCEL:
					requestScope = requireSessionScope(method, params, sessionHeader);
					responseScope = requestScope;
					break;
				default:
					Optional<String> sessionId = extractSessionId(params);
					if (routingMode == RoutingMode.STRICT) {
						throw new AcpConnectionException("No explicit routing rule for inbound method " + method);
					}
					if (sessionId.isPresent()) {
						requestScope = requireSessionScope(method, params, sessionHeader);
					}
					else {
						requestScope = RouteScope.connection();
					}
					responseScope = requestScope;
			}

			ClientRequestRoute requestRoute = message instanceof AcpSchema.JSONRPCRequest
					? new ClientRequestRoute(kind, requestScope, responseScope) : null;
			return new ResolvedInboundRoute(message, requestScope, requestRoute);
		}

		private RouteScope requireSessionScope(String method, Object params, String sessionHeader) {
			String sessionId = requireSessionId(params, method);
			if (sessionHeader == null) {
				throw new AcpConnectionException(HEADER_SESSION_ID + " header required for " + method);
			}
			if (!sessionId.equals(sessionHeader)) {
				throw new AcpConnectionException("Header " + HEADER_SESSION_ID + " does not match params.sessionId");
			}
			return RouteScope.session(sessionId);
		}

		private void prepareSessionForInbound(String sessionId, ClientRequestRoute route) {
			SessionState current = sessions.get(sessionId);
			if (route != null && route.kind() == RequestKind.SESSION_LOAD) {
				if (current == null) {
					if (routingMode == RoutingMode.STRICT) {
						throw new UnknownSessionException("Unknown session " + sessionId);
					}
					sessions.putIfAbsent(sessionId, SessionState.PENDING_LOAD);
					sessionStream(sessionId);
				}
				return;
			}
			if (current != SessionState.KNOWN) {
				throw new UnknownSessionException("Unknown session " + sessionId);
			}
		}

		private void validateClientResponseScope(AcpSchema.JSONRPCResponse response, String sessionHeader) {
			RouteScope expected = agentRequestRoutes.get(response.id());
			if (expected == null) {
				logger.warn("Client posted response for unknown agent request id {}", response.id());
				return;
			}
			RouteScope actual = sessionHeader == null ? RouteScope.connection() : RouteScope.session(sessionHeader);
			if (!Objects.equals(expected, actual)) {
				throw new AcpConnectionException(
						"Response id " + response.id() + " arrived on " + actual + " but expected " + expected);
			}
			agentRequestRoutes.remove(response.id(), expected);
		}

		private OutboundStream openSessionStream(String sessionId) {
			SessionState current = sessions.get(sessionId);
			if (current == null) {
				if (routingMode == RoutingMode.STRICT) {
					throw new UnknownSessionException("Unknown session " + sessionId);
				}
				/*
				 * RFD gap:
				 * The current text says unknown session-scoped GET requests return 404,
				 * but its resume flow also asks clients to open a session stream before
				 * sending session/load. Compatible mode keeps a provisional stream so
				 * practical resume can work while strict mode preserves the literal rule.
				 */
				sessions.putIfAbsent(sessionId, SessionState.PENDING_LOAD);
			}
			return sessionStream(sessionId);
		}

		private OutboundStream sessionStream(String sessionId) {
			return sessionStreams.computeIfAbsent(sessionId, ignored -> new OutboundStream());
		}

		private void markSessionKnown(String sessionId) {
			sessions.put(sessionId, SessionState.KNOWN);
			sessionStream(sessionId);
		}

		private String extractSessionIdFromNewSessionResponse(AcpSchema.JSONRPCResponse response) {
			AcpSchema.NewSessionResponse sessionResponse = jsonMapper.convertValue(response.result(),
					new TypeRef<AcpSchema.NewSessionResponse>() {
					});
			if (sessionResponse.sessionId() == null || sessionResponse.sessionId().isBlank()) {
				throw new AcpConnectionException("session/new response missing sessionId");
			}
			return sessionResponse.sessionId();
		}

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
			.orElseThrow(() -> new AcpConnectionException("Missing sessionId for method " + method));
	}

	private final class OutboundStream {

		private final ArrayDeque<String> replay = new ArrayDeque<>();

		private final List<SseSubscriber> subscribers = new CopyOnWriteArrayList<>();

		private final AtomicBoolean closed = new AtomicBoolean(false);

		private boolean replayOpen = true;

		synchronized void push(String payload) {
			if (closed.get()) {
				return;
			}
			if (replayOpen) {
				if (replay.size() == MAX_REPLAY_EVENTS) {
					replay.removeFirst();
				}
				replay.addLast(payload);
				return;
			}
			subscribers.forEach(subscriber -> subscriber.send(payload));
		}

		synchronized void subscribe(AsyncContext asyncContext, HttpServletResponse response) throws IOException {
			if (closed.get()) {
				throw new IOException("SSE stream is closed");
			}
			SseSubscriber subscriber = new SseSubscriber(this, asyncContext, response);
			subscribers.add(subscriber);
			if (replayOpen) {
				for (String payload : new ArrayList<>(replay)) {
					subscriber.send(payload);
				}
				replay.clear();
				replayOpen = false;
			}
			subscriber.flush();
		}

		void remove(SseSubscriber subscriber) {
			subscribers.remove(subscriber);
		}

		void close() {
			if (closed.compareAndSet(false, true)) {
				subscribers.forEach(SseSubscriber::close);
				subscribers.clear();
				synchronized (this) {
					replay.clear();
				}
			}
		}

	}

	private final class SseSubscriber {

		private final OutboundStream parent;

		private final AsyncContext asyncContext;

		private final PrintWriter writer;

		private final AtomicBoolean closed = new AtomicBoolean(false);

		SseSubscriber(OutboundStream parent, AsyncContext asyncContext, HttpServletResponse response) throws IOException {
			this.parent = parent;
			this.asyncContext = asyncContext;
			this.writer = response.getWriter();
		}

		synchronized void send(String payload) {
			if (closed.get()) {
				return;
			}
			writer.write("data: ");
			writer.write(payload);
			writer.write("\n\n");
			writer.flush();
			if (writer.checkError()) {
				close();
			}
		}

		synchronized void flush() {
			writer.flush();
		}

		void close() {
			if (closed.compareAndSet(false, true)) {
				parent.remove(this);
				try {
					asyncContext.complete();
				}
				catch (IllegalStateException ignored) {
				}
			}
		}

	}

	private static final class UnknownSessionException extends RuntimeException {

		UnknownSessionException(String message) {
			super(message);
		}

	}

}
