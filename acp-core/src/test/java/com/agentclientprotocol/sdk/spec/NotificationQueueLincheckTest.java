/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

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
 * Model checks {@link NotificationQueue}: notifications offered while the queue is completed,
 * in any interleaving. The drain subscribes as the session's does, and handles each
 * notification inside the offer's emission, as a synchronous handler does.
 *
 * <p>
 * The invariant, checked after every interleaving by {@link Notifications#completedAndDelivered()}:
 * once the queue was completed, the drain has received the completion (a lost completion
 * left {@code closeGracefully()} waiting out the request timeout); and every notification the
 * queue accepted is delivered exactly once, in each thread's order.
 * </p>
 */
class NotificationQueueLincheckTest {

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
	void completionIsNeverLostAndAcceptedNotificationsAreDeliveredInOrder() {
		new ModelCheckingOptions().iterations(20 * SCALE)
			.invocationsPerIteration(200)
			.threads(THREADS)
			.actorsPerThread(2)
			.actorsBefore(0)
			.actorsAfter(0)
			.sequentialSpecification(QueueSpec.class)
			.check(Notifications.class);
	}

	public static class Notifications {

		private final NotificationQueue<String> notifications = new NotificationQueue<>();

		/** What the drain received, in order. */
		private final Queue<String> delivered = new ConcurrentLinkedQueue<>();

		private volatile boolean drainCompleted;

		private volatile boolean completeCalled;

		/** Notifications each thread offered and the queue accepted; each slot is its thread's. */
		private final int[] accepted = new int[IDS];

		/** Notifications each thread offered; each slot is its thread's. */
		private final int[] offered = new int[IDS];

		public Notifications() {
			notifications.asFlux().subscribe(delivered::add, error -> {
			}, () -> drainCompleted = true);
		}

		@Operation
		public void offer(@Param(gen = ThreadIdGen.class) int thread) {
			String notification = thread + ":" + offered[thread]++;
			if (notifications.offer(notification) == Sinks.EmitResult.OK) {
				accepted[thread]++;
			}
		}

		@Operation
		public void complete() {
			completeCalled = true;
			notifications.complete();
		}

		@Validate
		public void completedAndDelivered() {
			if (completeCalled && !drainCompleted) {
				throw new IllegalStateException("completed, but the drain never received the completion; delivered "
						+ delivered);
			}
			int[] next = new int[IDS];
			for (String notification : delivered) {
				int thread = Integer.parseInt(notification.substring(0, notification.indexOf(':')));
				int index = Integer.parseInt(notification.substring(notification.indexOf(':') + 1));
				if (index < next[thread]) {
					throw new IllegalStateException("delivered " + delivered + ": out of order or twice");
				}
				next[thread] = index + 1;
			}
			for (int thread = 1; thread < IDS; thread++) {
				long count = 0;
				for (String notification : delivered) {
					if (notification.startsWith(thread + ":")) {
						count++;
					}
				}
				if (count != accepted[thread]) {
					throw new IllegalStateException("thread " + thread + " had " + accepted[thread]
							+ " notifications accepted, delivered " + delivered);
				}
			}
		}

	}

	public static class QueueSpec {

		public void offer(int thread) {
		}

		public void complete() {
		}

	}

}
