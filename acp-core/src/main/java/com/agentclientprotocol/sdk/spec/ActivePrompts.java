/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The single-turn rule of an agent session: at most one active prompt per logical ACP
 * session id, which one connection may multiplex.
 *
 * <p>
 * Kotlin SDK precedent: its Agent.SessionWrapper owns a single active prompt guard per
 * logical session wrapper. This Java session can multiplex multiple logical ACP sessionIds
 * over one transport connection, so the same single-turn rule is applied per sessionId
 * instead of once for the whole connection.
 * </p>
 *
 * <p>
 * A prompt holds its session through a {@link Turn}. Ending a turn releases the session only
 * while that same turn still holds it: turns compare by identity, so a prompt still finishing
 * after the session closed cannot release the turn of a later prompt, whatever their request
 * ids.
 * </p>
 *
 * <p>
 * A cancel does not end a turn; the cancelled prompt's response does (see {@link #cancel}).
 * </p>
 */
final class ActivePrompts {

	private static final Logger logger = LoggerFactory.getLogger(ActivePrompts.class);

	private final ConcurrentHashMap<String, Turn> active = new ConcurrentHashMap<>();

	/** One prompt's hold on its session. Identity, not value, decides which turn is ended. */
	static final class Turn {

		private final String sessionId;

		private final @Nullable Object requestId;

		private final PromptAnswer answer = new PromptAnswer();

		/** Completes when the turn ends and releases its session. */
		private final Sinks.Empty<Void> ended = Sinks.empty();

		/** Runs when a cancel arrives; set by {@link PromptDeadlines} to start the grace period. */
		private volatile @Nullable Runnable onCancelRequested;

		private Turn(String sessionId, @Nullable Object requestId) {
			this.sessionId = sessionId;
			this.requestId = requestId;
		}

		PromptAnswer answer() {
			return this.answer;
		}

		/**
		 * Runs {@code action} when a cancel arrives for this prompt, or now if one already
		 * has. It may run twice when the two race: it must be idempotent.
		 */
		void onCancelRequested(Runnable action) {
			this.onCancelRequested = action;
			if (this.answer.isCancelling()) {
				action.run();
			}
		}

		private void requestCancel() {
			if (this.answer.requestCancel()) {
				Runnable action = this.onCancelRequested;
				if (action != null) {
					action.run();
				}
			}
		}

		String sessionId() {
			return this.sessionId;
		}

		/** Completes once the turn has ended (its prompt answered, failed or was dropped). */
		Mono<Void> ended() {
			return this.ended.asMono();
		}

		@Nullable Object requestId() {
			return this.requestId;
		}

	}

	/**
	 * Starts a prompt's turn on its session.
	 * @return the turn, or null when the session already has an active prompt
	 */
	@Nullable Turn tryStart(String sessionId, @Nullable Object requestId) {
		Turn turn = new Turn(sessionId, requestId);
		Turn current = this.active.putIfAbsent(sessionId, turn);
		if (current != null) {
			logger.warn("Rejected concurrent prompt request for sessionId={}. Active requestId={}", sessionId,
					current.requestId());
			return null;
		}
		return turn;
	}

	/**
	 * Ends {@code turn} before {@code response} publishes its result or error.
	 *
	 * <p>
	 * The prompt response ends the turn (ACP semantics), so the session is released
	 * <em>before</em> the response is handed downstream to the transport. Releasing in
	 * doFinally instead ran after the response had already reached the client; under CPU
	 * contention the client's next prompt then arrived before the release and was rejected
	 * as a concurrent prompt (#14). doOnNext and doOnError run before the signal propagates, and a Mono
	 * emits at most once, so the release is ordered before publication on every path;
	 * doFinally covers cancellation.
	 * </p>
	 */
	<T> Mono<T> endBeforePublishing(Turn turn, Mono<T> response) {
		return response.doOnNext(result -> end(turn, "response"))
			.doOnError(error -> end(turn, "error"))
			.doFinally(signal -> end(turn, signal.toString()));
	}

	/**
	 * Releases the turn's session if this turn still holds it. Idempotent: the release is
	 * attempted on the response, on an error and on the terminal signal, and only the first
	 * attempt does anything.
	 * @return whether this call released the session
	 */
	boolean end(Turn turn, String reason) {
		if (this.active.remove(turn.sessionId(), turn)) {
			logger.debug("Prompt lock released for sessionId={} requestId={} ({})", turn.sessionId(),
					turn.requestId(), reason);
			turn.ended.tryEmitEmpty();
			return true;
		}
		return false;
	}

	/**
	 * Notes a {@code session/cancel}. The turn does not end here: after a cancel the agent
	 * may still send {@code session/update}s and must then answer the original
	 * {@code session/prompt} with stop reason {@code cancelled}, and only once the turn has
	 * completed may the client send another prompt (ACP v1, prompt turn, Cancellation). The
	 * turn ends when that response is published ({@link #endBeforePublishing}), when the
	 * handler fails (a timeout the handler applies included), when the request's
	 * subscription is cancelled, or when the session closes. If the handler has not answered
	 * within the cancel grace period ({@link PromptTimeouts}), the session answers
	 * {@code cancelled} itself ({@link PromptDeadlines}).
	 * @return whether a prompt is active, and so will end with its response
	 */
	boolean cancel(String sessionId) {
		Turn current = this.active.get(sessionId);
		if (current != null) {
			current.requestCancel();
			logger.debug("Cancel requested for sessionId={} requestId={}; the turn ends with its response",
					sessionId, current.requestId());
			return true;
		}
		return false;
	}

	/** The turn holding the session, or null. */
	@Nullable Turn current(String sessionId) {
		return this.active.get(sessionId);
	}

	boolean isActive(String sessionId) {
		return this.active.containsKey(sessionId);
	}

	/**
	 * Whether no session has an active prompt. Looks for a key rather than asking the map's
	 * size: ConcurrentHashMap counts its entries in striped counters that concurrent updates
	 * change after the entry itself, so its isEmpty() and size() can report no entries while
	 * a prompt started earlier is still active (found by Lincheck, ActivePromptsLincheckTest).
	 */
	boolean isEmpty() {
		return !this.active.keySet().iterator().hasNext();
	}

	/** One session with an active prompt, arbitrary when there are several, or null. */
	@Nullable String anySessionId() {
		return this.active.keySet().stream().findFirst().orElse(null);
	}

	/**
	 * An immutable snapshot of the sessions with an active prompt. Copied by iterating:
	 * Set.copyOf(keySet()) first asks isEmpty(), which can miss an active prompt (see
	 * {@link #isEmpty()}).
	 */
	Set<String> sessionIds() {
		return Set.copyOf(new HashSet<>(this.active.keySet()));
	}

	void clear() {
		new HashSet<>(this.active.values()).forEach(turn -> end(turn, "cleared"));
	}

}
