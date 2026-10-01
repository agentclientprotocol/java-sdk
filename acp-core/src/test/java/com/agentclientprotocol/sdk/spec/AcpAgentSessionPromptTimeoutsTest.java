/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.spec.AcpAgentSessionLifecycleTest.ControllableAgentTransport;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cancel grace period and the maximum prompt duration ({@link PromptTimeouts}), on a
 * virtual clock: the session's timer is a {@link VirtualTimeScheduler} the test advances.
 */
class AcpAgentSessionPromptTimeoutsTest {

	private static final Duration LONG_TIMEOUT = Duration.ofMinutes(5);

	private final VirtualTimeScheduler clock = VirtualTimeScheduler.create();

	private final ControllableAgentTransport transport = new ControllableAgentTransport();

	/** Set when the session cancels the prompt handler's subscription. */
	private final AtomicBoolean handlerCancelled = new AtomicBoolean();

	/** The prompt handler answers when the test emits here, if ever. */
	private final Sinks.One<AcpSchema.PromptResponse> handlerAnswer = Sinks.one();

	{
		this.transport.started.tryEmitEmpty();
	}

	private AcpAgentSession session(PromptTimeouts timeouts) {
		AcpAgentSession.RequestHandler<AcpSchema.PromptResponse> handler = params -> this.handlerAnswer.asMono()
			.doOnCancel(() -> this.handlerCancelled.set(true));
		return new AcpAgentSession(LONG_TIMEOUT, this.transport, Map.of(AcpSchema.METHOD_SESSION_PROMPT, handler),
				Map.of(), timeouts, delay -> Mono.delay(delay, this.clock));
	}

	/** Sends a prompt on session s1 and returns its response, cached so it can be read later. */
	private Mono<AcpSchema.JSONRPCMessage> prompt(String id) {
		Mono<AcpSchema.JSONRPCMessage> response = this.transport
			.deliver(new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, id, AcpSchema.METHOD_SESSION_PROMPT,
					Map.of("sessionId", "s1", "prompt", List.of())))
			.cache();
		response.subscribe(message -> {
		}, error -> {
		});
		return response;
	}

	private void cancel() {
		this.transport.deliver(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
				AcpSchema.METHOD_SESSION_CANCEL, Map.of("sessionId", "s1"))).block(Duration.ofSeconds(2));
	}

	private static AcpSchema.JSONRPCResponse answered(Mono<AcpSchema.JSONRPCMessage> response) {
		return (AcpSchema.JSONRPCResponse) response.block(Duration.ofSeconds(2));
	}

	private static Object stopReason(AcpSchema.JSONRPCResponse response) {
		assertThat(response.error()).isNull();
		return ((AcpSchema.PromptResponse) response.result()).stopReason();
	}

	@Test
	void aHandlerThatDoesNotAnswerWithinTheGracePeriodIsAnsweredCancelled() {
		AcpAgentSession session = session(PromptTimeouts.DEFAULTS);
		Mono<AcpSchema.JSONRPCMessage> response = prompt("p1");
		cancel();

		this.clock.advanceTimeBy(Duration.ofSeconds(59));
		assertThat(session.hasActivePrompt("s1")).as("still within the grace period").isTrue();
		assertThat(this.handlerCancelled).isFalse();

		this.clock.advanceTimeBy(Duration.ofSeconds(1));
		assertThat(stopReason(answered(response))).isEqualTo(AcpSchema.StopReason.CANCELLED);
		assertThat(this.handlerCancelled).as("the handler's subscription is cancelled").isTrue();
		assertThat(session.hasActivePrompt("s1")).as("the turn has ended").isFalse();
	}

	@Test
	void anAnswerWithinTheGracePeriodIsTheHandlers() {
		AcpAgentSession session = session(PromptTimeouts.DEFAULTS);
		Mono<AcpSchema.JSONRPCMessage> response = prompt("p1");
		cancel();
		this.clock.advanceTimeBy(Duration.ofSeconds(30));

		this.handlerAnswer.tryEmitValue(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
		assertThat(stopReason(answered(response))).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(session.hasActivePrompt("s1")).isFalse();

		Mono<AcpSchema.JSONRPCMessage> next = prompt("p2");
		this.clock.advanceTimeBy(Duration.ofMinutes(5));
		assertThat(stopReason(answered(next))).as("the old grace timer is gone; the next prompt is the handler's")
			.isEqualTo(AcpSchema.StopReason.END_TURN);
	}

	@Test
	void theGracePeriodStartsWithTheCancelNotWithThePrompt() {
		AcpAgentSession session = session(PromptTimeouts.DEFAULTS);
		prompt("p1");
		this.clock.advanceTimeBy(Duration.ofHours(1));
		assertThat(session.hasActivePrompt("s1")).as("no cancel, no grace period").isTrue();

		cancel();
		this.clock.advanceTimeBy(Duration.ofSeconds(60));
		assertThat(session.hasActivePrompt("s1")).isFalse();
	}

	@Test
	void aPromptThatRunsPastTheMaximumDurationIsAnsweredRequestCancelled() {
		AcpAgentSession session = session(PromptTimeouts.NONE.withMaxPromptDuration(Duration.ofMinutes(10)));
		Mono<AcpSchema.JSONRPCMessage> response = prompt("p1");

		this.clock.advanceTimeBy(Duration.ofMinutes(10));

		AcpSchema.JSONRPCError error = answered(response).error();
		assertThat(error).isNotNull();
		assertThat(error.code()).isEqualTo(AcpErrorCodes.REQUEST_CANCELLED);
		assertThat(error.message()).isEqualTo("Prompt exceeded maxPromptDuration of PT10M");
		assertThat(error.data()).isEqualTo(Map.of("maxPromptDuration", "PT10M"));
		assertThat(this.handlerCancelled).isTrue();
		assertThat(session.hasActivePrompt("s1")).isFalse();
	}

	@Test
	void aCancelledPromptThatRunsPastTheMaximumDurationIsAnsweredCancelled() {
		AcpAgentSession session = session(PromptTimeouts.DEFAULTS.withMaxPromptDuration(Duration.ofSeconds(10)));
		Mono<AcpSchema.JSONRPCMessage> response = prompt("p1");
		this.clock.advanceTimeBy(Duration.ofSeconds(5));
		cancel();

		this.clock.advanceTimeBy(Duration.ofSeconds(5));

		assertThat(stopReason(answered(response))).as("a cancelled prompt is answered cancelled, not with an error")
			.isEqualTo(AcpSchema.StopReason.CANCELLED);
		assertThat(session.hasActivePrompt("s1")).isFalse();
	}

	@Test
	void zeroTurnsBothLimitsOff() {
		AcpAgentSession session = session(PromptTimeouts.NONE);
		prompt("p1");
		cancel();

		this.clock.advanceTimeBy(Duration.ofDays(1));

		assertThat(session.hasActivePrompt("s1")).as("as before the limits: busy until the handler answers").isTrue();
		assertThat(this.handlerCancelled).isFalse();
	}

	@Test
	void withBothLimitsOffTheHandlerStillAnswers() {
		session(PromptTimeouts.NONE);
		Mono<AcpSchema.JSONRPCMessage> response = prompt("p1");
		this.handlerAnswer.tryEmitValue(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
		assertThat(stopReason(answered(response))).isEqualTo(AcpSchema.StopReason.END_TURN);
	}

	@Test
	void anAnswerBeforeTheMaximumDurationIsTheHandlers() {
		AcpAgentSession session = session(PromptTimeouts.NONE.withMaxPromptDuration(Duration.ofMinutes(10)));
		Mono<AcpSchema.JSONRPCMessage> response = prompt("p1");
		this.clock.advanceTimeBy(Duration.ofMinutes(9));
		this.handlerAnswer.tryEmitValue(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));

		this.clock.advanceTimeBy(Duration.ofMinutes(5));
		assertThat(stopReason(answered(response))).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(session.hasActivePrompt("s1")).isFalse();
	}

	@Test
	void defaultsAndValidation() {
		assertThat(PromptTimeouts.DEFAULTS.cancelGracePeriod()).isEqualTo(Duration.ofSeconds(60));
		assertThat(PromptTimeouts.DEFAULTS.maxPromptDuration()).isZero();
		assertThatThrownBy(() -> PromptTimeouts.NONE.withCancelGracePeriod(Duration.ofSeconds(-1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PromptTimeouts.NONE.withMaxPromptDuration(Duration.ofSeconds(-1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PromptTimeouts(null, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
	}

}
