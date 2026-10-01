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
	void cancelKeepsTheTurnUntilTheCancelledPromptEnds() {
		ActivePrompts.Turn cancelled = prompts.tryStart("s", 1);
		assertThat(cancelled).isNotNull();

		assertThat(prompts.cancel("s")).as("a prompt was active").isTrue();
		assertThat(prompts.current("s")).as("the turn ends with its response, not the cancel").isSameAs(cancelled);
		assertThat(prompts.tryStart("s", 2)).as("the session is still busy").isNull();

		assertThat(prompts.end(cancelled, "response")).isTrue();
		assertThat(prompts.cancel("s")).as("nothing left to cancel").isFalse();
		assertThat(prompts.tryStart("s", 2)).isNotNull();
	}

	@Test
	void aTurnFromBeforeClearDoesNotReleaseTheNextPromptWithTheSameRequestId() {
		ActivePrompts.Turn stale = prompts.tryStart("s", 1);
		assertThat(stale).isNotNull();
		prompts.clear();

		ActivePrompts.Turn next = prompts.tryStart("s", 1);
		assertThat(next).isNotNull();
		assertThat(prompts.end(stale, "response")).isFalse();
		assertThat(prompts.current("s")).isSameAs(next);
	}

}
