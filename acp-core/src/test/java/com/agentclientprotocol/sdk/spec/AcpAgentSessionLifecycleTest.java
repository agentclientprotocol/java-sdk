/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The agent session's failure, close and cancellation paths: a transport that fails to
 * start, closing with requests to the client pending, and the single-turn prompt lock
 * across session/cancel, cancellation and the {@code Map} params the JSON transports
 * deliver.
 */
class AcpAgentSessionLifecycleTest {

	/** Long enough that a request which is not failed at once times out the assertion first. */
	private static final Duration LONG_TIMEOUT = Duration.ofMinutes(5);

	private static final Duration WAIT = Duration.ofSeconds(2);

	private static final TypeRef<AcpSchema.ReadTextFileResponse> READ = new TypeRef<>() {
	};

	/** Hands the session's handler to the test; starts as the test says. */
	static class ControllableAgentTransport implements AcpAgentTransport {

		final AtomicReference<Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>>> handler = new AtomicReference<>();

		final Sinks.One<Void> started = Sinks.one();

		final List<AcpSchema.JSONRPCMessage> sent = new CopyOnWriteArrayList<>();

		final AtomicBoolean closed = new AtomicBoolean();

		@Override
		public Mono<Void> start(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
			this.handler.set(handler);
			return this.started.asMono();
		}

		@Override
		public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
			this.sent.add(message);
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(() -> this.closed.set(true));
		}

		@Override
		public void close() {
			this.closed.set(true);
		}

		@Override
		public Mono<Void> awaitTermination() {
			return Mono.never();
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			throw new UnsupportedOperationException();
		}

		Mono<AcpSchema.JSONRPCMessage> deliver(AcpSchema.JSONRPCMessage message) {
			return this.handler.get().apply(Mono.just(message));
		}

	}

	/** A prompt handler that answers only when the test opens the gate. */
	private final Sinks.One<AcpSchema.PromptResponse> gate = Sinks.one();

	private final ControllableAgentTransport transport = new ControllableAgentTransport();

	private AcpAgentSession promptSession(Map<String, AcpAgentSession.NotificationHandler> notificationHandlers) {
		AcpAgentSession.RequestHandler<AcpSchema.PromptResponse> gated = params -> this.gate.asMono();
		return new AcpAgentSession(LONG_TIMEOUT, this.transport, Map.of(AcpSchema.METHOD_SESSION_PROMPT, gated),
				notificationHandlers);
	}

	private static AcpSchema.JSONRPCRequest prompt(String id, Object params) {
		return new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, id, AcpSchema.METHOD_SESSION_PROMPT, params);
	}

	private static AcpSchema.JSONRPCNotification cancel(Object params) {
		return new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION, AcpSchema.METHOD_SESSION_CANCEL, params);
	}

	private AcpSchema.JSONRPCResponse answer(AcpSchema.JSONRPCRequest request) {
		return (AcpSchema.JSONRPCResponse) this.transport.deliver(request).block(WAIT);
	}

	private Mono<AcpSchema.ReadTextFileResponse> pendingRead(AcpAgentSession session) {
		Mono<AcpSchema.ReadTextFileResponse> pending = session
			.sendRequest(AcpSchema.METHOD_FS_READ_TEXT_FILE, Map.of("path", "/tmp/a"), READ)
			.cache();
		pending.subscribe(v -> {
		}, e -> {
		});
		return pending;
	}

	@Test
	void transportThatRefusesToStartFailsConstruction() {
		this.transport.started.tryEmitError(new IllegalStateException("already started"));

		assertThatThrownBy(() -> new AcpAgentSession(LONG_TIMEOUT, this.transport, Map.of(), Map.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("ACP agent transport is not started: already started");
	}

	@Test
	void transportThatFailsToStartLaterFailsPendingAndLaterRequestsAtOnce() {
		AcpAgentSession session = new AcpAgentSession(LONG_TIMEOUT, this.transport, Map.of(), Map.of());
		Mono<AcpSchema.ReadTextFileResponse> pending = pendingRead(session);

		IllegalStateException cause = new IllegalStateException("port in use");
		this.transport.started.tryEmitError(cause);

		assertThatThrownBy(() -> pending.block(WAIT)).hasMessage("ACP session with client terminated").hasCause(cause);
		assertThatThrownBy(() -> pendingRead(session).block(WAIT)).isInstanceOf(IllegalStateException.class)
			.hasMessage("ACP agent transport is not started: port in use");
	}

	@Test
	void closeFailsPendingRequestsReleasesPromptsAndClosesTheTransport() {
		AcpAgentSession session = promptSession(Map.of());
		Mono<AcpSchema.ReadTextFileResponse> pending = pendingRead(session);
		this.transport.deliver(prompt("p1", new AcpSchema.PromptRequest("s1", List.of()))).subscribe();
		assertThat(session.hasActivePrompt()).isTrue();

		session.close();

		assertThatThrownBy(() -> pending.block(WAIT)).hasMessage("ACP session with client terminated");
		assertThat(session.hasActivePrompt()).isFalse();
		assertThat(this.transport.closed).isTrue();
	}

	@Test
	void gracefulCloseFailsPendingRequestsReleasesPromptsAndClosesTheTransport() {
		AcpAgentSession session = promptSession(Map.of());
		Mono<AcpSchema.ReadTextFileResponse> pending = pendingRead(session);
		this.transport.deliver(prompt("p1", new AcpSchema.PromptRequest("s1", List.of()))).subscribe();

		session.closeGracefully().block(WAIT);

		assertThatThrownBy(() -> pending.block(WAIT)).hasMessage("ACP session with client terminated");
		assertThat(session.hasActivePrompt()).isFalse();
		assertThat(this.transport.closed).isTrue();
	}

	@Test
	void promptLockAppliesToMapParamsFromJsonTransports() {
		AcpAgentSession session = promptSession(Map.of());
		this.transport.deliver(prompt("p1", Map.of("sessionId", "s1", "prompt", List.of()))).subscribe();

		AcpSchema.JSONRPCResponse sameSession = answer(prompt("p2", Map.of("sessionId", "s1", "prompt", List.of())));
		assertThat(sameSession.error()).isNotNull();
		assertThat(sameSession.error().code()).isEqualTo(AcpErrorCodes.CONCURRENT_PROMPT);

		this.transport.deliver(prompt("p3", Map.of("sessionId", "s2", "prompt", List.of()))).subscribe();
		assertThat(session.getActivePromptSessionIds()).containsExactlyInAnyOrder("s1", "s2");
	}

	/**
	 * ACP v1 prompt turn, Cancellation: after session/cancel the agent may still send
	 * updates and must then answer the original session/prompt with stopReason cancelled;
	 * "once a prompt turn completes, the Client may send another session/prompt". The turn
	 * ends with that response, not with the cancel.
	 */
	@Test
	void cancelKeepsTheTurnUntilTheCancelledPromptAnswers() {
		AtomicReference<Object> cancelParams = new AtomicReference<>();
		AcpAgentSession session = promptSession(
				Map.of(AcpSchema.METHOD_SESSION_CANCEL, params -> Mono.fromRunnable(() -> cancelParams.set(params))));
		Mono<AcpSchema.JSONRPCMessage> cancelled = this.transport
			.deliver(prompt("p1", new AcpSchema.PromptRequest("s1", List.of())))
			.cache();
		cancelled.subscribe();
		this.transport.deliver(prompt("p2", Map.of("sessionId", "s2", "prompt", List.of()))).subscribe();

		// In-process transports deliver the typed record...
		AcpSchema.CancelNotification typedCancel = new AcpSchema.CancelNotification("s1");
		assertThat(this.transport.deliver(cancel(typedCancel)).block(WAIT)).isNull();
		assertThat(cancelParams.get()).as("the agent's cancel handler still runs").isSameAs(typedCancel);
		// ... and the JSON transports a Map.
		this.transport.deliver(cancel(Map.of("sessionId", "s1"))).block(WAIT);

		assertThat(session.getActivePromptSessionIds()).containsExactlyInAnyOrder("s1", "s2");
		AcpSchema.JSONRPCResponse tooEarly = answer(prompt("p3", Map.of("sessionId", "s1", "prompt", List.of())));
		assertThat(tooEarly.error()).as("a prompt before the cancelled one answered is rejected").isNotNull();
		assertThat(tooEarly.error().code()).isEqualTo(AcpErrorCodes.CONCURRENT_PROMPT);

		this.gate.tryEmitValue(new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED));
		AcpSchema.JSONRPCResponse cancelledAnswer = (AcpSchema.JSONRPCResponse) cancelled.block(WAIT);
		assertThat(cancelledAnswer.error()).isNull();

		AcpSchema.JSONRPCResponse next = answer(prompt("p4", Map.of("sessionId", "s1", "prompt", List.of())));
		assertThat(next.error()).as("once the cancelled turn has answered, the next prompt is accepted").isNull();
	}

	@Test
	void aCancelledPromptWhoseHandlerFailsEndsItsTurn() {
		Sinks.One<AcpSchema.PromptResponse> failing = Sinks.one();
		AcpAgentSession.RequestHandler<AcpSchema.PromptResponse> handler = params -> failing.asMono();
		AcpAgentSession session = new AcpAgentSession(LONG_TIMEOUT, this.transport,
				Map.of(AcpSchema.METHOD_SESSION_PROMPT, handler), Map.of());
		Mono<AcpSchema.JSONRPCMessage> first = this.transport
			.deliver(prompt("p1", Map.of("sessionId", "s1", "prompt", List.of())))
			.cache();
		first.subscribe();
		this.transport.deliver(cancel(Map.of("sessionId", "s1"))).block(WAIT);
		assertThat(session.hasActivePrompt("s1")).isTrue();

		failing.tryEmitError(new IllegalStateException("aborted"));

		assertThat(((AcpSchema.JSONRPCResponse) first.block(WAIT)).error()).isNotNull();
		assertThat(session.hasActivePrompt("s1")).as("an error response ends the turn").isFalse();
	}

	/**
	 * The session sets no timeout of its own on an inbound prompt: a handler that never
	 * answers after a cancel keeps the session busy. A timeout the handler applies is an
	 * error like any other, and ends the turn.
	 */
	@Test
	void aCancelledPromptWhoseHandlerTimesOutEndsItsTurn() {
		AcpAgentSession.RequestHandler<AcpSchema.PromptResponse> handler = params -> Mono
			.<AcpSchema.PromptResponse>never()
			.timeout(Duration.ofMillis(50));
		AcpAgentSession session = new AcpAgentSession(LONG_TIMEOUT, this.transport,
				Map.of(AcpSchema.METHOD_SESSION_PROMPT, handler), Map.of());
		Mono<AcpSchema.JSONRPCMessage> first = this.transport
			.deliver(prompt("p1", Map.of("sessionId", "s1", "prompt", List.of())))
			.cache();
		first.subscribe();
		this.transport.deliver(cancel(Map.of("sessionId", "s1"))).block(WAIT);

		assertThat(((AcpSchema.JSONRPCResponse) first.block(WAIT)).error()).isNotNull();
		assertThat(session.hasActivePrompt("s1")).isFalse();
	}

	@Test
	void closingTheSessionEndsACancelledTurnThatNeverAnswers() {
		AcpAgentSession session = promptSession(Map.of());
		this.transport.deliver(prompt("p1", Map.of("sessionId", "s1", "prompt", List.of()))).subscribe();
		this.transport.deliver(cancel(Map.of("sessionId", "s1"))).block(WAIT);
		assertThat(session.hasActivePrompt("s1")).as("busy while the handler has not answered").isTrue();

		session.close();

		assertThat(session.hasActivePrompt()).isFalse();
	}

	@Test
	void otherRequestsOfASessionAreServedWhileItsPromptRuns() {
		AcpAgentSession.RequestHandler<AcpSchema.PromptResponse> gated = params -> this.gate.asMono();
		AcpAgentSession.RequestHandler<AcpSchema.SetSessionModeResponse> setMode = params -> Mono
			.just(new AcpSchema.SetSessionModeResponse());
		AcpAgentSession session = new AcpAgentSession(LONG_TIMEOUT, this.transport,
				Map.of(AcpSchema.METHOD_SESSION_PROMPT, gated, AcpSchema.METHOD_SESSION_SET_MODE, setMode), Map.of());
		this.transport.deliver(prompt("p1", Map.of("sessionId", "s1", "prompt", List.of()))).subscribe();

		for (String id : List.of("m1", "m2")) {
			AcpSchema.JSONRPCResponse response = answer(new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, id,
					AcpSchema.METHOD_SESSION_SET_MODE, Map.of("sessionId", "s1", "modeId", "code")));
			assertThat(response.error()).as("only prompts take the single-turn lock").isNull();
		}
		assertThat(session.hasActivePrompt("s1")).isTrue();
	}

	@Test
	void onlyCancelReleasesThePromptLock() {
		AcpAgentSession session = promptSession(Map.of());
		this.transport.deliver(prompt("p1", Map.of("sessionId", "s1", "prompt", List.of()))).subscribe();

		this.transport.deliver(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
				AcpSchema.METHOD_SESSION_UPDATE, Map.of("sessionId", "s1"))).block(WAIT);

		assertThat(session.hasActivePrompt("s1")).isTrue();
	}

	@Test
	void promptWithoutASessionIdStillReachesTheHandler() {
		AcpAgentSession.RequestHandler<AcpSchema.PromptResponse> answers = params -> Mono
			.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
		new AcpAgentSession(LONG_TIMEOUT, this.transport, Map.of(AcpSchema.METHOD_SESSION_PROMPT, answers), Map.of());

		assertThat(answer(prompt("p1", Map.of("prompt", List.of()))).error()).isNull();
		assertThat(answer(prompt("p2", new AcpSchema.PromptRequest(null, List.of()))).error()).isNull();
	}

	@Test
	void cancelledPromptReleasesTheLock() {
		AcpAgentSession session = promptSession(Map.of());
		Disposable inFlight = this.transport.deliver(prompt("p1", new AcpSchema.PromptRequest("s1", List.of())))
			.subscribe();
		assertThat(session.hasActivePrompt("s1")).isTrue();

		inFlight.dispose();

		assertThat(session.hasActivePrompt("s1")).isFalse();
	}

	@Test
	void notificationsReachTheirHandlerAndUnknownOnesAreIgnored() {
		AtomicReference<Object> received = new AtomicReference<>();
		promptSession(Map.of(AcpSchema.METHOD_SESSION_UPDATE, params -> Mono.fromRunnable(() -> received.set(params))));

		this.transport.deliver(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
				AcpSchema.METHOD_SESSION_UPDATE, Map.of("n", 1))).block(WAIT);
		assertThat(received.get()).isEqualTo(Map.of("n", 1));

		assertThat(this.transport.deliver(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
				"_vendor/unknown", null)).block(WAIT)).isNull();
	}

	@Test
	void responseToARequestOfTheAgentProducesNoReply() {
		AcpAgentSession session = promptSession(Map.of());
		Mono<AcpSchema.ReadTextFileResponse> pending = pendingRead(session);
		var request = (AcpSchema.JSONRPCRequest) this.transport.sent.get(0);

		assertThat(this.transport.deliver(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), null,
				new AcpSchema.JSONRPCError(AcpErrorCodes.INTERNAL_ERROR, "no such file", null)))
			.block(WAIT)).isNull();
		assertThatThrownBy(() -> pending.block(WAIT)).isInstanceOf(AcpError.class).hasMessageContaining("no such file");
	}

	@Test
	void hasActivePromptRejectsAnEmptySessionId() {
		AcpAgentSession session = promptSession(Map.of());
		assertThatThrownBy(() -> session.hasActivePrompt("")).isInstanceOf(IllegalArgumentException.class);
	}

}
