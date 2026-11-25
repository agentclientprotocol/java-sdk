/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.inprocess;

import java.util.List;

import com.agentclientprotocol.sdk.agent.transport.InMemoryTransportPair;
import com.agentclientprotocol.sdk.integration.AcpInProcess;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * The in-process connection, with {@code acp.agent.transport.type=in-process}: one
 * {@link InMemoryTransportPair} for the application, its agent side the transport the
 * {@code @AcpAgent} bean is served on, and its client side the transport of the application's
 * {@code AcpSyncClient} and {@code AcpAsyncClient}. The agent runtime initializes the client once
 * the agent has started; closing the context closes the client, then the agent. Setting an
 * {@code acp.client.transport.*} property as well fails the startup.
 */
@Factory
@Requires(condition = AcpInProcessCondition.class)
public class AcpInProcessBeans {

	private static final List<String> CLIENT_TRANSPORT_KEYS = List.of("type", "stdio.command", "websocket.uri",
			"http.uri");

	/**
	 * Creates the pair.
	 * @param context the application context, whose configuration must set no client transport
	 * @return the pair
	 * @throws IllegalStateException if a client transport is configured as well
	 */
	@Singleton
	public InMemoryTransportPair acpInProcessTransportPair(ApplicationContext context) {
		boolean clientTransport = CLIENT_TRANSPORT_KEYS.stream()
			.anyMatch(key -> context.containsProperty("acp.client.transport." + key));
		return AcpInProcess.pair(clientTransport, "acp.client", "acp.agent");
	}

	/**
	 * The pair's agent side, which the agent runtime serves the {@code @AcpAgent} bean on and
	 * closes with it.
	 * @param pair the pair
	 * @return the agent transport
	 */
	@Singleton
	public AcpAgentTransport acpInProcessAgentTransport(InMemoryTransportPair pair) {
		return pair.agentTransport();
	}

	/**
	 * The pair's client side, which the application's client is built on and closes with it.
	 * @param pair the pair
	 * @return the client transport
	 */
	@Singleton
	public AcpClientTransport acpInProcessClientTransport(InMemoryTransportPair pair) {
		return pair.clientTransport();
	}

}
