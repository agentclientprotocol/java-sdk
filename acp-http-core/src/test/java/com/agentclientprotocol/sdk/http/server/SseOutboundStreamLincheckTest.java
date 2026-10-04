/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.jetbrains.lincheck.datastructures.BooleanGen;
import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.Validate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import org.slf4j.LoggerFactory;
import reactor.core.CoreSubscriber;

/**
 * Model checks the SSE mailbox of {@link SseOutboundStream} with Lincheck: the agent pushes
 * events while clients attach (a new GET takes the stream over), the container completes or
 * fails pending writes on its own threads, and clients reset their streams, in any
 * interleaving.
 *
 * <p>
 * The host side is a fake host that follows the host contract: it asks for the next frame only
 * once the previous write has completed. Its writes either complete at once or stay pending
 * until the container completes them. A reset stream behaves like Jetty's HTTP/2 output under
 * the servlet host: a write is accepted and silently discarded, and the host learns of the
 * reset only on its next write (the servlet host's empty-write check), when it cancels instead
 * of asking for more.
 * </p>
 *
 * <p>
 * The invariant, checked after every interleaving once pending writes have settled and a
 * healthy client has attached: every pushed event reached a client; the first deliveries are
 * in push order; and an event arrived twice only if it was written to a subscriber that was
 * detached before the write was confirmed (the documented pending-write takeover case).
 * </p>
 *
 * <p>
 * A second model races GETs attaching against the stream closing (a DELETE of the
 * connection): once closed, no GET may hold an open response, whether it attached before the
 * close, while a write was pending, or after it.
 * </p>
 */
class SseOutboundStreamLincheckTest {

	/**
	 * Multiplies the number of scenarios explored. The default keeps this test to seconds in
	 * the build; CI's lincheck job raises it with -Dlincheck.scale. Scenarios and interleavings
	 * are generated from a fixed seed, so a run is repeatable.
	 */
	private static final int SCALE = Integer.getInteger("lincheck.scale", 1);

	private static @Nullable Level previousLevel;

	/**
	 * The stream logs takeovers and backpressure; Lincheck model checks every instruction a
	 * thread runs, the log appender's included, so logging stays off while it runs.
	 */
	@BeforeAll
	static void quietLogs() {
		Logger logger = (Logger) LoggerFactory.getLogger(SseOutboundStream.class);
		previousLevel = logger.getLevel();
		logger.setLevel(Level.OFF);
	}

	@AfterAll
	static void restoreLogs() {
		((Logger) LoggerFactory.getLogger(SseOutboundStream.class)).setLevel(previousLevel);
	}

	@Test
	void noEventIsLostOrReordered() {
		new ModelCheckingOptions().iterations(30 * SCALE)
			.invocationsPerIteration(100)
			.threads(2)
			.actorsPerThread(3)
			.actorsBefore(1)
			.actorsAfter(1)
			.check(Mailbox.class);
	}

	@Test
	void closingLeavesNoResponseOpen() {
		new ModelCheckingOptions().iterations(50 * SCALE)
			.invocationsPerIteration(300)
			.threads(2)
			.actorsPerThread(2)
			.actorsBefore(1)
			.actorsAfter(0)
			.check(OpenAndClose.class);
	}

	/**
	 * GETs attaching race DELETE closing the stream, while the container completes pending
	 * writes. Once the stream is closed, no GET may be left holding an open response: one
	 * attached before the close is completed by it, one attaching after it is completed at
	 * once.
	 *
	 * <p>
	 * The clients are created up front, as in the servlet-era model of this test.
	 * </p>
	 */
	@Param(name = "client", gen = IntGen.class, conf = "0:1")
	public static class OpenAndClose {

		/** At most one GET per actor: one before the parallel part, two in each thread. */
		private static final int MAX_GETS = 5;

		private final SseOutboundStream stream = new SseOutboundStream(64, 2);

		private final Queue<Client> unusedSync = new ConcurrentLinkedQueue<>();

		private final Queue<Client> unusedAsync = new ConcurrentLinkedQueue<>();

		private final List<Client> clients = new CopyOnWriteArrayList<>();

		public OpenAndClose() {
			Deliveries deliveries = new Deliveries();
			for (int i = 0; i < MAX_GETS; i++) {
				unusedSync.add(new Client(false, deliveries));
				unusedAsync.add(new Client(true, deliveries));
			}
		}

		@Operation
		public void subscribe(@Param(gen = BooleanGen.class) boolean asyncWrites) {
			Client client = Objects.requireNonNull((asyncWrites ? unusedAsync : unusedSync).poll());
			clients.add(client);
			client.attach(stream);
		}

		@Operation
		public void completeWrite(@Param(name = "client") int index) {
			if (index < clients.size()) {
				clients.get(index).completePendingWrite();
			}
		}

		@Operation
		public void close() {
			stream.close();
		}

		@Validate
		public void aClosedStreamHoldsNoOpenResponse() {
			stream.close();
			for (int i = 0; i < clients.size(); i++) {
				if (!clients.get(i).isCompleted()) {
					throw new IllegalStateException("GET " + i + " of " + clients.size()
							+ " still holds an open response after the stream closed");
				}
			}
		}

	}

	@Param(name = "client", gen = IntGen.class, conf = "0:1")
	public static class Mailbox {

		/** Two pending events detach a subscriber: the backpressure path is part of the model. */
		private final SseOutboundStream stream = new SseOutboundStream(64, 2);

		private final Deliveries deliveries = new Deliveries();

		private final List<Client> clients = new CopyOnWriteArrayList<>();

		/**
		 * Events pushed so far; the event is its number. Guarded by this test's monitor on the
		 * stream, which makes numbering and pushing one step.
		 */
		private int pushed;

		@Operation
		public void push() {
			synchronized (stream) {
				stream.push(Integer.toString(pushed));
				pushed++;
			}
		}

		@Operation
		public void subscribe(@Param(gen = BooleanGen.class) boolean asyncWrites) {
			Client client = new Client(asyncWrites, deliveries);
			clients.add(client);
			client.attach(stream);
		}

		/** The container completes the client's pending write (or fails it, if the client reset). */
		@Operation
		public void completeWrite(@Param(name = "client") int index) {
			if (index < clients.size()) {
				clients.get(index).completePendingWrite();
			}
		}

		/** The client drops the stream; the server learns of it only on a later write. */
		@Operation
		public void resetByClient(@Param(name = "client") int index) {
			if (index < clients.size()) {
				clients.get(index).reset();
			}
		}

		@Operation
		public void keepAlive() {
			stream.keepAlive();
		}

		@Validate
		public void everyEventDeliveredInOrder() {
			clients.forEach(Client::completePendingWrite);
			Client healthy = new Client(false, deliveries);
			clients.add(healthy);
			healthy.attach(stream);
			clients.forEach(Client::completePendingWrite);

			int total;
			synchronized (stream) {
				total = pushed;
			}
			deliveries.check(total);
		}

	}

	/** What clients received, across every stream that was attached. */
	static final class Deliveries {

		private final Queue<Integer> received = new ConcurrentLinkedQueue<>();

		/** Events written to a subscriber that was detached before the write was confirmed. */
		private final Set<Integer> mayRepeat = ConcurrentHashMap.newKeySet();

		void check(int pushed) {
			List<Integer> firsts = new ArrayList<>();
			Set<Integer> seen = ConcurrentHashMap.newKeySet();
			for (Integer event : received) {
				if (seen.add(event)) {
					firsts.add(event);
				}
				else if (!mayRepeat.contains(event)) {
					throw new IllegalStateException("event " + event + " delivered twice: " + received);
				}
			}
			List<Integer> expected = new ArrayList<>();
			for (int event = 0; event < pushed; event++) {
				expected.add(event);
			}
			if (!firsts.equals(expected)) {
				throw new IllegalStateException(
						"pushed " + expected + " but first deliveries were " + firsts + " (all: " + received + ")");
			}
		}

	}

	/**
	 * One GET's host: it writes each frame it is handed, synchronously or held pending until the
	 * container completes it, and asks for the next only once a write has completed. After a
	 * reset its writes are discarded, and the next write it makes reveals the reset: it cancels.
	 */
	static final class Client implements CoreSubscriber<SseFrame> {

		private final boolean asyncWrites;

		private final Deliveries deliveries;

		private @Nullable Subscription subscription;

		/** A written frame the container has not completed; no request is made meanwhile. */
		private @Nullable SseFrame pending;

		/** Events delivered since the last request, not yet confirmed by the stream. */
		private final List<Integer> unconfirmed = new ArrayList<>();

		private boolean reset;

		/** A write was discarded by the reset stream: the next write fails. */
		private boolean failNextWrite;

		private boolean completed;

		Client(boolean asyncWrites, Deliveries deliveries) {
			this.asyncWrites = asyncWrites;
			this.deliveries = deliveries;
		}

		void attach(SseOutboundStream stream) {
			stream.subscribe().subscribe(this);
		}

		@Override
		public void onSubscribe(Subscription s) {
			synchronized (this) {
				this.subscription = s;
			}
			s.request(1);
		}

		@Override
		public void onNext(SseFrame frame) {
			boolean next;
			synchronized (this) {
				if (completed) {
					return;
				}
				if (asyncWrites) {
					pending = frame;
					return;
				}
				next = written(frame);
			}
			proceed(next);
		}

		/** Under the monitor: the write of {@code frame} completed; true to ask for more, false to cancel. */
		private boolean written(SseFrame frame) {
			if (failNextWrite) {
				return false;
			}
			if (reset) {
				failNextWrite = true;
				// The servlet host checks the stream with an empty write before asking again.
				return false;
			}
			if (!frame.comment()) {
				int event = Integer.parseInt(frame.data());
				unconfirmed.add(event);
				deliveries.received.add(event);
			}
			return true;
		}

		private void proceed(boolean next) {
			Subscription current;
			synchronized (this) {
				current = subscription;
				if (next) {
					// The next request confirms what was delivered.
					unconfirmed.clear();
				}
			}
			if (current == null) {
				return;
			}
			if (next) {
				current.request(1);
			}
			else {
				current.cancel();
				onComplete();
			}
		}

		void completePendingWrite() {
			boolean next;
			synchronized (this) {
				SseFrame frame = pending;
				if (frame == null || completed) {
					return;
				}
				pending = null;
				next = written(frame);
			}
			proceed(next);
		}

		synchronized void reset() {
			reset = true;
		}

		synchronized boolean isCompleted() {
			return completed;
		}

		@Override
		public void onError(Throwable error) {
			onComplete();
		}

		/** The response completes: what was delivered unconfirmed, or is pending, may also reach the client. */
		@Override
		public synchronized void onComplete() {
			if (completed) {
				return;
			}
			completed = true;
			deliveries.mayRepeat.addAll(unconfirmed);
			SseFrame frame = pending;
			if (frame != null && !frame.comment()) {
				deliveries.mayRepeat.add(Integer.parseInt(frame.data()));
			}
		}

	}

}
