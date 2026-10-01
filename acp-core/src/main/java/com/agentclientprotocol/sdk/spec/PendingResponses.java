/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

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
	 * session ends.
	 * @return whether the request was registered and should be sent
	 */
	boolean register(Object requestId, MonoSink<AcpSchema.JSONRPCResponse> sink) {
		Throwable failure = this.transportFailure.get();
		if (failure != null) {
			sink.error(this.unavailable.apply(failure));
			return false;
		}
		this.pending.put(requestId, sink);
		sink.onDispose(() -> abandon(requestId, sink));
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

	/** Forgets the request without signalling it: it failed to send, timed out or was cancelled. */
	void abandon(Object requestId, MonoSink<AcpSchema.JSONRPCResponse> sink) {
		this.pending.remove(requestId, sink);
	}

	/** Completes the request this response answers. */
	void complete(AcpSchema.JSONRPCResponse response) {
		logger.debug("Received response for id {}", response.id());
		if (response.id() == null) {
			logger.error("Discarded ACP request response without session id. "
					+ "This is an indication of a bug in the request sender code that can lead to memory "
					+ "leaks as pending requests will never be completed.");
			return;
		}
		MonoSink<AcpSchema.JSONRPCResponse> sink = this.pending.remove(response.id());
		if (sink == null) {
			logger.warn("Unexpected response for unknown id {}", response.id());
		}
		else {
			logger.trace("Completing pending response for id {}", response.id());
			sink.success(response);
		}
	}

	/**
	 * Fails every request still waiting for a response. Each entry is removed before it is
	 * failed: a request registered while this runs is either failed here or stays waiting
	 * for its response, never dropped unsignalled.
	 */
	void dismissAll(@Nullable Throwable cause) {
		for (Object requestId : this.pending.keySet()) {
			MonoSink<AcpSchema.JSONRPCResponse> sink = this.pending.remove(requestId);
			if (sink != null) {
				logger.warn("Abruptly terminating exchange for request {}", requestId);
				sink.error(new RuntimeException("ACP session with " + this.peer + " terminated", cause));
			}
		}
	}

	/** The number of requests waiting for a response. */
	int size() {
		return this.pending.size();
	}

}
