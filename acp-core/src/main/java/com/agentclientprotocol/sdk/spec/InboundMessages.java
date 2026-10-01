/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.Map;
import java.util.function.BiFunction;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;

/**
 * How a session answers the requests and notifications its peer sends, the same way for
 * both session sides.
 */
final class InboundMessages {

	private InboundMessages() {
	}

	/**
	 * The params a handler receives. JSON-RPC lets a request or notification omit them;
	 * an omitted params reads as an empty object, which is what a peer sending {@code {}}
	 * would deliver, so handlers never see null.
	 */
	static Object paramsOrEmpty(@Nullable Object params) {
		return (params != null) ? params : Map.of();
	}

	/**
	 * Passes a notification to the handler registered for its method. One without a handler
	 * is ignored with a warning that names its method only: its params can carry personal
	 * data (agents send {@code _auth/status_update}, whose params carry the account's email
	 * address).
	 */
	static <H> Mono<Void> deliver(Logger logger, AcpSchema.JSONRPCNotification notification,
			Map<String, H> handlers, BiFunction<H, Object, Mono<Void>> handle) {
		H handler = handlers.get(notification.method());
		if (handler == null) {
			logger.warn("No handler registered for notification method: {}", notification.method());
			return Mono.empty();
		}
		return handle.apply(handler, paramsOrEmpty(notification.params()));
	}

	/** A request always gets a response: a handler that completes empty is answered with an error. */
	static <T> Mono<T> requireResult(Mono<T> result, String method) {
		return result.switchIfEmpty(Mono.error(() -> new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR,
				"The " + method + " handler produced no response")));
	}

	static AcpSchema.JSONRPCResponse result(AcpSchema.JSONRPCRequest request, Object result) {
		return new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), result, null);
	}

	static AcpSchema.JSONRPCResponse error(AcpSchema.JSONRPCRequest request, int code, String message,
			@Nullable Object data) {
		return new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), null,
				new AcpSchema.JSONRPCError(code, message, data));
	}

	/**
	 * The response to a request whose handling failed: an {@link AcpProtocolException} keeps
	 * its code and data, anything else is an internal error.
	 */
	static AcpSchema.JSONRPCResponse error(AcpSchema.JSONRPCRequest request, Throwable error) {
		if (error instanceof AcpProtocolException protocolException) {
			return error(request, protocolException.getCode(), errorMessage(error), protocolException.getData());
		}
		return error(request, AcpErrorCodes.INTERNAL_ERROR, errorMessage(error), null);
	}

	/** JSON-RPC requires an error message; an exception without one is named by its type. */
	private static String errorMessage(Throwable error) {
		String message = error.getMessage();
		return (message != null) ? message : error.getClass().getName();
	}

}
