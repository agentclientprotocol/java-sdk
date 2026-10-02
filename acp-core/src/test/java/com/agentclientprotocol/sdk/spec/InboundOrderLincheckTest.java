/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Validate;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Model checks {@link InboundOrder}: notifications and responses arriving on the inbound
 * thread, while handlers finish on another thread and the session closes on a third, in any
 * interleaving. Each handler finishes only when the {@code finish} operation completes it, as
 * an asynchronous handler does.
 *
 * <p>
 * The invariant, checked after every interleaving by {@link Inbound#orderedAndReleased()}:
 * every response is released exactly once; one released while the session was open was
 * released only after every notification that arrived before it was handled; and once the
 * remaining handlers finish (or the session is closed), no response is still held.
 * </p>
 */
class InboundOrderLincheckTest {

	/** Multiplies the number of scenarios explored; CI's lincheck job raises it. */
	private static final int SCALE = Integer.getInteger("lincheck.scale", 1);

	@Test
	void aResponseIsReleasedOnceAfterTheNotificationsBeforeItWereHandled() {
		new ModelCheckingOptions().iterations(20 * SCALE)
			.invocationsPerIteration(200)
			.threads(3)
			.actorsPerThread(3)
			.actorsBefore(0)
			.actorsAfter(0)
			.sequentialSpecification(InboundSpec.class)
			.check(Inbound.class);
	}

	public static class Inbound {

		private final InboundOrder<String> order = new InboundOrder<>();

		/** The handler being run; completing it finishes that notification's delivery. */
		private final AtomicReference<Sinks.Empty<Void>> running = new AtomicReference<>();

		private final AtomicInteger handled = new AtomicInteger();

		private final Disposable drain;

		private volatile boolean closing;

		/** Notifications the queue accepted; the inbound thread's own. */
		private int accepted;

		/** Per response: how many notifications arrived before it; the inbound thread's own. */
		private final List<Integer> arrivedBefore = new ArrayList<>();

		/** Per response: how many times it was released. */
		private final AtomicInteger[] releases = new AtomicInteger[8];

		/** Per response: whether it was released while notifications before it were unhandled. */
		private final boolean[] early = new boolean[8];

		public Inbound() {
			for (int i = 0; i < this.releases.length; i++) {
				this.releases[i] = new AtomicInteger();
			}
			this.drain = this.order.drain(notification -> {
				Sinks.Empty<Void> done = Sinks.empty();
				this.running.set(done);
				return done.asMono().doOnSuccess(v -> this.handled.incrementAndGet());
			}).subscribe();
		}

		@Operation(nonParallelGroup = "inbound")
		public void notification() {
			if (this.order.offer("n" + this.accepted).isSuccess()) {
				this.accepted++;
			}
		}

		@Operation(nonParallelGroup = "inbound")
		public void response() {
			int index = this.arrivedBefore.size();
			int before = this.accepted;
			this.arrivedBefore.add(before);
			// Sent before every notification, as a prompt is before its turn's updates.
			this.order.release(0, () -> {
				if (!this.closing && this.handled.get() < before) {
					this.early[index] = true;
				}
				this.releases[index].incrementAndGet();
			});
		}

		@Operation
		public void finish() {
			Sinks.Empty<Void> done = this.running.getAndSet(null);
			if (done != null) {
				done.tryEmitEmpty();
			}
		}

		@Operation
		public void closeGracefully() {
			this.closing = true;
			this.order.complete();
		}

		@Operation
		public void close() {
			this.closing = true;
			this.order.complete();
			this.drain.dispose();
			this.order.releaseHeld();
		}

		@Validate
		public void orderedAndReleased() {
			// Finish the handlers still running: every response must then be released.
			for (int i = 0; i < 16; i++) {
				finish();
			}
			for (int i = 0; i < this.arrivedBefore.size(); i++) {
				if (this.early[i]) {
					throw new IllegalStateException("response " + i + " released before the " + this.arrivedBefore.get(i)
							+ " notifications before it were handled; handled " + this.handled.get());
				}
				if (this.releases[i].get() != 1) {
					throw new IllegalStateException("response " + i + " released " + this.releases[i].get() + " times");
				}
			}
		}

	}

	public static class InboundSpec {

		public void notification() {
		}

		public void response() {
		}

		public void finish() {
		}

		public void closeGracefully() {
		}

		public void close() {
		}

	}

}
