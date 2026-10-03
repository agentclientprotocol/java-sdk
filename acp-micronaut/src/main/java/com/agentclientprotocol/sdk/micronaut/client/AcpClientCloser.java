/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.client;

import java.time.Duration;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanPreDestroyEvent;
import io.micronaut.context.event.BeanPreDestroyEventListener;
import jakarta.inject.Singleton;

/**
 * Closes the configured client gracefully, once, when the application context destroys it:
 * pending notifications are delivered (bounded by the request timeout) and the transport is
 * closed (for stdio, the agent's input is closed first so it can exit by itself). The sync
 * facade and the transport are not closed separately.
 */
@Singleton
@Requires(property = AcpClientConfiguration.PREFIX + ".transport")
final class AcpClientCloser implements BeanPreDestroyEventListener<AcpAsyncClient> {

	/** Margin over the client's own bound (its request timeout) when closing waits for it. */
	private static final Duration CLOSE_MARGIN = Duration.ofSeconds(10);

	private final AcpClientConfiguration config;

	AcpClientCloser(AcpClientConfiguration config) {
		this.config = config;
	}

	@Override
	public AcpAsyncClient onPreDestroy(BeanPreDestroyEvent<AcpAsyncClient> event) {
		AcpAsyncClient client = event.getBean();
		client.closeGracefully().block(config.getRequestTimeout().plus(CLOSE_MARGIN));
		return client;
	}

}
