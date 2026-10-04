/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;

/**
 * The single agent transports a framework creates itself. A listener transport (HTTP and
 * WebSocket) is in {@link AcpListeners}, apart, because its module is optional.
 */
public final class AcpAgentTransports {

	private AcpAgentTransports() {
	}

	/**
	 * A stdio transport over {@code System.in} and {@code System.out}, which must then carry
	 * nothing else: send logging to standard error.
	 * @return a new stdio transport
	 */
	public static AcpAgentTransport stdio() {
		return new StdioAcpAgentTransport();
	}

}
