/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.security.Principal;
import java.util.Locale;

import com.agentclientprotocol.sdk.http.server.AcpHttpExchange;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * A servlet request as the endpoint sees it. The body is read on the request thread, blocking,
 * as a servlet reads a JSON body, and never beyond the endpoint's limit.
 *
 * @author Mark Pollack
 */
final class ServletExchange implements AcpHttpExchange {

	private final HttpServletRequest request;

	ServletExchange(HttpServletRequest request) {
		this.request = request;
	}

	@Override
	public String method() {
		return request.getMethod().toUpperCase(Locale.ROOT);
	}

	@Override
	public @Nullable String header(String name) {
		return request.getHeader(name);
	}

	@Override
	public Mono<byte[]> body(long maxBytes) {
		return Mono.fromCallable(
				() -> request.getInputStream().readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1)));
	}

	@Override
	public @Nullable Principal principal() {
		return request.getUserPrincipal();
	}

}
