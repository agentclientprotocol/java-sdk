/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

import com.agentclientprotocol.sdk.error.AcpException;
import com.agentclientprotocol.sdk.error.AcpTimeoutException;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import org.jspecify.annotations.Nullable;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * How the sync agent API blocks on a {@code Mono}: failures reach the caller as the SDK's
 * documented exceptions, never as Reactor's internal wrapper. A timeout becomes an {@link
 * AcpTimeoutException} whose cause is the {@link TimeoutException}; an interrupt of the waiting
 * thread (the SDK interrupts a sync handler to cancel it) becomes a {@link CancellationException},
 * with the thread's interrupt flag set again; any other checked cause becomes an {@link
 * AcpException}. Runtime failures pass through unchanged. The same rules as the sync client's copy
 * of this class.
 */
final class SyncCalls {

	private SyncCalls() {
	}

	/**
	 * Blocks until {@code mono} completes.
	 * @param <T> the value type
	 * @param mono what to wait for
	 * @return the value, or null if it completed empty
	 */
	static <T> @Nullable T block(Mono<T> mono) {
		return block(mono, null);
	}

	/**
	 * Blocks until {@code mono} completes, at most {@code timeout}; when that passes the
	 * {@code Mono} is cancelled and the call throws {@link AcpTimeoutException}.
	 * @param <T> the value type
	 * @param mono what to wait for
	 * @param timeout the longest to wait, or null for no limit of its own
	 * @return the value, or null if it completed empty
	 */
	static <T> @Nullable T block(Mono<T> mono, @Nullable Duration timeout) {
		try {
			return (timeout != null) ? AcpSchedulers.withTimeout(mono, timeout).block() : mono.block();
		}
		catch (RuntimeException failure) {
			throw translate(failure);
		}
	}

	private static RuntimeException translate(RuntimeException failure) {
		Throwable cause = Exceptions.unwrap(failure);
		if (cause instanceof TimeoutException timeout) {
			return new AcpTimeoutException(timeout);
		}
		if (cause instanceof InterruptedException) {
			Thread.currentThread().interrupt();
			CancellationException cancelled = new CancellationException(
					"Interrupted while waiting for an answer");
			cancelled.initCause(cause);
			return cancelled;
		}
		if (cause instanceof RuntimeException runtime) {
			return runtime;
		}
		return new AcpException(cause.toString(), cause);
	}

}
