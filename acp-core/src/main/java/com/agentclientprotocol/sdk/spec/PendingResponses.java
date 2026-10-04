/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.MonoSink;

/**
 * The requests a session sent that are waiting for their response, keyed by request id.
 *
 * <p>
 * Each waiting request is completed at most once, by whichever comes first: its response
 * ({@link #complete}), the end of the session ({@link #dismissAll}), or the request giving up
 * (its timeout or cancellation disposes its sink, which removes it). Every path removes the
 * entry before signalling it, so two paths never both signal one request, and no path drops an
 * entry without signalling it.
 * </p>
 *
 * <p>
 * A request whose caller gave up before any signal (its subscription was cancelled, by the
 * caller or by its timeout) is reported once to its cancel callback, which tells the peer
 * ({@code $/cancel_request}). Only the cancel callback can still find the entry then: every
 * other path removed it first, so a request that was answered, dismissed or failed to send
 * is never reported. The peer still answers a cancelled request; that late response is
 * expected and is discarded quietly.
 * </p>
 */
final class PendingResponses {

	private static final Logger logger = LoggerFactory.getLogger(PendingResponses.class);

	private final ConcurrentHashMap<Object, MonoSink<AcpSchema.JSONRPCResponse>> pending = new ConcurrentHashMap<>();

	/** The reason the transport cannot deliver (it failed to connect or start, or terminated), or null. */
	private final Supplier<@Nullable Throwable> transportFailure;

	/** Wraps the transport failure into the error a caller receives. */
	private final Function<Throwable, RuntimeException> unavailable;

	/** Names the peer in the error a dismissed request fails with ("agent", "client"). */
	private final String peer;

	/** How many cancelled ids are remembered, so their late responses are not reported. */
	static final int REMEMBERED_CANCELLATIONS = 1024;

	/**
	 * The most recently cancelled ids, whose late response is expected. Bounded: a peer that
	 * never answers a cancelled request must not grow it.
	 */
	private final Set<Object> recentlyCancelled = Collections.synchronizedSet(Collections
		.newSetFromMap(new LinkedHashMap<Object, Boolean>(16, 0.75f, false) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Object, Boolean> eldest) {
				return size() > REMEMBERED_CANCELLATIONS;
			}
		}));

	PendingResponses(Supplier<@Nullable Throwable> transportFailure, Function<Throwable, RuntimeException> unavailable,
			String peer) {
		this.transportFailure = transportFailure;
		this.unavailable = unavailable;
		this.peer = peer;
	}

	/**
	 * Records the request as waiting for its response, or fails it at once when the
	 * transport cannot deliver. A sink that is disposed before its response arrives (the
	 * request timed out or was cancelled) is removed, so it does not stay here until the
	 * session ends; one cancelled before any signal is reported to {@code onCancelled}.
	 * @return whether the request was registered and should be sent
	 */
	boolean register(Object requestId, MonoSink<AcpSchema.JSONRPCResponse> sink, Runnable onCancelled) {
		Throwable failure = this.transportFailure.get();
		if (failure != null) {
			sink.error(this.unavailable.apply(failure));
			return false;
		}
		this.pending.put(requestId, sink);
		AtomicBoolean registered = new AtomicBoolean();
		AtomicBoolean cancelledEarly = new AtomicBoolean();
		// A subscriber that cancelled before the request was created runs these callbacks
		// as they are installed, before the flag is set: the request is dropped unsent, and
		// the peer, which never saw it, is not told to cancel it. A cancel after the flag
		// is set is reported. Only a cancellation is: a request that failed (its send
		// threw, say) is disposed too, after its error signal. Reactor runs onCancel before
		// onDispose, so the entry is still here on a cancel.
		sink.onCancel(() -> {
			if (!registered.get()) {
				cancelledEarly.set(true);
			}
			else {
				// Remembered before the removal, so a response racing the cancel finds
				// either the entry or the memory, and is never reported as unknown.
				this.recentlyCancelled.add(requestId);
				if (this.pending.remove(requestId, sink)) {
					onCancelled.run();
				}
				else {
					this.recentlyCancelled.remove(requestId);
				}
			}
		});
		sink.onDispose(() -> this.pending.remove(requestId, sink));
		registered.set(true);
		if (cancelledEarly.get()) {
			return false;
		}
		// Re-check after registering: a failure recorded between the check above and the
		// put may already have dismissed the map without seeing this request.
		Throwable lateFailure = this.transportFailure.get();
		if (lateFailure != null) {
			if (this.pending.remove(requestId, sink)) {
				sink.error(this.unavailable.apply(lateFailure));
			}
			return false;
		}
		return true;
	}

	/** Forgets the request without signalling it: it failed to send. */
	void abandon(Object requestId, MonoSink<AcpSchema.JSONRPCResponse> sink) {
		this.pending.remove(requestId, sink);
	}

	/** Completes the request this response answers. */
	void complete(AcpSchema.JSONRPCResponse response) {
		logger.debug("Received response for id {}", response.id());
		AcpSchema.JSONRPCError error = response.error();
		if (response.id() == null && error != null) {
			// JSON-RPC 2.0: the answer to a message whose id could not be read.
			logger.warn("The {} reported an error for a message it could not read: {} {}", this.peer, error.code(),
					error.message());
			return;
		}
		if (response.id() == null) {
			// A stray response: it names no request, so it answers nothing this side sent.
			logger.warn("Discarded a response from the {} without an id: it answers no request", this.peer);
			return;
		}
		MonoSink<AcpSchema.JSONRPCResponse> sink = this.pending.remove(response.id());
		if (sink == null) {
			if (this.recentlyCancelled.remove(response.id())) {
				logger.debug("Discarded the response to cancelled request {}", response.id());
			}
			else {
				logger.warn("Unexpected response for unknown id {}", response.id());
			}
		}
		else {
			logger.trace("Completing pending response for id {}", response.id());
			sink.success(response);
		}
	}

	/**
	 * Fails every request still waiting for a response with an {@link AcpConnectionException},
	 * whose cause, when there is one, is why the session ended. Each entry is removed before it is
	 * failed: a request registered while this runs is either failed here or stays waiting
	 * for its response, never dropped unsignalled.
	 */
	void dismissAll(@Nullable Throwable cause) {
		this.recentlyCancelled.clear();
		for (Object requestId : this.pending.keySet()) {
			MonoSink<AcpSchema.JSONRPCResponse> sink = this.pending.remove(requestId);
			if (sink != null) {
				logger.warn("Abruptly terminating exchange for request {}", requestId);
				String message = "ACP session with " + this.peer + " terminated";
				sink.error((cause != null) ? new AcpConnectionException(message, cause)
						: new AcpConnectionException(message));
			}
		}
	}

	/** Whether this request still waits for its response. */
	boolean isPending(Object requestId) {
		return this.pending.containsKey(requestId);
	}

	/** The number of requests waiting for a response. */
	int size() {
		return this.pending.size();
	}

}
