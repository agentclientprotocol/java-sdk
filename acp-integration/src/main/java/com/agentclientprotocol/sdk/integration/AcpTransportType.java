/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.util.Locale;

/**
 * The value of the {@code transport.type} setting: the transport an agent is served over
 * ({@link AcpAgentSettings#transport()}) or a client connects with
 * ({@link AcpClientSettings#transport()}). Configuration writes it in any case, as {@code stdio},
 * {@code websocket} or {@code http}; {@link #parse(String)} reads it and {@link #value()} writes
 * it back.
 *
 * <p>For an agent, {@link #WEBSOCKET} means the same as {@link #HTTP}: the SDK's listener serves
 * Streamable HTTP and accepts WebSocket upgrades on the same path
 * ({@link AcpAgentSettings#servesHttp()} is true for both). The SDK's servlet serves HTTP only,
 * so inside a Servlet container WebSocket needs the framework's own routing. For a client the
 * two differ: each selects its own client transport and URI ({@link AcpClientTransports}).
 */
public enum AcpTransportType {

	/**
	 * Newline-delimited JSON-RPC over standard input and output: the agent is a subprocess the
	 * client starts. The agent's default.
	 */
	STDIO,

	/**
	 * ACP over a WebSocket connection, one ACP message per text frame. For an agent, the
	 * same as {@link #HTTP}.
	 */
	WEBSOCKET,

	/**
	 * ACP Streamable HTTP: the client posts its messages to one endpoint and reads the agent's
	 * from Server-Sent Event (SSE) streams.
	 */
	HTTP;

	/**
	 * Returns the type a configuration value names, ignoring case and surrounding white space.
	 * @param value {@code stdio}, {@code websocket} or {@code http}
	 * @return the type
	 * @throws IllegalArgumentException if the value names no type; the message lists the three
	 * values
	 */
	public static AcpTransportType parse(String value) {
		String name = value.trim().toUpperCase(Locale.ROOT);
		for (AcpTransportType type : values()) {
			if (type.name().equals(name)) {
				return type;
			}
		}
		throw new IllegalArgumentException("Unknown ACP transport type '" + value + "': use stdio, websocket or http");
	}

	/**
	 * Returns the configuration value for this type, as messages and settings write it.
	 * @return the lower-case name: {@code stdio}, {@code websocket} or {@code http}
	 */
	public String value() {
		return name().toLowerCase(Locale.ROOT);
	}

}
