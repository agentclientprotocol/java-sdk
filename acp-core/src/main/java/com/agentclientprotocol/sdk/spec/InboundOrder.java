/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
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
 * A request from the peer takes its place the same way: it is dispatched to its handler only
 * once the notifications that arrived before it have been handled, so a permission request
 * reaches its handler after the {@code tool_call} update announcing it. The same escape keeps
 * it from deadlocking: while the handler running now waits for a response to a request it
 * sent, held requests are dispatched at once, since answering one may be what that response
 * waits for. Dispatching does not wait for the request's handler to finish.
 * </p>
 *
 * <p>
 * The inbound thread offers notifications, responses and requests, one at a time; one subscriber
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

	/** The responses and requests queued behind notifications and not yet released. */
	private final Set<Release> held = ConcurrentHashMap.newKeySet();

	/** The requests from the peer queued behind notifications and not yet dispatched. */
	private final Set<Release> heldRequests = ConcurrentHashMap.newKeySet();

	/**
	 * The requests this side sent that wait for a response, each with the number of the
	 * handler that was running when it was sent (0 for none).
	 */
	private final Map<Object, Long> awaiting = new ConcurrentHashMap<>();

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
		Release release = new Release(this.held, null, completion);
		this.held.add(release);
		if (this.queue.offer(release).isFailure()) {
			// The queue is closing: nothing more is delivered in order.
			release.run();
		}
	}

	/**
	 * Runs {@code dispatch} once every notification that arrived before it has been handled,
	 * or at once when the handler running now waits for a response (see the type comment).
	 * Called on the inbound thread when a request arrives.
	 */
	void inOrder(Runnable dispatch) {
		Release release = new Release(this.held, this.heldRequests, dispatch);
		this.held.add(release);
		this.heldRequests.add(release);
		if (runningHandlerAwaits() || this.queue.offer(release).isFailure()) {
			// A waiting handler must not hold it; a closing queue delivers nothing more in order.
			release.run();
		}
	}

	@Override
	public Runnable awaiting(long sentAt) {
		Object key = new Object();
		long running = this.handling;
		this.awaiting.put(key, running);
		if (running != 0) {
			// This handler may now wait for the response, which may need a held request answered.
			for (Release request : this.heldRequests) {
				request.run();
			}
		}
		return () -> this.awaiting.remove(key);
	}

	/** Whether the handler running now sent a request that still waits for its response. */
	private boolean runningHandlerAwaits() {
		long running = this.handling;
		return running != 0 && this.awaiting.containsValue(running);
	}

	/** Releases every response and request still held: the drain will not reach them. */
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

		/** The second set a held request is in, or null for a response. */
		private final @Nullable Set<Release> alsoIn;

		Release(Set<Release> held, @Nullable Set<Release> alsoIn, Runnable completion) {
			this.held = held;
			this.alsoIn = alsoIn;
			this.completion = completion;
		}

		@Override
		public void run() {
			if (this.held.remove(this)) {
				Set<Release> other = this.alsoIn;
				if (other != null) {
					other.remove(this);
				}
				this.completion.run();
			}
		}

	}

}
