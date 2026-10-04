/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

/**
 * Raised by a transport when it cannot carry a message or the connection has ended. A request or
 * notification sent after the transport closed fails with it ({@code "The transport is closed"}),
 * on the asynchronous API as the {@code Mono}'s error and on the sync API as a thrown exception.
 * Catch it around a send to notice a connection that is gone; a closed transport does not open
 * again, so connect a new transport and client or agent.
 *
 * <p>Transports also raise it when a message cannot be queued, when a stdio agent sends a request
 * after the client closed the agent's input, for Streamable HTTP and WebSocket protocol failures,
 * and as the error of {@code awaitTermination()} when the connection ends, for example
 * {@code "ACP agent process exited with code 137 (signal 9)"} from a stdio client.
 *
 * <p>A request still waiting for its answer when the connection ends does not fail with this type:
 * it fails with a {@link RuntimeException} whose message says the session terminated and whose
 * cause is this exception. A request sent after the connection ended fails with an
 * {@link IllegalStateException} whose cause it is. Look at the cause to treat both as connection
 * failures.
 *
 * <p>Example:
 * <pre>{@code
 * try {
 *     client.sendExtNotification("_example.com/file_opened", Map.of("path", path));
 * }
 * catch (AcpConnectionException e) {
 *     // the transport is closed: start a new transport and client to go on
 * }
 * }</pre>
 *
 * @author Mark Pollack
 */
public class AcpConnectionException extends AcpException {

	/**
	 * Creates the exception with this message.
	 * @param message what failed
	 */
	public AcpConnectionException(String message) {
		super(message);
	}

	/**
	 * Creates the exception with this message and cause.
	 * @param message what failed
	 * @param cause the underlying failure, such as an {@code IOException}
	 */
	public AcpConnectionException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Creates the exception for this cause; the message is the cause's description.
	 * @param cause the underlying failure
	 */
	public AcpConnectionException(Throwable cause) {
		super(cause);
	}

}
