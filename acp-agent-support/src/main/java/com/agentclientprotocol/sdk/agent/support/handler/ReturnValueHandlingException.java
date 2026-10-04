/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

/**
 * Thrown when what a handler method returned cannot be turned into its response: no return value
 * handler accepts the type, or the value does not suit the method, such as a {@code String}
 * returned outside a prompt turn. The call fails, and the client receives an internal error
 * ({@code -32603}) with the message "Internal error"; this exception is logged at the agent. Building
 * the agent rejects most unsuitable return types up front, so at run time it usually comes from a
 * custom {@link ReturnValueHandler}, which throws it for the same purpose.
 *
 * @author Mark Pollack
 */
public class ReturnValueHandlingException extends RuntimeException {

	/**
	 * Creates the exception.
	 * @param message what could not be handled, sent to the client
	 */
	public ReturnValueHandlingException(String message) {
		super(message);
	}

	/**
	 * Creates the exception with the failure that caused it.
	 * @param message what could not be handled, sent to the client
	 * @param cause the underlying failure
	 */
	public ReturnValueHandlingException(String message, Throwable cause) {
		super(message, cause);
	}

}
