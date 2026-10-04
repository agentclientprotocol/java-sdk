/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;

/**
 * Creates the SDK's Streamable HTTP endpoints, which come from the optional
 * {@code acp-streamable-http-jetty} module: the SDK's own {@link #listener listener}, for a
 * framework without a Servlet container, or its {@link #servlet servlet}, for one with. Both
 * serve one agent per connection from an {@code AcpAgentFactory} that
 * {@link AcpAgents#builder} builds with {@code buildFactory()}, and both apply the endpoint
 * limits of {@link AcpAgentSettings}.
 *
 * <p>The optional module's types appear only here, in {@link AcpListenerHost} and
 * {@link AcpServletHost}, and in {@link AcpAgentSettings#toOptions(boolean)}, so a framework
 * without the module can load the rest of the package. Use them only when the agent is served
 * over HTTP, and call {@link #isListenerAvailable()} before {@link #listener} to report a
 * missing module in the framework's own words.
 */
public final class AcpListeners {

	/** The coordinates named when the listener is missing. */
	static final String MODULE = "com.agentclientprotocol:acp-streamable-http-jetty";

	private static final String LISTENER_CLASS = "com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport";

	private static final String JETTY_SERVER_CLASS = "org.eclipse.jetty.server.Server";

	private AcpListeners() {
	}

	/**
	 * Returns whether the SDK's own listener can run: {@code acp-streamable-http-jetty} and its
	 * Jetty server are both on the classpath. Checked without initialising either class. A
	 * framework that uses only the servlet may exclude the Jetty server, so this can be false
	 * while {@link #servlet} still works.
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
	 * Returns the SDK's listener on the settings' listener port and path: HTTP/1.1, cleartext
	 * HTTP/2 and WebSocket upgrades on that one path, with the settings' limits and the
	 * listener's stream limit, and the default JSON mapper. It is not started; give it to an
	 * {@link AcpListenerHost}.
	 * @param settings the agent settings
	 * @param factory the factory creating one agent per connection
	 * @return the listener, not started
	 * @throws IllegalStateException if {@code acp-streamable-http-jetty} or its Jetty server is
	 * not on the classpath; the message names the module
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
	 * Returns the SDK's servlet, for Streamable HTTP inside the framework's own Servlet container,
	 * with the settings' limits (not the listener's port or stream limit) and the default JSON
	 * mapper. The framework registers it at {@link AcpAgentSettings.Http#path()} with async
	 * support on, and calls {@link AcpServletHost#closeBeforeShutdown} before the container shuts
	 * down. The servlet serves no WebSocket: a framework that wants WebSocket here routes the
	 * upgrades itself (Quarkus does, through Vert.x), and otherwise a {@code websocket} transport
	 * setting gets HTTP only.
	 * @param settings the agent settings
	 * @param factory the factory creating one agent per connection
	 * @return the servlet
	 */
	public static StreamableHttpAcpServlet servlet(AcpAgentSettings settings, AcpAgentFactory factory) {
		return new StreamableHttpAcpServlet(AcpJsonMapper.createDefault(), factory, settings.toOptions(false));
	}

}
