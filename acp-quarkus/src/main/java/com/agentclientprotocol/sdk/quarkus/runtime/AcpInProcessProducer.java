/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import com.agentclientprotocol.sdk.agent.transport.InMemoryTransportPair;
import com.agentclientprotocol.sdk.integration.AcpInProcess;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * The in-process connection, with {@code quarkus.acp.agent.transport.type=in-process}: one
 * {@link InMemoryTransportPair} for the application, its agent side the transport
 * {@link AcpStdioAgentHost} serves the {@code @AcpAgent} bean on, and its client side the transport
 * of the application's {@code AcpSyncClient} and {@code AcpAsyncClient}, which replaces the client
 * transport from {@code quarkus.acp.client.transport.*}. The host initializes the client once the
 * agent has started. The extension adds it only for an in-process agent; setting a
 * {@code quarkus.acp.client.transport.*} property as well fails the startup. Part of the extension's
 * wiring; an application does not use it directly.
 *
 * @author Mark Pollack
 */
@Singleton
public class AcpInProcessProducer {

	private final AcpRuntimeConfig config;

	AcpInProcessProducer(AcpRuntimeConfig config) {
		this.config = config;
	}

	/**
	 * Returns the pair.
	 * @return the pair
	 * @throws IllegalStateException if a client transport is configured as well
	 */
	@Produces
	@Singleton
	public InMemoryTransportPair acpInProcessTransportPair() {
		return AcpInProcess.pair(AcpSettings.client(config.client()), AcpSettings.CLIENT_PREFIX, "quarkus.acp.agent");
	}

	/**
	 * Returns the pair's agent side, which the host closes with the agent.
	 * @param pair the pair
	 * @return the agent transport
	 */
	@Produces
	@Singleton
	public AcpAgentTransport acpInProcessAgentTransport(InMemoryTransportPair pair) {
		return pair.agentTransport();
	}

	/**
	 * Returns the pair's client side, which the client closes with itself.
	 * @param pair the pair
	 * @return the client transport
	 */
	@Produces
	@Singleton
	public AcpClientTransport acpInProcessClientTransport(InMemoryTransportPair pair) {
		return pair.clientTransport();
	}

}
