/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus;

import com.agentclientprotocol.sdk.client.AcpClient;

/**
 * Customizes the ACP client bean before it is built: a CDI bean of this type adds
 * handlers for the agent's requests and session updates, or changes any setting the
 * configuration made. Customizers run in {@code jakarta.annotation.Priority} order, the
 * highest first.
 *
 * @author Mark Pollack
 */
@FunctionalInterface
public interface AcpClientCustomizer {

	/**
	 * Customizes the client specification.
	 * @param spec the specification the client is built from
	 */
	void customize(AcpClient.AsyncSpec spec);

}
