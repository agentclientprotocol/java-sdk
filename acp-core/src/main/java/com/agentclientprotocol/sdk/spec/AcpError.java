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
 * A request that failed with a JSON-RPC error code: the request's Mono fails with it, on the
 * client side and the agent side alike, so one {@code catch (AcpError e)} covers both of its
 * causes:
 * <ul>
 * <li>the peer answered with an error response; {@link #getCode()}, the message and
 * {@link #getData()} are the peer's;</li>
 * <li>the peer answered with a success response the SDK rejected: the result lacks a field the
 * ACP schema requires, there is no result, or it cannot be read as the method's result type.
 * The code is {@code -32603} (internal error; JSON-RPC 2.0 defines none for an invalid
 * response), the message names the method and the problem, and {@link #getData()} is a map
 * whose {@code "reason"} is {@code "missing-required-field"} (with the field's path under
 * {@code "field"}), {@code "missing-result"} or {@code "unreadable-result"}, and whose
 * {@code "method"} names the request's method. A prompt context's {@code askChoice} answered
 * with an option it did not offer fails the same way, with reason {@code "unoffered-option"} and
 * the {@code "optionId"}.</li>
 * </ul>
 * Use the data to tell the two apart. A request that timed out, or that a capability check
 * refused before sending it, fails with its own documented type instead. To answer a request
 * with an error from a handler, throw
 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException}.
 *
 * <p>
 * {@link #getMessage()} is the peer's message, followed by the detail its data carries, if
 * any; for a rejected response it is the SDK's message alone. It leaves out the code, which
 * {@link #getCode()} returns, so {@code e.getCode() + " " + e.getMessage()} names it once;
 * {@link #toString()}, which stack traces show, includes it.
 * </p>
 */
public class AcpError extends RuntimeException {

	private final AcpSchema.JSONRPCError error;

	public AcpError(AcpSchema.JSONRPCError error) {
		super(buildErrorMessage(error, false));
		this.error = error;
	}

	private AcpError(AcpSchema.JSONRPCError error, String message) {
		super(message);
		this.error = error;
	}

	/**
	 * Returns the error for a success response the SDK rejected locally: code {@code -32603},
	 * the given message as it is, and data naming the reason and the method, followed by the
	 * detail (see the class description). The SDK uses it; it is public for its own packages.
	 * @param method the method of the request whose response was rejected
	 * @param reason why, for example {@code "missing-required-field"}
	 * @param message the message, naming the method and the problem
	 * @param detail more data entries, for example the missing field's path
	 * @return the error
	 */
	public static AcpError rejectedResponse(String method, String reason, String message, Map<String, Object> detail) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("reason", reason);
		data.put("method", method);
		data.putAll(detail);
		return new AcpError(new AcpSchema.JSONRPCError(AcpErrorCodes.INTERNAL_ERROR, message,
				Collections.unmodifiableMap(data)), message);
	}

	/** The class, the peer's message, the code and the data's detail, as stack traces show it. */
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

	public AcpSchema.JSONRPCError getError() {
		return error;
	}

	public int getCode() {
		return error.code();
	}

	public @Nullable Object getData() {
		return error.data();
	}

}

