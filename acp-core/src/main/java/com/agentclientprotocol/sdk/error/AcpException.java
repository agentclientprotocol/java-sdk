/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

/**
 * Base exception for all ACP-related errors.
 *
 * <p>
 * This is the root exception class for the ACP SDK. All ACP-specific exceptions extend
 * this class, enabling catch blocks to handle all ACP errors uniformly when desired.
 * </p>
 *
 * <p>
 * Exception hierarchy:
 * <ul>
 * <li>{@link AcpException} - Base class for all ACP errors</li>
 * <li>{@link AcpProtocolException} - JSON-RPC protocol errors with error codes</li>
 * <li>{@link AcpCapabilityException} - Capability negotiation errors</li>
 * <li>{@link AcpConnectionException} - Transport/connection errors</li>
 * </ul>
 *
 * @author Mark Pollack
 * @see AcpProtocolException
 * @see AcpCapabilityException
 * @see AcpConnectionException
 */
public class AcpException extends RuntimeException {

	/**
	 * Constructs a new ACP exception with the specified detail message.
	 * @param message the detail message
	 */
	public AcpException(String message) {
		super(message);
	}

	/**
	 * Constructs a new ACP exception with the specified detail message and cause.
	 * @param message the detail message
	 * @param cause the cause of this exception
	 */
	public AcpException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Constructs a new ACP exception with the specified cause.
	 * @param cause the cause of this exception
	 */
	public AcpException(Throwable cause) {
		super(cause);
	}

	/**
	 * Returns the detail message. Every constructor sets one (the cause-only constructor
	 * uses the cause's description), so an ACP exception always has a message: a
	 * JSON-RPC error built from it needs one.
	 * @return the detail message, never {@code null}
	 */
	@Override
	public String getMessage() {
		String message = super.getMessage();
		return (message != null) ? message : getClass().getName();
	}

}
