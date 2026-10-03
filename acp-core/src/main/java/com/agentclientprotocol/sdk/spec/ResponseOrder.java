/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import reactor.core.publisher.Mono;

/**
 * Where a session's responses take their place among the notifications it receives: a
 * response can be held until the notifications that arrived before it have been handled.
 */
interface ResponseOrder {

	/** Responses complete their caller as they arrive. */
	ResponseOrder IMMEDIATE = new ResponseOrder() {

		@Override
		public long position() {
			return 0;
		}

		@Override
		public <R> Mono<R> after(long sentAt, R response) {
			return Mono.just(response);
		}

	};

	/**
	 * How many notifications have arrived so far; read when a request is sent, and passed
	 * back to {@link #after} with its response.
	 */
	long position();

	/**
	 * The response, once it may complete its caller.
	 * @param sentAt the {@link #position} when the request was sent
	 * @param response the response, which has just arrived
	 * @return a Mono emitting the response when its caller may have it
	 */
	<R> Mono<R> after(long sentAt, R response);

	/**
	 * Records that a request sent at {@code sentAt} is waiting for its response, so that what
	 * the peer asks meanwhile is not held behind a handler that may be the one waiting.
	 * @param sentAt the {@link #position} when the request was sent
	 * @return run once the request no longer waits (answered, failed or cancelled)
	 */
	default Runnable awaiting(long sentAt) {
		return () -> {
		};
	}

}
