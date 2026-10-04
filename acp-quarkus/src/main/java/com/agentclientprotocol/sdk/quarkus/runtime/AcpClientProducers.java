/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.time.Duration;
import java.util.List;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.quarkus.AcpClientCustomizer;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.quarkus.arc.All;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * The ACP client beans: a transport from {@code quarkus.acp.client.transport.*}, one
 * {@link AcpAsyncClient} on it with the configured request timeout and capabilities,
 * then every {@link AcpClientCustomizer}, and an {@link AcpSyncClient} over that same
 * client. Each is created when first injected, so an application that injects none
 * needs no client configuration, and an application bean of any of these types replaces
 * the default. The client (and with it its transport) is closed once, gracefully, when
 * the application stops.
 *
 * @author Mark Pollack
 */
@Singleton
public class AcpClientProducers {

	private static final Logger logger = LoggerFactory.getLogger(AcpClientProducers.class);

	/** The SDK's default request timeout, which applies when none is configured. */
	private static final Duration SDK_REQUEST_TIMEOUT = Duration.ofSeconds(60);

	private final AcpRuntimeConfig config;

	AcpClientProducers(AcpRuntimeConfig config) {
		this.config = config;
	}

	/**
	 * The transport the configuration selects.
	 * @return the client transport
	 */
	@Produces
	@Singleton
	@DefaultBean
	public AcpClientTransport acpClientTransport() {
		return AcpClientTransports.create(config.client().transport());
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
		AcpClient.AsyncSpec spec = AcpClient.async(transport)
			.clientCapabilities(capabilities(config.client().capabilities()))
			// Replaced by a consumer a customizer adds.
			.defaultSessionUpdateConsumer(AcpClientProducers::logSessionUpdate);
		config.client().requestTimeout().ifPresent(spec::requestTimeout);
		config.client().promptTimeout().ifPresent(spec::promptTimeout);
		customizers.forEach(customizer -> customizer.customize(spec));
		return spec.build();
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
		return new AcpSyncClient(client);
	}

	/**
	 * The capabilities to advertise, from the configuration.
	 * @param capabilities the configured capabilities
	 * @return the client capabilities
	 */
	static AcpSchema.ClientCapabilities capabilities(AcpRuntimeConfig.Capabilities capabilities) {
		AcpSchema.ElicitationCapabilities elicitation = null;
		if (capabilities.elicitationForm() || capabilities.elicitationUrl()) {
			elicitation = new AcpSchema.ElicitationCapabilities(
					capabilities.elicitationForm() ? new AcpSchema.ElicitationFormCapabilities() : null,
					capabilities.elicitationUrl() ? new AcpSchema.ElicitationUrlCapabilities() : null, null);
		}
		return new AcpSchema.ClientCapabilities(
				new AcpSchema.FileSystemCapability(capabilities.readTextFile(), capabilities.writeTextFile()),
				capabilities.terminal(),
				capabilities.booleanConfigOptions() ? AcpSchema.ClientSessionCapabilities.withBooleanConfigOptions()
						: null,
				null, elicitation, null);
	}

	void close(@Disposes AcpAsyncClient client) {
		Duration timeout = config.client().requestTimeout().orElse(SDK_REQUEST_TIMEOUT).plusSeconds(5);
		try {
			client.closeGracefully().block(timeout);
		}
		catch (RuntimeException e) {
			logger.warn("ACP client did not close within {}: {}", timeout, e.getMessage());
			client.close();
		}
	}

	private static Mono<Void> logSessionUpdate(AcpSchema.SessionNotification notification) {
		logger.debug("Session update for {}: {}", notification.sessionId(), notification.update().getClass().getSimpleName());
		return Mono.empty();
	}

}
