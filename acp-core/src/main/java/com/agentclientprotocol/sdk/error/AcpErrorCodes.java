/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

import java.util.Map;

/**
 * The JSON-RPC error codes ACP defines, as constants: use them to answer a request with an error by
 * throwing an {@link AcpProtocolException} from a handler, and to compare with
 * {@link com.agentclientprotocol.sdk.spec.AcpError#getCode()} when a request you sent failed.
 * {@link #getDescription(int)} gives a code's short name for messages and logs.
 *
 * <p>Every constant is a code from the ACP v1 schema ({@code $defs.ErrorCode}): the JSON-RPC 2.0
 * standard codes, {@code -32800} (request cancelled), and the two codes ACP defines in its reserved
 * range {@code -32000..-32099}: {@code -32000} (authentication required) and {@code -32002}
 * (resource not found). The SDK uses no other code in the reserved range, because a peer reads any
 * code there as the meaning ACP gives it, or will give it. Each constant says when the SDK answers
 * with it by itself; the last two it never sends on its own, they are for handlers.
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
	 * Parse error ({@code -32700}): the message was not valid JSON. The SDK's transports answer a
	 * message they cannot parse with it, with a {@code null} id, and keep reading.
	 */
	public static final int PARSE_ERROR = -32700;

	/**
	 * Invalid request ({@code -32600}): the message is JSON but not a valid JSON-RPC request, or
	 * the request is not valid in the current state. The SDK answers with it a JSON message it
	 * cannot read as a JSON-RPC message, and a second {@code session/prompt} on a session whose
	 * prompt turn is still running, with data {@code {"sessionId": ...}}.
	 */
	public static final int INVALID_REQUEST = -32600;

	/**
	 * Method not found ({@code -32601}): the peer has no handler for the method. Both sides answer
	 * with it a request for which no handler is registered, extension methods included.
	 */
	public static final int METHOD_NOT_FOUND = -32601;

	/**
	 * Invalid params ({@code -32602}): the params do not fit the method. Both sides answer with it
	 * a request whose params cannot be read as the handler's params type, or lack a field the ACP
	 * schema requires; handlers use it for params they read but do not accept.
	 */
	public static final int INVALID_PARAMS = -32602;

	/**
	 * Internal error ({@code -32603}): the handler failed. Both sides answer with it a request
	 * whose handler threw an exception other than {@link AcpProtocolException}, or produced no
	 * response. It is also the code of the {@link com.agentclientprotocol.sdk.spec.AcpError} that
	 * fails a request whose response the SDK rejected.
	 */
	public static final int INTERNAL_ERROR = -32603;

	/**
	 * Request cancelled ({@code -32800}): the method's work was stopped, because the caller
	 * cancelled the request ({@code $/cancel_request}) or because of resource constraints or
	 * shutdown. ACP answers an internally cancelled request (an internal timeout, for one) with
	 * this code too. The SDK answers with it a request whose handler it cancelled for a
	 * {@code $/cancel_request}, a handler that failed with a
	 * {@link java.util.concurrent.CancellationException} or an interrupt, and a prompt that ran
	 * past the agent's maximum prompt duration.
	 */
	public static final int REQUEST_CANCELLED = -32800;

	// --------------------------
	// ACP-Specific Errors
	// --------------------------

	/**
	 * Authentication required ({@code -32000}): the agent requires {@code authenticate} before this
	 * request. An agent's handler answers with it; the SDK never does by itself.
	 */
	public static final int AUTHENTICATION_REQUIRED = -32000;

	/**
	 * Resource not found ({@code -32002}): a resource the request names, such as a file or a
	 * session, does not exist. A handler answers with it; the SDK never does by itself.
	 */
	public static final int RESOURCE_NOT_FOUND = -32002;

	private static final Map<Integer, String> DESCRIPTIONS = Map.ofEntries(Map.entry(PARSE_ERROR, "Parse error"),
			Map.entry(INVALID_REQUEST, "Invalid request"), Map.entry(METHOD_NOT_FOUND, "Method not found"),
			Map.entry(INVALID_PARAMS, "Invalid params"), Map.entry(INTERNAL_ERROR, "Internal error"),
			Map.entry(REQUEST_CANCELLED, "Request cancelled"),
			Map.entry(AUTHENTICATION_REQUIRED, "Authentication required"),
			Map.entry(RESOURCE_NOT_FOUND, "Resource not found"));


	/**
	 * Returns the short name of an error code as JSON-RPC and ACP spell it, such as
	 * {@code "Method not found"} for {@code -32601}.
	 * @param code the error code
	 * @return the code's name, or {@code "Unknown error"} for a code not defined here
	 */
	public static String getDescription(int code) {
		return DESCRIPTIONS.getOrDefault(code, "Unknown error");
	}

}
