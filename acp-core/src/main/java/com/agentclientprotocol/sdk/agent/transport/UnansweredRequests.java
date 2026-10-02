/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The requests sent to a peer that has not answered them yet, on a connection whose inbound
 * half can end while its outbound half stays open: once the peer's input to this side has
 * ended, no answer can come, and each such request must be failed, exactly once, by
 * whichever of the sender and the reader claims it.
 *
 * <p>
 * A sender records each request before sending it ({@link #sent}); the reader records each
 * answer ({@link #answered}) and, when the input ends, claims every request still waiting
 * ({@link #end}). A request is claimed by removing it: the sender re-checks the end after
 * recording, so a request sent while the input ends is claimed by exactly one of the two.
 * </p>
 *
 * @author Mark Pollack
 */
final class UnansweredRequests {

	/** The requests waiting for an answer: the id as text, mapped to the id as sent. */
	private final ConcurrentHashMap<String, Object> waiting = new ConcurrentHashMap<>();

	private volatile boolean ended;

	/**
	 * Records a request about to be sent.
	 * @param id the request's id
	 * @return {@code true} if the request may be sent; {@code false} if the input has ended,
	 * so no answer can come, and the caller must fail the request itself
	 */
	boolean sent(Object id) {
		String key = key(id);
		this.waiting.put(key, id);
		// Recorded first, checked second: an end that ran in between sees the request.
		return !this.ended || this.waiting.remove(key) == null;
	}

	/**
	 * Records the answer to a request.
	 * @param id the id the answer carries
	 */
	void answered(Object id) {
		this.waiting.remove(key(id));
	}

	/**
	 * Ends the input: no request is answered any more.
	 * @return the ids, as sent, of the requests still waiting, which the caller must fail;
	 * each is returned by at most one call, and not to a sender
	 */
	List<Object> end() {
		this.ended = true;
		List<Object> claimed = new ArrayList<>();
		for (String key : this.waiting.keySet()) {
			Object id = this.waiting.remove(key);
			if (id != null) {
				claimed.add(id);
			}
		}
		return claimed;
	}

	/**
	 * Whether the input has ended.
	 * @return {@code true} once {@link #end} ran
	 */
	boolean ended() {
		return this.ended;
	}

	/** JSON-RPC ids are strings or numbers; a number read back may be of another width. */
	private static String key(Object id) {
		return id instanceof Number number ? "n:" + number.longValue() : "s:" + id;
	}

}
