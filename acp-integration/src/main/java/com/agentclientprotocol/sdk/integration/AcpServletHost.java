/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes the SDK's servlet before a framework's Servlet container shuts down, for an agent served
 * over HTTP inside the framework's own server (Spring Boot's Tomcat or Jetty, Quarkus' Undertow).
 * There the container starts the servlet and routes requests to it, so no {@link AcpHost} is
 * needed; the framework's one job is to call {@link #closeBeforeShutdown} from a stop hook that
 * runs before the container's graceful shutdown. Create the servlet with
 * {@link AcpListeners#servlet}. When the framework has no Servlet container, use the SDK's own
 * listener and {@link AcpListenerHost} instead.
 */
public final class AcpServletHost {

	private static final Logger logger = LoggerFactory.getLogger(AcpServletHost.class);

	private AcpServletHost() {
	}

	/**
	 * Closes every ACP connection the servlet holds and waits for that at most {@code timeout}.
	 * Call it before the container's graceful shutdown begins: each connection holds an open SSE
	 * response, which the container counts as an active request and would wait for until its own
	 * shutdown timeout (in Spring Boot, a {@code SmartLifecycle} in {@code DEFAULT_PHASE} stops
	 * before the graceful shutdown does). As {@link StreamableHttpAcpServlet#closeGracefully()}
	 * does, in-flight prompts are cancelled, their SSE responses complete, an {@code initialize}
	 * still in flight is answered 503, and new connections are refused; a connection whose agent
	 * has not closed within the endpoint's shutdown timeout is closed at once. A close that fails
	 * or does not finish within {@code timeout} is logged as a warning, not thrown, and goes on
	 * after this method returns.
	 * @param servlet the servlet from {@link AcpListeners#servlet}
	 * @param timeout how long to wait for the connections to close; best a little longer than the
	 * endpoint's shutdown timeout, which bounds the close itself
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
