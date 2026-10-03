/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.client;

import com.agentclientprotocol.sdk.client.AcpClient;

/**
 * Customizes the configured client before it is built: register a session-update consumer, a
 * permission handler, file system or terminal handlers, or anything else the SDK's
 * {@link AcpClient.AsyncSpec} takes. Every {@code AcpClientCustomizer} bean is applied, in
 * bean order ({@code @Order} or {@code Ordered}), to the one builder behind both
 * {@code AcpAsyncClient} and {@code AcpSyncClient}.
 */
@FunctionalInterface
public interface AcpClientCustomizer {

	/**
	 * Customizes the client builder.
	 * @param spec the builder of the configured client
	 */
	void customize(AcpClient.AsyncSpec spec);

}
