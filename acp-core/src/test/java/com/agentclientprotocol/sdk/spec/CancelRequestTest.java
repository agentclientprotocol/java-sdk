/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpAgentSessionPromptLockTest.CapturingAgentTransport;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code $/cancel_request} (ACP v1, Cancellation) at the session level, both as the side that
 * cancels a request it sent and as the side that answers a cancelled request. The receiver
 * cancels the handler and answers exactly once: with the handler's result when that came
 * first, otherwise with {@code -32800}.
 */
class CancelRequestTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final TypeRef<Map<String, Object>> MAP = new TypeRef<>() {
	};

	/** Waits, at most {@link #TIMEOUT}, for a condition another thread makes true. */
	private static void eventually(BooleanSupplier condition) {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("condition not met within " + TIMEOUT);
			}
			try {
				Thread.sleep(5);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		}
	}

	private static AcpSchema.JSONRPCRequest request(Object id, String method, Object params) {
		return new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, id, method, params);
	}

	/** A {@code $/cancel_request} as it arrives off the wire: params are a map. */
	private static AcpSchema.JSONRPCNotification cancelRequest(Object requestId) {
		return new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION, AcpSchema.METHOD_CANCEL_REQUEST,
				Map.of("requestId", requestId));
	}

	private static AcpSchema.JSONRPCRequest prompt(String id) {
		return request(id, AcpSchema.METHOD_SESSION_PROMPT,
				new AcpSchema.PromptRequest("s1", List.of(new AcpSchema.TextContent("prompt " + id))));
	}

	/** Subscribes to the session's answer to one inbound message and collects what it emits. */
	private static List<AcpSchema.JSONRPCMessage> answers(CapturingAgentTransport transport,
			AcpSchema.JSONRPCMessage message) {
		List<AcpSchema.JSONRPCMessage> answers = new CopyOnWriteArrayList<>();
		transport.deliver(message).subscribe(answers::add);
		return answers;
	}

	private static AcpSchema.JSONRPCResponse single(List<AcpSchema.JSONRPCMessage> answers) {
		eventually(() -> !answers.isEmpty());
		assertThat(answers).hasSize(1);
		assertThat(answers.get(0)).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		return (AcpSchema.JSONRPCResponse) answers.get(0);
	}

	// ---------------------------------------------------------------------------
	// Receiving side: the agent answers a cancelled request
	// ---------------------------------------------------------------------------

	@Test
	void theAgentCancelsTheHandlerAndAnswersRequestCancelled() {
		AtomicBoolean handlerCancelled = new AtomicBoolean();
		CapturingAgentTransport transport = new CapturingAgentTransport();
		new AcpAgentSession(TIMEOUT, transport,
				Map.of("_slow", params -> Mono.never().doOnCancel(() -> handlerCancelled.set(true))), Map.of());

		List<AcpSchema.JSONRPCMessage> answers = answers(transport, request("r1", "_slow", Map.of()));
		assertThat(answers).isEmpty();
		transport.deliver(cancelRequest("r1")).block(TIMEOUT);

		AcpSchema.JSONRPCResponse response = single(answers);
		assertThat(response.id()).isEqualTo("r1");
		assertThat(response.result()).isNull();
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(AcpErrorCodes.REQUEST_CANCELLED);
		assertThat(response.error().message()).isEqualTo("Request cancelled");
		assertThat(handlerCancelled).isTrue();
	}

	@Test
	void aNumericIdMatchesWhateverIntegerTypeTheMapperChose() {
		CapturingAgentTransport transport = new CapturingAgentTransport();
		new AcpAgentSession(TIMEOUT, transport, Map.of("_slow", params -> Mono.never()), Map.of());

		List<AcpSchema.JSONRPCMessage> answers = answers(transport, request(7, "_slow", Map.of()));
		transport.deliver(cancelRequest(7L)).block(TIMEOUT);

		AcpSchema.JSONRPCResponse response = single(answers);
		assertThat(response.id()).isEqualTo(7);
		assertThat(response.error().code()).isEqualTo(AcpErrorCodes.REQUEST_CANCELLED);
	}

	@Test
	void aStringIdDoesNotMatchTheSameDigitsAsANumber() {
		CapturingAgentTransport transport = new CapturingAgentTransport();
		new AcpAgentSession(TIMEOUT, transport, Map.of("_slow", params -> Mono.never()), Map.of());

		List<AcpSchema.JSONRPCMessage> answers = answers(transport, request(7, "_slow", Map.of()));
		transport.deliver(cancelRequest("7")).block(TIMEOUT);

		assertThat(answers).as("\"7\" and 7 are different JSON-RPC ids").isEmpty();
	}

	@Test
	void aCancelAfterTheResponseIsIgnored() {
		AtomicInteger calls = new AtomicInteger();
		CapturingAgentTransport transport = new CapturingAgentTransport();
		new AcpAgentSession(TIMEOUT, transport,
				Map.of("_fast", params -> Mono.fromSupplier(() -> Map.of("n", calls.incrementAndGet()))), Map.of());

		List<AcpSchema.JSONRPCMessage> answers = answers(transport, request("r1", "_fast", Map.of()));
		AcpSchema.JSONRPCResponse response = single(answers);
		assertThat(response.error()).isNull();

		List<AcpSchema.JSONRPCMessage> afterCancel = answers(transport, cancelRequest("r1"));
		assertThat(afterCancel).isEmpty();
		assertThat(answers).hasSize(1);
		assertThat(transport.sent).isEmpty();
	}

	@Test
	void aCancelForAnUnknownIdIsIgnored() {
		AtomicBoolean handlerCancelled = new AtomicBoolean();
		CapturingAgentTransport transport = new CapturingAgentTransport();
		new AcpAgentSession(TIMEOUT, transport,
				Map.of("_slow", params -> Mono.never().doOnCancel(() -> handlerCancelled.set(true))), Map.of());

		List<AcpSchema.JSONRPCMessage> answers = answers(transport, request("r1", "_slow", Map.of()));
		assertThat(answers(transport, cancelRequest("other"))).isEmpty();

		assertThat(answers).isEmpty();
		assertThat(handlerCancelled).isFalse();
		assertThat(transport.sent).isEmpty();
	}

	@Test
	void aCancelRequestIsNotOfferedToTheNotificationHandlers() {
		AtomicBoolean offered = new AtomicBoolean();
		CapturingAgentTransport transport = new CapturingAgentTransport();
		new AcpAgentSession(TIMEOUT, transport, Map.of(), Map.of(AcpSchema.METHOD_CANCEL_REQUEST, params -> {
			offered.set(true);
			return Mono.empty();
		}));

		transport.deliver(cancelRequest("r1")).block(TIMEOUT);

		assertThat(offered).isFalse();
	}

	@Test
	void aCancelledPromptEndsItsTurnBeforeTheCancelledAnswerIsPublished() {
		AtomicInteger prompts = new AtomicInteger();
		CapturingAgentTransport transport = new CapturingAgentTransport();
		AcpAgentSession session = new AcpAgentSession(TIMEOUT, transport,
				Map.of(AcpSchema.METHOD_SESSION_PROMPT, params -> prompts.incrementAndGet() == 1 ? Mono.never()
						: Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN))),
				Map.of());

		AtomicReference<AcpSchema.JSONRPCMessage> next = new AtomicReference<>();
		List<AcpSchema.JSONRPCMessage> answers = new CopyOnWriteArrayList<>();
		transport.deliver(prompt("1"))
			// The client sends its next prompt the instant the cancelled answer arrives.
			.doOnNext(answer -> next.set(transport.deliver(prompt("2")).block(TIMEOUT)))
			.subscribe(answers::add);
		assertThat(session.hasActivePrompt("s1")).isTrue();

		transport.deliver(cancelRequest("1")).block(TIMEOUT);

		AcpSchema.JSONRPCResponse cancelled = single(answers);
		assertThat(cancelled.error().code()).isEqualTo(AcpErrorCodes.REQUEST_CANCELLED);
		assertThat(next.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		assertThat(((AcpSchema.JSONRPCResponse) next.get()).error())
			.as("the next prompt must not be rejected as concurrent")
			.isNull();
		assertThat(session.hasActivePrompt()).isFalse();
	}

	@Test
	void aHandlerThatFailsWithCancellationExceptionIsAnsweredRequestCancelled() {
		CapturingAgentTransport transport = new CapturingAgentTransport();
		new AcpAgentSession(TIMEOUT, transport,
				Map.of("_gone", params -> Mono.error(new CancellationException("context limit reached"))), Map.of());

		AcpSchema.JSONRPCResponse response = single(answers(transport, request("r1", "_gone", Map.of())));

		assertThat(response.error().code()).isEqualTo(AcpErrorCodes.REQUEST_CANCELLED);
		assertThat(response.error().message()).isEqualTo("context limit reached");
	}

	@Test
	void aRequestIdIsCancellableAgainOnceItsFirstUseAnswered() {
		CapturingAgentTransport transport = new CapturingAgentTransport();
		AtomicInteger calls = new AtomicInteger();
		new AcpAgentSession(TIMEOUT, transport,
				Map.of("_x", params -> calls.incrementAndGet() == 1 ? Mono.just(Map.of()) : Mono.never()), Map.of());

		single(answers(transport, request("r1", "_x", Map.of())));
		List<AcpSchema.JSONRPCMessage> second = answers(transport, request("r1", "_x", Map.of()));
		transport.deliver(cancelRequest("r1")).block(TIMEOUT);

		assertThat(single(second).error().code()).isEqualTo(AcpErrorCodes.REQUEST_CANCELLED);
	}

	@Test
	void aPromptAlreadyCancelledBySessionCancelAnswersStopReasonCancelled() {
		CapturingAgentTransport transport = new CapturingAgentTransport();
		new AcpAgentSession(TIMEOUT, transport, Map.of(AcpSchema.METHOD_SESSION_PROMPT, params -> Mono.never()),
				Map.of());

		List<AcpSchema.JSONRPCMessage> answers = answers(transport, prompt("1"));
		transport.deliver(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
				AcpSchema.METHOD_SESSION_CANCEL, Map.of("sessionId", "s1")))
			.block(TIMEOUT);
		transport.deliver(cancelRequest("1")).block(TIMEOUT);

		AcpSchema.JSONRPCResponse response = single(answers);
		assertThat(response.error()).isNull();
		assertThat(response.result()).isEqualTo(new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED));
	}

	@Test
	void aPromptHandlerAbortedAfterSessionCancelAnswersStopReasonCancelled() {
		Sinks.Empty<Void> abort = Sinks.empty();
		CapturingAgentTransport transport = new CapturingAgentTransport();
		new AcpAgentSession(TIMEOUT, transport, Map.of(AcpSchema.METHOD_SESSION_PROMPT,
				params -> abort.asMono().then(Mono.error(new CancellationException("aborted")))), Map.of());

		List<AcpSchema.JSONRPCMessage> answers = answers(transport, prompt("1"));
		transport.deliver(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
				AcpSchema.METHOD_SESSION_CANCEL, Map.of("sessionId", "s1")))
			.block(TIMEOUT);
		abort.tryEmitEmpty();

		AcpSchema.JSONRPCResponse response = single(answers);
		assertThat(response.error()).isNull();
		assertThat(response.result()).isEqualTo(new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED));
	}

	@Test
	void anInterruptedBlockingHandlerIsAnsweredRequestCancelledNotInternalError() throws InterruptedException {
		ExecutorService pool = Executors.newCachedThreadPool();
		Scheduler blocking = Schedulers.fromExecutorService(pool, "blocking-handler");
		try {
			CapturingAgentTransport transport = new CapturingAgentTransport();
			AtomicReference<CountDownLatch> started = new AtomicReference<>();
			AcpAgentSession.RequestHandler<Object> handler = params -> Mono.<Object>fromCallable(() -> {
				started.get().countDown();
				Thread.sleep(TIMEOUT.toMillis());
				return Map.of();
			}).subscribeOn(blocking);
			new AcpAgentSession(TIMEOUT, transport, Map.of("_blocking", handler), Map.of());

			// The interrupt the cancel causes races the cancel itself; run it often.
			for (int i = 0; i < 50; i++) {
				CountDownLatch running = new CountDownLatch(1);
				started.set(running);
				List<AcpSchema.JSONRPCMessage> answers = answers(transport, request("b" + i, "_blocking", Map.of()));
				assertThat(running.await(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)).isTrue();
				transport.deliver(cancelRequest("b" + i)).block(TIMEOUT);
				assertThat(single(answers).error().code()).as("attempt %d", i)
					.isEqualTo(AcpErrorCodes.REQUEST_CANCELLED);
			}
		}
		finally {
			blocking.dispose();
		}
	}

	@Test
	void closingTheAgentSessionCancelsTheRequestsItIsHandling() {
		AtomicBoolean handlerCancelled = new AtomicBoolean();
		CapturingAgentTransport transport = new CapturingAgentTransport();
		AcpAgentSession session = new AcpAgentSession(TIMEOUT, transport,
				Map.of("_slow", params -> Mono.never().doOnCancel(() -> handlerCancelled.set(true))), Map.of());

		List<AcpSchema.JSONRPCMessage> answers = answers(transport, request("r1", "_slow", Map.of()));
		session.close();

		assertThat(handlerCancelled).isTrue();
		assertThat(single(answers).error().code()).isEqualTo(AcpErrorCodes.REQUEST_CANCELLED);
		// A request arriving after the close is cancelled at once.
		assertThat(single(answers(transport, request("r2", "_slow", Map.of()))).error().code())
			.isEqualTo(AcpErrorCodes.REQUEST_CANCELLED);
	}

	// ---------------------------------------------------------------------------
	// Receiving side: the client answers a cancelled request
	// ---------------------------------------------------------------------------

	@Test
	void theClientAnswersACancelledPermissionRequestWithoutWaitingForItsNotificationDrain() {
		AtomicBoolean handlerCancelled = new AtomicBoolean();
		Sinks.Empty<Void> releaseUpdate = Sinks.empty();
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport,
				Map.of(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION,
						params -> Mono.never().doOnCancel(() -> handlerCancelled.set(true))),
				// A slow session/update consumer holds the ordered notification drain.
				Map.of(AcpSchema.METHOD_SESSION_UPDATE, params -> releaseUpdate.asMono()), Function.identity());
		try {
			transport.simulateIncomingMessage(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
					AcpSchema.METHOD_SESSION_UPDATE, Map.of()));
			transport.simulateIncomingMessage(
					request("p1", AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, Map.of("sessionId", "s1")));
			transport.simulateIncomingMessage(cancelRequest("p1"));

			eventually(() -> !transport.getSentMessages().isEmpty());
			assertThat(transport.getSentMessages()).hasSize(1);
			AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) transport.getLastSentMessage();
			assertThat(response.id()).isEqualTo("p1");
			assertThat(response.error().code()).isEqualTo(AcpErrorCodes.REQUEST_CANCELLED);
			assertThat(handlerCancelled).isTrue();
		}
		finally {
			releaseUpdate.tryEmitEmpty();
			session.close();
		}
	}

	@Test
	void theClientIgnoresACancelForAnUnknownId() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(),
				Function.identity());
		try {
			transport.simulateIncomingMessage(cancelRequest("nope"));
			assertThat(transport.getSentMessages()).isEmpty();
		}
		finally {
			session.close();
		}
	}

	// ---------------------------------------------------------------------------
	// Sending side: cancelling a request sent to the peer
	// ---------------------------------------------------------------------------

	private static List<AcpSchema.JSONRPCNotification> cancelsSent(MockAcpClientTransport transport) {
		return transport.getSentMessages()
			.stream()
			.filter(AcpSchema.JSONRPCNotification.class::isInstance)
			.map(AcpSchema.JSONRPCNotification.class::cast)
			.filter(n -> AcpSchema.METHOD_CANCEL_REQUEST.equals(n.method()))
			.toList();
	}

	private static Object requestIdOf(AcpSchema.JSONRPCNotification cancel) {
		assertThat(cancel.params()).isInstanceOf(AcpSchema.CancelRequestNotification.class);
		return ((AcpSchema.CancelRequestNotification) cancel.params()).requestId();
	}

	@Test
	void disposingARequestSendsOneCancelRequestWithItsId() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(),
				Function.identity());
		try {
			Disposable request = session.sendRequest("_slow", Map.of(), MAP).subscribe();
			Object id = transport.getLastSentMessageAsRequest().id();

			request.dispose();
			request.dispose();

			eventually(() -> !cancelsSent(transport).isEmpty());
			assertThat(cancelsSent(transport)).hasSize(1);
			assertThat(requestIdOf(cancelsSent(transport).get(0))).isEqualTo(id);
		}
		finally {
			session.close();
		}
	}

	@Test
	void aRequestCancelledBeforeItIsCreatedIsNeitherSentNorCancelled() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(),
				Function.identity());
		try {
			// takeUntilOther with a trigger that has already fired cancels the request's
			// subscription before Mono.create runs its callback.
			session.sendRequest("_slow", Map.of(), MAP).takeUntilOther(Mono.just("now")).subscribe();

			assertThat(transport.getSentMessages()).isEmpty();
		}
		finally {
			session.close();
		}
	}

	@Test
	void eachSubscriptionToARequestIsANewRequestWithItsOwnId() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(),
				Function.identity());
		try {
			Mono<Map<String, Object>> request = session.sendRequest("_x", Map.of(), MAP);
			request.subscribe();
			request.subscribe();

			assertThat(transport.getSentMessages()).hasSize(2)
				.extracting(message -> ((AcpSchema.JSONRPCRequest) message).id())
				.doesNotHaveDuplicates();
		}
		finally {
			session.close();
		}
	}

	@Test
	void theCancelIsSentOnlyAfterItsRequestWasWritten() {
		Sinks.Empty<Void> gate = Sinks.empty();
		List<AcpSchema.JSONRPCMessage> written = new CopyOnWriteArrayList<>();
		CapturingAgentTransport transport = new CapturingAgentTransport() {
			@Override
			public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
				if (message instanceof AcpSchema.JSONRPCRequest) {
					return gate.asMono().doOnSuccess(v -> written.add(message));
				}
				written.add(message);
				return Mono.empty();
			}
		};
		AcpAgentSession session = new AcpAgentSession(TIMEOUT, transport, Map.of(), Map.of());

		session.sendRequest("_slow", Map.of(), MAP).subscribe().dispose();
		gate.tryEmitEmpty();

		eventually(() -> written.size() == 2);
		assertThat(written.get(0)).isInstanceOf(AcpSchema.JSONRPCRequest.class);
		assertThat(((AcpSchema.JSONRPCNotification) written.get(1)).method())
			.isEqualTo(AcpSchema.METHOD_CANCEL_REQUEST);
	}

	@Test
	void aRequestThatFailedToSendIsNotCancelled() throws InterruptedException {
		CapturingAgentTransport transport = new CapturingAgentTransport() {
			@Override
			public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
				sent.add(message);
				if (message instanceof AcpSchema.JSONRPCRequest) {
					throw new IllegalStateException("cannot write");
				}
				return Mono.empty();
			}
		};
		AcpAgentSession session = new AcpAgentSession(TIMEOUT, transport, Map.of(), Map.of());
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Disposable request = session.sendRequest("_x", Map.of(), MAP).subscribe(v -> {
		}, failure::set);
		request.dispose();

		Thread.sleep(100);
		assertThat(failure.get()).hasMessage("cannot write");
		assertThat(transport.sent).hasSize(1);
	}

	@Test
	void aRequestAlreadyAnsweredSendsNoCancel() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(),
				Function.identity());
		try {
			AtomicReference<Map<String, Object>> result = new AtomicReference<>();
			Disposable request = session.sendRequest("_fast", Map.of(), MAP).subscribe(result::set);
			Object id = transport.getLastSentMessageAsRequest().id();
			transport.simulateIncomingMessage(
					new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, id, Map.of("ok", true), null));
			eventually(() -> result.get() != null);

			request.dispose();

			Thread.sleep(100);
			assertThat(cancelsSent(transport)).isEmpty();
		}
		finally {
			session.close();
		}
	}

	@Test
	void aRequestThatTimesOutSendsOneCancelRequest() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(Duration.ofMillis(100), transport, Map.of(), Map.of(),
				Function.identity());
		try {
			AtomicReference<Throwable> failure = new AtomicReference<>();
			session.sendRequest("_slow", Map.of(), MAP).subscribe(v -> {
			}, failure::set);
			Object id = transport.getLastSentMessageAsRequest().id();

			eventually(() -> failure.get() != null);

			eventually(() -> !cancelsSent(transport).isEmpty());
			assertThat(cancelsSent(transport)).hasSize(1);
			assertThat(requestIdOf(cancelsSent(transport).get(0))).isEqualTo(id);
		}
		finally {
			session.close();
		}
	}

	@Test
	void closingTheSessionSendsNoCancel() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(),
				Function.identity());
		AtomicReference<Throwable> failure = new AtomicReference<>();
		session.sendRequest("_slow", Map.of(), MAP).subscribe(v -> {
		}, failure::set);

		session.close();

		assertThat(failure.get()).isNotNull();
		assertThat(cancelsSent(transport)).isEmpty();
	}

	@Test
	void theAgentSideCancelsItsOwnRequestsToo() {
		CapturingAgentTransport transport = new CapturingAgentTransport();
		AcpAgentSession session = new AcpAgentSession(TIMEOUT, transport, Map.of(), Map.of());

		Disposable request = session.sendRequest(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, Map.of(), MAP)
			.subscribe();
		Object id = ((AcpSchema.JSONRPCRequest) transport.sent.get(0)).id();
		request.dispose();

		eventually(() -> transport.sent.size() == 2);
		AcpSchema.JSONRPCNotification cancel = (AcpSchema.JSONRPCNotification) transport.sent.get(1);
		assertThat(cancel.method()).isEqualTo(AcpSchema.METHOD_CANCEL_REQUEST);
		assertThat(requestIdOf(cancel)).isEqualTo(id);
	}

	@Test
	void aLateAnswerToACancelledRequestIsExpectedNotAWarning() {
		Logger logger = (Logger) LoggerFactory.getLogger(PendingResponses.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(),
				Function.identity());
		try {
			Disposable request = session.sendRequest("_slow", Map.of(), MAP).subscribe();
			Object id = transport.getLastSentMessageAsRequest().id();
			request.dispose();

			transport.simulateIncomingMessage(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, id, null,
					new AcpSchema.JSONRPCError(AcpErrorCodes.REQUEST_CANCELLED, "Request cancelled", null)));
			transport.simulateIncomingMessage(
					new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, "never-sent", Map.of(), null));

			assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN)
				.extracting(ILoggingEvent::getFormattedMessage)
				.containsExactly("Unexpected response for unknown id never-sent");
		}
		finally {
			logger.detachAppender(appender);
			session.close();
		}
	}

}
