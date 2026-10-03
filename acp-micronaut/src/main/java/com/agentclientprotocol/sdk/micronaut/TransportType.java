/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut;

/**
 * The ACP transport an agent serves or a client connects with, as configured by
 * {@code acp.agent.transport.type} and {@code acp.client.transport.type}. Configuration
 * values are written in lower or upper case ({@code stdio}, {@code http}, {@code websocket}).
 */
public enum TransportType {

	/** Newline-delimited JSON-RPC over standard input and output. */
	STDIO,

	/**
	 * ACP over WebSocket. An agent serves it from the Streamable HTTP listener, which accepts
	 * WebSocket upgrades on its endpoint, so for an agent it means the same as {@link #HTTP}.
	 */
	WEBSOCKET,

	/** ACP Streamable HTTP. */
	HTTP

}
