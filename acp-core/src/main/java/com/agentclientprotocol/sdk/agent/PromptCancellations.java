/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The cancellation signal of each prompt an agent is running, which its {@link PromptContext}
 * reads. The session allows one prompt per session id at a time, so a {@code session/cancel}
 * names its prompt by session id; a {@code $/cancel_request}, a deadline or a closing
 * connection cancels the handler's subscription, which signals too.
 */
final class PromptCancellations {

	/** One prompt's signal: set once, never cleared. */
	static final class Signal {

		private final AtomicBoolean cancelled = new AtomicBoolean();

		private final Sinks.Empty<Void> sink = Sinks.empty();

		/** Signals the cancel; only the first call does anything. */
		void cancel() {
			if (this.cancelled.compareAndSet(false, true)) {
				this.sink.tryEmitEmpty();
			}
		}

		boolean isCancelled() {
			return this.cancelled.get();
		}

		Mono<Void> whenCancelled() {
			return this.sink.asMono();
		}

	}

	private final ConcurrentHashMap<String, Signal> active = new ConcurrentHashMap<>();

	/**
	 * Starts the signal of a prompt on {@code sessionId}, replacing that of an earlier prompt
	 * on the session that has not ended yet.
	 */
	Signal start(String sessionId) {
		Signal signal = new Signal();
		this.active.put(sessionId, signal);
		return signal;
	}

	/** Ends a prompt's signal, if it is still the session's. */
	void end(String sessionId, Signal signal) {
		this.active.remove(sessionId, signal);
	}

	/** Signals the prompt running on {@code sessionId}, if there is one. */
	void cancel(@Nullable String sessionId) {
		if (sessionId == null) {
			return;
		}
		Signal signal = this.active.get(sessionId);
		if (signal != null) {
			signal.cancel();
		}
	}

	/** The signal of the prompt running on {@code sessionId}, or null. */
	@Nullable Signal current(String sessionId) {
		return this.active.get(sessionId);
	}

}
