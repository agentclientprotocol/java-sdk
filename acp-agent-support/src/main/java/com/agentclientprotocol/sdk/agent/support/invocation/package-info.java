/*
 * Copyright 2025-2026 the original author or authors.
 */

/**
 * The invocation model shared by argument resolvers, return-value handlers and interceptors: the
 * per-call {@link com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext} and the
 * {@link com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter} metadata they inspect.
 *
 * <p>
 * It sits beneath the {@code resolver}, {@code handler} and {@code interceptor} packages and depends on
 * none of them, nor on {@code AcpAgentSupport}, so extension points can be written against it without
 * a package cycle.
 * </p>
 *
 * <p>
 * The package is null-marked: every type is non-null unless annotated
 * {@link org.jspecify.annotations.Nullable @Nullable}.
 * </p>
 */
@NullMarked
package com.agentclientprotocol.sdk.agent.support.invocation;

import org.jspecify.annotations.NullMarked;
