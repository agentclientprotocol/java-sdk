/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import org.jspecify.annotations.Nullable;

/**
 * The error response a peer sent to a request: the request's Mono fails with it, on the
 * client side and the agent side alike. Carries the JSON-RPC error's code, message and data.
 */
public class AcpError extends RuntimeException {

	private final AcpSchema.JSONRPCError error;

	public AcpError(AcpSchema.JSONRPCError error) {
		super(buildErrorMessage(error));
		this.error = error;
	}

	private static String buildErrorMessage(AcpSchema.JSONRPCError error) {
		StringBuilder sb = new StringBuilder();
		sb.append(error.message());
		sb.append(" [code=").append(error.code()).append("]");
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

