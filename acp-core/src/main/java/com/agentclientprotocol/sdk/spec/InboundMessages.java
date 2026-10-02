/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.concurrent.CancellationException;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.util.HandlerFailures;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * How a session answers the requests and notifications its peer sends, the same way for
 * both session sides.
 */
final class InboundMessages {

	private static final Logger LOGGER = LoggerFactory.getLogger(InboundMessages.class);

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
	 * Passes a notification to the handler registered for its method; a handler that fails
	 * (params it cannot read, say) is logged and skipped. One without a handler is ignored
	 * with a warning that names its method only: its params can carry personal
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
		// A notification has no answer to carry a failure: it is logged (method and error
		// only) and the session reads on. A failure must not end the inbound stream. An Error
		// (HandlerFailures) is logged at ERROR with its stack trace.
		return HandlerFailures.invoke(() -> handle.apply(handler, paramsOrEmpty(notification.params())))
			.onErrorResume(error -> {
				Throwable failure = HandlerFailures.contain(error);
				if (failure instanceof HandlerFailures.HandlerError) {
					LOGGER.error("Notification {} handler failed with an error and was skipped",
							notification.method(), failure.getCause());
				}
				else {
					logger.warn("Notification {} failed and was skipped: {}", notification.method(), error.toString());
				}
				return Mono.empty();
			});
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
	 * its code and data, a cancellation ({@link #isCancellation}) is the handler cancelling
	 * its own work (ACP v1 internal cancellation: the same {@code -32800} as a cancel from the
	 * caller), anything else is an internal error. A handler that failed with an
	 * {@link Error} ({@link HandlerFailures}) is answered with an internal error that names
	 * the method and the error's type only, never its message or stack trace, and the error
	 * is logged at ERROR with its stack trace.
	 */
	static AcpSchema.JSONRPCResponse error(AcpSchema.JSONRPCRequest request, Throwable error) {
		if (HandlerFailures.contain(error) instanceof HandlerFailures.HandlerError handlerError) {
			Throwable cause = (handlerError.getCause() != null) ? handlerError.getCause() : handlerError;
			LOGGER.error("The {} handler failed with {}; answered {} (Internal error)", request.method(),
					cause.getClass().getName(), AcpErrorCodes.INTERNAL_ERROR, cause);
			return error(request, AcpErrorCodes.INTERNAL_ERROR,
					"Internal error in the " + request.method() + " handler (" + cause.getClass().getName() + ")",
					null);
		}
		if (error instanceof AcpProtocolException protocolException) {
			return new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), null,
					AcpSchema.JSONRPCError.from(protocolException));
		}
		if (isCancellation(error)) {
			String message = error.getMessage();
			return error(request, AcpErrorCodes.REQUEST_CANCELLED,
					(message != null) ? message : InboundRequests.CANCELLED_MESSAGE, null);
		}
		return error(request, AcpErrorCodes.INTERNAL_ERROR, errorMessage(error), null);
	}

	/**
	 * Whether a handler's failure means its work was cancelled: a
	 * {@link CancellationException} or an interrupt (Reactor's {@code block()} wraps it), or
	 * an {@link AcpProtocolException} with code -32800.
	 */
	static boolean isCancellation(Throwable error) {
		Throwable unwrapped = Exceptions.unwrap(error);
		return unwrapped instanceof CancellationException || unwrapped instanceof InterruptedException
				|| (error instanceof AcpProtocolException protocolException
						&& protocolException.getCode() == AcpErrorCodes.REQUEST_CANCELLED);
	}

	/** JSON-RPC requires an error message; an exception without one is named by its type. */
	private static String errorMessage(Throwable error) {
		String message = error.getMessage();
		return (message != null) ? message : error.getClass().getName();
	}

}
