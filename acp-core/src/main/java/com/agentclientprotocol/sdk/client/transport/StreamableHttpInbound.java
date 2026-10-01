/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Delivers the messages a Streamable HTTP client receives to the session, in one ordered
 * stream: routes each through {@link StreamableHttpRoutes}, and opens the new session's
 * SSE stream before delivering a {@code session/new} response.
 */
final class StreamableHttpInbound {

	private final StreamableHttpRoutes routes;

	private final StreamableHttpStreams streams;

	private final AcpJsonMapper jsonMapper;

	private final Sinks.Many<JSONRPCMessage> inboundSink = Sinks.many().unicast().onBackpressureBuffer();

	/*
	 * A streamable HTTP client may have one connection SSE reader and multiple session
	 * SSE readers active at the same time. Reactor unicast sinks require serialized
	 * producers, so every SSE reader emits through this monitor.
	 */
	private final Object inboundEmitMonitor = new Object();

	StreamableHttpInbound(StreamableHttpRoutes routes, StreamableHttpStreams streams, AcpJsonMapper jsonMapper) {
		this.routes = routes;
		this.streams = streams;
		this.jsonMapper = jsonMapper;
	}

	/** The messages received, in order; it may be subscribed once. */
	Flux<JSONRPCMessage> messages() {
		return inboundSink.asFlux();
	}

	/** Routes and delivers a message that arrived on the SSE stream of {@code actualScope}. */
	Mono<Void> process(RouteScope actualScope, JSONRPCMessage message) {
		StreamableHttpRoutes.InboundRoute route = routes.routeInbound(actualScope, message);
		Object answeredRequestId = route.answeredRequestId();
		if (answeredRequestId == null) {
			return emit(message);
		}
		Mono<Void> delivered = route.newSessionResponse() && message instanceof AcpSchema.JSONRPCResponse response
				? processNewSessionResponse(response) : emit(message);
		return delivered.doFinally(signal -> routes.answered(answeredRequestId));
	}

	private Mono<Void> processNewSessionResponse(AcpSchema.JSONRPCResponse response) {
		if (response.error() != null) {
			return emit(response);
		}
		Object result = response.result();
		if (result == null) {
			return emit(errorResponse(response.id(), "session/new response carried no result", null));
		}
		String sessionId;
		try {
			AcpSchema.NewSessionResponse sessionResponse = jsonMapper.convertValue(result,
					new TypeRef<AcpSchema.NewSessionResponse>() {
					});
			sessionId = sessionResponse.sessionId();
		}
		catch (Exception e) {
			return emit(errorResponse(response.id(), "Failed to read session/new response", e));
		}
		// Required by the schema, but Jackson does not enforce it: the agent can omit it.
		if (sessionId == null || sessionId.isBlank()) {
			return emit(errorResponse(response.id(), "session/new response missing sessionId", null));
		}
		return streams.openSessionStream(sessionId)
			.then(emit(response))
			.onErrorResume(error -> emit(errorResponse(response.id(),
					"Failed to open session SSE stream for session " + sessionId, error)));
	}

	private static AcpSchema.JSONRPCResponse errorResponse(@Nullable Object id, String message,
			@Nullable Throwable error) {
		Object data = error == null ? null : error.getMessage();
		return new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, id, null,
				new AcpSchema.JSONRPCError(AcpErrorCodes.INTERNAL_ERROR, message, data));
	}

	/** Emits a message into the ordered inbound stream. */
	Mono<Void> emit(JSONRPCMessage message) {
		return Mono.fromRunnable(() -> {
			synchronized (inboundEmitMonitor) {
				Sinks.EmitResult result = inboundSink.tryEmitNext(message);
				if (result.isFailure()) {
					throw new AcpConnectionException("Failed to enqueue inbound message: " + result);
				}
			}
		});
	}

	/** Ends the inbound stream. */
	void complete() {
		inboundSink.tryEmitComplete();
	}

}
