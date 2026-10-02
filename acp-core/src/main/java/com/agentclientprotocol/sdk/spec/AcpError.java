/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import org.jspecify.annotations.Nullable;

/**
 * The error response a peer sent to a request: the request's Mono fails with it, on the
 * client side and the agent side alike. Carries the JSON-RPC error's code, message and data.
 *
 * <p>
 * {@link #getMessage()} is the peer's message, followed by the detail its data carries, if
 * any. It leaves out the code, which {@link #getCode()} returns, so
 * {@code e.getCode() + " " + e.getMessage()} names it once; {@link #toString()}, which stack
 * traces show, includes it.
 * </p>
 */
public class AcpError extends RuntimeException {

	private final AcpSchema.JSONRPCError error;

	public AcpError(AcpSchema.JSONRPCError error) {
		super(buildErrorMessage(error, false));
		this.error = error;
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

