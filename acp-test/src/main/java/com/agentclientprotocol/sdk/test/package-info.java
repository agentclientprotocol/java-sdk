/*
 * Copyright 2025-2026 the original author or authors.
 */

/**
 * Test support for ACP applications: a mock agent and a mock client. The in-process transport
 * they usually run on is acp-core's
 * {@link com.agentclientprotocol.sdk.agent.transport.InMemoryTransportPair}.
 *
 * <p>
 * The package is null-marked: every type is non-null unless annotated
 * {@link org.jspecify.annotations.Nullable @Nullable}.
 * </p>
 */
@NullMarked
package com.agentclientprotocol.sdk.test;

import org.jspecify.annotations.NullMarked;
