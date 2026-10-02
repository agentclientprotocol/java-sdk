/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.LongConsumer;

import com.agentclientprotocol.sdk.QuietLoggers;
import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.ThreadIdGen;
import org.jetbrains.lincheck.datastructures.Validate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.util.context.Context;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Model checks {@link PendingResponses}, the table of requests waiting for a response, with
 * Lincheck. Requests are registered, answered, cancelled (their sink is disposed, as a
 * disposed subscription or {@code Mono.timeout} cancels the {@code Mono.create} source),
 * failed by a transport failure and dismissed by close, in any interleaving.
 *
 * <p>
 * The sequential specification covers what {@code send} returns. The invariant that matters,
 * checked after every interleaving by {@link Requests#noLostOrDoubleCompletion()}: every
 * request is signalled at most once, and a request that was neither signalled nor cancelled
 * is still waiting (its response can still complete it), while a cancelled one is not
 * (nothing leaks until the session ends); and a {@code $/cancel_request} is sent for a request
 * exactly when its caller gave up before any signal, never twice and never after a response,
 * a dismissal or a transport failure.
 * </p>
 */
class PendingResponsesLincheckTest {

	/**
	 * Multiplies the number of scenarios explored. The default keeps this test to seconds in
	 * the build; CI's lincheck job raises it with -Dlincheck.scale. Scenarios and interleavings
	 * are generated from a fixed seed, so a run is repeatable.
	 */
	private static final int SCALE = Integer.getInteger("lincheck.scale", 1);

	private static @Nullable QuietLoggers quiet;

	@BeforeAll
	static void quietLogs() {
		quiet = QuietLoggers.of(PendingResponses.class);
	}

	@AfterAll
	static void restoreLogs() {
		if (quiet != null) {
			quiet.close();
		}
	}


	private static final int THREADS = 2;

	/** Request ids are thread ids, which Lincheck numbers from 1 (0 is its own thread). */
	private static final int REQUESTS = THREADS + 1;

	@Test
	void noRequestIsLostOrCompletedTwice() {
		new ModelCheckingOptions().iterations(50 * SCALE)
			.invocationsPerIteration(100)
			.threads(THREADS)
			.actorsPerThread(3)
			.actorsBefore(0)
			.actorsAfter(0)
			.sequentialSpecification(RequestsSpec.class)
			.check(Requests.class);
	}

	/**
	 * The Reactor contract the model's sink relies on: a cancel that arrives while another is
	 * still in the cancel callback does nothing, so the dispose callback (which removes the
	 * entry) cannot overtake the cancel callback (which reports the cancel only if it removes
	 * the entry itself).
	 */
	@Test
	void reactorRunsTheCallbacksOfOnlyTheFirstCancel() throws Exception {
		CountDownLatch inCancelCallback = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger cancelCallbacks = new AtomicInteger();
		AtomicBoolean disposed = new AtomicBoolean();
		AtomicReference<@Nullable Subscription> subscription = new AtomicReference<>();
		Mono.create(sink -> {
			sink.onCancel(() -> {
				cancelCallbacks.incrementAndGet();
				inCancelCallback.countDown();
				awaitQuietly(release);
			});
			sink.onDispose(() -> disposed.set(true));
		}).subscribe(new BaseSubscriber<Object>() {
			@Override
			protected void hookOnSubscribe(Subscription s) {
				subscription.set(s);
			}
		});
		Subscription sink = Objects.requireNonNull(subscription.get());
		ExecutorService first = Executors.newSingleThreadExecutor();
		try {
			Future<?> firstCancel = first.submit(sink::cancel);
			assertThat(inCancelCallback.await(5, TimeUnit.SECONDS)).isTrue();

			sink.cancel();

			assertThat(disposed).as("the second cancel ran the dispose callback").isFalse();
			release.countDown();
			firstCancel.get(5, TimeUnit.SECONDS);
			assertThat(cancelCallbacks).hasValue(1);
			assertThat(disposed).isTrue();
		}
		finally {
			release.countDown();
			first.shutdownNow();
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("not released");
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Param(name = "id", gen = IntGen.class, conf = "1:2")
	public static class Requests {

		private final AtomicReference<@Nullable Throwable> transportFailure = new AtomicReference<>();

		/** How many $/cancel_request notifications each request id caused. */
		private final AtomicIntegerArray cancelsSent = new AtomicIntegerArray(REQUESTS);

		private final PendingResponses pending = new PendingResponses(transportFailure::get,
				cause -> new IllegalStateException("not started", cause), "agent");

		/** Request ids are unique: each may be sent once. Only the thread of that id touches its slot. */
		private final boolean[] used = new boolean[REQUESTS];

		/** The sink of each request that was registered. */
		private final AtomicReferenceArray<RecordingSink> sinks = new AtomicReferenceArray<>(REQUESTS);

		/** Each thread sends one request, whose id is the thread's. */
		@Operation
		public String send(@Param(gen = ThreadIdGen.class) int id) {
			if (used[id]) {
				return "DUPLICATE";
			}
			used[id] = true;
			RecordingSink sink = new RecordingSink();
			if (!pending.register(id, sink, () -> cancelsSent.incrementAndGet(id))) {
				return "FAILED_AT_ONCE";
			}
			sinks.set(id, sink);
			return "SENT";
		}

		@Operation
		public void respond(@Param(name = "id") int id) {
			pending.complete(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, id, "result", null));
		}

		/**
		 * The caller gives up: it disposes the request, or its timeout fires. Reactor cancels
		 * and disposes the sink.
		 */
		@Operation
		public void cancel(@Param(name = "id") int id) {
			RecordingSink sink = sinks.get(id);
			if (sink != null) {
				sink.cancel();
			}
		}

		/** The transport terminates (or failed to start): what the sessions do. */
		@Operation
		public void transportFails() {
			IllegalStateException failure = new IllegalStateException("transport terminated");
			transportFailure.compareAndSet(null, failure);
			pending.dismissAll(failure);
		}

		@Operation
		public void close() {
			pending.dismissAll(null);
		}

		@Validate
		public void noLostOrDoubleCompletion() {
			int waiting = 0;
			for (int id = 1; id < REQUESTS; id++) {
				RecordingSink sink = sinks.get(id);
				if (sink == null) {
					continue;
				}
				if (sink.signals.get() > 1) {
					throw new IllegalStateException("request " + id + " was completed " + sink.signals.get() + " times");
				}
				int cancels = cancelsSent.get(id);
				boolean gaveUpUnanswered = sink.cancelled && sink.signals.get() == 0;
				if (cancels != (gaveUpUnanswered ? 1 : 0)) {
					throw new IllegalStateException("request " + id + " caused " + cancels
							+ " $/cancel_request, cancelled before any signal: " + gaveUpUnanswered);
				}
				if (sink.signals.get() == 0 && !sink.cancelled) {
					waiting++;
				}
			}
			if (transportFailure.get() != null && waiting > 0) {
				throw new IllegalStateException(waiting + " requests still wait on a transport that failed");
			}
			if (pending.size() != waiting) {
				throw new IllegalStateException(
						"requests waiting: " + waiting + ", but " + pending.size() + " can still be completed");
			}
		}

	}

	/** Sequential specification of what {@code send} returns. */
	public static class RequestsSpec {

		private final Set<Integer> used = new HashSet<>();

		private boolean failed;

		public String send(int id) {
			if (!used.add(id)) {
				return "DUPLICATE";
			}
			return failed ? "FAILED_AT_ONCE" : "SENT";
		}

		public void respond(int id) {
		}

		public void cancel(int id) {
		}

		public void transportFails() {
			failed = true;
		}

		public void close() {
		}

	}

	/**
	 * A {@link MonoSink} that counts the signals it is given and, like Reactor's, runs its
	 * dispose callback when it is cancelled or signalled.
	 */
	static final class RecordingSink implements MonoSink<AcpSchema.JSONRPCResponse> {

		final AtomicInteger signals = new AtomicInteger();

		volatile boolean cancelled;

		/** Whether a cancel already ran: Reactor's sink acts on the first cancel only. */
		private final AtomicBoolean cancelRan = new AtomicBoolean();

		private final AtomicReference<@Nullable Disposable> onDispose = new AtomicReference<>();

		private final AtomicReference<@Nullable Disposable> onCancel = new AtomicReference<>();

		/**
		 * Like Reactor's: the cancel callback, then the dispose callback, unless already
		 * signalled. Only the first cancel does anything, as in Reactor's sink, whose
		 * {@code cancel()} swaps its state once and runs both callbacks on the thread that
		 * won: a second cancel (the caller disposes as its timeout fires, say) cannot run the
		 * dispose callback while the first is still in the cancel callback. A model that let
		 * it would report a lost {@code $/cancel_request} that Reactor never produces
		 * ({@link PendingResponsesLincheckTest#reactorRunsTheCallbacksOfOnlyTheFirstCancel()} pins
		 * that contract).
		 */
		void cancel() {
			cancelled = true;
			if (!cancelRan.compareAndSet(false, true)) {
				return;
			}
			Disposable callback = onCancel.getAndSet(null);
			if (callback != null && signals.get() == 0) {
				callback.dispose();
			}
			dispose();
		}

		private void dispose() {
			onCancel.set(null);
			Disposable callback = onDispose.getAndSet(null);
			if (callback != null) {
				callback.dispose();
			}
		}

		@Override
		public void success() {
			signals.incrementAndGet();
			dispose();
		}

		@Override
		public void success(AcpSchema.@Nullable JSONRPCResponse value) {
			signals.incrementAndGet();
			dispose();
		}

		@Override
		public void error(Throwable e) {
			signals.incrementAndGet();
			dispose();
		}

		@Override
		@SuppressWarnings("deprecation")
		public Context currentContext() {
			return Context.empty();
		}

		@Override
		public MonoSink<AcpSchema.JSONRPCResponse> onRequest(LongConsumer consumer) {
			return this;
		}

		@Override
		public MonoSink<AcpSchema.JSONRPCResponse> onCancel(Disposable d) {
			if (cancelled) {
				d.dispose();
			}
			else {
				onCancel.set(d);
			}
			return this;
		}

		@Override
		public MonoSink<AcpSchema.JSONRPCResponse> onDispose(Disposable d) {
			if (cancelled) {
				d.dispose();
			}
			else {
				onDispose.set(d);
			}
			return this;
		}

	}

}
