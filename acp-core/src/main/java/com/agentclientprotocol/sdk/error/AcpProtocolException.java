/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

import org.jspecify.annotations.Nullable;


/**
 * The exception a request handler throws to answer the request with a JSON-RPC error: the peer
 * receives its code, message and data as the error response. Throw it, or fail the handler's
 * {@code Mono} with it, for a failure the caller should see, with a code from
 * {@link AcpErrorCodes}; this works the same in agent and client handlers, builder lambdas and
 * annotated methods. Its message and data go to the peer as they are, so keep secrets out of them.
 * An {@code AcpError} that escapes a handler passes on the error it carries, and a
 * {@link java.util.concurrent.CancellationException} is answered {@code -32800} (request
 * cancelled) with its own message, as ACP asks for a request cancelled internally. Any other
 * exception from a handler is answered {@code -32603} (internal error) with the generic message
 * "Internal error"; that exception is logged on the handling side and not sent.
 *
 * <p>The caller of the request never receives this type. On the caller's side the error response
 * fails the request with {@link com.agentclientprotocol.sdk.spec.AcpError}, which carries the same
 * code, message and data. A handler that calls the peer and lets an {@code AcpError} it received
 * escape passes that error on unchanged; it need not convert it.
 *
 * <p>Codes a handler commonly answers with:
 * <ul>
 * <li>{@link AcpErrorCodes#INVALID_PARAMS} ({@code -32602}): the params are readable but not
 * acceptable, such as an unknown config option value</li>
 * <li>{@link AcpErrorCodes#RESOURCE_NOT_FOUND} ({@code -32002}): a session, file or other resource
 * the request names does not exist</li>
 * <li>{@link AcpErrorCodes#AUTHENTICATION_REQUIRED} ({@code -32000}): the client must call
 * {@code authenticate} first</li>
 * <li>{@link AcpErrorCodes#INVALID_REQUEST} ({@code -32600}): the request is not valid in the
 * current state</li>
 * <li>{@link AcpErrorCodes#REQUEST_CANCELLED} ({@code -32800}): the handler stopped because its
 * work was cancelled</li>
 * </ul>
 *
 * <p>Thrown from a notification handler, it is logged and dropped: a notification gets no answer.
 * {@link #getMessage()} puts the code in front of the message, for logs; {@link #getErrorMessage()}
 * is the message the peer receives.
 *
 * <p>Example, in a handler that looks a session up:
 * <pre>{@code
 * if (!sessions.containsKey(request.sessionId())) {
 *     throw new AcpProtocolException(AcpErrorCodes.RESOURCE_NOT_FOUND,
 *             "Unknown session: " + request.sessionId());
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @see AcpErrorCodes
 * @see com.agentclientprotocol.sdk.spec.AcpError
 */
public class AcpProtocolException extends AcpException {

	/** The JSON-RPC error code. */
	private final int code;

	/** The error data, or null for none. */
	private final @Nullable Object data;

	/** The error message without the code. */
	private final String errorMessage;

	/**
	 * Creates the exception for an error response with this code and message and no data.
	 * @param code the JSON-RPC error code, usually one of {@link AcpErrorCodes}
	 * @param message the error message the peer receives
	 */
	public AcpProtocolException(int code, String message) {
		this(code, message, null);
	}

	/**
	 * Creates the exception for an error response with this code, message and data.
	 * @param code the JSON-RPC error code, usually one of {@link AcpErrorCodes}
	 * @param message the error message the peer receives
	 * @param data more detail for the peer, any value the JSON mapper can write, or {@code null}
	 * for none
	 */
	public AcpProtocolException(int code, String message, @Nullable Object data) {
		super(formatMessage(code, message));
		this.code = code;
		this.data = data;
		this.errorMessage = message;
	}

	/**
	 * Returns the JSON-RPC error code the peer receives.
	 * @return the error code
	 * @see AcpErrorCodes
	 */
	public int getCode() {
		return code;
	}

	/**
	 * Returns the data the peer receives with the error.
	 * @return the data, or {@code null} if there is none
	 */
	public @Nullable Object getData() {
		return data;
	}

	/**
	 * Returns the error message the peer receives, without the code. {@link #getMessage()} puts the
	 * code in front of it, as {@code [-32602] message}, for logs.
	 * @return the error message
	 */
	public String getErrorMessage() {
		return errorMessage;
	}

	/**
	 * Returns whether the code is {@code -32601} ("Method not found").
	 * @return true for {@link AcpErrorCodes#METHOD_NOT_FOUND}
	 */
	public boolean isMethodNotFound() {
		return code == AcpErrorCodes.METHOD_NOT_FOUND;
	}

	/**
	 * Returns whether the code is {@code -32000} (authentication required).
	 * @return true for {@link AcpErrorCodes#AUTHENTICATION_REQUIRED}
	 */
	public boolean isAuthenticationRequired() {
		return code == AcpErrorCodes.AUTHENTICATION_REQUIRED;
	}

	/**
	 * Returns whether the code is {@code -32602} ("Invalid params").
	 * @return true for {@link AcpErrorCodes#INVALID_PARAMS}
	 */
	public boolean isInvalidParams() {
		return code == AcpErrorCodes.INVALID_PARAMS;
	}

	/**
	 * Returns whether the code is {@code -32603} ("Internal error").
	 * @return true for {@link AcpErrorCodes#INTERNAL_ERROR}
	 */
	public boolean isInternalError() {
		return code == AcpErrorCodes.INTERNAL_ERROR;
	}

	private static String formatMessage(int code, String message) {
		return "[" + code + "] " + message;
	}

}
