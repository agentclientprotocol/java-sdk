/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single-turn rule, one operation at a time. ActivePromptsLincheckTest checks the same
 * rule under every interleaving; these cover what each operation reports.
 */
class ActivePromptsTest {

	private final ActivePrompts prompts = new ActivePrompts();

	@Test
	void aTurnEndsOnlyWhileItHoldsItsSession() {
		ActivePrompts.Turn turn = prompts.tryStart("s", 1);
		assertThat(turn).isNotNull();
		assertThat(prompts.current("s")).isSameAs(turn);
		assertThat(turn.requestId()).isEqualTo(1);

		assertThat(prompts.end(turn, "response")).isTrue();
		assertThat(prompts.end(turn, "onComplete")).as("idempotent").isFalse();
		assertThat(prompts.current("s")).isNull();
	}

	@Test
	void aCancelledTurnDoesNotReleaseTheNextPromptWithTheSameRequestId() {
		ActivePrompts.Turn cancelled = prompts.tryStart("s", 1);
		assertThat(cancelled).isNotNull();
		assertThat(prompts.cancel("s")).isTrue();
		assertThat(prompts.cancel("s")).as("nothing left to cancel").isFalse();

		ActivePrompts.Turn next = prompts.tryStart("s", 1);
		assertThat(next).isNotNull();
		assertThat(prompts.end(cancelled, "response")).isFalse();
		assertThat(prompts.current("s")).isSameAs(next);
	}

}
