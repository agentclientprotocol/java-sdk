/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.Assert;
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
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * RFD-compliant listener-backed ACP WebSocket transport for agents.
 *
 * <p>
 * This transport accepts WebSocket upgrades on one ACP endpoint and creates one fresh
 * agent runtime per accepted remote connection via {@link AcpAgentFactory}. The
 * resulting per-connection {@link RemoteAcpConnection} owns ACP JSON-RPC semantics,
 * while this class owns only Jetty listener setup, WebSocket framing, connection IDs,
 * and close/error lifecycle.
 * </p>
 *
 * <p>
 * Unlike the legacy {@link WebSocketAcpAgentTransport}, this class is intentionally not
 * an {@code AcpAgentTransport} itself. It is a listener that creates one
 * connection-bound transport per WebSocket client.
 * </p>
 *
 * @author Kaiser Dandangi
 */
public class RemoteWebSocketAcpAgentTransport {

	private static final Logger logger = LoggerFactory.getLogger(RemoteWebSocketAcpAgentTransport.class);

	public static final String DEFAULT_ACP_PATH = "/acp";

	public static final String HEADER_CONNECTION_ID = "Acp-Connection-Id";

	private static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(30);

	private final int configuredPort;

	private final String path;

	private final AcpJsonMapper jsonMapper;

	private final AcpAgentFactory agentFactory;

	private final ConcurrentMap<String, ConnectionState> connections = new ConcurrentHashMap<>();

	private final AtomicBoolean started = new AtomicBoolean(false);

	private final AtomicBoolean closing = new AtomicBoolean(false);

	private final Sinks.One<Void> terminationSink = Sinks.one();

	private volatile Duration idleTimeout = Duration.ofMinutes(30);

	private volatile Server server;

	private volatile ServerConnector connector;

	/**
	 * Creates a new RFD-compliant WebSocket listener on the default ACP path.
	 * @param port port to listen on
	 * @param jsonMapper JSON mapper used for serialization
	 * @param agentFactory factory used to create one agent runtime per connection
	 */
	public RemoteWebSocketAcpAgentTransport(int port, AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory) {
		this(port, DEFAULT_ACP_PATH, jsonMapper, agentFactory);
	}

	/**
	 * Creates a new RFD-compliant WebSocket listener.
	 * @param port port to listen on
	 * @param path endpoint path
	 * @param jsonMapper JSON mapper used for serialization
	 * @param agentFactory factory used to create one agent runtime per connection
	 */
	public RemoteWebSocketAcpAgentTransport(int port, String path, AcpJsonMapper jsonMapper,
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
	 * Sets the WebSocket idle timeout.
	 * @param timeout idle timeout
	 * @return this transport
	 */
	public RemoteWebSocketAcpAgentTransport idleTimeout(Duration timeout) {
		Assert.notNull(timeout, "The timeout can not be null");
		this.idleTimeout = timeout;
		return this;
	}

	/**
	 * Starts the embedded Jetty WebSocket listener.
	 * @return a mono that completes when the listener is ready
	 */
	public Mono<Void> start() {
		if (!started.compareAndSet(false, true)) {
			return Mono.error(new IllegalStateException("Already started"));
		}

		return Mono.fromRunnable(() -> {
			Server jettyServer = new Server();
			ServerConnector jettyConnector = new ServerConnector(jettyServer);
			jettyConnector.setPort(configuredPort);
			jettyServer.addConnector(jettyConnector);

			WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(jettyServer, container -> {
				container.setIdleTimeout(idleTimeout);
				container.addMapping(path, (request, response, callback) -> {
					ConnectionState connection = createConnection();
					try {
						connection.start();
						connections.put(connection.id(), connection);
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
			jettyServer.setHandler(wsHandler);

			try {
				jettyServer.start();
			}
			catch (Exception e) {
				throw new AcpConnectionException("Failed to start Remote WebSocket listener", e);
			}
			this.server = jettyServer;
			this.connector = jettyConnector;
			logger.info("Remote WebSocket ACP agent listener started on port {} at path {}", getPort(), path);
		});
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
	 * Closes all active WebSocket connections and stops the listener.
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
					throw new AcpConnectionException("Failed to stop Remote WebSocket listener", e);
				}
			}
			terminationSink.tryEmitValue(null);
		});
	}

	/**
	 * Closes all active WebSocket connections and stops the listener immediately.
	 */
	public void close() {
		closeGracefully().block(Duration.ofSeconds(5));
	}

	/**
	 * Returns a mono that completes once the listener terminates.
	 * @return termination mono
	 */
	public Mono<Void> awaitTermination() {
		return terminationSink.asMono();
	}

	int activeConnectionCount() {
		return connections.size();
	}

	private ConnectionState createConnection() {
		String connectionId = UUID.randomUUID().toString();
		return new ConnectionState(connectionId);
	}

	private boolean isInitializeRequest(JSONRPCMessage message) {
		return message instanceof AcpSchema.JSONRPCRequest request
				&& AcpSchema.METHOD_INITIALIZE.equals(request.method()) && request.id() != null;
	}

	private final class ConnectionState {

		private final String id;

		private final RemoteAcpConnection remoteConnection;

		private final AtomicBoolean initialized = new AtomicBoolean(false);

		private final AtomicBoolean closed = new AtomicBoolean(false);

		private volatile Session session;

		ConnectionState(String id) {
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
				// The WebSocket RFD makes initialize the first client-originated
				// JSON-RPC request. Enforce that at the wire adapter before the
				// shared connection core sees application messages.
				if (!isInitializeRequest(message)) {
					close(StatusCode.PROTOCOL, "first ACP WebSocket message must be initialize");
					return;
				}
				initialized.set(true);
			}
			remoteConnection.acceptInbound(message);
		}

		void sendToClient(JSONRPCMessage message) {
			try {
				Session currentSession = this.session;
				if (closed.get() || currentSession == null || !currentSession.isOpen()) {
					throw new AcpConnectionException("Remote WebSocket connection is closed");
				}
				String payload = jsonMapper.writeValueAsString(message);
				logger.debug("Sending remote WebSocket message: {}", payload);
				currentSession.sendText(payload, Callback.from(() -> {
				}, error -> {
					if (!closed.get()) {
						remoteConnection.signalException(error);
					}
				}));
			}
			catch (Exception e) {
				remoteConnection.signalException(e);
				close(StatusCode.SERVER_ERROR, "failed to send ACP message");
			}
		}

		void close() {
			close(StatusCode.NORMAL, "server closing");
		}

		void close(int statusCode, String reason) {
			if (!closed.compareAndSet(false, true)) {
				return;
			}
			connections.remove(id, this);
			Session currentSession = this.session;
			if (currentSession != null && currentSession.isOpen()) {
				currentSession.close(statusCode, reason, Callback.NOOP);
			}
			remoteConnection.closeGracefully().subscribe();
		}

	}

	/**
	 * Jetty WebSocket endpoint for one accepted ACP connection.
	 */
	@WebSocket
	public class AcpWebSocketEndpoint {

		private final ConnectionState connection;

		AcpWebSocketEndpoint(ConnectionState connection) {
			this.connection = connection;
		}

		@OnWebSocketOpen
		public void onOpen(Session session) {
			logger.info("Remote WebSocket ACP client connected from {}", session.getRemoteSocketAddress());
			connection.open(session);
		}

		@OnWebSocketMessage
		public void onMessage(Session session, String message) {
			logger.debug("Received remote WebSocket message: {}", message);

			try {
				JSONRPCMessage jsonRpcMessage = AcpSchema.deserializeJsonRpcMessage(jsonMapper, message);
				connection.acceptFromClient(jsonRpcMessage);
			}
			catch (Exception e) {
				logger.warn("Closing remote WebSocket ACP connection after invalid JSON-RPC frame", e);
				connection.close(StatusCode.PROTOCOL, "invalid JSON-RPC frame");
			}
		}

		@OnWebSocketClose
		public void onClose(Session session, int statusCode, String reason) {
			logger.info("Remote WebSocket ACP client disconnected: {} - {}", statusCode, reason);
			connection.close(statusCode, reason);
		}

		@OnWebSocketError
		public void onError(Session session, Throwable error) {
			logger.error("Remote WebSocket ACP error", error);
			connection.remoteConnection.signalException(error);
			connection.close(StatusCode.SERVER_ERROR, "WebSocket error");
		}

	}

}
