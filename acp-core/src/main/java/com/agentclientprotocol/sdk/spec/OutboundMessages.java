/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SynchronousSink;

/**
 * The requests and notifications a session sends to its peer, the same way for both session
 * sides: request ids, the requests still waiting for a response, the request timeout, and
 * failing fast once the transport can no longer deliver.
 */
final class OutboundMessages {

	private static final Logger logger = LoggerFactory.getLogger(OutboundMessages.class);

	private final AcpTransport transport;

	private final Duration requestTimeout;

	/** The reason the transport cannot deliver (it failed to connect or start, or terminated), or null. */
	private final Supplier<@Nullable Throwable> transportFailure;

	/** Wraps the transport failure into the error a caller receives. */
	private final Function<Throwable, RuntimeException> unavailable;

	/** Requests waiting for a response, keyed by request id. */
	private final PendingResponses pendingResponses;

	/** When a response that arrived may complete its caller. */
	private final ResponseOrder responseOrder;

	/** Session-specific prefix for request ids. */
	private final String idPrefix = UUID.randomUUID().toString().substring(0, 8);

	private final AtomicLong requestCounter = new AtomicLong(0);

	OutboundMessages(AcpTransport transport, Duration requestTimeout, Supplier<@Nullable Throwable> transportFailure,
			Function<Throwable, RuntimeException> unavailable, String peer) {
		this(transport, requestTimeout, transportFailure, unavailable, peer, ResponseOrder.IMMEDIATE);
	}

	OutboundMessages(AcpTransport transport, Duration requestTimeout, Supplier<@Nullable Throwable> transportFailure,
			Function<Throwable, RuntimeException> unavailable, String peer, ResponseOrder responseOrder) {
		this.transport = transport;
		this.responseOrder = responseOrder;
		this.requestTimeout = requestTimeout;
		this.transportFailure = transportFailure;
		this.unavailable = unavailable;
		this.pendingResponses = new PendingResponses(transportFailure, unavailable, peer);
	}

	/**
	 * Sends a request and waits, at most the request timeout, for its response. An error
	 * response fails the returned Mono with {@link AcpError}. A response that arrived
	 * completes the Mono when the {@link ResponseOrder} lets it, within the same timeout.
	 */
	<T> Mono<T> sendRequest(String method, Object params, TypeRef<T> typeRef) {
		return sendRequest(method, params, typeRef, this.requestTimeout);
	}

	/**
	 * Sends a request and waits, at most {@code timeout}, for its response; with a null
	 * {@code timeout} it waits until the response arrives, the caller disposes the request, or
	 * the transport fails. Otherwise as {@link #sendRequest(String, Object, TypeRef)}.
	 */
	<T> Mono<T> sendRequest(String method, Object params, TypeRef<T> typeRef, @Nullable Duration timeout) {
		Mono<AcpSchema.JSONRPCResponse> response = Mono.deferContextual(ctx -> {
			// One id per subscription: a resubscribed request (a retry) is a new request.
			String requestId = this.idPrefix + "-" + this.requestCounter.getAndIncrement();
			long sentAt = this.responseOrder.position();
			Runnable answered = this.responseOrder.awaiting(sentAt);
			// Completes once the request is handed to the transport; a $/cancel_request
			// waits for it, so it never overtakes its request on an ordered transport.
			Sinks.Empty<Void> written = Sinks.empty();
			// At most one $/cancel_request per request, whether the caller disposed it or a
			// RequestCancellation trigger fired.
			AtomicBoolean cancelSent = new AtomicBoolean();
			Runnable cancel = () -> {
				if (cancelSent.compareAndSet(false, true)) {
					cancelRequest(requestId, written.asMono());
				}
			};
			Disposable.Swap gracefulCancel = Disposables.swap();
			return Mono.<AcpSchema.JSONRPCResponse>create(responseSink -> {
				if (!this.pendingResponses.register(requestId, responseSink, cancel)) {
					return;
				}
				logger.debug("Sending request for method {} with id {}", method, requestId);
				logger.trace("Outgoing request method='{}' id={} params={}", method, requestId, params);
				AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, requestId,
						method, params);
				this.transport.sendMessage(request).contextWrite(ctx).subscribe(v -> {
				}, error -> {
					written.tryEmitError(error);
					this.pendingResponses.abandon(requestId, responseSink);
					responseSink.error(error);
				}, written::tryEmitEmpty);
				Object trigger = ctx.getOrDefault(RequestCancellation.KEY, null);
				if (trigger instanceof Publisher<?> publisher) {
					gracefulCancel.update(Flux.from(publisher).take(1).then().subscribe(v -> {
					}, error -> logger.debug("Ignored a failed cancel trigger for request {}", requestId), () -> {
						if (this.pendingResponses.isPending(requestId)) {
							cancel.run();
						}
					}));
				}
			}).doFinally(signal -> {
				gracefulCancel.dispose();
				answered.run();
			})
				.flatMap(answer -> this.responseOrder.after(sentAt, answer));
		});
		if (timeout != null) {
			response = AcpSchedulers.withTimeout(response, timeout);
		}
		return response.handle((answer, resultSink) -> deliver(method, answer, typeRef, resultSink));
	}

	private <T> void deliver(String method, AcpSchema.JSONRPCResponse response, TypeRef<T> typeRef,
			SynchronousSink<T> resultSink) {
		AcpSchema.JSONRPCError error = response.error();
		if (error != null) {
			// The caller's failure is the report; the error's data is the peer's payload.
			logger.debug("Request {} failed with peer error {}", method, error.code());
			resultSink.error(new AcpError(error));
		}
		else if (typeRef.getType().equals(Void.class)) {
			resultSink.complete();
		}
		else {
			ResponseResults.deliver(method, response.result(), typeRef, this.transport, resultSink);
		}
	}

	/**
	 * Tells the peer that the caller gave up on a request (ACP v1 {@code $/cancel_request}):
	 * its subscription was cancelled, by the caller or by its timeout, before a response.
	 * Sent once the request itself has been handed to the transport, and not at all if that
	 * failed. Fire and forget: the transport may be why the caller gave up.
	 */
	private void cancelRequest(String requestId, Mono<Void> written) {
		written.then(sendNotification(AcpSchema.METHOD_CANCEL_REQUEST, new AcpSchema.CancelRequestNotification(requestId)))
			// Off the thread that cancelled, which may be the shared timeout timer: the
			// transport's emission can wait for a busy outbound sink.
			.subscribeOn(AcpSchedulers.timeoutDelivery())
			.subscribe(v -> {
			}, error -> logger.debug("No $/cancel_request sent for id {}: {}", requestId, error.getMessage()),
					() -> logger.debug("Sent $/cancel_request for id {}", requestId));
	}

	/** Sends a notification, or fails at once when the transport cannot deliver. */
	Mono<Void> sendNotification(String method, @Nullable Object params) {
		return Mono.defer(() -> {
			Throwable failure = this.transportFailure.get();
			if (failure != null) {
				return Mono.error(this.unavailable.apply(failure));
			}
			return this.transport
				.sendMessage(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION, method, params));
		});
	}

	/** Completes the request this response answers. */
	void complete(AcpSchema.JSONRPCResponse response) {
		this.pendingResponses.complete(response);
	}

	/** Fails every request still waiting for a response. */
	void dismissPending(@Nullable Throwable cause) {
		this.pendingResponses.dismissAll(cause);
	}

	/** The number of requests waiting for a response. */
	int pendingRequests() {
		return this.pendingResponses.size();
	}

}
