/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.security.Principal;

import com.agentclientprotocol.sdk.http.server.AcpHttpExchange;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * A Vert.x request as the endpoint sees it. The body is aggregated without blocking the event
 * loop (or taken from a body handler that already ran); Quarkus's
 * {@code quarkus.http.limits.max-body-size} bounds what the server reads, and the endpoint checks
 * its own limit. The principal is the one Quarkus security authenticated.
 *
 * @author Mark Pollack
 */
final class VertxExchange implements AcpHttpExchange {

	private final RoutingContext context;

	VertxExchange(RoutingContext context) {
		this.context = context;
	}

	@Override
	public String method() {
		return context.request().method().name();
	}

	@Override
	public @Nullable String header(String name) {
		return context.request().getHeader(name);
	}

	@Override
	public Mono<byte[]> body(long maxBytes) {
		RequestBody handled = context.body();
		if (handled != null && handled.available()) {
			Buffer buffer = handled.buffer();
			return Mono.just(buffer != null ? buffer.getBytes() : new byte[0]);
		}
		return Mono.fromCompletionStage(() -> {
			// Quarkus pauses a request until a handler wants its body.
			var body = context.request().body();
			context.request().resume();
			return body.toCompletionStage();
		}).map(Buffer::getBytes);
	}

	@Override
	public @Nullable Principal principal() {
		if (context.user() instanceof QuarkusHttpUser user && !user.getSecurityIdentity().isAnonymous()) {
			return user.getSecurityIdentity().getPrincipal();
		}
		return null;
	}

}
