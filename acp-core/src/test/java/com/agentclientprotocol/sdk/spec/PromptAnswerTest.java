/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who answers a prompt, one step at a time. PromptAnswerLincheckTest checks the same claims
 * under every interleaving.
 */
class PromptAnswerTest {

	private final PromptAnswer answer = new PromptAnswer();

	@Test
	void theHandlerAnswersOnce() {
		assertThat(answer.handlerAnswered()).isTrue();
		assertThat(answer.handlerAnswered()).isFalse();
		assertThat(answer.requestCancel()).as("too late to cancel").isFalse();
		assertThat(answer.graceExpired()).isFalse();
		assertThat(answer.maxDurationExpired()).isNull();
	}

	@Test
	void theGracePeriodAnswersOnlyACancelledPrompt() {
		assertThat(answer.graceExpired()).as("not cancelled").isFalse();
		assertThat(answer.requestCancel()).isTrue();
		assertThat(answer.requestCancel()).as("the grace period starts once").isFalse();
		assertThat(answer.isCancelling()).isTrue();

		assertThat(answer.graceExpired()).isTrue();
		assertThat(answer.isCancelling()).isFalse();
		assertThat(answer.handlerAnswered()).as("the handler's late answer is dropped").isFalse();
	}

	@Test
	void theMaximumDurationAnswersExpiredOrCancelled() {
		assertThat(answer.maxDurationExpired()).isEqualTo(PromptAnswer.Forced.EXPIRED);

		PromptAnswer cancelled = new PromptAnswer();
		cancelled.requestCancel();
		assertThat(cancelled.maxDurationExpired()).isEqualTo(PromptAnswer.Forced.CANCELLED);
		assertThat(cancelled.graceExpired()).isFalse();
	}

	@Test
	void aCancelBeforeTheDeadlinesRegisterStillReachesThem() {
		ActivePrompts prompts = new ActivePrompts();
		ActivePrompts.Turn turn = prompts.tryStart("s", 1);
		assertThat(turn).isNotNull();
		prompts.cancel("s");

		AtomicInteger runs = new AtomicInteger();
		turn.onCancelRequested(runs::incrementAndGet);
		assertThat(runs).as("registered after the cancel: runs at once").hasValue(1);

		prompts.cancel("s");
		assertThat(runs).as("a second cancel starts nothing").hasValue(1);
	}

	@Test
	void aCancelAfterTheDeadlinesRegisterRunsThem() {
		ActivePrompts prompts = new ActivePrompts();
		ActivePrompts.Turn turn = prompts.tryStart("s", 1);
		assertThat(turn).isNotNull();
		AtomicInteger runs = new AtomicInteger();
		turn.onCancelRequested(runs::incrementAndGet);
		assertThat(runs).hasValue(0);

		prompts.cancel("s");
		assertThat(runs).hasValue(1);
	}

}
