/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.client;

import java.util.List;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.integration.AcpClientCustomizer;
import com.agentclientprotocol.sdk.integration.AcpClientTransports;
import com.agentclientprotocol.sdk.integration.AcpClients;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * The configured client, when {@code acp.client.transport.*} is set: its transport, an
 * {@link AcpAsyncClient} and an {@link AcpSyncClient} facade over that same client (one
 * session on one transport connection). The client connects when the application calls
 * {@code initialize()}, and closes gracefully, once, with the application context.
 *
 * <p>
 * The builder gets the configured request timeout and capabilities and a session-update
 * consumer that logs at DEBUG, then every {@link AcpClientCustomizer} bean in order. Replace
 * the transport with an application bean annotated
 * {@code @Replaces(bean = AcpClientTransport.class, factory = AcpClientBeans.class)}.
 */
@Factory
@Requires(property = AcpClientConfiguration.PREFIX + ".transport")
public class AcpClientBeans {

	/**
	 * The transport {@code acp.client.transport} describes, by the SDK's rule
	 * ({@link AcpClientTransports}).
	 * @param config the client settings
	 * @return the transport
	 * @throws IllegalStateException if the settings name no transport, several without a
	 * type, or a type without its command or URI
	 */
	@Singleton
	public AcpClientTransport acpClientTransport(AcpClientConfiguration config) {
		return AcpClientTransports.create(config.toSettings(), AcpClientConfiguration.PREFIX)
			.orElseThrow(() -> new IllegalStateException("An ACP client needs a transport: set "
					+ AcpClientConfiguration.PREFIX + ".transport.stdio.command, .websocket.uri or .http.uri"));
	}

	/**
	 * The client, built once over the transport.
	 * @param transport the client transport
	 * @param config the client settings
	 * @param customizers every customizer bean, in bean order
	 * @return the async client
	 */
	@Singleton
	public AcpAsyncClient acpAsyncClient(AcpClientTransport transport, AcpClientConfiguration config,
			List<AcpClientCustomizer> customizers) {
		return AcpClients.async(transport, config.toSettings(), customizers);
	}

	/**
	 * The sync facade over the one async client. Building it from the transport instead
	 * would connect that transport a second time, which the SDK refuses.
	 * @param client the async client
	 * @return the sync client
	 */
	@Singleton
	public AcpSyncClient acpSyncClient(AcpAsyncClient client) {
		return AcpClients.sync(client);
	}

}
