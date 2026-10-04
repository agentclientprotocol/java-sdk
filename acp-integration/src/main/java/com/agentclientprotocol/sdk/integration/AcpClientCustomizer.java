/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import com.agentclientprotocol.sdk.client.AcpClient;

/**
 * Lets the application add to the ACP client a framework builds: a session-update consumer, a
 * permission handler, file system, terminal or elicitation handlers, or anything else the SDK's
 * {@link AcpClient.AsyncSpec} takes. The application declares customizers as beans; the
 * framework passes every one, in its bean order, to {@link AcpClients#async}, which applies them
 * to the one builder behind both the async and the sync client. This is the one type of this
 * package an application on Spring Boot, Micronaut or Quarkus uses directly.
 *
 * <p>A capability turned on in the settings ({@link AcpClientSettings.Capabilities}) needs its
 * handler registered here, or building the client fails. A session-update consumer added here
 * replaces the default one that only logs. The builder already has a default session-update
 * consumer, so a customizer must not call {@code defaultSessionUpdateConsumer}, which then
 * fails.
 */
@FunctionalInterface
public interface AcpClientCustomizer {

	/**
	 * Adds to the client builder, before the client is built. Called once per client, on the
	 * thread that creates it, after the settings were applied and before later customizers.
	 * @param spec the builder of the client
	 */
	void customize(AcpClient.AsyncSpec spec);

}
