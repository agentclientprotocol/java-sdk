/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import org.jspecify.annotations.Nullable;

/**
 * The failure a caller catches when a request it sent did not succeed: the peer answered with a
 * JSON-RPC error, or answered with a result the SDK rejected. It fails the request's {@code Mono}
 * on the asynchronous API and is thrown as it is by the sync API, on the client side and the agent
 * side alike, so one {@code catch (AcpError e)} covers both cases. Branch on {@link #getCode()}
 * (see {@link AcpErrorCodes}). To answer a request with an error from a handler, throw
 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} instead.
 *
 * <p>The two cases, which the data tells apart:
 * <ul>
 * <li>The peer answered with an error. {@link #getCode()}, the message and {@link #getData()} are
 * the peer's. A Java peer answers {@code -32601} ("Method not found") when it has no handler for
 * the method, {@code -32602} ("Invalid params") when it cannot read the params, {@code -32800} when
 * the request was cancelled, and {@code -32603} when its handler failed; a handler can answer any
 * code it chooses.</li>
 * <li>The peer answered with a success response the SDK rejected: the result lacks a field the ACP
 * schema requires, there is no result, or it cannot be read as the method's result type. The code
 * is {@code -32603} (internal error; JSON-RPC 2.0 defines none for an invalid response), the
 * message names the method and the problem, and {@link #getData()} is a map whose {@code "reason"}
 * is {@code "missing-required-field"} (with the field's path under {@code "field"}),
 * {@code "missing-result"} or {@code "unreadable-result"}, and whose {@code "method"} names the
 * request's method. A prompt context's {@code askChoice} answered with an option it did not offer
 * fails the same way, with reason {@code "unoffered-option"} and the {@code "optionId"}. Nothing is
 * sent back to the peer.</li>
 * </ul>
 *
 * <p>Other failures of a request have their own types:
 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException AcpCapabilityException} for a
 * call the peer did not advertise, refused before sending;
 * {@link com.agentclientprotocol.sdk.error.AcpConnectionException AcpConnectionException} for a
 * request the transport could not send, such as one sent after the transport closed; and a
 * {@link java.util.concurrent.TimeoutException} for a request that got no answer in time, which the
 * sync API throws as
 * {@link com.agentclientprotocol.sdk.error.AcpTimeoutException AcpTimeoutException}. This class
 * does not extend {@link com.agentclientprotocol.sdk.error.AcpException AcpException}.
 *
 * <p>A handler that lets an {@code AcpError} escape answers its own request with the error this
 * exception carries, unchanged: the peer's code, message and data, or for a rejected response the
 * SDK's {@code -32603} and data. So a handler that calls the other side and has nothing to add
 * just lets the failure propagate. To answer with a different error, catch it and throw an
 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} instead.
 *
 * <p>{@link #getMessage()} is the peer's message, followed by the detail its data carries, if any;
 * for a rejected response it is the SDK's message alone. It leaves out the code, which
 * {@link #getCode()} returns, so {@code e.getCode() + " " + e.getMessage()} names it once;
 * {@link #toString()}, which stack traces show, includes it.
 *
 * <p>Example, a client that falls back when the agent does not know an extension method:
 * <pre>{@code
 * Object buffers;
 * try {
 *     buffers = client.sendExtRequest("_example.com/workspace/buffers", Map.of());
 * }
 * catch (AcpError e) {
 *     if (e.getCode() != AcpErrorCodes.METHOD_NOT_FOUND) {
 *         throw e;
 *     }
 *     buffers = Map.of();
 * }
 * }</pre>
 */
public class AcpError extends RuntimeException {

	/** The error the peer sent, or the SDK built for a rejected response. */
	private final AcpSchema.JSONRPCError error;

	/**
	 * Creates the failure for an error response from the peer. The SDK creates it when a request is
	 * answered with an error; an application needs it only to fake a failed request, in a test for
	 * example.
	 * @param error the error as the peer sent it
	 */
	public AcpError(AcpSchema.JSONRPCError error) {
		super(buildErrorMessage(error, false));
		this.error = error;
	}

	private AcpError(AcpSchema.JSONRPCError error, String message) {
		super(message);
		this.error = error;
	}

	/**
	 * Returns the failure for a success response the SDK rejected: code {@code -32603}, the given
	 * message as it is, and data naming the reason and the method, followed by the detail (see the
	 * class description). The SDK calls it from more than one of its packages, which is why it is
	 * public.
	 * @param method the method of the request whose response was rejected
	 * @param reason why, for example {@code "missing-required-field"}
	 * @param message the message, naming the method and the problem
	 * @param detail more data entries, for example the missing field's path; empty for none
	 * @return the failure
	 */
	public static AcpError rejectedResponse(String method, String reason, String message, Map<String, Object> detail) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("reason", reason);
		data.put("method", method);
		data.putAll(detail);
		return new AcpError(new AcpSchema.JSONRPCError(AcpErrorCodes.INTERNAL_ERROR, message,
				Collections.unmodifiableMap(data)), message);
	}

	/**
	 * Returns the class name, the message, the code and the data's detail, as stack traces show
	 * them, for example
	 * {@code com.agentclientprotocol.sdk.spec.AcpError: No such session [code=-32002]}. Unlike
	 * {@link #getMessage()}, it includes the code.
	 * @return the description
	 */
	// getMessage() leaves the code out on purpose (getCode() returns it); a stack trace and a
	// log of the exception itself still name it.
	@SuppressWarnings("OverrideThrowableToString")
	@Override
	public String toString() {
		return getClass().getName() + ": " + buildErrorMessage(this.error, true);
	}

	private static String buildErrorMessage(AcpSchema.JSONRPCError error, boolean withCode) {
		StringBuilder sb = new StringBuilder();
		sb.append(error.message());
		if (withCode) {
			sb.append(" [code=").append(error.code()).append("]");
		}
		if (error.data() != null) {
			sb.append(": ").append(formatErrorData(error.data()));
		}
		return sb.toString();
	}

	private static String formatErrorData(Object data) {
		if (data instanceof java.util.Map<?, ?> map) {
			// Extract common fields for better readability
			Object details = map.get("details");
			if (details != null) {
				return details.toString();
			}
			Object reason = map.get("reason");
			if (reason != null) {
				return reason.toString();
			}
		}
		return data.toString();
	}

	/**
	 * Returns the JSON-RPC error itself: its code, message and data, as the peer sent them or as
	 * the SDK built them for a rejected response.
	 * @return the error
	 */
	public AcpSchema.JSONRPCError getError() {
		return error;
	}

	/**
	 * Returns the error code, such as {@link AcpErrorCodes#METHOD_NOT_FOUND}.
	 * @return the code
	 */
	public int getCode() {
		return error.code();
	}

	/**
	 * Returns the error's data: the detail the peer added, any JSON value (a {@code Map} for an
	 * object), or for a rejected response the map the class description lists.
	 * @return the data, or {@code null} if there is none
	 */
	public @Nullable Object getData() {
		return error.data();
	}

}

