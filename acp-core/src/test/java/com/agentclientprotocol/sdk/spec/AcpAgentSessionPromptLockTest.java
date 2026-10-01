/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic ordering tests for the single-turn prompt lock (#14).
 *
 * <p>
 * The transport here hands the session's handler back to the test, so the test can play
 * the role of a client that sends its next prompt the instant it receives a response —
 * synchronously, inside {@code onNext}, which is the worst case a CPU-starved client can
 * produce. The lock must already be released at that point, on the success path, on the
 * error path, and when the handler throws before returning a {@code Mono}.
 * </p>
 *
 * @author Mark Pollack
 */
class AcpAgentSessionPromptLockTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	/** Captures the handler the session installs; delivers nothing on its own. */
	static class CapturingAgentTransport implements AcpAgentTransport {

		final AtomicReference<Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>>> handler = new AtomicReference<>();

		final List<AcpSchema.JSONRPCMessage> sent = new CopyOnWriteArrayList<>();

		@Override
		public Mono<Void> start(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
			this.handler.set(handler);
			return Mono.empty();
		}

		@Override
		public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
			sent.add(message);
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		public Mono<Void> awaitTermination() {
			return Mono.empty();
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return null;
		}

		@Override
		public void setExceptionHandler(Consumer<Throwable> handler) {
		}

		/** Delivers one inbound message and returns the response the session produced. */
		Mono<AcpSchema.JSONRPCMessage> deliver(AcpSchema.JSONRPCMessage message) {
			return handler.get().apply(Mono.just(message));
		}

	}

	private static AcpSchema.JSONRPCRequest prompt(String id) {
		return new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, id, AcpSchema.METHOD_SESSION_PROMPT,
				new AcpSchema.PromptRequest("session-14", List.of(new AcpSchema.TextContent("prompt " + id))));
	}

	private static AcpSchema.JSONRPCResponse response(AcpSchema.JSONRPCMessage message) {
		assertThat(message).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		return (AcpSchema.JSONRPCResponse) message;
	}

	@Test
	void lockIsReleasedBeforeTheResponseIsPublished() {
		CapturingAgentTransport transport = new CapturingAgentTransport();
		AcpAgentSession session = new AcpAgentSession(TIMEOUT, transport,
				Map.of(AcpSchema.METHOD_SESSION_PROMPT,
						params -> Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN))),
				Map.of());

		AtomicReference<AcpSchema.JSONRPCResponse> second = new AtomicReference<>();
		AcpSchema.JSONRPCResponse first = response(transport.deliver(prompt("1"))
			// The client's next prompt, sent the instant the first response arrives.
			.doOnNext(ignored -> second.set(response(transport.deliver(prompt("2")).block(TIMEOUT))))
			.block(TIMEOUT));

		assertThat(first.error()).isNull();
		assertThat(second.get()).isNotNull();
		assertThat(second.get().error()).as("second prompt must not be rejected as concurrent").isNull();
		assertThat(session.hasActivePrompt()).isFalse();
	}

	@Test
	void lockIsReleasedBeforeAnErrorResponseIsPublished() {
		CapturingAgentTransport transport = new CapturingAgentTransport();
		AcpAgentSession session = new AcpAgentSession(TIMEOUT, transport,
				Map.of(AcpSchema.METHOD_SESSION_PROMPT, params -> Mono.error(new IllegalStateException("boom"))),
				Map.of());

		AtomicReference<Boolean> activeWhenErrorPublished = new AtomicReference<>();
		AcpSchema.JSONRPCResponse first = response(transport.deliver(prompt("1"))
			.doOnNext(ignored -> activeWhenErrorPublished.set(session.hasActivePrompt()))
			.block(TIMEOUT));

		assertThat(first.error()).isNotNull();
		assertThat(first.error().code()).isEqualTo(AcpErrorCodes.INTERNAL_ERROR);
		assertThat(activeWhenErrorPublished.get()).as("lock held while the error response was being published")
			.isFalse();
	}

	@Test
	void handlerThatThrowsBeforeReturningAMonoDoesNotKeepTheLock() {
		CapturingAgentTransport transport = new CapturingAgentTransport();
		java.util.concurrent.atomic.AtomicBoolean firstCall = new java.util.concurrent.atomic.AtomicBoolean(true);
		AcpAgentSession.RequestHandler<AcpSchema.PromptResponse> throwsOnce = params -> {
			if (firstCall.getAndSet(false)) {
				throw new IllegalStateException("thrown, not signalled");
			}
			return Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
		};
		AcpAgentSession session = new AcpAgentSession(TIMEOUT, transport,
				Map.of(AcpSchema.METHOD_SESSION_PROMPT, throwsOnce), Map.of());

		AcpSchema.JSONRPCResponse first = response(transport.deliver(prompt("1")).block(TIMEOUT));
		assertThat(first.error()).isNotNull();
		assertThat(session.hasActivePrompt()).as("a throwing handler must not hold the lock forever").isFalse();

		AcpSchema.JSONRPCResponse second = response(transport.deliver(prompt("2")).block(TIMEOUT));
		assertThat(second.error()).as("the next prompt must be accepted").isNull();
	}

	@Test
	void concurrentPromptOnTheSameConnectionIsStillRejectedWhileTheFirstRuns() {
		CapturingAgentTransport transport = new CapturingAgentTransport();
		reactor.core.publisher.Sinks.One<AcpSchema.PromptResponse> gate = reactor.core.publisher.Sinks.one();
		AcpAgentSession.RequestHandler<AcpSchema.PromptResponse> gated = params -> gate.asMono();
		AcpAgentSession session = new AcpAgentSession(TIMEOUT, transport,
				Map.of(AcpSchema.METHOD_SESSION_PROMPT, gated), Map.of());

		AtomicReference<AcpSchema.JSONRPCResponse> first = new AtomicReference<>();
		transport.deliver(prompt("1")).subscribe(message -> first.set(response(message)));
		assertThat(session.hasActivePrompt()).isTrue();

		AcpSchema.JSONRPCResponse second = response(transport.deliver(prompt("2")).block(TIMEOUT));
		assertThat(second.error()).isNotNull();
		assertThat(second.error().code()).isEqualTo(-32000);

		gate.tryEmitValue(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
		assertThat(first.get()).isNotNull();
		assertThat(first.get().error()).isNull();
		assertThat(session.hasActivePrompt()).isFalse();
	}

}
