/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.util;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.ThreadIdGen;
import org.jetbrains.lincheck.datastructures.Validate;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

/**
 * Model checks {@link OutboundSinks#emit}: several threads emitting on one transport's
 * outbound sink (the shape every transport uses: a unicast sink whose writer subscribed)
 * lose no message and keep each thread's messages in order (#14: a plain emission that
 * collided with another failed with {@code FAIL_NON_SERIALIZED} and the message was lost).
 */
class OutboundSinksLincheckTest {

	/**
	 * Multiplies the number of scenarios explored. The default keeps this test to seconds in
	 * the build; CI's lincheck job raises it with -Dlincheck.scale. Scenarios and interleavings
	 * are generated from a fixed seed, so a run is repeatable.
	 */
	private static final int SCALE = Integer.getInteger("lincheck.scale", 1);

	private static final int THREADS = 3;

	/** Lincheck numbers its threads from 1. */
	private static final int IDS = THREADS + 1;

	@Test
	void concurrentEmissionsAreAllWrittenInOrder() {
		new ModelCheckingOptions().iterations(10 * SCALE)
			.invocationsPerIteration(200)
			.threads(THREADS)
			.actorsPerThread(2)
			.actorsBefore(0)
			.actorsAfter(0)
			.sequentialSpecification(EmitterSpec.class)
			.check(Emitter.class);
	}

	public static class Emitter {

		private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();

		/** What the transport's writer wrote, in order. */
		private final Queue<String> written = new ConcurrentLinkedQueue<>();

		/** Messages each thread emitted; each slot is touched only by its own thread. */
		private final int[] emitted = new int[IDS];

		public Emitter() {
			outbound.asFlux().subscribe(written::add);
		}

		@Operation
		public void emit(@Param(gen = ThreadIdGen.class) int thread) {
			OutboundSinks.emit(outbound, thread + ":" + emitted[thread]++);
		}

		@Validate
		public void everyMessageWrittenOnceInOrder() {
			int[] next = new int[IDS];
			for (String message : written) {
				int thread = Integer.parseInt(message.substring(0, message.indexOf(':')));
				String inOrder = thread + ":" + next[thread]++;
				if (!message.equals(inOrder)) {
					throw new IllegalStateException("written " + written + ": expected " + inOrder);
				}
			}
			for (int thread = 1; thread < IDS; thread++) {
				if (next[thread] != emitted[thread]) {
					throw new IllegalStateException("thread " + thread + " emitted " + emitted[thread]
							+ " messages, written " + written);
				}
			}
		}

	}

	public static class EmitterSpec {

		public void emit(int thread) {
		}

	}

}
