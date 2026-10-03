/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.RemoteAcpConnection;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import io.vertx.core.http.ServerWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * One ACP connection upgraded to WebSocket on the Quarkus HTTP server: its agent runtime,
 * the initialize-first rule, and a bounded count of frames waiting to be written. The
 * Vert.x counterpart of the SDK's Jetty WebSocket connection, with the same behaviour.
 * <p>
 * A copy for 0.80.0: after it, a container-neutral WebSocket connection over
 * {@code RemoteAcpConnection} (a sender interface each container adapts, planned for a
 * Jetty-free servlet module) replaces both this class and the Jetty one.
 * </p>
 *
 * @author Mark Pollack
 */
final class VertxWebSocketConnection {

	private static final Logger logger = LoggerFactory.getLogger(VertxWebSocketConnection.class);

	static final short NORMAL = 1000;

	static final short PROTOCOL_ERROR = 1002;

	static final short MESSAGE_TOO_BIG = 1009;

	static final short SERVER_ERROR = 1011;

	private final String id;

	private final ServerWebSocket socket;

	private final AcpJsonMapper jsonMapper;

	private final StreamableHttpAcpAgentTransportOptions options;

	private final Consumer<VertxWebSocketConnection> deregister;

	private final RemoteAcpConnection remoteConnection;

	private final AtomicBoolean initialized = new AtomicBoolean(false);

	private final AtomicBoolean closed = new AtomicBoolean(false);

	private final AtomicInteger pendingFrames = new AtomicInteger();

	private final Object writeLock = new Object();

	VertxWebSocketConnection(String id, ServerWebSocket socket, AcpJsonMapper jsonMapper,
			StreamableHttpAcpAgentTransportOptions options, Consumer<VertxWebSocketConnection> deregister,
			Consumer<Throwable> exceptionHandler) {
		this.id = id;
		this.socket = socket;
		this.jsonMapper = jsonMapper;
		this.options = options;
		this.deregister = deregister;
		this.remoteConnection = new RemoteAcpConnection(id, jsonMapper, this::sendToClient, exceptionHandler);
	}

	String id() {
		return id;
	}

	Mono<Void> start(AcpAgentFactory agentFactory) {
		return remoteConnection.start(agentFactory);
	}

	/** A text message from the client: read, checked against the initialize-first rule, delivered. */
	void receive(String text) {
		if (text.length() > options.maxPostBodyBytes()) {
			close(MESSAGE_TOO_BIG, "ACP message exceeds " + options.maxPostBodyBytes() + " bytes");
			return;
		}
		JSONRPCMessage message;
		try {
			message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, text);
		}
		catch (Exception e) {
			// Answered as JSON-RPC 2.0 says and skipped, as every ACP transport does.
			logger.warn("Skipped an ACP WebSocket frame that is not a JSON-RPC message", e);
			remoteConnection.signalException(e);
			sendToClient(AcpSchema.unreadableMessageResponse(jsonMapper, text));
			return;
		}
		if (!initialized.get()) {
			// No POST initialize created this connection, so the first client message on
			// the socket must be initialize.
			if (!isInitializeRequest(message)) {
				close(PROTOCOL_ERROR, "first ACP WebSocket message must be initialize");
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

	static boolean isInitializeRequest(JSONRPCMessage message) {
		return message instanceof AcpSchema.JSONRPCRequest request
				&& AcpSchema.METHOD_INITIALIZE.equals(request.method()) && request.id() != null;
	}

	/**
	 * Writes one message as a text frame. Writes are issued in order under a lock; at most
	 * {@code maxWebSocketPendingFrames} may wait for Vert.x to write them.
	 */
	void sendToClient(JSONRPCMessage message) {
		try {
			String payload = jsonMapper.writeValueAsString(message);
			synchronized (writeLock) {
				if (closed.get()) {
					throw new AcpConnectionException("ACP WebSocket connection is closed");
				}
				if (pendingFrames.incrementAndGet() > options.maxWebSocketPendingFrames()) {
					pendingFrames.decrementAndGet();
					throw new AcpConnectionException(
							"WebSocket send queue exceeded " + options.maxWebSocketPendingFrames() + " pending frames");
				}
				socket.writeTextMessage(payload).onComplete(written -> {
					pendingFrames.decrementAndGet();
					if (written.failed()) {
						failed(written.cause());
					}
				});
			}
		}
		catch (Exception e) {
			failed(e);
		}
	}

	private void failed(Throwable error) {
		if (!closed.get()) {
			remoteConnection.signalException(error);
			close(SERVER_ERROR, "failed to send ACP message");
		}
	}

	/**
	 * Closes the socket and, gracefully, the connection's agent.
	 * @return completes when the agent has closed
	 */
	Mono<Void> closeGracefully() {
		return closeGracefully(NORMAL, "server closing");
	}

	private Mono<Void> closeGracefully(short statusCode, String reason) {
		if (!closed.compareAndSet(false, true)) {
			return Mono.empty();
		}
		deregister.accept(this);
		if (!socket.isClosed()) {
			socket.close(statusCode, reason);
		}
		return remoteConnection.closeGracefully();
	}

	/** The client closed the socket, or it failed. */
	void closed() {
		close(NORMAL, "client closed");
	}

	void close(short statusCode, String reason) {
		closeGracefully(statusCode, reason).subscribe(ignored -> {
		}, error -> logger.warn("Error closing ACP WebSocket connection {}", id, error));
	}

	/** Closes at once, also after a graceful close that has not finished. */
	void closeNow() {
		close(NORMAL, "server closing");
		remoteConnection.close();
	}

	void signalException(Throwable error) {
		remoteConnection.signalException(error);
	}

}
