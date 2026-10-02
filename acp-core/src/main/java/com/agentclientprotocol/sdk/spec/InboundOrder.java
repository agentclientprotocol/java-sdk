/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The order in which a session hands on what its peer sent: each notification to its
 * handler, one at a time in arrival order, and each response to its caller only once every
 * notification that arrived before it has been handled. A prompt turn's updates come before
 * its response, so a caller that reads what its update handler collected once the prompt
 * returns finds all of them.
 *
 * <p>
 * A response takes its place in the notification queue, so it is released by the drain
 * once the notifications ahead of it are handled. One response is not held: when a handler
 * that was already running when the request was sent is still running as its response
 * arrives, that handler may be the one waiting for the response (it sent the request
 * itself), so the response completes at once rather than deadlock it.
 * </p>
 *
 * <p>
 * The inbound thread offers notifications and responses, one at a time; one subscriber
 * drains them; a closing thread completes the queue. A response still held when the drain
 * ends (the session was closed without waiting for it) is released then.
 * </p>
 *
 * @param <T> the notification type
 */
final class InboundOrder<T> implements ResponseOrder {

	private final NotificationQueue<Object> queue = new NotificationQueue<>();

	/** How many notifications were offered; it numbers them. Written by the inbound thread only. */
	private volatile long received;

	/** The number of the notification whose handler is running, or 0 when none is. */
	private volatile long handling;

	/** The responses queued behind notifications and not yet released. */
	private final Set<Release> held = ConcurrentHashMap.newKeySet();

	/**
	 * The drain: delivers each notification, waiting for its delivery to complete before the
	 * next, and releases each response in its place.
	 * @param deliver delivers one notification; the Mono completes when it was handled
	 * @return the drain, to subscribe once; it completes once the queue is completed
	 */
	Flux<Void> drain(Function<? super T, Mono<Void>> deliver) {
		return this.queue.asFlux().concatMap(item -> {
			if (item instanceof Release release) {
				return Mono.fromRunnable(release);
			}
			@SuppressWarnings("unchecked")
			Numbered<T> numbered = (Numbered<T>) item;
			return Mono.defer(() -> {
				this.handling = numbered.number();
				return deliver.apply(numbered.notification());
			}).onErrorComplete().then(Mono.fromRunnable(() -> this.handling = 0));
		});
	}

	/**
	 * Queues a notification for delivery.
	 * @return the emission result: a failure when the queue is completed or completing
	 */
	Sinks.EmitResult offer(T notification) {
		// Numbered before the emission: a handler run inside it may send a request, which
		// must count this notification as already arrived.
		long number = this.received + 1;
		this.received = number;
		return this.queue.offer(new Numbered<>(number, notification));
	}

	@Override
	public long position() {
		return this.received;
	}

	@Override
	public <R> Mono<R> after(long sentAt, R response) {
		return Mono.create(sink -> release(sentAt, () -> sink.success(response)));
	}

	/**
	 * Runs {@code completion} once every notification that arrived before it has been
	 * handled, or at once when the handler running now was already running when the request
	 * was sent.
	 */
	void release(long sentAt, Runnable completion) {
		long running = this.handling;
		if (running != 0 && running <= sentAt) {
			// The running handler may be waiting for this very response.
			completion.run();
			return;
		}
		Release release = new Release(this.held, completion);
		this.held.add(release);
		if (this.queue.offer(release).isFailure()) {
			// The queue is closing: nothing more is delivered in order.
			release.run();
		}
	}

	/** Releases every response still held: the drain will not reach them. */
	void releaseHeld() {
		for (Release release : this.held) {
			release.run();
		}
	}

	/**
	 * Completes the queue: the drain delivers what was queued before, then completes. Never
	 * lost to an offer emitting concurrently (see {@link NotificationQueue}).
	 */
	void complete() {
		this.queue.complete();
	}

	/** Whether {@link #complete} has been called. */
	boolean isCompleting() {
		return this.queue.isCompleting();
	}

	private record Numbered<T>(long number, T notification) {
	}

	/** A held response; runs its completion at most once, whichever releases it first. */
	private static final class Release implements Runnable {

		private final Set<Release> held;

		private final Runnable completion;

		Release(Set<Release> held, Runnable completion) {
			this.held = held;
			this.completion = completion;
		}

		@Override
		public void run() {
			if (this.held.remove(this)) {
				this.completion.run();
			}
		}

	}

}
