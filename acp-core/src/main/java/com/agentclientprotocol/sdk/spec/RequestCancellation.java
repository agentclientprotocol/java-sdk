/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import org.reactivestreams.Publisher;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Cancels a request the SDK sends while still waiting for the peer's answer: write
 * {@link #cancelWhen} into the request's Reactor context, and when its trigger fires the SDK sends
 * {@code $/cancel_request} for the request, which then ends with whatever the peer answers. Use it
 * when the answer still matters after cancelling, for example to see whether the work finished
 * first; to give up on a request at once, dispose its subscription instead. It works with the
 * request methods of the asynchronous API: {@code AcpAsyncClient} on the client side,
 * {@code AcpAsyncAgent} and {@code PromptContext} on the agent side. ACP v1 calls this graceful
 * cancellation ("the calling side MAY implement graceful cancellation processing by waiting for the
 * response").
 *
 * <p>When the trigger emits a value or completes while the request waits for its answer, the
 * session sends {@code $/cancel_request} once the request has been written, and keeps waiting. The
 * request then ends with the peer's answer: its result, or an {@link AcpError} with code
 * {@code -32800} ({@code AcpErrorCodes.REQUEST_CANCELLED}). A Java peer cancels the handler and
 * answers {@code -32800} unless the handler answered first; for a {@code session/prompt} that the
 * agent is already cancelling under {@code session/cancel}, it answers stop reason
 * {@code cancelled} instead. If the peer never answers, the request still ends at its timeout, if
 * it has one. A trigger that fails is ignored and cancels nothing. Every request sent within the
 * subscription that carries the context is cancelled on the trigger, and at most one
 * {@code $/cancel_request} is sent per request, whichever way it is cancelled.
 *
 * <p>Disposing a request's subscription, directly or through a timeout, also sends
 * {@code $/cancel_request}, but stops listening: the peer's answer is discarded. To stop a prompt
 * turn the way ACP intends, send {@code session/cancel} with {@code AcpAsyncClient.cancel(..)};
 * this class cancels any one request, in either direction. The sync API cannot carry a trigger:
 * {@code AcpSyncClient.async()} gives the asynchronous client to use it with.
 *
 * <pre>{@code
 * Sinks.Empty<Void> stop = Sinks.empty();
 * Mono<AcpSchema.PromptResponse> answer = client.prompt(request)
 *     .contextWrite(RequestCancellation.cancelWhen(stop.asMono()));
 * // when the user presses Stop:
 * stop.tryEmitEmpty();
 * }</pre>
 */
public final class RequestCancellation {

	/** The context key of the cancel trigger. */
	static final Object KEY = RequestCancellation.class;

	private RequestCancellation() {
	}

	/**
	 * Returns a Reactor context that cancels every request sent under it, gracefully, when
	 * {@code trigger} emits its first value or completes.
	 * @param trigger the cancel trigger, any Reactive Streams publisher, such as a {@code Mono} or
	 * a {@code Sinks.Empty}'s {@code asMono()}; must not be null
	 * @return the context, for {@code contextWrite}
	 */
	public static ContextView cancelWhen(Publisher<?> trigger) {
		return Context.of(KEY, trigger);
	}

}
