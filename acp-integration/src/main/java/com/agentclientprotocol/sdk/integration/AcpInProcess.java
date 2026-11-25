/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import com.agentclientprotocol.sdk.agent.transport.InMemoryTransportPair;
import com.agentclientprotocol.sdk.client.AcpSyncClient;

/**
 * The in-process connection a framework makes when the agent's {@code transport.type} is
 * {@code in-process}: one {@link InMemoryTransportPair} for the application, the
 * {@code @AcpAgent} served on its agent side by an {@link AcpAgentHost}, and the application's
 * client built on its client side by {@link AcpClients}, with the client settings and handler
 * beans. Every framework does the same three steps:
 * <ol>
 * <li>{@link #pair} once, as a singleton, and its two transports as the agent and client
 * transports;</li>
 * <li>start the agent host, then {@link #initialize} the client, in the start hook, so the
 * application's client is ready when the application has started;</li>
 * <li>on stop, close the client ({@link AcpClientHost}), then stop the agent host. The end of
 * this transport never stops the application ({@link AcpAgentSettings#shutsDownWhenEnded}).</li>
 * </ol>
 *
 * @author Mark Pollack
 */
public final class AcpInProcess {

	private AcpInProcess() {
	}

	/**
	 * Returns the pair for the application's in-process agent and client, after checking that no
	 * client transport is configured as well: the in-process client is the application's client,
	 * and a second one, to another agent, would compete with it for the client beans.
	 * @param client the client settings
	 * @param clientPrefix the framework's prefix of the client keys, such as {@code spring.acp.client}
	 * @param agentPrefix the framework's prefix of the agent keys, such as {@code spring.acp.agent}
	 * @return a new pair
	 * @throws IllegalStateException if a client transport is configured; the message names both
	 * settings
	 */
	public static InMemoryTransportPair pair(AcpClientSettings client, String clientPrefix, String agentPrefix) {
		return pair(client.hasTransport(), clientPrefix, agentPrefix);
	}

	/**
	 * Returns the pair as {@link #pair(AcpClientSettings, String, String)} does, for a framework
	 * that tells from its configuration whether a client transport is set
	 * ({@code transport.type}, {@code .stdio.command}, {@code .websocket.uri} or {@code .http.uri}
	 * under the client prefix, as {@link AcpClientSettings#hasTransport()} decides).
	 * @param clientTransportConfigured whether a client transport is configured
	 * @param clientPrefix the framework's prefix of the client keys
	 * @param agentPrefix the framework's prefix of the agent keys
	 * @return a new pair
	 * @throws IllegalStateException if a client transport is configured
	 */
	public static InMemoryTransportPair pair(boolean clientTransportConfigured, String clientPrefix,
			String agentPrefix) {
		if (clientTransportConfigured) {
			throw new IllegalStateException(agentPrefix + ".transport.type=in-process connects the application's client "
					+ "to its own agent; remove " + clientPrefix + ".transport.*, which configures a client of another agent");
		}
		return InMemoryTransportPair.create();
	}

	/**
	 * Initializes the in-process client, once its agent has started: the ACP handshake, so the
	 * application can open sessions at once. Blocks for the answer, within the client's request
	 * timeout.
	 * @param client the application's client, on the pair's client side
	 * @throws RuntimeException if the agent refuses or does not answer in time; the framework's
	 * startup then fails
	 */
	public static void initialize(AcpSyncClient client) {
		client.initialize();
	}

}
