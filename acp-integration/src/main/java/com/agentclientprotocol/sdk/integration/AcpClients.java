/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Builds the application's client the same way in every framework: one async client on the
 * transport, and a sync facade over that same client.
 */
public final class AcpClients {

	private static final Logger logger = LoggerFactory.getLogger(AcpClients.class);

	private AcpClients() {
	}

	/**
	 * The async client: the settings' capabilities and request timeout (unset keeps the SDK
	 * default), a session-update consumer that logs at DEBUG, then the customizers in order. The
	 * client connects when the application calls {@code initialize()}.
	 * @param transport the client transport
	 * @param settings the client settings
	 * @param customizers the customizers, in order
	 * @return the client
	 */
	public static AcpAsyncClient async(AcpClientTransport transport, AcpClientSettings settings,
			List<? extends AcpClientCustomizer> customizers) {
		AcpClient.AsyncSpec spec = AcpClient.async(transport)
			.clientCapabilities(settings.capabilities().toClientCapabilities());
		Duration requestTimeout = settings.requestTimeout();
		if (requestTimeout != null) {
			spec.requestTimeout(requestTimeout);
		}
		// TODO(fix4): pass settings.promptTimeout() to AsyncSpec.promptTimeout once it exists.
		// TODO(api1): register this as the replaceable default, so that a customizer's own consumer
		// replaces it rather than runs beside it. Today AsyncSpec.sessionUpdateConsumer only adds.
		// Session updates always have a consumer, so the SDK does not warn about an unhandled one.
		spec.sessionUpdateConsumer(AcpClients::logSessionUpdate);
		customizers.forEach(customizer -> customizer.customize(spec));
		return spec.build();
	}

	/**
	 * The sync facade over the one async client: one session on one transport connection.
	 * Building a sync client from the transport instead would connect it a second time, which the
	 * SDK refuses.
	 * @param async the async client
	 * @return the sync client
	 */
	public static AcpSyncClient sync(AcpAsyncClient async) {
		return new AcpSyncClient(async);
	}

	static Mono<Void> logSessionUpdate(AcpSchema.SessionNotification notification) {
		logger.debug("Session update for {}: {}", notification.sessionId(), notification.update());
		return Mono.empty();
	}

}
