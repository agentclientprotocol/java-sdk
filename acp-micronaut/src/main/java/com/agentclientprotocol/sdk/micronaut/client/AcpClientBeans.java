/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.client;

import java.util.List;
import java.util.concurrent.ExecutorService;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.integration.AcpClientCustomizer;
import com.agentclientprotocol.sdk.integration.AcpClientTransports;
import com.agentclientprotocol.sdk.integration.AcpClients;
import com.agentclientprotocol.sdk.integration.AcpTransportThreads;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Singleton;

/**
 * Creates the application's ACP client from {@code acp.client.*} ({@link AcpClientConfiguration})
 * when a transport is named, that is when {@code acp.client.transport.type},
 * {@code .stdio.command}, {@code .websocket.uri} or {@code .http.uri} is set: its transport, an
 * {@link AcpAsyncClient}, and an {@link AcpSyncClient} facade over that same client, so both share
 * one transport connection. Inject either. An application that sets none of these four gets none
 * of these beans, even with other client properties set, such as
 * {@code acp.client.transport.websocket.connect-timeout}; Spring Boot and Quarkus decide the same
 * way ({@link com.agentclientprotocol.sdk.integration.AcpClientSettings#hasTransport()}).
 *
 * <p>The beans are singletons, created when first injected or looked up. Creating the client
 * connects its transport (for stdio, starts the agent process); the application then calls
 * {@code initialize()} and opens sessions. The client closes gracefully, once, with the application
 * context, waiting at most its request timeout plus 10 seconds.
 *
 * <p>The builder gets the configured timeouts and capabilities and a session-update handler that
 * logs at DEBUG, then every {@link AcpClientCustomizer} bean in order; a session-update handler a
 * customizer registers replaces the logging one. Replace the transport with an application bean
 * annotated {@code @Replaces(bean = AcpClientTransport.class, factory = AcpClientBeans.class)}.
 */
@Factory
@Requires(condition = AcpClientTransportCondition.class)
public class AcpClientBeans {

	/**
	 * Creates the transport {@code acp.client.transport.*} describes, by the SDK's rule
	 * ({@link AcpClientTransports}): a {@code type} that is set wins; otherwise the one transport
	 * whose command or URI is set. Not connected yet; the client connects it. The WebSocket and
	 * HTTP transports run on Micronaut's virtual-thread executor ({@code TaskExecutors.VIRTUAL})
	 * on JDK 21 and later, creating no pool of their own; before, on the SDK's own threads.
	 * @param config the client settings
	 * @param context the application context, which holds the virtual-thread executor
	 * @return the transport
	 * @throws IllegalStateException if the settings name no transport, several without a type, or a
	 * type without its command or URI
	 */
	@Singleton
	public AcpClientTransport acpClientTransport(AcpClientConfiguration config, BeanContext context) {
		AcpTransportThreads threads = context
			.findBean(ExecutorService.class, Qualifiers.byName(TaskExecutors.VIRTUAL))
			.map(AcpTransportThreads::executor)
			.orElse(AcpTransportThreads.sdkDefault());
		return AcpClientTransports.create(config.toSettings(), AcpClientConfiguration.PREFIX, threads)
			.orElseThrow(() -> new IllegalStateException("An ACP client needs a transport: set "
					+ AcpClientConfiguration.PREFIX + ".transport.stdio.command, .websocket.uri or .http.uri"));
	}

	/**
	 * Creates the client, once, over the transport, which this connects. Its capabilities and
	 * timeouts come from the configuration; then each customizer is applied to its builder.
	 * @param transport the client transport
	 * @param config the client settings
	 * @param customizers every customizer bean, in bean order
	 * @return the async client
	 * @throws IllegalStateException if a capability property is true but no customizer registers
	 * its handler; the message names the property
	 */
	@Singleton
	public AcpAsyncClient acpAsyncClient(AcpClientTransport transport, AcpClientConfiguration config,
			List<AcpClientCustomizer> customizers) {
		return AcpClients.async(transport, config.toSettings(), customizers, AcpClientConfiguration.PREFIX);
	}

	/**
	 * Creates the sync facade over the one async client. Building it from the transport instead
	 * would connect that transport a second time, which the SDK refuses.
	 * @param client the async client
	 * @return the sync client
	 */
	@Singleton
	public AcpSyncClient acpSyncClient(AcpAsyncClient client) {
		return AcpClients.sync(client);
	}

}
