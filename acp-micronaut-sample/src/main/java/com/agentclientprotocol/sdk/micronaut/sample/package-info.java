/*
 * Copyright 2025-2026 the original author or authors.
 */

/**
 * A Micronaut application serving an annotated ACP agent: {@link Application} starts it, and
 * {@link EchoAgent} is the agent, a Micronaut bean that takes other beans by injection.
 *
 * <p>
 * The package is null-marked: every type is non-null unless annotated
 * {@link org.jspecify.annotations.Nullable @Nullable}.
 * </p>
 */
@NullMarked
package com.agentclientprotocol.sdk.micronaut.sample;

import org.jspecify.annotations.NullMarked;
