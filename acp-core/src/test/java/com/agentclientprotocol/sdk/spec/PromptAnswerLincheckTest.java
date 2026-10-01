/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.QuietLoggers;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Validate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Model checks who answers a prompt ({@link PromptAnswer}) with Lincheck: the handler's
 * answer, a {@code session/cancel}, the cancel grace timer and the maximum-duration timer
 * race in every interleaving. The winner publishes through the session's release-before-
 * publish path ({@link ActivePrompts#endBeforePublishing}).
 *
 * <p>
 * Checked after every interleaving: at most one answer reaches the wire, and once one has,
 * the turn is released. The sequential specification fixes who wins: the first claim, the
 * grace timer only while the prompt is being cancelled, and a maximum-duration expiry
 * answers {@code CANCELLED} for a cancelled prompt, {@code EXPIRED} otherwise.
 * </p>
 */
class PromptAnswerLincheckTest {

	/**
	 * Multiplies the number of scenarios explored. The default keeps this test to seconds in
	 * the build; CI's lincheck job raises it with -Dlincheck.scale. Scenarios and interleavings
	 * are generated from a fixed seed, so a run is repeatable.
	 */
	private static final int SCALE = Integer.getInteger("lincheck.scale", 1);

	private static @Nullable QuietLoggers quiet;

	@BeforeAll
	static void quietLogs() {
		quiet = QuietLoggers.of(ActivePrompts.class);
	}

	@AfterAll
	static void restoreLogs() {
		if (quiet != null) {
			quiet.close();
		}
	}

	@Test
	void exactlyOneAnswerPerPrompt() {
		new ModelCheckingOptions().iterations(30 * SCALE)
			.invocationsPerIteration(200)
			.threads(3)
			.actorsPerThread(2)
			.actorsBefore(0)
			.actorsAfter(1)
			.sequentialSpecification(AnswerSpec.class)
			.check(Answer.class);
	}

	/**
	 * A cancel can arrive while the prompt's deadlines are being set up: the grace period
	 * must still start, exactly once.
	 */
	@Test
	void aCancelRacingTheDeadlinesSetUpStartsTheGracePeriodOnce() {
		new ModelCheckingOptions().iterations(10 * SCALE)
			.invocationsPerIteration(200)
			.threads(2)
			.actorsPerThread(1)
			.actorsBefore(0)
			.actorsAfter(0)
			.sequentialSpecification(GraceStartSpec.class)
			.check(GraceStart.class);
	}

	/** The session's deadlines register for the cancel while a cancel arrives. */
	public static class GraceStart {

		private final ActivePrompts prompts = new ActivePrompts();

		private final AtomicInteger starts = new AtomicInteger();

		private final AtomicBoolean started = new AtomicBoolean();

		private volatile boolean registered;

		public GraceStart() {
			prompts.tryStart("s", 0);
		}

		@Operation(runOnce = true)
		public void deadlinesSetUp() {
			ActivePrompts.Turn turn = prompts.current("s");
			if (turn != null) {
				// What PromptDeadlines registers: an idempotent start of the grace timer.
				turn.onCancelRequested(() -> {
					if (started.compareAndSet(false, true)) {
						starts.incrementAndGet();
					}
				});
				registered = true;
			}
		}

		@Operation(runOnce = true)
		public void cancel() {
			prompts.cancel("s");
		}

		@Validate
		public void graceStartedOnceIfCancelled() {
			ActivePrompts.Turn turn = prompts.current("s");
			boolean cancelling = turn != null && turn.answer().isCancelling();
			if (registered && cancelling && starts.get() != 1) {
				throw new IllegalStateException("cancelled, but the grace period started " + starts.get() + " times");
			}
		}

	}

	public static class GraceStartSpec {

		public void deadlinesSetUp() {
		}

		public void cancel() {
		}

	}

	/**
	 * One prompt, active from the start. The grace timer is modelled as firing at any time:
	 * it wins only while the prompt is being cancelled, which is the state a cancel moves it
	 * to before starting the timer.
	 */
	public static class Answer {

		private final ActivePrompts prompts = new ActivePrompts();

		private final ActivePrompts.Turn turn;

		/** The answers the client received. */
		private final Queue<String> wire = new ConcurrentLinkedQueue<>();

		public Answer() {
			ActivePrompts.Turn started = prompts.tryStart("s", 0);
			if (started == null) {
				throw new IllegalStateException("no turn");
			}
			this.turn = started;
		}

		private void publish(String answer) {
			prompts.endBeforePublishing(turn, Mono.just(answer)).subscribe(wire::add);
		}

		@Operation
		public boolean handlerAnswers() {
			if (turn.answer().handlerAnswered()) {
				publish("HANDLER");
				return true;
			}
			return false;
		}

		@Operation
		public void cancel() {
			prompts.cancel("s");
		}

		@Operation
		public String graceTimerFires() {
			if (turn.answer().graceExpired()) {
				publish("CANCELLED");
				return "CANCELLED";
			}
			return "LOST";
		}

		@Operation
		public String maxDurationTimerFires() {
			PromptAnswer.Forced forced = turn.answer().maxDurationExpired();
			if (forced == null) {
				return "LOST";
			}
			publish(forced.name());
			return forced.name();
		}

		@Validate
		public void oneAnswerAndTheTurnReleased() {
			if (wire.size() > 1) {
				throw new IllegalStateException("the prompt was answered " + wire.size() + " times: " + wire);
			}
			if (wire.size() == 1 && prompts.isActive("s")) {
				throw new IllegalStateException("answered " + wire + " but the turn is still held");
			}
		}

	}

	/** Sequential specification of {@link Answer}. */
	public static class AnswerSpec {

		private boolean cancelling;

		private boolean answered;

		public boolean handlerAnswers() {
			if (answered) {
				return false;
			}
			answered = true;
			return true;
		}

		public void cancel() {
			if (!answered) {
				cancelling = true;
			}
		}

		public String graceTimerFires() {
			if (!cancelling || answered) {
				return "LOST";
			}
			answered = true;
			return "CANCELLED";
		}

		public String maxDurationTimerFires() {
			if (answered) {
				return "LOST";
			}
			answered = true;
			return cancelling ? "CANCELLED" : "EXPIRED";
		}

	}

}
