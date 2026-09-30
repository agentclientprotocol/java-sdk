/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * Blocking for the sync agent API. A request's Mono emits the peer's response or fails:
 * the session delivers every result through a Reactor sink, which cannot carry null, so
 * it never completes empty. An empty completion would be a broken invariant, and is
 * reported as one rather than returned as a null response.
 */
final class SyncBlocking {

	private SyncBlocking() {
	}

	static <T> T awaitResponse(Mono<T> response) {
		return requireResponse(response.block());
	}

	static <T> T awaitResponse(Mono<T> response, Duration timeout) {
		return requireResponse(response.block(timeout));
	}

	private static <T> T requireResponse(@Nullable T value) {
		if (value == null) {
			throw new IllegalStateException("ACP request completed without a response");
		}
		return value;
	}

}
