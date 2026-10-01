/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.agentclientprotocol.sdk.QuietLoggers;
import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Model checks the single-turn rule of {@link ActivePrompts} with Lincheck: every
 * interleaving of the operations below, on two threads plus a sequential prefix and suffix,
 * must produce results some sequential execution of the specification could produce.
 *
 * <p>
 * {@link Turns} checks the rule itself (at most one active prompt per session; cancel
 * releases whichever turn is active) and the views of it: it found the active-session count
 * reading ConcurrentHashMap's size counters, which can show no active prompt while one is
 * active. {@link Wire}
 * checks #14: an agent that ends a prompt and a client that sends its next prompt as soon as
 * it sees the response must never see that prompt rejected.
 * </p>
 */
class ActivePromptsLincheckTest {

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


	/** Bounded, so CI time is predictable; scenarios and interleavings are seeded. */
	private static ModelCheckingOptions modelChecking() {
		return new ModelCheckingOptions().iterations(20 * SCALE)
			.invocationsPerIteration(200)
			.threads(2)
			.actorsPerThread(3)
			.actorsBefore(1)
			.actorsAfter(1);
	}

	@Test
	void turnsAreLinearizable() {
		modelChecking().sequentialSpecification(TurnsSpec.class).check(Turns.class);
	}


	@Test
	void aClientThatSawTheResponseIsNeverRejected() {
		modelChecking().sequentialSpecification(WireSpec.class).check(Wire.class);
	}

	/** Two logical sessions on one connection; a prompt ends through the session's own pipeline. */
	@Param(name = "session", gen = IntGen.class, conf = "0:1")
	public static class Turns {

		private final ActivePrompts prompts = new ActivePrompts();

		@Operation
		public boolean start(@Param(name = "session") int session) {
			return prompts.tryStart("s" + session, session) != null;
		}

		/** The prompt holding the session publishes its response, which ends its turn. */
		@Operation
		public void finish(@Param(name = "session") int session) {
			ActivePrompts.Turn turn = prompts.current("s" + session);
			if (turn != null) {
				prompts.endBeforePublishing(turn, Mono.just("response")).subscribe();
			}
		}

		@Operation
		public boolean cancel(@Param(name = "session") int session) {
			return prompts.cancel("s" + session);
		}

		@Operation
		public boolean isActive(@Param(name = "session") int session) {
			return prompts.isActive("s" + session);
		}

		@Operation
		public int activeCount() {
			return prompts.sessionIds().size();
		}

	}

	/** Sequential specification of {@link Turns}: a set of the sessions with an active prompt. */
	public static class TurnsSpec {

		private final Set<Integer> active = new HashSet<>();

		public boolean start(int session) {
			return active.add(session);
		}

		public void finish(int session) {
			active.remove(session);
		}

		public boolean cancel(int session) {
			return active.remove(session);
		}

		public boolean isActive(int session) {
			return active.contains(session);
		}

		public int activeCount() {
			return active.size();
		}

	}

	/**
	 * One agent thread ends the active prompt and publishes its response on the wire; one
	 * client thread sends the next prompt only once it has read a response off the wire.
	 * A prompt is active at the start.
	 */
	public static class Wire {

		private final ActivePrompts prompts = new ActivePrompts();

		private final AtomicBoolean responseOnWire = new AtomicBoolean();

		public Wire() {
			prompts.tryStart("s", 0);
		}

		@Operation(nonParallelGroup = "agent")
		public void respond() {
			ActivePrompts.Turn turn = prompts.current("s");
			if (turn != null) {
				prompts.endBeforePublishing(turn, Mono.just("response"))
					.subscribe(response -> responseOnWire.set(true));
			}
		}

		@Operation(nonParallelGroup = "client")
		public String nextPrompt() {
			if (!responseOnWire.getAndSet(false)) {
				return "WAITING";
			}
			return prompts.tryStart("s", 1) != null ? "ACCEPTED" : "REJECTED";
		}

	}

	/** Sequential specification of {@link Wire}: the response is published after the turn ends. */
	public static class WireSpec {

		private boolean active = true;

		private boolean responseOnWire;

		public void respond() {
			if (active) {
				active = false;
				responseOnWire = true;
			}
		}

		public String nextPrompt() {
			if (!responseOnWire) {
				return "WAITING";
			}
			responseOnWire = false;
			if (active) {
				return "REJECTED";
			}
			active = true;
			return "ACCEPTED";
		}

	}

}
