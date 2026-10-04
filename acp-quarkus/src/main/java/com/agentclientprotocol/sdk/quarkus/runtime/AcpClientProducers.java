/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.util.List;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.integration.AcpClientCustomizer;
import com.agentclientprotocol.sdk.integration.AcpClientHost;
import com.agentclientprotocol.sdk.integration.AcpClientSettings;
import com.agentclientprotocol.sdk.integration.AcpClientTransports;
import com.agentclientprotocol.sdk.integration.AcpClients;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import io.quarkus.arc.All;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the ACP client beans an application injects: a transport from
 * {@code quarkus.acp.client.transport.*}, one {@link AcpAsyncClient} on it with the configured
 * timeouts and capabilities, then every {@link AcpClientCustomizer}, and an {@link AcpSyncClient}
 * over that same client ({@link AcpClients}), so both share one connection. Inject
 * {@code AcpSyncClient} or {@code AcpAsyncClient}; the producer itself is part of the extension's
 * wiring.
 *
 * <p>Each bean is created when first injected, so an application that injects none needs no client
 * configuration, and an application bean of any of these types replaces the default. Creating the
 * client connects its transport (for stdio, starts the agent process); the application then calls
 * {@code initialize()}. The client (and with it its transport) is closed once, gracefully, when the
 * application stops, waiting at most its request timeout plus 10 seconds.
 *
 * @author Mark Pollack
 */
@Singleton
public class AcpClientProducers {

	private final AcpRuntimeConfig config;

	AcpClientProducers(AcpRuntimeConfig config) {
		this.config = config;
	}

	/**
	 * Produces the transport the configuration selects, by the SDK's rule
	 * ({@link AcpClientTransports}): a {@code type} that is set wins; otherwise the one transport
	 * whose command or URI is set. Not connected yet; the client connects it.
	 * @return the client transport
	 * @throws IllegalStateException when no transport is configured, several are without a type, or
	 * the type lacks its command or URI; the message names the properties
	 */
	@Produces
	@Singleton
	@DefaultBean
	public AcpClientTransport acpClientTransport() {
		String prefix = AcpSettings.CLIENT_PREFIX + ".transport.";
		return AcpClientTransports.create(settings(), AcpSettings.CLIENT_PREFIX)
			.orElseThrow(() -> new IllegalStateException("No ACP client transport is configured: set " + prefix
					+ "stdio.command, " + prefix + "websocket.uri or " + prefix + "http.uri"));
	}

	/**
	 * Produces the client, customized by every {@link AcpClientCustomizer} bean, highest priority
	 * first. Session updates are logged at DEBUG unless a customizer consumes them.
	 * @param transport the client transport
	 * @param customizers the customizer beans
	 * @return the async client
	 * @throws IllegalStateException if a capability property is true but no customizer registers
	 * its handler; the message names the property
	 */
	@Produces
	@Singleton
	@DefaultBean
	public AcpAsyncClient acpAsyncClient(AcpClientTransport transport, @All List<AcpClientCustomizer> customizers) {
		return AcpClients.async(transport, settings(), customizers, AcpSettings.CLIENT_PREFIX);
	}

	/**
	 * Produces the blocking facade over the async client bean, on the same connection.
	 * @param client the async client
	 * @return the sync client
	 */
	@Produces
	@Singleton
	@DefaultBean
	public AcpSyncClient acpSyncClient(AcpAsyncClient client) {
		return AcpClients.sync(client);
	}

	void close(@Disposes AcpAsyncClient client) {
		new AcpClientHost(client).close(settings().closeTimeout());
	}

	private AcpClientSettings settings() {
		return AcpSettings.client(config.client());
	}

}
