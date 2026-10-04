/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import com.agentclientprotocol.sdk.client.AcpClient;

/**
 * Customizes the framework-built client before it is built: register a session-update consumer, a
 * permission handler, file system or terminal handlers, or anything else the SDK's
 * {@link AcpClient.AsyncSpec} takes. A framework applies every customizer bean, in its bean order,
 * to the one builder behind both the async and the sync client ({@link AcpClients}).
 */
@FunctionalInterface
public interface AcpClientCustomizer {

	/**
	 * Customizes the client builder.
	 * @param spec the builder of the client
	 */
	void customize(AcpClient.AsyncSpec spec);

}
