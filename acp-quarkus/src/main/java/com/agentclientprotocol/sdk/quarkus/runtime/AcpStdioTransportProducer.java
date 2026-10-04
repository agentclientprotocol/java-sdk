/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import com.agentclientprotocol.sdk.integration.AcpAgentTransports;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the stdio agent transport that {@link AcpStdioAgentHost} serves the agent on. It is a
 * default bean, so an {@link AcpAgentTransport} bean of the application's own replaces it (a test,
 * for one, provides an in-memory transport). The extension adds it only for a stdio agent. Part of
 * the extension's wiring; an application does not use it directly.
 *
 * @author Mark Pollack
 */
@Singleton
public class AcpStdioTransportProducer {

	/**
	 * Returns the transport over the process's standard input and output, which must then carry
	 * nothing else. The agent host closes it when it closes the agent.
	 * @return the stdio transport
	 */
	@Produces
	@Singleton
	@DefaultBean
	public AcpAgentTransport stdioAgentTransport() {
		return AcpAgentTransports.stdio();
	}

}
