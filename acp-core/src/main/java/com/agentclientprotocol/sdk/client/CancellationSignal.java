/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * A stop button for one prompt turn: pass it to {@link AcpAsyncClient#prompt(
 * com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest, CancellationSignal)} or
 * {@link AcpSyncClient#prompt(com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest,
 * CancellationSignal)}, and call {@link #cancel()} from any thread to stop the turn. The client
 * then sends {@code session/cancel} for the prompt's session, as ACP intends, and the prompt still
 * returns the agent's answer, normally stop reason {@code cancelled}, together with the session
 * updates the agent sent before it.
 *
 * <pre>{@code
 * CancellationSignal stop = new CancellationSignal();
 * stopButton.onClick(stop::cancel);                      // from the UI thread
 * AcpSchema.PromptResponse response = client.prompt(request, stop);   // blocks until the answer
 * }</pre>
 *
 * <p>A signal is cancelled once and stays cancelled; use a new one for each prompt. Cancelling it
 * before the prompt is sent sends the prompt and then {@code session/cancel} at once; cancelling
 * it after the answer has arrived sends nothing. It is safe to use from several threads.
 *
 * @author Mark Pollack
 * @see AcpAsyncClient#cancel(com.agentclientprotocol.sdk.spec.AcpSchema.CancelNotification)
 */
public final class CancellationSignal {

	private final Sinks.Empty<Void> cancelled = Sinks.empty();

	private volatile boolean isCancelled;

	/** Creates a signal that is not cancelled. */
	public CancellationSignal() {
	}

	/**
	 * Cancels the prompt this signal was passed to: the client sends {@code session/cancel} for
	 * its session, unless the prompt has already been answered. Calling it again does nothing.
	 */
	public void cancel() {
		this.isCancelled = true;
		this.cancelled.tryEmitEmpty();
	}

	/**
	 * Whether {@link #cancel()} has been called.
	 * @return whether the signal is cancelled
	 */
	public boolean isCancelled() {
		return this.isCancelled;
	}

	/** Completes when the signal is cancelled, at once if it already is. */
	Mono<Void> whenCancelled() {
		return this.cancelled.asMono();
	}

}
