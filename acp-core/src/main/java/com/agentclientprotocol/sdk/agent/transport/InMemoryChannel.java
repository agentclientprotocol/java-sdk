/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * One direction of an {@link InMemoryTransportPair}: the messages one side sends, delivered to
 * the other side's single subscriber in the order they were accepted.
 *
 * <p>
 * Several threads send at once, and delivery runs on a sending thread, for as long as the
 * receiver takes. A {@code Sinks.many()} sink refuses an emission while another thread is
 * emitting ({@code FAIL_NON_SERIALIZED}), so a send is queued, and whichever thread finds no
 * other thread emitting emits the queue in order: no accepted message is dropped and no sender
 * waits, including a receiver that sends from inside a delivery. Closing is queued the same
 * way: every message accepted before the close is delivered, then the subscriber's flux
 * completes, and a send after the close is refused.
 * </p>
 *
 * @param <T> the message type
 * @author Mark Pollack
 */
final class InMemoryChannel<T> {

	private static final Logger logger = LoggerFactory.getLogger(InMemoryChannel.class);

	private final Sinks.Many<T> sink = Sinks.many().unicast().onBackpressureBuffer();

	private final Queue<T> pending = new ConcurrentLinkedQueue<>();

	/** Threads that want to emit: only the one that raised it from zero emits. */
	private final AtomicInteger emitters = new AtomicInteger();

	/** Guards the decision to accept a message against a concurrent close. */
	private final Object acceptLock = new Object();

	private volatile boolean closed;

	/** Touched only by the emitting thread. */
	private boolean completed;

	/**
	 * The messages, for the receiving side's single subscriber; a second subscriber is refused.
	 * @return the flux of messages, which completes after the close
	 */
	Flux<T> asFlux() {
		return this.sink.asFlux();
	}

	/**
	 * Queues a message and delivers what is queued, unless another thread is delivering.
	 * @param message the message
	 * @return {@code true} if the message was accepted and will be delivered before the flux
	 * completes; {@code false} if the channel is closed
	 */
	boolean send(T message) {
		synchronized (this.acceptLock) {
			if (this.closed) {
				return false;
			}
			this.pending.offer(message);
		}
		drain();
		return true;
	}

	/**
	 * Closes the channel: the messages already accepted are still delivered, then the flux
	 * completes. Later calls do nothing.
	 */
	void close() {
		synchronized (this.acceptLock) {
			if (this.closed) {
				return;
			}
			this.closed = true;
		}
		drain();
	}

	/**
	 * Whether the channel is closed.
	 * @return {@code true} once {@link #close()} ran
	 */
	boolean isClosed() {
		return this.closed;
	}

	private void drain() {
		if (this.emitters.getAndIncrement() != 0) {
			return; // the thread emitting now emits what was queued too
		}
		int missed = 1;
		do {
			T next;
			while ((next = this.pending.poll()) != null) {
				// Fails only once the subscriber has cancelled: nobody is left to deliver to.
				Sinks.EmitResult result = this.sink.tryEmitNext(next);
				if (result.isFailure()) {
					logger.debug("Dropped a message on a channel nobody reads: {}", result);
				}
			}
			// Every message accepted before the close was queued before closed was set.
			if (this.closed && !this.completed && this.pending.isEmpty()) {
				this.completed = true;
				this.sink.tryEmitComplete();
			}
			missed = this.emitters.addAndGet(-missed);
		}
		while (missed != 0);
	}

}
