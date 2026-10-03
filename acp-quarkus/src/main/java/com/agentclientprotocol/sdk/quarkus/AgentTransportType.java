/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus;

/**
 * How a Quarkus-hosted ACP agent is reached: over standard input and output, or over
 * Streamable HTTP (with WebSocket upgrades on the same path) on the Quarkus HTTP server.
 *
 * @author Mark Pollack
 */
public enum AgentTransportType {

	/** JSON-RPC over the process's standard input and output. */
	STDIO,

	/** Streamable HTTP and WebSocket at {@code quarkus.acp.agent.transport.http.path}. */
	HTTP

}
