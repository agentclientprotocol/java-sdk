/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

import java.util.Map;

/**
 * Standard JSON-RPC 2.0 error codes used by ACP.
 *
 * <p>
 * The error codes follow the JSON-RPC 2.0 specification:
 * <ul>
 * <li>-32700 to -32600: Parse/transport errors</li>
 * <li>-32603 to -32600: Request errors</li>
 * <li>-32099 to -32000: Server/implementation errors (reserved for ACP)</li>
 * </ul>
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
	 * Concurrent prompt: There is already an active prompt execution on this session.
	 * ACP enforces single-turn semantics - only one prompt can be active at a time.
	 */
	public static final int CONCURRENT_PROMPT = -32000;

	/**
	 * Capability not supported: The peer does not support the requested capability.
	 */
	public static final int CAPABILITY_NOT_SUPPORTED = -32001;

	/**
	 * Session not found: The specified session ID does not exist.
	 */
	public static final int SESSION_NOT_FOUND = -32002;

	/**
	 * Not initialized: A method was called before the connection was initialized.
	 */
	public static final int NOT_INITIALIZED = -32003;

	/**
	 * Authentication required: The operation requires authentication.
	 */
	public static final int AUTHENTICATION_REQUIRED = -32004;

	/**
	 * Permission denied: The user denied permission for the requested operation.
	 */
	public static final int PERMISSION_DENIED = -32005;

	private static final Map<Integer, String> DESCRIPTIONS = Map.ofEntries(Map.entry(PARSE_ERROR, "Parse error"),
			Map.entry(INVALID_REQUEST, "Invalid request"), Map.entry(METHOD_NOT_FOUND, "Method not found"),
			Map.entry(INVALID_PARAMS, "Invalid params"), Map.entry(INTERNAL_ERROR, "Internal error"),
			Map.entry(REQUEST_CANCELLED, "Request cancelled"),
			Map.entry(CONCURRENT_PROMPT, "Concurrent prompt"),
			Map.entry(CAPABILITY_NOT_SUPPORTED, "Capability not supported"),
			Map.entry(SESSION_NOT_FOUND, "Session not found"), Map.entry(NOT_INITIALIZED, "Not initialized"),
			Map.entry(AUTHENTICATION_REQUIRED, "Authentication required"),
			Map.entry(PERMISSION_DENIED, "Permission denied"));

	/**
	 * Returns a human-readable description for the given error code.
	 * @param code the error code
	 * @return a description of the error code
	 */
	public static String getDescription(int code) {
		return DESCRIPTIONS.getOrDefault(code, "Unknown error");
	}

}
