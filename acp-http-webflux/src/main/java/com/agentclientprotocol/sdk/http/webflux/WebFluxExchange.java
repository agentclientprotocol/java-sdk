/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.webflux;

import java.security.Principal;

import com.agentclientprotocol.sdk.http.server.AcpHttpExchange;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.server.ServerWebExchange;

/**
 * A WebFlux request as the endpoint sees it. The body is aggregated without blocking, and no
 * further than one byte past the endpoint's limit: the rest of an oversized body is never read
 * into memory. The principal is the one WebFlux resolved for the exchange (Spring Security's
 * authentication), resolved before the endpoint is called.
 *
 * @author Mark Pollack
 */
final class WebFluxExchange implements AcpHttpExchange {

	private final ServerWebExchange exchange;

	private final @Nullable Principal principal;

	WebFluxExchange(ServerWebExchange exchange, @Nullable Principal principal) {
		this.exchange = exchange;
		this.principal = principal;
	}

	@Override
	public String method() {
		return exchange.getRequest().getMethod().name();
	}

	@Override
	public @Nullable String header(String name) {
		return exchange.getRequest().getHeaders().getFirst(name);
	}

	@Override
	public Mono<byte[]> body(long maxBytes) {
		long limit = Math.min(Integer.MAX_VALUE - 8L, maxBytes + 1);
		return DataBufferUtils.join(DataBufferUtils.takeUntilByteCount(exchange.getRequest().getBody(), limit))
			.map(WebFluxExchange::bytes)
			.defaultIfEmpty(new byte[0]);
	}

	private static byte[] bytes(DataBuffer buffer) {
		try {
			byte[] bytes = new byte[buffer.readableByteCount()];
			buffer.read(bytes);
			return bytes;
		}
		finally {
			DataBufferUtils.release(buffer);
		}
	}

	@Override
	public @Nullable Principal principal() {
		return principal;
	}

}
