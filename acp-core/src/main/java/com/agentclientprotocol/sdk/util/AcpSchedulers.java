/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.util;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Library-owned schedulers shared by every session in the JVM.
 *
 * <p>
 * Timeouts only need a timer: one daemon thread ({@code acp-timeout}) serves every
 * {@code AcpClientSession} and {@code AcpAgentSession}, and the listener's keep-alive.
 * Sessions used to create a scheduled pool each, which over a listener transport (one agent
 * session per remote connection) meant one idle thread per connection. The shared scheduler is
 * never disposed; its thread is a daemon and does not keep the JVM alive. It stays a platform
 * thread even where virtual threads exist, so that handlers which pin every carrier thread
 * cannot also stop the timeouts meant to end them. What a timeout triggers runs off the timer:
 * on a virtual thread per task on JDK 21 and later, else on a cached pool of daemon threads
 * ({@code acp-timeout-delivery}).
 * </p>
 *
 * @author Mark Pollack
 */
public final class AcpSchedulers {

	private static final class TimeoutHolder {

		static final Scheduler SCHEDULER = Schedulers.fromExecutorService(timerExecutor(), "acp-timeout");

		/**
		 * Timeout errors are handed off the timer so a subscriber that blocks in its error
		 * handler cannot delay every other session's timeouts: a virtual thread per delivery
		 * where the JDK has them, else a cached pool of daemon threads.
		 */
		static final Scheduler DELIVERY = deliveryScheduler(VirtualThreads.isSupported());

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
	 * The delivery threads: a virtual thread per delivery, else a cached pool of daemon threads.
	 * Whether the JDK has virtual threads is given, so tests make the choice on any JDK.
	 */
	static Scheduler deliveryScheduler(boolean virtualThreads) {
		if (virtualThreads) {
			ThreadFactory virtual = VirtualThreads.factoryOrDaemon("acp-timeout-delivery");
			return Schedulers.fromExecutor(task -> virtual.newThread(task).start());
		}
		return Schedulers.fromExecutorService(Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "acp-timeout-delivery");
			t.setDaemon(true);
			return t;
		}), "acp-timeout-delivery");
	}

	/**
	 * The shared scheduler for request timeouts.
	 * @return a daemon-threaded scheduler that must not be disposed by callers
	 */
	public static Scheduler timeouts() {
		return TimeoutHolder.SCHEDULER;
	}

	/**
	 * The threads timeouts are delivered on (virtual threads on JDK 21 and later, else daemon
	 * threads), for work a timeout triggers that must not
	 * run on (and so delay) the shared timer, such as telling the peer a request was given
	 * up on.
	 * @return a scheduler that must not be disposed by callers
	 */
	public static Scheduler timeoutDelivery() {
		return TimeoutHolder.DELIVERY;
	}

	/**
	 * Emits once after {@code delay}, timed on the shared timer and delivered on the same
	 * separate threads as a timeout, so what runs on it cannot delay other sessions'
	 * timeouts. Cancelling the subscription cancels the timer.
	 * @param delay how long to wait
	 * @return a Mono that emits {@code 0} after the delay
	 */
	public static Mono<Long> after(Duration delay) {
		return Mono.delay(delay, TimeoutHolder.SCHEDULER).publishOn(TimeoutHolder.DELIVERY);
	}

	/**
	 * Applies a timeout on the shared timer and delivers the resulting
	 * {@link TimeoutException} on a separate thread (see {@link #timeoutDelivery()}).
	 * @param mono the source
	 * @param timeout how long to wait for its first signal
	 * @return the source with the timeout applied
	 */
	public static <T> Mono<T> withTimeout(Mono<T> mono, Duration timeout) {
		return mono.timeout(timeout, TimeoutHolder.SCHEDULER)
			.onErrorResume(TimeoutException.class, e -> Mono.<T>error(e).subscribeOn(TimeoutHolder.DELIVERY));
	}

}
