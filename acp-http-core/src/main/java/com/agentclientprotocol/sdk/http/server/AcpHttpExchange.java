/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.security.Principal;
import java.util.Locale;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * A host's view of one HTTP request to the ACP endpoint, or of a WebSocket handshake: the
 * method, the headers, the body and the authenticated principal, as the host's framework
 * received them. The host creates one per request and hands it to
 * {@link AcpHttpEndpoint#handle} or {@link AcpHttpEndpoint#webSocketHandshake}; the endpoint
 * reads it on the calling thread, except for {@link #body}, which it subscribes to once.
 *
 * @author Mark Pollack
 */
@UnstableAcpApi
public interface AcpHttpExchange {

	/**
	 * Returns the request method, such as {@code POST}.
	 * @return the method, in upper case
	 */
	String method();

	/**
	 * Returns the first value of a request header, compared without regard to case.
	 * @param name the header name, such as {@code Acp-Connection-Id}
	 * @return the value, or null when the request has no such header
	 */
	@Nullable String header(String name);

	/**
	 * Reads the request body, reading at most {@code maxBytes + 1} bytes so that the endpoint can
	 * tell an oversized body from one at the limit without the host buffering more. A servlet host
	 * reads it from the request's input stream; a reactive host aggregates it without blocking.
	 * @param maxBytes the largest body the endpoint accepts
	 * @return the body, or its first {@code maxBytes + 1} bytes; empty bytes for no body
	 */
	Mono<byte[]> body(long maxBytes);

	/**
	 * Returns the principal the host's security authenticated for this request, such as the
	 * servlet's {@code getUserPrincipal()}. Framework security runs in the host, before the
	 * endpoint; the endpoint has no authentication of its own.
	 * @return the principal, or null for an unauthenticated request
	 */
	@Nullable Principal principal();

	/**
	 * Returns whether this request asks to upgrade to WebSocket ({@code Upgrade: websocket}). A
	 * host that serves WebSocket gives such a request to
	 * {@link AcpHttpEndpoint#webSocketHandshake} instead of {@link AcpHttpEndpoint#handle}.
	 * @return true for a WebSocket upgrade request
	 */
	default boolean isWebSocketUpgrade() {
		String upgrade = header("Upgrade");
		return upgrade != null && upgrade.trim().toLowerCase(Locale.ROOT).equals("websocket");
	}

}
