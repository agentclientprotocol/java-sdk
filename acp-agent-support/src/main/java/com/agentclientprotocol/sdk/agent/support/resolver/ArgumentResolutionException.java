/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

/**
 * Thrown when a handler-method parameter cannot be supplied for a call: no resolver supports it, or
 * the resolver lacks what it needs, such as a session id in a method without a session. The call
 * fails, and the client receives an internal error ({@code -32603}) whose message is this
 * exception's message, so keep secrets out of it. Building the agent rejects most such parameters
 * up front, so at run time it usually comes from a custom {@link ArgumentResolver}. To answer with
 * another error code, a resolver throws an {@code AcpProtocolException} instead.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class ArgumentResolutionException extends RuntimeException {

	/**
	 * Creates the exception.
	 * @param message what could not be supplied, sent to the client
	 */
	public ArgumentResolutionException(String message) {
		super(message);
	}

	/**
	 * Creates the exception with the failure that caused it.
	 * @param message what could not be supplied, sent to the client
	 * @param cause the underlying failure
	 */
	public ArgumentResolutionException(String message, Throwable cause) {
		super(message, cause);
	}

}
