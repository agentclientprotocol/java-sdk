/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

/**
 * Who answers one {@code session/prompt}: its handler, or the session when a deadline passes
 * first (the cancel grace period, or the maximum prompt duration). Each claim is one atomic
 * step, so exactly one of them answers, however the handler's answer and the timers race.
 *
 * <pre>
 * RUNNING --requestCancel--> CANCELLING
 * RUNNING | CANCELLING --handler / max duration--> ANSWERED
 * CANCELLING --grace period--> ANSWERED
 * </pre>
 */
final class PromptAnswer {

	/** What a deadline that won the claim answers with. */
	enum Forced {

		/** Stop reason {@code cancelled}: the prompt was cancelled (ACP v1, prompt turn, Cancellation). */
		CANCELLED,

		/** Error {@code -32800}: the prompt ran past the maximum prompt duration, uncancelled. */
		EXPIRED

	}

	private enum State {

		RUNNING, CANCELLING, ANSWERED

	}

	private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);

	/**
	 * A {@code session/cancel} arrived for this prompt.
	 * @return true the first time, while the prompt is unanswered: the grace period starts
	 */
	boolean requestCancel() {
		return this.state.compareAndSet(State.RUNNING, State.CANCELLING);
	}

	boolean isCancelling() {
		return this.state.get() == State.CANCELLING;
	}

	/** Whether the prompt has been answered, by its handler or by a deadline. */
	boolean isAnswered() {
		return this.state.get() == State.ANSWERED;
	}

	/**
	 * The handler answered (or failed).
	 * @return whether its answer is the prompt's answer; false when a deadline answered first
	 */
	boolean handlerAnswered() {
		return claim() != null;
	}

	/**
	 * The cancel grace period passed.
	 * @return whether the session answers {@link Forced#CANCELLED}; false when the prompt
	 * was answered first
	 */
	boolean graceExpired() {
		return this.state.compareAndSet(State.CANCELLING, State.ANSWERED);
	}

	/**
	 * The maximum prompt duration passed.
	 * @return what the session answers with, or null when the prompt was answered first. A
	 * prompt that is being cancelled is answered {@link Forced#CANCELLED}, as a cancelled
	 * prompt must be.
	 */
	@Nullable Forced maxDurationExpired() {
		State previous = claim();
		if (previous == null) {
			return null;
		}
		return previous == State.CANCELLING ? Forced.CANCELLED : Forced.EXPIRED;
	}

	/** Moves to ANSWERED, returning the state it left, or null when it was already answered. */
	private @Nullable State claim() {
		while (true) {
			State current = this.state.get();
			if (current == State.ANSWERED) {
				return null;
			}
			if (this.state.compareAndSet(current, State.ANSWERED)) {
				return current;
			}
		}
	}

}
