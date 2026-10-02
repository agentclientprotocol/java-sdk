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

}
