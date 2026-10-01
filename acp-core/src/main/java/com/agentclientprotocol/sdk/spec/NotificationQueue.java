/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * The inbound notifications a session has yet to deliver, in arrival order: the transport's
 * inbound thread offers them and one subscriber drains them; a closing thread completes the
 * queue.
 *
 * <p>
 * Completion is never lost. Offering and completing emit on one serialized sink, which refuses
 * an emission that collides with another ({@code FAIL_NON_SERIALIZED}) rather than wait. A
 * subscriber that handles a notification synchronously does so inside the offer's emission, so
 * a close that arrives meanwhile collides with it for as long as the handler runs: no bounded
 * retry would do. Instead {@link #complete} first records that completion is wanted, and every
 * offer, once its emission returns, completes the queue when it is wanted. The emission a
 * complete collided with is therefore followed by a completion from the thread that made it.
 * </p>
 *
 * @param <T> the notification type
 */
final class NotificationQueue<T> {

	private final Sinks.Many<T> sink = Sinks.many().unicast().onBackpressureBuffer();

	/** Set before the first attempt to complete; read by every offer after its emission. */
	private volatile boolean completing;

	/** The notifications in arrival order; it completes once the queue is completed. */
	Flux<T> asFlux() {
		return this.sink.asFlux();
	}

	/**
	 * Queues a notification.
	 * @param notification the notification
	 * @return the emission result: a failure when the queue is completed or completing, or the
	 * notification collided with another offer
	 */
	Sinks.EmitResult offer(T notification) {
		Sinks.EmitResult result = this.sink.tryEmitNext(notification);
		if (this.completing) {
			// A complete may have collided with this emission and left the completion to us.
			completeNow();
		}
		return result;
	}

	/**
	 * Completes the queue: the subscriber receives what was queued before, then completion.
	 * Returns without waiting when an offer is emitting; that offer completes the queue.
	 */
	void complete() {
		this.completing = true;
		completeNow();
	}

	/** Whether {@link #complete} has been called. */
	boolean isCompleting() {
		return this.completing;
	}

	private void completeNow() {
		// The result needs no handling. FAIL_NON_SERIALIZED: another thread is emitting, and
		// completes the queue once it has (an offer) or is completing it now (another
		// complete). FAIL_TERMINATED: already completed.
		this.sink.tryEmitComplete();
	}

}
