/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.util;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeoutException;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Library-owned schedulers shared by every session in the JVM.
 *
 * <p>
 * Timeouts only need a timer: one daemon thread serves every {@code AcpClientSession} and
 * {@code AcpAgentSession}. Sessions used to create a scheduled pool each, which over a
 * listener transport (one agent session per remote connection) meant one idle thread per
 * connection. The shared scheduler is never disposed; its thread is a daemon and does not
 * keep the JVM alive.
 * </p>
 *
 * @author Mark Pollack
 */
public final class AcpSchedulers {

	private static final class TimeoutHolder {

		static final Scheduler SCHEDULER = Schedulers.fromExecutorService(timerExecutor(), "acp-timeout");

		/**
		 * Timeout errors are handed off the timer so a subscriber that blocks in its error
		 * handler cannot delay every other session's timeouts.
		 */
		static final Scheduler DELIVERY = Schedulers.fromExecutorService(Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "acp-timeout-delivery");
			t.setDaemon(true);
			return t;
		}), "acp-timeout-delivery");

		private static ScheduledThreadPoolExecutor timerExecutor() {
			ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
				Thread t = new Thread(r, "acp-timeout");
				t.setDaemon(true);
				return t;
			});
			// A request that completes cancels its timeout; drop the task instead of
			// keeping it queued until it would have fired (up to the request timeout).
			executor.setRemoveOnCancelPolicy(true);
			return executor;
		}

	}

	private AcpSchedulers() {
	}

	/**
	 * The shared scheduler for request timeouts.
	 * @return a daemon-threaded scheduler that must not be disposed by callers
	 */
	public static Scheduler timeouts() {
		return TimeoutHolder.SCHEDULER;
	}

	/**
	 * Applies a timeout on the shared timer and delivers the resulting
	 * {@link TimeoutException} on a separate daemon thread.
	 * @param mono the source
	 * @param timeout how long to wait for its first signal
	 * @return the source with the timeout applied
	 */
	public static <T> Mono<T> withTimeout(Mono<T> mono, Duration timeout) {
		return mono.timeout(timeout, TimeoutHolder.SCHEDULER)
			.onErrorResume(TimeoutException.class, e -> Mono.<T>error(e).subscribeOn(TimeoutHolder.DELIVERY));
	}

}
