/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.client;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.integration.AcpClientHost;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanPreDestroyEvent;
import io.micronaut.context.event.BeanPreDestroyEventListener;
import jakarta.inject.Singleton;

/**
 * Closes the configured client gracefully, once, when the application context destroys it
 * ({@link AcpClientHost}): pending notifications are delivered (bounded by the request
 * timeout) and the transport is closed. The sync facade and the transport are not closed
 * separately.
 */
@Singleton
@Requires(property = AcpClientConfiguration.PREFIX + ".transport")
final class AcpClientCloser implements BeanPreDestroyEventListener<AcpAsyncClient> {

	private final AcpClientConfiguration config;

	AcpClientCloser(AcpClientConfiguration config) {
		this.config = config;
	}

	@Override
	public AcpAsyncClient onPreDestroy(BeanPreDestroyEvent<AcpAsyncClient> event) {
		AcpAsyncClient client = event.getBean();
		new AcpClientHost(client).close(config.toSettings().closeTimeout());
		return client;
	}

}
