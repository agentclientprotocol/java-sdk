/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;

import com.agentclientprotocol.sdk.util.Assert;

/**
 * How long an agent session lets a {@code session/prompt} run before it answers the prompt
 * itself. {@link Duration#ZERO} turns a limit off.
 *
 * @param cancelGracePeriod how long after {@code session/cancel} the prompt handler has to
 * answer. When it passes, the session cancels the handler and answers the prompt with stop
 * reason {@code cancelled}, which ACP requires of a cancelled prompt and which ends the
 * turn. On by default ({@link #DEFAULT_CANCEL_GRACE_PERIOD}): without it, a handler that
 * never answers a cancelled prompt keeps its session busy for good.
 * @param maxPromptDuration how long a prompt may run at all. When it passes, the session
 * cancels the handler and answers with error {@code -32800} (request cancelled; ACP v1,
 * Cancellation: internal cancellation, such as an internal timeout, answers as if the
 * request had been cancelled), or, when the prompt is being cancelled, with stop reason
 * {@code cancelled}. Off by default: a prompt turn can legitimately run for a long time.
 */
public record PromptTimeouts(Duration cancelGracePeriod, Duration maxPromptDuration) {

	/** The default cancel grace period: 60 seconds. */
	public static final Duration DEFAULT_CANCEL_GRACE_PERIOD = Duration.ofSeconds(60);

	/** The defaults: a 60 second cancel grace period, no maximum prompt duration. */
	public static final PromptTimeouts DEFAULTS = new PromptTimeouts(DEFAULT_CANCEL_GRACE_PERIOD, Duration.ZERO);

	/** No limits: the session never answers a prompt itself. */
	public static final PromptTimeouts NONE = new PromptTimeouts(Duration.ZERO, Duration.ZERO);

	public PromptTimeouts {
		Assert.notNull(cancelGracePeriod, "cancelGracePeriod must not be null");
		Assert.notNull(maxPromptDuration, "maxPromptDuration must not be null");
		Assert.isTrue(!cancelGracePeriod.isNegative(), "cancelGracePeriod must not be negative");
		Assert.isTrue(!maxPromptDuration.isNegative(), "maxPromptDuration must not be negative");
	}

	/** Returns a copy with this cancel grace period. */
	public PromptTimeouts withCancelGracePeriod(Duration cancelGracePeriod) {
		return new PromptTimeouts(cancelGracePeriod, this.maxPromptDuration);
	}

	/** Returns a copy with this maximum prompt duration. */
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
