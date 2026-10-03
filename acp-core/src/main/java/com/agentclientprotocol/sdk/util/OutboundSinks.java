/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.util;

import java.time.Duration;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The outbound sink of a message transport: one sink that the transport's writer drains and
 * that several threads emit on, the inbound thread with the replies to inbound requests and
 * user threads with their own requests and notifications.
 *
 * @author Mark Pollack
 */
public final class OutboundSinks {

	private static final Logger logger = LoggerFactory.getLogger(OutboundSinks.class);

	/** How long an emission keeps retrying while another thread is emitting on the sink. */
	private static final Duration BUSY_LOOP = Duration.ofMillis(100);

	private OutboundSinks() {
	}

	/**
	 * Emits a message on an outbound sink, retrying while another thread is emitting on it:
	 * a plain emission that collides with another fails ({@code FAIL_NON_SERIALIZED}) and
	 * the message is lost (#14).
	 * @param sink the outbound sink
	 * @param message the message
	 * @param <T> the message type
	 * @throws Sinks.EmissionException if the sink still refuses the message after retrying,
	 * or is terminated ({@code FAIL_TERMINATED}: the transport is closed) or cancelled
	 */
	public static <T> void emit(Sinks.Many<T> sink, T message) {
		// Not emitNext: on a terminated sink it drops the value silently instead of failing.
		long deadline = System.nanoTime() + BUSY_LOOP.toNanos();
		while (true) {
			Sinks.EmitResult result = sink.tryEmitNext(message);
			if (result.isSuccess()) {
				return;
			}
			if (result != Sinks.EmitResult.FAIL_NON_SERIALIZED || System.nanoTime() >= deadline) {
				throw new Sinks.EmissionException(result, "Could not emit the message: " + result);
			}
			Thread.onSpinWait();
		}
	}

	/**
	 * Whether an emission failed because the sink is terminated or cancelled: the transport
	 * was closed.
	 * @param failure the failure {@link #emit} threw
	 * @return {@code true} if the sink no longer accepts messages
	 */
	public static boolean isClosed(Sinks.EmissionException failure) {
		return failure.getReason() == Sinks.EmitResult.FAIL_TERMINATED
				|| failure.getReason() == Sinks.EmitResult.FAIL_CANCELLED;
	}

	/**
	 * Passes each inbound message to the session's handler and emits the handler's reply,
	 * when there is one, on the outbound sink. A reply the sink refuses is logged and
	 * dropped. When the inbound messages end, the outbound sink is completed and
	 * {@code afterTermination} runs.
	 * @param inbound the inbound sink
	 * @param handler the session's message handler
	 * @param outbound the outbound sink
	 * @param afterTermination what else the transport releases when the inbound side ends
	 * @param <T> the message type
	 * @return the subscription
	 */
	public static <T> Disposable replyThrough(Sinks.Many<T> inbound, Function<Mono<T>, Mono<T>> handler,
			Sinks.Many<T> outbound, Runnable afterTermination) {
		return inbound.asFlux()
			.flatMap(message -> Mono.just(message).transform(handler))
			.doOnNext(reply -> {
				try {
					emit(outbound, reply);
				}
				catch (Sinks.EmissionException e) {
					logger.error("Dropped a response: {}", e.getReason());
					logger.debug("Dropped response: {}", reply);
				}
			})
			.doOnTerminate(() -> {
				outbound.tryEmitComplete();
				afterTermination.run();
			})
			.subscribe(ignored -> {
			}, error -> logger.warn("Inbound message processing ended with an error", error));
	}

}
