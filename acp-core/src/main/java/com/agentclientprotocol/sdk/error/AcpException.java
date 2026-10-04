/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

/**
 * The base class of the SDK's own exceptions: catch it to handle, in one place, every failure the
 * SDK raises about capabilities, the connection or timeouts. It does not cover
 * {@link com.agentclientprotocol.sdk.spec.AcpError}, the failure of a request the peer answered
 * with an error, which extends {@link RuntimeException} directly; catch both where every failure of
 * a request matters.
 *
 * <p>Subclasses:
 * <ul>
 * <li>{@link AcpProtocolException}: what a handler throws to answer a request with a JSON-RPC
 * error</li>
 * <li>{@link AcpCapabilityException}: a call needs a capability the peer did not advertise</li>
 * <li>{@link AcpConnectionException}: the transport could not send a message, or the connection
 * ended</li>
 * <li>{@link AcpTimeoutException}: a blocking call of the sync API got no answer in time</li>
 * </ul>
 *
 * <p>The sync API also throws a plain {@code AcpException} when a call fails with a checked
 * exception, which it carries as the cause.
 *
 * <p>Every instance has a message (see {@link #getMessage()}), since a JSON-RPC error built from it
 * needs one.
 *
 * @author Mark Pollack
 * @see AcpProtocolException
 * @see AcpCapabilityException
 * @see AcpConnectionException
 * @see AcpTimeoutException
 */
public class AcpException extends RuntimeException {

	/**
	 * Creates an exception with this message.
	 * @param message the detail message
	 */
	public AcpException(String message) {
		super(message);
	}

	/**
	 * Creates an exception with this message and cause.
	 * @param message the detail message
	 * @param cause the cause of this exception
	 */
	public AcpException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Creates an exception for this cause; the message is the cause's description.
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
