/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.util.Locale;

/**
 * The transport an agent is served over or a client connects with. Configuration values are
 * written in any case: {@code stdio}, {@code websocket}, {@code http}.
 *
 * <p>
 * For an agent, {@link #WEBSOCKET} means the same as {@link #HTTP}: the Streamable HTTP endpoint
 * accepts WebSocket upgrades on its path.
 * </p>
 */
public enum AcpTransportType {

	/** Newline-delimited JSON-RPC over standard input and output. */
	STDIO,

	/** ACP over WebSocket. */
	WEBSOCKET,

	/** ACP Streamable HTTP. */
	HTTP;

	/**
	 * The type a configuration value names, in any case.
	 * @param value {@code stdio}, {@code websocket} or {@code http}
	 * @return the type
	 * @throws IllegalArgumentException if the value names no type
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
	 * The configuration value for this type.
	 * @return the lower-case name
	 */
	public String value() {
		return name().toLowerCase(Locale.ROOT);
	}

}
