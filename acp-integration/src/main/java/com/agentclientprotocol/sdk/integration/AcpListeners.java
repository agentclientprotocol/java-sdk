/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;

/**
 * The Streamable HTTP endpoints of {@code acp-streamable-http-jetty}, which is optional: the SDK's
 * own listener, or its servlet for a framework's Servlet container. Kept apart so that no class a
 * framework always loads names the optional types; check {@link #isListenerAvailable()} first.
 */
public final class AcpListeners {

	/** The coordinates named when the listener is missing. */
	static final String MODULE = "com.agentclientprotocol:acp-streamable-http-jetty";

	private static final String LISTENER_CLASS = "com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport";

	private static final String JETTY_SERVER_CLASS = "org.eclipse.jetty.server.Server";

	private AcpListeners() {
	}

	/**
	 * Whether the SDK's own listener can run: {@code acp-streamable-http-jetty} and its Jetty
	 * server are on the classpath. (A framework may keep only the servlet and exclude Jetty.)
	 * @return true when {@link #listener} can be called
	 */
	public static boolean isListenerAvailable() {
		return isPresent(LISTENER_CLASS) && isPresent(JETTY_SERVER_CLASS);
	}

	static boolean isPresent(String className) {
		try {
			Class.forName(className, false, AcpListeners.class.getClassLoader());
			return true;
		}
		catch (ClassNotFoundException | LinkageError ex) {
			return false;
		}
	}

	/**
	 * The SDK's listener for HTTP (and WebSocket on the same path), on the settings' listener port
	 * and path, with their limits. Not started.
	 * @param settings the agent settings
	 * @param factory the factory creating one agent per connection
	 * @return the listener
	 * @throws IllegalStateException naming {@code acp-streamable-http-jetty} when it is absent
	 */
	public static StreamableHttpAcpAgentTransport listener(AcpAgentSettings settings, AcpAgentFactory factory) {
		if (!isListenerAvailable()) {
			throw new IllegalStateException("An ACP agent served over " + settings.transport().value() + " needs "
					+ MODULE + " (and its Jetty server) on the classpath");
		}
		AcpAgentSettings.Http http = settings.http();
		return new StreamableHttpAcpAgentTransport(http.listener().port(), http.path(), AcpJsonMapper.createDefault(),
				factory, settings.toOptions(true));
	}

	/**
	 * The SDK's servlet, for HTTP inside the framework's own Servlet container (no WebSocket),
	 * with the settings' limits; the framework maps it at the settings' path.
	 * @param settings the agent settings
	 * @param factory the factory creating one agent per connection
	 * @return the servlet
	 */
	public static StreamableHttpAcpServlet servlet(AcpAgentSettings settings, AcpAgentFactory factory) {
		return new StreamableHttpAcpServlet(AcpJsonMapper.createDefault(), factory, settings.toOptions(false));
	}

}
