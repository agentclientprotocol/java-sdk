/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

/**
 * Raised when a message cannot be carried because the connection is gone, on the client and the
 * agent side alike: a request still waiting for its answer when the connection ends fails with it
 * ({@code "ACP session with agent terminated"}), and so does a request or notification sent
 * afterwards ({@code "ACP client transport is not connected: ..."}) or after the transport closed
 * ({@code "The transport is closed"}). It fails the {@code Mono} on the asynchronous API and is
 * thrown by the sync API. Its cause, when there is one, is why the connection ended, such as the
 * transport's own failure. Catch it around a call to notice a connection that is gone; a closed
 * transport does not open again, so connect a new transport and client or agent.
 *
 * <p>Transports also raise it when a message cannot be queued, when a stdio agent sends a request
 * after the client closed the agent's input, for Streamable HTTP and WebSocket protocol failures,
 * and as the error of {@code awaitTermination()} when the connection ends, for example
 * {@code "ACP agent process exited with code 137 (signal 9)"} from a stdio client.
 *
 * <p>Building a client or agent on a transport that refuses to connect or start at once, such as
 * one already in use, fails with an {@link IllegalStateException} instead: that is a misuse, not a
 * lost connection.
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
