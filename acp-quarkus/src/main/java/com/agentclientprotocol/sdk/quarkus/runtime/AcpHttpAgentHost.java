/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.time.Duration;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drains the ACP endpoint first when the application stops: every open SSE stream and WebSocket is
 * an in-flight request, so a graceful HTTP shutdown would otherwise wait for clients that never
 * finish. SSE streams get a closing comment and complete, WebSockets close with 1001, and
 * in-flight prompts are cancelled, within the endpoint's shutdown timeout
 * ({@code quarkus.acp.agent.transport.http.shutdown-timeout}). The extension adds it for an HTTP
 * agent. An application does not call it; a test can inject it to count the open connections.
 *
 * @author Mark Pollack
 */
@Singleton
public class AcpHttpAgentHost {

	private static final Logger logger = LoggerFactory.getLogger(AcpHttpAgentHost.class);

	private final AcpVertxHost host;

	AcpHttpAgentHost(AcpVertxHost host) {
		this.host = host;
	}

	void stop(@Observes @Priority(0) ShutdownEvent event) {
		drain();
	}

	/**
	 * Drains the endpoint now, as the application's shutdown does, waiting at most the endpoint's
	 * shutdown timeout plus a second.
	 */
	public void drain() {
		Duration timeout = host.endpoint().options().shutdownTimeout().plusSeconds(1);
		try {
			host.closeGracefully().block(timeout);
		}
		catch (RuntimeException e) {
			logger.warn("ACP connections did not close within {}: {}", timeout, e.toString());
		}
	}

	/**
	 * Returns how many ACP connections are open: Streamable HTTP and WebSocket together.
	 * @return the connection count
	 */
	public int activeConnectionCount() {
		return host.activeConnectionCount();
	}

}
