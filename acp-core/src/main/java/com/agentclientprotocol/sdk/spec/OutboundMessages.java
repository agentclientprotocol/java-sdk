/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
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

	/** Names the peer in the error a dismissed request fails with ("agent", "client"). */
	private final String peer;

	/** Requests waiting for a response, keyed by request id. */
	private final ConcurrentHashMap<Object, MonoSink<AcpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<>();

	/** Session-specific prefix for request ids. */
	private final String idPrefix = UUID.randomUUID().toString().substring(0, 8);

	private final AtomicLong requestCounter = new AtomicLong(0);

	OutboundMessages(AcpTransport transport, Duration requestTimeout, Supplier<@Nullable Throwable> transportFailure,
			Function<Throwable, RuntimeException> unavailable, String peer) {
		this.transport = transport;
		this.requestTimeout = requestTimeout;
		this.transportFailure = transportFailure;
		this.unavailable = unavailable;
		this.peer = peer;
	}

	/**
	 * Sends a request and waits, at most the request timeout, for its response. An error
	 * response fails the returned Mono with {@link AcpError}.
	 */
	<T> Mono<T> sendRequest(String method, Object params, TypeRef<T> typeRef) {
		String requestId = this.idPrefix + "-" + this.requestCounter.getAndIncrement();
		return Mono.deferContextual(ctx -> Mono.<AcpSchema.JSONRPCResponse>create(responseSink -> {
			if (!register(requestId, responseSink)) {
				return;
			}
			// A request that times out or is cancelled stops waiting: without this its entry
			// stayed in the map until the session closed, one per timed-out request.
			responseSink.onDispose(() -> this.pendingResponses.remove(requestId, responseSink));
			logger.debug("Sending request for method {} with id {}", method, requestId);
			logger.trace("Outgoing request method='{}' id={} params={}", method, requestId, params);
			AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, requestId,
					method, params);
			this.transport.sendMessage(request).contextWrite(ctx).subscribe(v -> {
			}, error -> {
				this.pendingResponses.remove(requestId);
				responseSink.error(error);
			});
		}))
			.transform(response -> AcpSchedulers.withTimeout(response, this.requestTimeout))
			.handle((response, resultSink) -> deliver(method, response, typeRef, resultSink));
	}

	/**
	 * Records the request as waiting for its response, or fails it at once when the
	 * transport cannot deliver.
	 * @return whether the request was registered and should be sent
	 */
	private boolean register(String requestId, MonoSink<AcpSchema.JSONRPCResponse> responseSink) {
		Throwable failure = this.transportFailure.get();
		if (failure != null) {
			responseSink.error(this.unavailable.apply(failure));
			return false;
		}
		this.pendingResponses.put(requestId, responseSink);
		// Re-check after registering: a failure recorded between the check above and the
		// put may already have dismissed the map without seeing this request.
		Throwable lateFailure = this.transportFailure.get();
		if (lateFailure != null) {
			this.pendingResponses.remove(requestId);
			responseSink.error(this.unavailable.apply(lateFailure));
			return false;
		}
		return true;
	}

	private <T> void deliver(String method, AcpSchema.JSONRPCResponse response, TypeRef<T> typeRef,
			SynchronousSink<T> resultSink) {
		AcpSchema.JSONRPCError error = response.error();
		if (error != null) {
			logger.error("Error handling request: {}", error);
			resultSink.error(new AcpError(error));
		}
		else if (typeRef.getType().equals(Void.class)) {
			resultSink.complete();
		}
		else {
			ResponseResults.deliver(method, response.result(), typeRef, this.transport, resultSink);
		}
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
		logger.debug("Received response for id {}", response.id());
		if (response.id() == null) {
			logger.error("Discarded ACP request response without session id. "
					+ "This is an indication of a bug in the request sender code that can lead to memory "
					+ "leaks as pending requests will never be completed.");
			return;
		}
		MonoSink<AcpSchema.JSONRPCResponse> sink = this.pendingResponses.remove(response.id());
		if (sink == null) {
			logger.warn("Unexpected response for unknown id {}", response.id());
		}
		else {
			logger.trace("Completing pending response for id {}", response.id());
			sink.success(response);
		}
	}

	/** Fails every request still waiting for a response. */
	void dismissPending(@Nullable Throwable cause) {
		this.pendingResponses.forEach((id, sink) -> {
			logger.warn("Abruptly terminating exchange for request {}", id);
			sink.error(new RuntimeException("ACP session with " + this.peer + " terminated", cause));
		});
		this.pendingResponses.clear();
	}

	/** The number of requests waiting for a response. */
	int pendingRequests() {
		return this.pendingResponses.size();
	}

}
