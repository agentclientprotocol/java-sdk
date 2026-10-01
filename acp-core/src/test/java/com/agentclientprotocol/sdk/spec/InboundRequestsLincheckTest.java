/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import com.agentclientprotocol.sdk.QuietLoggers;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
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
import reactor.core.publisher.Sinks;

/**
 * Model checks {@link InboundRequests}, the table of inbound requests a
 * {@code $/cancel_request} can cancel, with Lincheck. Requests start (their handler is
 * subscribed), their handler answers, and the peer cancels them, in any interleaving.
 *
 * <p>
 * The invariant, checked after every interleaving by {@link Requests#exactlyOneResponse()}:
 * a request gets at most one response, and exactly one once its handler answered or a cancel
 * reached it; a {@code -32800} only when a cancel reached it; and the table holds exactly the
 * requests that have not been answered (no leak).
 * </p>
 */
class InboundRequestsLincheckTest {

	/** See {@link PendingResponsesLincheckTest}: CI's lincheck job raises it. */
	private static final int SCALE = Integer.getInteger("lincheck.scale", 1);

	private static @Nullable QuietLoggers quiet;

	@BeforeAll
	static void quietLogs() {
		quiet = QuietLoggers.of(InboundRequests.class);
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
	void everyRequestIsAnsweredExactlyOnce() {
		new ModelCheckingOptions().iterations(30 * SCALE)
			.invocationsPerIteration(100)
			.threads(THREADS)
			.actorsPerThread(3)
			.actorsBefore(0)
			.actorsAfter(0)
			.sequentialSpecification(RequestsSpec.class)
			.check(Requests.class);
	}

	@Param(name = "id", gen = IntGen.class, conf = "1:2")
	public static class Requests {

		private final InboundRequests inbound = new InboundRequests();

		/** Each request's handler: answers when {@link #respond} completes it. */
		private final AtomicReferenceArray<Sinks.@Nullable One<Object>> handlers = new AtomicReferenceArray<>(
				REQUESTS);

		private final AtomicIntegerArray responses = new AtomicIntegerArray(REQUESTS);

		private final AtomicIntegerArray cancelledResponses = new AtomicIntegerArray(REQUESTS);

		private final AtomicIntegerArray handlerAnswered = new AtomicIntegerArray(REQUESTS);

		private final AtomicIntegerArray cancelReached = new AtomicIntegerArray(REQUESTS);

		private final AtomicBoolean[] started = { new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean() };

		/** The peer's request with this thread's id arrives; the session subscribes its handler. */
		@Operation
		public void start(@Param(gen = ThreadIdGen.class) int id) {
			if (!started[id].compareAndSet(false, true)) {
				return;
			}
			Sinks.One<Object> handler = Sinks.one();
			handlers.set(id, handler);
			AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, id, "_work",
					Map.of());
			inbound.track(request, handler.asMono().map(result -> InboundMessages.result(request, result)))
				.subscribe(response -> {
					responses.incrementAndGet(id);
					AcpSchema.JSONRPCError error = response.error();
					if (error != null && error.code() == AcpErrorCodes.REQUEST_CANCELLED) {
						cancelledResponses.incrementAndGet(id);
					}
				});
		}

		/** The handler answers. */
		@Operation
		public void respond(@Param(name = "id") int id) {
			Sinks.One<Object> handler = handlers.get(id);
			if (handler != null && handler.tryEmitValue("result").isSuccess()) {
				handlerAnswered.set(id, 1);
			}
		}

		/** The peer sends {@code $/cancel_request}; the id arrives as whatever type the mapper chose. */
		@Operation
		public void cancel(@Param(name = "id") int id) {
			if (inbound.cancel((long) id)) {
				cancelReached.set(id, 1);
			}
		}

		@Validate
		public void exactlyOneResponse() {
			int unanswered = 0;
			for (int id = 1; id < REQUESTS; id++) {
				if (handlers.get(id) == null) {
					continue;
				}
				int count = responses.get(id);
				if (count > 1) {
					throw new IllegalStateException("request " + id + " got " + count + " responses");
				}
				boolean due = handlerAnswered.get(id) == 1 || cancelReached.get(id) == 1;
				if (due && count == 0) {
					throw new IllegalStateException("request " + id + " was answered or cancelled but got no response");
				}
				if (!due && count == 1) {
					throw new IllegalStateException("request " + id + " got a response nobody produced");
				}
				if (cancelledResponses.get(id) == 1 && cancelReached.get(id) == 0) {
					throw new IllegalStateException("request " + id + " got -32800 without a cancel");
				}
				if (count == 0) {
					unanswered++;
				}
			}
			if (inbound.size() != unanswered) {
				throw new IllegalStateException(
						"requests unanswered: " + unanswered + ", but the table holds " + inbound.size());
			}
		}

	}

	/** Sequential specification: the operations return nothing; the validator checks the state. */
	public static class RequestsSpec {

		public void start(int id) {
		}

		public void respond(int id) {
		}

		public void cancel(int id) {
		}

	}

}
