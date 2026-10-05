/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.RemoteAcpConnection;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * One ACP connection over WebSocket, on any host: its agent runtime, the initialize-first
 * rule, the inbound size limit, and a serialized, bounded frame sender over the host's
 * {@link AcpWsOutbound}. The one WebSocket connection of the SDK; the Jetty-native and Vert.x
 * copies it replaces each held the same rules.
 *
 * @author Kaiser Dandangi
 * @author Mark Pollack
 */
final class WebSocketConnection implements AcpWsHandler {

	private static final Logger logger = LoggerFactory.getLogger(WebSocketConnection.class);

	static final int NORMAL = 1000;

	static final int GOING_AWAY = 1001;

	static final int PROTOCOL_ERROR = 1002;

	static final int MESSAGE_TOO_BIG = 1009;

	static final int SERVER_ERROR = 1011;

	private final String id;

	private final AcpJsonMapper jsonMapper;

	private final StreamableHttpAcpAgentTransportOptions options;

	private final AcpWsOutbound outbound;

	private final Consumer<WebSocketConnection> deregister;

	private final RemoteAcpConnection remoteConnection;

	private final AtomicBoolean initialized = new AtomicBoolean(false);

	private final AtomicBoolean closed = new AtomicBoolean(false);

	/** Guards the send queue. A lock, not a monitor: senders may be virtual threads. */
	private final ReentrantLock sendLock = new ReentrantLock();

	/** Frames waiting for the previous send to complete. Guarded by {@code sendLock}. */
	private final ArrayDeque<String> queue = new ArrayDeque<>();

	/** Whether a frame is with the host. Guarded by {@code sendLock}. */
	private boolean sendInProgress;

	WebSocketConnection(String id, AcpJsonMapper jsonMapper, StreamableHttpAcpAgentTransportOptions options,
			AcpWsOutbound outbound, Consumer<WebSocketConnection> deregister, Consumer<Throwable> exceptionHandler) {
		this.id = id;
		this.jsonMapper = jsonMapper;
		this.options = options;
		this.outbound = outbound;
		this.deregister = deregister;
		this.remoteConnection = new RemoteAcpConnection(id, jsonMapper, this::sendToClient, exceptionHandler);
	}

	String id() {
		return id;
	}

	/** Starts the agent; frames that arrive meanwhile are kept until it runs. */
	void start(AcpAgentFactory agentFactory) {
		remoteConnection.start(agentFactory)
			.timeout(StreamableHttpRouting.INITIALIZE_TIMEOUT, com.agentclientprotocol.sdk.util.AcpSchedulers.timeouts())
			.subscribe(ignored -> {
			}, error -> close(SERVER_ERROR, "agent failed to start"));
	}

	@Override
	public void onText(String text) {
		logger.debug("ACP WebSocket connection {} received {} characters", id, text.length());
		if (closed.get()) {
			return;
		}
		if (exceedsLimit(text)) {
			logger.warn("Closing ACP WebSocket connection {}: a message exceeded {} bytes", id,
					options.maxPostBodyBytes());
			close(MESSAGE_TOO_BIG, "message too big");
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
		acceptFromClient(message);
	}

	private boolean exceedsLimit(String text) {
		long max = options.maxPostBodyBytes();
		// A char is at most three UTF-8 bytes: count them only when it could matter.
		return text.length() > max || (text.length() * 3L > max && text.getBytes(StandardCharsets.UTF_8).length > max);
	}

	private void acceptFromClient(JSONRPCMessage message) {
		if (!initialized.get()) {
			// No POST initialize created this connection, so the first client-originated
			// JSON-RPC message on the socket must be initialize.
			if (!StreamableHttpRouting.isInitializeRequest(message)) {
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
		try {
			remoteConnection.acceptInbound(message);
		}
		catch (AcpConnectionException e) {
			logger.debug("ACP WebSocket connection {} dropped a message while closing: {}", id, e.getMessage());
		}
	}

	@Override
	public void onClose(int code, @Nullable String reason) {
		logger.debug("ACP WebSocket connection {} closed: {} {}", id, code, reason);
		closeLocally();
	}

	@Override
	public void onError(Throwable error) {
		if (closed.get()) {
			logger.debug("ACP WebSocket connection {} failed after closing: {}", id, error.toString());
			return;
		}
		remoteConnection.signalException(error);
		close(SERVER_ERROR, "WebSocket error");
	}

	void sendToClient(JSONRPCMessage message) {
		try {
			send(jsonMapper.writeValueAsString(message));
		}
		catch (Exception e) {
			if (!closed.get()) {
				remoteConnection.signalException(e);
				close(SERVER_ERROR, "failed to send ACP message");
			}
		}
	}

	private void send(String payload) {
		sendLock.lock();
		try {
			if (closed.get()) {
				throw new AcpConnectionException("ACP WebSocket connection is closed");
			}
			if (queue.size() >= options.maxWebSocketPendingFrames()) {
				throw new AcpConnectionException(
						"WebSocket send queue exceeded " + options.maxWebSocketPendingFrames() + " pending frames");
			}
			queue.addLast(payload);
			if (sendInProgress) {
				return;
			}
			sendInProgress = true;
		}
		finally {
			sendLock.unlock();
		}
		drain();
	}

	/**
	 * Hands the host one frame at a time, the next only once the previous send completed: agent
	 * messages come from concurrent handlers, and a container may allow one outstanding write
	 * (Tomcat fails a second one). A send the host completes at once continues in this loop
	 * rather than recursing.
	 */
	private void drain() {
		String payload = nextPayload();
		while (payload != null && sendCompletedAtOnce(payload)) {
			payload = nextPayload();
		}
	}

	/** The next frame to send, or null (and no send in progress) when there is none. */
	private @Nullable String nextPayload() {
		sendLock.lock();
		try {
			if (closed.get()) {
				queue.clear();
				sendInProgress = false;
				return null;
			}
			String payload = queue.pollFirst();
			if (payload == null) {
				sendInProgress = false;
			}
			return payload;
		}
		finally {
			sendLock.unlock();
		}
	}

	/**
	 * Hands the host one frame. The callback handles the send's failure itself, so the stage
	 * {@code whenComplete} returns is not needed.
	 * @return true when the send completed at once, so the caller continues with the next frame;
	 * false when the completion continues the drain, or the send failed
	 */
	@SuppressWarnings("FutureReturnValueIgnored")
	private boolean sendCompletedAtOnce(String payload) {
		logger.debug("ACP WebSocket connection {} sends {} characters", id, payload.length());
		CompletableFuture<Void> sent;
		try {
			sent = outbound.sendText(payload).toCompletableFuture();
		}
		catch (RuntimeException e) {
			sendFailed(e);
			return false;
		}
		if (sent.isDone() && !sent.isCompletedExceptionally()) {
			return true;
		}
		sent.whenComplete((ignored, error) -> {
			if (error != null) {
				sendFailed(error);
			}
			else {
				drain();
			}
		});
		return false;
	}

	private void sendFailed(Throwable error) {
		if (!closed.get()) {
			remoteConnection.signalException(error);
			close(SERVER_ERROR, "failed to send ACP message");
		}
	}

	/** Closes the socket with a going-away frame and the agent gracefully: the endpoint shuts down. */
	Mono<Void> closeForShutdown() {
		return closeGracefully(GOING_AWAY, "server shutting down");
	}

	private Mono<Void> closeGracefully(int code, String reason) {
		if (!closed.compareAndSet(false, true)) {
			return Mono.empty();
		}
		deregister.accept(this);
		clearQueue();
		try {
			outbound.close(code, reason);
		}
		catch (RuntimeException e) {
			logger.debug("Closing ACP WebSocket connection {} failed: {}", id, e.toString());
		}
		return remoteConnection.closeGracefully();
	}

	void close(int code, String reason) {
		closeGracefully(code, reason).subscribe(v -> {
		}, error -> logger.warn("Error closing ACP WebSocket connection {}", id, error));
	}

	/** The socket is already closed: close the agent, send nothing. */
	private void closeLocally() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		deregister.accept(this);
		clearQueue();
		remoteConnection.closeGracefully()
			.subscribe(v -> {
			}, error -> logger.warn("Error closing ACP WebSocket connection {}", id, error));
	}

	/** Closes the agent at once, also after a graceful close that has not finished. */
	void closeNow() {
		close(GOING_AWAY, "server shutting down");
		remoteConnection.close();
	}

	private void clearQueue() {
		sendLock.lock();
		try {
			queue.clear();
		}
		finally {
			sendLock.unlock();
		}
	}

}
