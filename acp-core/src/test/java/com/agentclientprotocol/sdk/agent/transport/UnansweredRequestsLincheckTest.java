/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.ThreadIdGen;
import org.jetbrains.lincheck.datastructures.Validate;
import org.junit.jupiter.api.Test;

/**
 * Model checks {@link UnansweredRequests}: senders recording requests while the reader ends
 * the input. Once the input has ended, every request sent is failed exactly once, either by
 * its sender (refused) or by the reader (claimed), never by both and never by neither: a
 * request failed by neither would wait out the request timeout for an answer that cannot
 * come, and one failed by both would be failed twice.
 */
class UnansweredRequestsLincheckTest {

	/** Multiplies the number of scenarios explored; CI's lincheck job raises it with -Dlincheck.scale. */
	private static final int SCALE = Integer.getInteger("lincheck.scale", 1);

	private static final int THREADS = 3;

	/** Lincheck numbers its threads from 1. */
	private static final int IDS = THREADS + 1;

	@Test
	void everyRequestSentIsFailedOnceAfterTheEnd() {
		new ModelCheckingOptions().iterations(10 * SCALE)
			.invocationsPerIteration(200)
			.threads(THREADS)
			.actorsPerThread(2)
			.actorsBefore(0)
			.actorsAfter(0)
			.sequentialSpecification(RequestsSpec.class)
			.check(Requests.class);
	}

	public static class Requests {

		private final UnansweredRequests requests = new UnansweredRequests();

		/** Requests each thread sent; each slot is touched only by its own thread. */
		private final int[] sent = new int[IDS];

		private final Queue<String> refused = new ConcurrentLinkedQueue<>();

		private final Queue<Object> claimed = new ConcurrentLinkedQueue<>();

		private volatile boolean ended;

		@Operation
		public void send(@Param(gen = ThreadIdGen.class) int thread) {
			String id = thread + ":" + sent[thread]++;
			if (!requests.sent(id)) {
				refused.add(id);
			}
		}

		@Operation
		public void end() {
			claimed.addAll(requests.end());
			ended = true;
		}

		@Validate
		public void failedOnceAfterTheEnd() {
			List<Object> failed = new ArrayList<>(refused);
			failed.addAll(claimed);
			Set<Object> distinct = new HashSet<>(failed);
			if (distinct.size() != failed.size()) {
				throw new IllegalStateException("failed twice: refused " + refused + ", claimed " + claimed);
			}
			if (!ended) {
				return;
			}
			for (int thread = 1; thread < IDS; thread++) {
				for (int i = 0; i < sent[thread]; i++) {
					if (!distinct.contains(thread + ":" + i)) {
						throw new IllegalStateException(thread + ":" + i + " never failed: refused " + refused
								+ ", claimed " + claimed);
					}
				}
			}
		}

	}

	public static class RequestsSpec {

		public void send(int thread) {
		}

		public void end() {
		}

	}

}
