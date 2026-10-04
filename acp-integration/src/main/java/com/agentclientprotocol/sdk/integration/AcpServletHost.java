/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SDK's servlet inside a framework's Servlet container (Spring Boot's Tomcat or Jetty,
 * Quarkus' Undertow). The container starts and routes to it; the framework only has to close its
 * connections in time.
 */
public final class AcpServletHost {

	private static final Logger logger = LoggerFactory.getLogger(AcpServletHost.class);

	private AcpServletHost() {
	}

	/**
	 * Closes the servlet's ACP connections. Call it before the container's graceful shutdown:
	 * each connection holds an open SSE response, which the container counts as an active request
	 * and would wait for (Spring: a {@code SmartLifecycle} at {@code DEFAULT_PHASE}). In-flight
	 * prompts are cancelled and every stream completes within the servlet's shutdown timeout.
	 * @param servlet the servlet
	 * @param timeout how long to wait for the connections to close
	 */
	public static void closeBeforeShutdown(StreamableHttpAcpServlet servlet, Duration timeout) {
		try {
			servlet.closeGracefully().block(timeout);
		}
		catch (RuntimeException ex) {
			logger.warn("ACP HTTP connections did not close within {}: {}", timeout, ex.getMessage());
		}
	}

}
