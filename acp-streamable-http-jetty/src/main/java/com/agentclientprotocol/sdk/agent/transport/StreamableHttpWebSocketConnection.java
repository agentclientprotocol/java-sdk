/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * One remote ACP connection upgraded to WebSocket on the Streamable HTTP endpoint: its
 * agent runtime, the initialize-first rule, and a serialized, bounded frame sender.
 *
 * @author Kaiser Dandangi
 */
final class StreamableHttpWebSocketConnection {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpWebSocketConnection.class);


	private final String id;

	private final RemoteAcpConnection remoteConnection;

	private final AtomicBoolean initialized = new AtomicBoolean(false);

	private final AtomicBoolean closed = new AtomicBoolean(false);

	private final SerializedWebSocketSender outboundSender = new SerializedWebSocketSender();

	private volatile Session session;

	private final AcpJsonMapper jsonMapper;

	private final AcpAgentFactory agentFactory;

	private final StreamableHttpAcpAgentTransportOptions options;

	private final Consumer<StreamableHttpWebSocketConnection> deregister;

	StreamableHttpWebSocketConnection(String id, AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory,
			StreamableHttpAcpAgentTransportOptions options, Consumer<StreamableHttpWebSocketConnection> deregister) {
		this.id = id;
		this.jsonMapper = jsonMapper;
		this.agentFactory = agentFactory;
		this.options = options;
		this.deregister = deregister;
		this.remoteConnection = new RemoteAcpConnection(id, jsonMapper, this::sendToClient);
	}

	String id() {
		return id;
	}

	void start() {
		this.remoteConnection.start(agentFactory).block(StreamableHttpAcpAgentTransport.INITIALIZE_TIMEOUT);
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
		deregister.accept(this);
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

	void signalException(Throwable error) {
		remoteConnection.signalException(error);
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
				StreamableHttpWebSocketConnection.this.close(StatusCode.SERVER_ERROR, "failed to send ACP message");
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

	/**
	 * Jetty WebSocket endpoint for one WebSocket-upgraded ACP connection.
	 * <p>
	 * Public because Jetty binds the annotated methods through
	 * {@code MethodHandles.publicLookup()}; a package-private endpoint class fails the
	 * upgrade with {@code IllegalAccessException}.
	 * </p>
	 */
	@WebSocket
	public static final class AcpWebSocketEndpoint {

		private final StreamableHttpWebSocketConnection connection;

		private final AcpJsonMapper jsonMapper;

		AcpWebSocketEndpoint(StreamableHttpWebSocketConnection connection, AcpJsonMapper jsonMapper) {
			this.connection = connection;
			this.jsonMapper = jsonMapper;
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
			connection.signalException(error);
			connection.close(StatusCode.SERVER_ERROR, "WebSocket error");
		}

	}

}
