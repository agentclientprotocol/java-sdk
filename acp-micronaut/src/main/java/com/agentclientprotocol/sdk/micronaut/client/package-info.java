/*
 * Copyright 2025-2026 the original author or authors.
 */

/**
 * Builds an ACP client from {@code acp.client.*}: {@link AcpClientBeans} provides the
 * transport, an {@code AcpAsyncClient} customized by every
 * {@link com.agentclientprotocol.sdk.integration.AcpClientCustomizer AcpClientCustomizer} bean,
 * and an {@code AcpSyncClient} over it.
 *
 * <p>
 * The package is null-marked: every type is non-null unless annotated
 * {@link org.jspecify.annotations.Nullable @Nullable}.
 * </p>
 */
@NullMarked
package com.agentclientprotocol.sdk.micronaut.client;

import org.jspecify.annotations.NullMarked;
