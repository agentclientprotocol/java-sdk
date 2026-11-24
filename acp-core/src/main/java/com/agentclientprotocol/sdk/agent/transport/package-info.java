/*
 * Copyright 2025-2026 the original author or authors.
 */

/**
 * Agent-side transports: stdio, the in-process {@link InMemoryTransportPair} that also hands out its
 * client side, and the connection abstraction network transports serve sessions through.
 *
 * <p>
 * The package is null-marked: every type is non-null unless annotated
 * {@link org.jspecify.annotations.Nullable @Nullable}.
 * </p>
 */
@NullMarked
package com.agentclientprotocol.sdk.agent.transport;

import org.jspecify.annotations.NullMarked;
