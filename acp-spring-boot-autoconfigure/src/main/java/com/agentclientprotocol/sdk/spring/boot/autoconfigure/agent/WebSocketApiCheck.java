/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.security.CodeSource;
import java.util.Arrays;

import org.jspecify.annotations.Nullable;

/**
 * Refuses to serve ACP on Tomcat 11 when the Jakarta WebSocket API classes on the classpath are
 * older than the 2.2 that Tomcat implements: Tomcat then fails every WebSocket send with
 * {@code NoSuchMethodError} ({@code SendResult(Session)}), and clients hang without a word in
 * the server's log. It happens when a build takes the API from Jetty's jars without managing its
 * version, as adding {@code acp-streamable-http-jetty} to a Boot web application used to do.
 * Checked by name, so nothing here loads unless Tomcat's WebSocket support is present.
 *
 * @author Mark Pollack
 */
final class WebSocketApiCheck {

	private static final String TOMCAT_WEBSOCKET = "org.apache.tomcat.websocket.server.WsSci";

	private WebSocketApiCheck() {
	}

	/**
	 * Fails when Tomcat's WebSocket support sees an older Jakarta WebSocket API.
	 * @param classLoader the application's class loader
	 * @throws IllegalStateException naming the fix
	 */
	static void requireCompatible(@Nullable ClassLoader classLoader) {
		String problem = problem(classLoader);
		if (problem != null) {
			throw new IllegalStateException(problem);
		}
	}

	static @Nullable String problem(@Nullable ClassLoader classLoader) {
		Class<?> sendResult;
		Class<?> session;
		try {
			Class.forName(TOMCAT_WEBSOCKET, false, classLoader);
			sendResult = Class.forName("jakarta.websocket.SendResult", false, classLoader);
			session = Class.forName("jakarta.websocket.Session", false, classLoader);
		}
		catch (ClassNotFoundException | LinkageError e) {
			return null;
		}
		boolean api22 = Arrays.stream(sendResult.getConstructors())
			.anyMatch(constructor -> Arrays.equals(constructor.getParameterTypes(), new Class<?>[] { session }));
		if (api22) {
			return null;
		}
		return "ACP WebSocket on Tomcat 11 needs the Jakarta WebSocket 2.2 API, but the "
					+ "jakarta.websocket classes come from an older one (" + location(sendResult)
				+ "), and Tomcat would fail every WebSocket send. In a servlet web application add "
				+ "com.agentclientprotocol:acp-http-servlet, not acp-streamable-http-jetty (the SDK's own "
				+ "listener, for applications without a web server), or manage "
				+ "jakarta.websocket:jakarta.websocket-api and jakarta.websocket-client-api to 2.2.0";
	}

	private static String location(Class<?> type) {
		CodeSource source = type.getProtectionDomain().getCodeSource();
		return (source != null && source.getLocation() != null) ? source.getLocation().toString() : "unknown jar";
	}

}
