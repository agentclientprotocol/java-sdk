/*
 * Copyright 2025-2026 the original author or authors.
 */

/**
 * Framework-neutral building blocks for ACP framework integrations (Spring Boot, Micronaut,
 * Quarkus and any other container). A framework binds its own configuration onto
 * {@link com.agentclientprotocol.sdk.integration.AcpAgentSettings} and
 * {@link com.agentclientprotocol.sdk.integration.AcpClientSettings}, finds the application's
 * {@code @AcpAgent} with {@link com.agentclientprotocol.sdk.integration.AcpAgentDiscovery},
 * assembles it with {@link com.agentclientprotocol.sdk.integration.AcpAgents} or the client with
 * {@link com.agentclientprotocol.sdk.integration.AcpClients}, and drives an
 * {@link com.agentclientprotocol.sdk.integration.AcpHost} from its own lifecycle hooks. Nothing
 * here depends on a framework.
 *
 * <p>
 * The package is null-marked: every type is non-null unless annotated
 * {@link org.jspecify.annotations.Nullable @Nullable}.
 * </p>
 */
@NullMarked
package com.agentclientprotocol.sdk.integration;

import org.jspecify.annotations.NullMarked;
