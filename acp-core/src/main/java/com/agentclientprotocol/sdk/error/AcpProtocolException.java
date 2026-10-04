/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

import org.jspecify.annotations.Nullable;


/**
 * A JSON-RPC error a handler throws to answer the peer's request with that error: its code,
 * message and optional data become the error response. Any other exception from a handler is
 * answered {@code -32603} (internal error).
 *
 * <p>
 * The caller of a request does not receive this type: a request that failed with an error code,
 * whether the peer answered with an error or the SDK rejected the peer's response, fails with
 * {@link com.agentclientprotocol.sdk.spec.AcpError}.
 * </p>
 *
 * <p>
 * Common error codes are defined in {@link AcpErrorCodes}:
 * <ul>
 * <li>{@link AcpErrorCodes#METHOD_NOT_FOUND} (-32601): Method not available</li>
 * <li>{@link AcpErrorCodes#INVALID_PARAMS} (-32602): Invalid parameters</li>
 * <li>{@link AcpErrorCodes#INTERNAL_ERROR} (-32603): Internal error</li>
 * <li>{@link AcpErrorCodes#INVALID_REQUEST} (-32600): Invalid request, such as a prompt while
 * the session already has an active prompt</li>
 * <li>{@link AcpErrorCodes#AUTHENTICATION_REQUIRED} (-32000): Authentication required</li>
 * </ul>
 *
 * <p>
 * Example usage, in an agent's authenticate handler:
 * <pre>{@code
 * if (!credentials.valid()) {
 *     throw new AcpProtocolException(AcpErrorCodes.AUTHENTICATION_REQUIRED, "Log in first");
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @see AcpErrorCodes
 */
public class AcpProtocolException extends AcpException {

	private final int code;

	private final @Nullable Object data;

	private final String errorMessage;

	/**
	 * Constructs a new protocol exception with the specified code and message.
	 * @param code the JSON-RPC error code
	 * @param message the error message
	 */
	public AcpProtocolException(int code, String message) {
		this(code, message, null);
	}

	/**
	 * Constructs a new protocol exception with the specified code, message, and data.
	 * @param code the JSON-RPC error code
	 * @param message the error message
	 * @param data optional additional error data
	 */
	public AcpProtocolException(int code, String message, @Nullable Object data) {
		super(formatMessage(code, message));
		this.code = code;
		this.data = data;
		this.errorMessage = message;
	}

	/**
	 * Returns the JSON-RPC error code.
	 * @return the error code
	 * @see AcpErrorCodes
	 */
	public int getCode() {
		return code;
	}

	/**
	 * Returns the optional additional error data.
	 * @return the error data, or null if none was provided
	 */
	public @Nullable Object getData() {
		return data;
	}

	/**
	 * Returns the error message without the code: the JSON-RPC error {@code message}.
	 * {@link #getMessage()} prefixes the code, for logs.
	 * @return the plain error message
	 */
	public String getErrorMessage() {
		return errorMessage;
	}

	/**
	 * Returns true if this error indicates the method was not found.
	 * @return true if method not found error
	 */
	public boolean isMethodNotFound() {
		return code == AcpErrorCodes.METHOD_NOT_FOUND;
	}

	/**
	 * Returns true if this error says authentication is required first.
	 * @return true if authentication required error
	 */
	public boolean isAuthenticationRequired() {
		return code == AcpErrorCodes.AUTHENTICATION_REQUIRED;
	}

	/**
	 * Returns true if this error indicates invalid parameters.
	 * @return true if invalid params error
	 */
	public boolean isInvalidParams() {
		return code == AcpErrorCodes.INVALID_PARAMS;
	}

	/**
	 * Returns true if this error indicates an internal error.
	 * @return true if internal error
	 */
	public boolean isInternalError() {
		return code == AcpErrorCodes.INTERNAL_ERROR;
	}

	private static String formatMessage(int code, String message) {
		return "[" + code + "] " + message;
	}

}
