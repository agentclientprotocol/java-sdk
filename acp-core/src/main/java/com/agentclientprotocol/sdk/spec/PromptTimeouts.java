/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;

import com.agentclientprotocol.sdk.util.Assert;

/**
 * The two time limits an agent puts on a prompt turn: the cancel grace period, how long a prompt
 * handler has to answer after {@code session/cancel} before the SDK answers {@code cancelled} for
 * it, and the maximum prompt duration, after which the SDK ends the turn with {@code -32800}. Set
 * them with {@code cancelGracePeriod} and {@code maxPromptDuration} on
 * {@link com.agentclientprotocol.sdk.agent.AcpAgent.AsyncAgentBuilder},
 * {@link com.agentclientprotocol.sdk.agent.AcpAgent.SyncAgentBuilder} or
 * {@code AcpAgentSupport.Builder}; this record holds the values and the defaults
 * ({@link #DEFAULTS}). {@link Duration#ZERO} turns a limit off. Both limits are Java SDK policy;
 * ACP defines neither.
 *
 * <p>They exist because ACP requires every cancelled prompt to be answered with stop reason
 * {@code cancelled} and a session runs one prompt turn at a time: a handler that never answers
 * would keep its session busy for good. Whichever limit passes, the SDK cancels the handler (a sync
 * handler's thread is interrupted) and sends the answer itself; exactly one answer goes to each
 * prompt, and the session updates the handler sent before it reach the client first. A handler
 * should stop when cancelled: updates it sends after the SDK's answer reach the client after the
 * turn has ended.
 *
 * <ul>
 * <li>The cancel grace period starts when {@code session/cancel} arrives, not with the prompt. When
 * it passes, the answer is stop reason {@code cancelled}, which ends the turn. On by default, 60
 * seconds ({@link #DEFAULT_CANCEL_GRACE_PERIOD}).</li>
 * <li>The maximum prompt duration starts with the prompt. When it passes, the answer is error
 * {@code -32800} (request cancelled; ACP v1, Cancellation: an internally cancelled request, such as
 * one that hit an internal timeout, answers as if the request had been cancelled), with message
 * {@code "Prompt exceeded maxPromptDuration of <duration>"} and data
 * {@code {"maxPromptDuration": "<ISO-8601 duration>"}}; or stop reason {@code cancelled} when the
 * prompt is already being cancelled. Off by default: a prompt turn can legitimately run for a long
 * time.</li>
 * </ul>
 *
 * @param cancelGracePeriod how long a prompt handler has to answer after {@code session/cancel};
 * zero for no limit
 * @param maxPromptDuration how long a prompt may run in all; zero for no limit
 */
public record PromptTimeouts(Duration cancelGracePeriod, Duration maxPromptDuration) {

	/** The default cancel grace period: 60 seconds. */
	public static final Duration DEFAULT_CANCEL_GRACE_PERIOD = Duration.ofSeconds(60);

	/** The defaults: a 60 second cancel grace period and no maximum prompt duration. */
	public static final PromptTimeouts DEFAULTS = new PromptTimeouts(DEFAULT_CANCEL_GRACE_PERIOD, Duration.ZERO);

	/**
	 * No limits: the SDK never answers a prompt itself, so a handler that never answers keeps its
	 * session busy.
	 */
	public static final PromptTimeouts NONE = new PromptTimeouts(Duration.ZERO, Duration.ZERO);

	/**
	 * Creates the limits.
	 * @param cancelGracePeriod how long a prompt handler has to answer after
	 * {@code session/cancel}; zero for no limit
	 * @param maxPromptDuration how long a prompt may run in all; zero for no limit
	 * @throws IllegalArgumentException if either is null or negative
	 */
	public PromptTimeouts {
		Assert.notNull(cancelGracePeriod, "cancelGracePeriod must not be null");
		Assert.notNull(maxPromptDuration, "maxPromptDuration must not be null");
		Assert.isTrue(!cancelGracePeriod.isNegative(), "cancelGracePeriod must not be negative");
		Assert.isTrue(!maxPromptDuration.isNegative(), "maxPromptDuration must not be negative");
	}

	/**
	 * Returns a copy with another cancel grace period.
	 * @param cancelGracePeriod the grace period; zero for none
	 * @return the new limits
	 * @throws IllegalArgumentException if {@code cancelGracePeriod} is null or negative
	 */
	public PromptTimeouts withCancelGracePeriod(Duration cancelGracePeriod) {
		return new PromptTimeouts(cancelGracePeriod, this.maxPromptDuration);
	}

	/**
	 * Returns a copy with another maximum prompt duration.
	 * @param maxPromptDuration the maximum duration; zero for none
	 * @return the new limits
	 * @throws IllegalArgumentException if {@code maxPromptDuration} is null or negative
	 */
	public PromptTimeouts withMaxPromptDuration(Duration maxPromptDuration) {
		return new PromptTimeouts(this.cancelGracePeriod, maxPromptDuration);
	}

	boolean hasCancelGracePeriod() {
		return !this.cancelGracePeriod.isZero();
	}

	boolean hasMaxPromptDuration() {
		return !this.maxPromptDuration.isZero();
	}

}
