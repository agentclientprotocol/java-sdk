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
 * The ACP client beans: a transport from {@code quarkus.acp.client.transport.*}, one
 * {@link AcpAsyncClient} on it with the configured request timeout and capabilities,
 * then every {@link AcpClientCustomizer}, and an {@link AcpSyncClient} over that same
 * client ({@link AcpClients}). Each is created when first injected, so an application that
 * injects none needs no client configuration, and an application bean of any of these types
 * replaces the default. The client (and with it its transport) is closed once, gracefully,
 * when the application stops.
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
	 * The transport the configuration selects, by the SDK's rule ({@link AcpClientTransports}).
	 * @return the client transport
	 * @throws IllegalStateException when no transport, or several without a type, are configured
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
	 * The client, customized by every {@link AcpClientCustomizer} bean, highest priority
	 * first. Session updates are logged at DEBUG unless a customizer consumes them.
	 * @param transport the client transport
	 * @param customizers the customizer beans
	 * @return the async client
	 */
	@Produces
	@Singleton
	@DefaultBean
	public AcpAsyncClient acpAsyncClient(AcpClientTransport transport, @All List<AcpClientCustomizer> customizers) {
		return AcpClients.async(transport, settings(), customizers);
	}

	/**
	 * A blocking facade over the async client bean.
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
