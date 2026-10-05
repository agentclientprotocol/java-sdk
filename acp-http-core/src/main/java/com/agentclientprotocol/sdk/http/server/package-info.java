/*
 * Copyright 2025-2026 the original author or authors.
 */

/**
 * The ACP host contract: the one framework-neutral ACP endpoint ({@link AcpHttpEndpoint}) and
 * the types a server adapter (a host) uses to mount it in its framework's router and lifecycle.
 *
 * <p>Every protocol rule lives behind {@link AcpHttpEndpoint}: method and header checks, the
 * status and WebSocket close-code table, connection and session routing, the bounded SSE
 * mailboxes and WebSocket send queue, the {@code Origin} check, keep-alive and shutdown. A host
 * only adapts I/O: it turns its framework's request into an {@link AcpHttpExchange}, writes the
 * {@link AcpHttpReply} it gets back, and carries WebSocket frames between its framework's
 * socket and an {@link AcpWsHandler}. Hosts never decide a status code, a header or a close
 * code themselves, and every host passes the shared transport TCK in {@code acp-test}.
 *
 * <p>Applications use the hosts the SDK ships (the servlet, the embedded listener, the Spring
 * Boot, Quarkus and Micronaut integrations); this package is for writing a host, and is
 * {@link com.agentclientprotocol.sdk.annotation.UnstableAcpApi unstable}: it may change in a
 * minor release while the set of hosts grows.
 *
 * <p>The package is null-marked: every type is non-null unless annotated
 * {@link org.jspecify.annotations.Nullable @Nullable}.
 */
@NullMarked
@UnstableAcpApi
package com.agentclientprotocol.sdk.http.server;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import org.jspecify.annotations.NullMarked;
