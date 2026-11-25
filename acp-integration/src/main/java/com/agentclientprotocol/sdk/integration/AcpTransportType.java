/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.util.Locale;

/**
 * The value of the {@code transport.type} setting: the transport an agent is served over
 * ({@link AcpAgentSettings#transport()}) or a client connects with
 * ({@link AcpClientSettings#transport()}). Configuration writes it in any case, as {@code stdio},
 * {@code websocket}, {@code http} or {@code in-process}; {@link #parse(String)} reads it and
 * {@link #value()} writes it back.
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
	HTTP,

	/**
	 * In the application's own JVM: the framework serves the agent on one side of an
	 * {@link com.agentclientprotocol.sdk.agent.transport.InMemoryTransportPair} and gives the
	 * application its client on the other side, initialized once the agent has started. An agent
	 * setting only: the client follows from it, so a client's {@code transport.type} cannot name it.
	 */
	IN_PROCESS;

	/**
	 * Returns the type a configuration value names, ignoring case, surrounding white space, and
	 * whether words are joined by {@code -} or {@code _}.
	 * @param value {@code stdio}, {@code websocket}, {@code http} or {@code in-process}
	 * @return the type
	 * @throws IllegalArgumentException if the value names no type; the message lists the four
	 * values
	 */
	public static AcpTransportType parse(String value) {
		String name = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
		for (AcpTransportType type : values()) {
			if (type.name().equals(name)) {
				return type;
			}
		}
		throw new IllegalArgumentException(
				"Unknown ACP transport type '" + value + "': use stdio, websocket, http or in-process");
	}

	/**
	 * Returns the configuration value for this type, as messages and settings write it.
	 * @return the lower-case name, words joined by {@code -}: {@code stdio}, {@code websocket},
	 * {@code http} or {@code in-process}
	 */
	public String value() {
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

}
