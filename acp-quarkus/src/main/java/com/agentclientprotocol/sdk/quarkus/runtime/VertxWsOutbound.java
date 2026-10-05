/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.agentclientprotocol.sdk.http.server.AcpWsOutbound;
import io.vertx.core.http.ServerWebSocket;

/**
 * Sends through a Vert.x server WebSocket; the endpoint sends one frame at a time.
 *
 * @author Mark Pollack
 */
final class VertxWsOutbound implements AcpWsOutbound {

	private final ServerWebSocket socket;

	VertxWsOutbound(ServerWebSocket socket) {
		this.socket = socket;
	}

	@Override
	public CompletionStage<Void> sendText(String text) {
		return socket.writeTextMessage(text).toCompletionStage();
	}

	@Override
	public CompletionStage<Void> close(int code, String reason) {
		if (socket.isClosed()) {
			return CompletableFuture.completedFuture(null);
		}
		return socket.close((short) code, reason).toCompletionStage();
	}

}
