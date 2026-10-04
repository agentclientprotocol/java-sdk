/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.agentclientprotocol.sdk.integration.AcpServletHost;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes the HTTP and WebSocket connections first when the application stops: every
 * open SSE stream is an in-flight request, so a graceful HTTP shutdown would otherwise
 * wait for clients that never finish. In-flight prompts are cancelled and every stream
 * completes within the endpoint's shutdown timeout.
 *
 * @author Mark Pollack
 */
@Singleton
public class AcpHttpAgentHost {

	private static final Logger logger = LoggerFactory.getLogger(AcpHttpAgentHost.class);

	private final AcpHttpServlet servlet;

	private final AcpWebSocketRoute webSockets;

	private final AcpHttpEndpoint endpoint;

	AcpHttpAgentHost(AcpHttpServlet servlet, AcpWebSocketRoute webSockets, AcpHttpEndpoint endpoint) {
		this.servlet = servlet;
		this.webSockets = webSockets;
		this.endpoint = endpoint;
	}

	void stop(@Observes @Priority(0) ShutdownEvent event) {
		Duration timeout = endpoint.options().shutdownTimeout().plusSeconds(1);
		// Both at once: the WebSocket connections close while the servlet's do.
		CompletableFuture<Void> webSocketsClosed = webSockets.closeGracefully().toFuture();
		AcpServletHost.closeBeforeShutdown(servlet, timeout);
		try {
			webSocketsClosed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		catch (ExecutionException | TimeoutException e) {
			logger.warn("ACP WebSocket connections did not close within {}: {}", timeout, e.toString());
		}
	}

	/**
	 * The open HTTP and WebSocket connections.
	 * @return the connection count
	 */
	public int activeConnectionCount() {
		return servlet.activeConnectionCount() + webSockets.activeConnectionCount();
	}

}
