/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.ThreadIdGen;
import org.jetbrains.lincheck.datastructures.Validate;
import org.junit.jupiter.api.Test;

/**
 * Model checks {@link InMemoryChannel}: several threads sending while another closes, the
 * subscriber delivered to on whichever sending thread finds no delivery in progress. A send
 * is accepted exactly when it comes before the close; every accepted message is delivered
 * once, in each thread's order, before the subscriber's flux completes, and the flux
 * completes once the channel is closed.
 */
class InMemoryChannelLincheckTest {

	/** Multiplies the number of scenarios explored; CI's lincheck job raises it with -Dlincheck.scale. */
	private static final int SCALE = Integer.getInteger("lincheck.scale", 1);

	private static final int THREADS = 3;

	/** Lincheck numbers its threads from 1. */
	private static final int IDS = THREADS + 1;

	@Test
	void acceptedMessagesAreDeliveredOnceInOrderBeforeTheEnd() {
		new ModelCheckingOptions().iterations(10 * SCALE)
			.invocationsPerIteration(200)
			.threads(THREADS)
			.actorsPerThread(2)
			.actorsBefore(0)
			.actorsAfter(0)
			.sequentialSpecification(ChannelSpec.class)
			.check(Channel.class);
	}

	public static class Channel {

		private final InMemoryChannel<String> channel = new InMemoryChannel<>();

		/** What the subscriber received, in order. */
		private final Queue<String> delivered = new ConcurrentLinkedQueue<>();

		/** Messages each thread had accepted; each slot is touched only by its own thread. */
		private final int[] accepted = new int[IDS];

		private volatile boolean completed;

		private volatile boolean deliveredAfterCompletion;

		public Channel() {
			channel.asFlux().subscribe(message -> {
				if (completed) {
					deliveredAfterCompletion = true;
				}
				delivered.add(message);
			}, error -> {
				throw new IllegalStateException(error);
			}, () -> completed = true);
		}

		@Operation
		public boolean send(@Param(gen = ThreadIdGen.class) int thread) {
			boolean sent = channel.send(thread + ":" + accepted[thread]);
			if (sent) {
				accepted[thread]++;
			}
			return sent;
		}

		@Operation
		public void close() {
			channel.close();
		}

		@Validate
		public void deliveredOnceInOrderBeforeTheEnd() {
			int[] next = new int[IDS];
			for (String message : delivered) {
				int thread = Integer.parseInt(message.substring(0, message.indexOf(':')));
				String inOrder = thread + ":" + next[thread]++;
				if (!message.equals(inOrder)) {
					throw new IllegalStateException("delivered " + delivered + ": expected " + inOrder);
				}
			}
			for (int thread = 1; thread < IDS; thread++) {
				if (next[thread] != accepted[thread]) {
					throw new IllegalStateException("thread " + thread + " had " + accepted[thread]
							+ " messages accepted, delivered " + delivered);
				}
			}
			if (deliveredAfterCompletion) {
				throw new IllegalStateException("a message was delivered after the flux completed: " + delivered);
			}
			if (channel.isClosed() != completed) {
				throw new IllegalStateException("closed " + channel.isClosed() + " but completed " + completed);
			}
		}

	}

	/** A send is accepted exactly when no close came before it. */
	public static class ChannelSpec {

		private boolean closed;

		public boolean send(int thread) {
			return !closed;
		}

		public void close() {
			closed = true;
		}

	}

}
