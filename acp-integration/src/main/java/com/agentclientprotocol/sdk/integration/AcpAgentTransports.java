/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;

/**
 * Creates the agent transport a framework uses when it serves one agent on stdio, for an
 * {@link AcpAgentHost}. The HTTP and WebSocket endpoints are in {@link AcpListeners}, apart,
 * because they come from the optional {@code acp-streamable-http-jetty} module; a class the
 * framework always loads must not name those types.
 */
public final class AcpAgentTransports {

	private AcpAgentTransports() {
	}

	/**
	 * Returns a new stdio transport over {@code System.in} and {@code System.out}, for the
	 * builder from {@link AcpAgents#builder}. Standard output then carries ACP messages only, so
	 * the framework must send its logging and banner to standard error or turn them off; anything
	 * else written there corrupts the stream the client reads. The transport ends by itself when
	 * the client closes the agent's input and every answer has been written; an
	 * {@link AcpAgentHost} then runs its transport-end action.
	 * @return a new stdio transport, not started
	 */
	public static AcpAgentTransport stdio() {
		return new StdioAcpAgentTransport();
	}

}
