/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import org.reactivestreams.Publisher;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Graceful cancellation of the requests a session sends (ACP v1, Cancellation: "the calling
 * side MAY implement graceful cancellation processing by waiting for the response").
 *
 * <p>
 * Disposing a request's subscription sends {@code $/cancel_request} and stops listening: the
 * peer's answer is discarded. To cancel a request but still receive the peer's answer, write a
 * trigger into the request's Reactor context:
 * </p>
 *
 * <pre>{@code
 * context.readTextFile(request)
 *     .contextWrite(RequestCancellation.cancelWhen(userPressedStop))
 * }</pre>
 *
 * <p>
 * When the trigger emits a value or completes while the request waits for its response, the
 * session sends {@code $/cancel_request} for it and keeps waiting: the request then ends with
 * the peer's answer, either its result or an {@link AcpError} with code {@code -32800}
 * ({@code AcpErrorCodes.REQUEST_CANCELLED}), or with the request timeout if the peer never
 * answers. A trigger that fails is ignored. Every request sent within the subscription that
 * carries the context is cancelled on the trigger; at most one {@code $/cancel_request} is
 * sent per request, whichever way it is cancelled.
 * </p>
 */
public final class RequestCancellation {

	/** The context key of the cancel trigger. */
	static final Object KEY = RequestCancellation.class;

	private RequestCancellation() {
	}

	/**
	 * A context that cancels the requests sent under it, gracefully, when {@code trigger}
	 * emits a value or completes.
	 * @param trigger the cancel trigger
	 * @return the context to pass to {@code contextWrite}
	 */
	public static ContextView cancelWhen(Publisher<?> trigger) {
		return Context.of(KEY, trigger);
	}

}
