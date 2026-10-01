/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

import java.util.Map;

/**
 * The JSON-RPC 2.0 error codes ACP defines.
 *
 * <p>
 * Every constant is a code from the ACP v1 schema ({@code $defs.ErrorCode}): the JSON-RPC
 * 2.0 standard codes, {@code -32800} (request cancelled), and the two codes ACP defines in
 * its reserved range {@code -32000..-32099}: {@code -32000} (authentication required) and
 * {@code -32002} (resource not found). The SDK uses no other code in the reserved range,
 * because a peer reads any code there as the meaning ACP gives it, or will give it.
 * </p>
 *
 * @author Mark Pollack
 * @see <a href="https://www.jsonrpc.org/specification#error_object">JSON-RPC Error Object</a>
 */
public final class AcpErrorCodes {

	private AcpErrorCodes() {
		// Utility class - no instantiation
	}

	// --------------------------
	// Standard JSON-RPC Errors
	// --------------------------

	/**
	 * Parse error: Invalid JSON was received by the server. An error occurred on the
	 * server while parsing the JSON text.
	 */
	public static final int PARSE_ERROR = -32700;

	/**
	 * Invalid Request: The JSON sent is not a valid Request object.
	 */
	public static final int INVALID_REQUEST = -32600;

	/**
	 * Method not found: The method does not exist or is not available.
	 */
	public static final int METHOD_NOT_FOUND = -32601;

	/**
	 * Invalid params: Invalid method parameter(s).
	 */
	public static final int INVALID_PARAMS = -32602;

	/**
	 * Internal error: Internal JSON-RPC error.
	 */
	public static final int INTERNAL_ERROR = -32603;

	/**
	 * Request cancelled: execution of the method was aborted, because the caller cancelled
	 * the request or because of resource constraints or shutdown. ACP answers an internally
	 * cancelled request (an internal timeout, for one) with this code too.
	 */
	public static final int REQUEST_CANCELLED = -32800;

	// --------------------------
	// ACP-Specific Errors
	// --------------------------

	/**
	 * Authentication required: authentication is required before this operation can be
	 * performed.
	 */
	public static final int AUTHENTICATION_REQUIRED = -32000;

	/**
	 * Resource not found: a given resource, such as a file or a session, was not found.
	 */
	public static final int RESOURCE_NOT_FOUND = -32002;

	private static final Map<Integer, String> DESCRIPTIONS = Map.ofEntries(Map.entry(PARSE_ERROR, "Parse error"),
			Map.entry(INVALID_REQUEST, "Invalid request"), Map.entry(METHOD_NOT_FOUND, "Method not found"),
			Map.entry(INVALID_PARAMS, "Invalid params"), Map.entry(INTERNAL_ERROR, "Internal error"),
			Map.entry(REQUEST_CANCELLED, "Request cancelled"),
			Map.entry(AUTHENTICATION_REQUIRED, "Authentication required"),
			Map.entry(RESOURCE_NOT_FOUND, "Resource not found"));


	/**
	 * Returns a human-readable description for the given error code.
	 * @param code the error code
	 * @return a description of the error code
	 */
	public static String getDescription(int code) {
		return DESCRIPTIONS.getOrDefault(code, "Unknown error");
	}

}
