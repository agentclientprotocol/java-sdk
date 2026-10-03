/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * The stdio agent transport, unless the application provides its own
 * {@link AcpAgentTransport} bean (a test, for one, provides an in-memory transport).
 *
 * @author Mark Pollack
 */
@Singleton
public class AcpStdioTransportProducer {

	/**
	 * The transport over the process's standard input and output. The agent host closes it
	 * when it closes the agent.
	 * @return the stdio transport
	 */
	@Produces
	@Singleton
	@DefaultBean
	public AcpAgentTransport stdioAgentTransport() {
		return new StdioAcpAgentTransport();
	}

}
