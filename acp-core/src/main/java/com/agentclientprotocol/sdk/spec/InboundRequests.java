/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The requests a session received from its peer and has not answered yet, keyed by request
 * id, so that a {@code $/cancel_request} can cancel one (ACP v1, Cancellation), the same way
 * for both session sides.
 *
 * <p>
 * A cancelled request still gets exactly one response: the handler's, when it came first,
 * otherwise the error {@code -32800} (Request cancelled). Both race inside one {@code Mono}
 * ({@link Mono#takeUntilOther}), which signals at most once, so there is never a second
 * response for the id. The handler's subscription is cancelled, which also cancels the
 * requests it was waiting on (their sessions send {@code $/cancel_request} in turn).
 * </p>
 */
final class InboundRequests {

	private static final Logger logger = LoggerFactory.getLogger(InboundRequests.class);

	static final String CANCELLED_MESSAGE = "Request cancelled";

	/** The cancel trigger of each request being handled, keyed by its normalised id. */
	private final ConcurrentHashMap<Object, Cancel> inFlight = new ConcurrentHashMap<>();

	/** One request's cancel: the trigger, and whether a cancel has been asked for. */
	private static final class Cancel {

		final Sinks.Empty<Void> trigger = Sinks.empty();

		volatile boolean requested;

		boolean fire() {
			this.requested = true;
			return this.trigger.tryEmitEmpty().isSuccess();
		}

	}

	/** Set once the session closes: requests still arriving are cancelled at once. */
	private volatile boolean closed;

	/**
	 * Makes {@code response}, the answer to {@code request}, cancellable by
	 * {@link #cancel}, which answers {@code -32800}. See
	 * {@link #track(AcpSchema.JSONRPCRequest, Mono, Supplier)}.
	 */
	Mono<AcpSchema.JSONRPCResponse> track(AcpSchema.JSONRPCRequest request, Mono<AcpSchema.JSONRPCResponse> response) {
		return track(request, response, () -> cancelled(request));
	}

	/**
	 * Makes {@code response}, the answer to {@code request}, cancellable by
	 * {@link #cancel}. Call it on the complete answer: whatever {@code response} emits is the
	 * request's only response, and when a cancel wins the race, {@code whenCancelled}
	 * supplies the response instead. A request without an id cannot be cancelled and is
	 * passed through.
	 *
	 * <p>
	 * The request becomes cancellable only once {@code response} is subscribed, so a cancel
	 * always cancels a running handler (whose own cancellation, a prompt's turn included,
	 * runs before the answer is produced), and stops being cancellable as soon as its
	 * response is produced.
	 * </p>
	 */
	Mono<AcpSchema.JSONRPCResponse> track(AcpSchema.JSONRPCRequest request, Mono<AcpSchema.JSONRPCResponse> response,
			Supplier<AcpSchema.JSONRPCResponse> whenCancelled) {
		Object key = key(request.id());
		if (key == null) {
			return response;
		}
		return Mono.defer(() -> {
			Cancel cancel = new Cancel();
			// takeUntilOther subscribes the trigger before the response, and passes
			// onSubscribe down only once the response is subscribed: registering here means
			// a cancel can only reach a handler that is running. A handler that fails once
			// it is cancelled (an interrupted blocking handler throws) was cancelled too.
			return response.onErrorResume(error -> cancel.requested, error -> Mono.empty())
				.takeUntilOther(cancel.trigger.asMono())
				.doOnSubscribe(subscription -> register(key, cancel, request))
				.switchIfEmpty(Mono.fromSupplier(whenCancelled))
				.doOnNext(answer -> this.inFlight.remove(key, cancel))
				.doFinally(signal -> this.inFlight.remove(key, cancel));
		});
	}

	private void register(Object key, Cancel cancel, AcpSchema.JSONRPCRequest request) {
		if (this.closed) {
			cancel.fire();
			return;
		}
		if (this.inFlight.putIfAbsent(key, cancel) != null) {
			// The peer reused the id of a request still being handled: a cancel names the
			// first one.
			logger.warn("Request id {} is already in use by an unanswered request", request.id());
		}
		else if (this.closed) {
			// Closed while registering: cancelAll may not have seen this request.
			cancel.fire();
		}
	}

	/**
	 * Cancels the request with this id, if it is still being handled.
	 * @return whether a request was cancelled; false when the id is unknown or already
	 * answered
	 */
	boolean cancel(@Nullable Object requestId) {
		Object key = key(requestId);
		Cancel cancel = (key != null) ? this.inFlight.get(key) : null;
		if (cancel == null || !cancel.fire()) {
			logger.debug("Ignored $/cancel_request for id {}: no request with that id is being handled", requestId);
			return false;
		}
		logger.debug("Cancelled request {}", requestId);
		return true;
	}

	/**
	 * Cancels every request being handled, and every request that arrives after: the session
	 * is closing (ACP v1 internal cancellation). Each is answered as if the peer had
	 * cancelled it, so far as the transport still delivers.
	 */
	void cancelAll() {
		this.closed = true;
		for (Object key : this.inFlight.keySet()) {
			cancel(key);
		}
	}

	/**
	 * Cancels the request a {@code $/cancel_request} notification names. The params are read
	 * here rather than through {@link AcpTransport#unmarshalParams}: a notification has no
	 * response to carry a -32602, so params without a usable {@code requestId} (missing, null,
	 * or not a string or integer, as the schema's {@code RequestId} requires) are logged and
	 * ignored.
	 */
	boolean cancel(AcpSchema.JSONRPCNotification notification) {
		Object requestId = requestId(notification.params());
		if (!isRequestId(requestId)) {
			logger.debug("Ignored $/cancel_request with invalid params {}", notification.params());
			return false;
		}
		return cancel(requestId);
	}

	/** The number of requests being handled. */
	int size() {
		return this.inFlight.size();
	}

	static AcpSchema.JSONRPCResponse cancelled(AcpSchema.JSONRPCRequest request) {
		return InboundMessages.error(request, AcpErrorCodes.REQUEST_CANCELLED, CANCELLED_MESSAGE, null);
	}

	/**
	 * The id a {@code $/cancel_request} names. Its params arrive as a map from the JSON
	 * transports and as the typed record from in-process ones.
	 */
	private static @Nullable Object requestId(@Nullable Object params) {
		if (params instanceof AcpSchema.CancelRequestNotification notification) {
			return notification.requestId();
		}
		if (params instanceof Map<?, ?> map) {
			return map.get("requestId");
		}
		return null;
	}

	/** A JSON-RPC id the schema allows and a request can carry: a string or an integer. */
	private static boolean isRequestId(@Nullable Object id) {
		return id instanceof String || id instanceof Integer || id instanceof Long || id instanceof Short
				|| id instanceof Byte || (id instanceof BigInteger big && big.bitLength() < Long.SIZE);
	}

	/**
	 * The table key of a JSON-RPC id. An integer id may be read as an Integer in one message
	 * and a Long in another, so integer types compare by value; a string id never equals a
	 * number, and a fractional or out-of-range number is kept as it is. A null id cannot be
	 * correlated.
	 */
	static @Nullable Object key(@Nullable Object id) {
		if (id instanceof Integer || id instanceof Long || id instanceof Short || id instanceof Byte) {
			return ((Number) id).longValue();
		}
		if (id instanceof BigInteger big && big.bitLength() < Long.SIZE) {
			return big.longValue();
		}
		return id;
	}

}
