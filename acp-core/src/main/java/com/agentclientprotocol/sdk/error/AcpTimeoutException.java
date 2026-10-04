/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

import java.util.concurrent.TimeoutException;

/**
 * Thrown by a blocking call of the sync API ({@code AcpSyncClient}, {@code AcpSyncAgent},
 * {@code SyncPromptContext}) when the peer did not answer in time: the request timeout, the
 * client's prompt timeout or the sync agent's block timeout passed. Catch it to handle a peer that
 * is slow or gone; the peer may still have done the work. Its cause is the
 * {@link TimeoutException}. By then the SDK has given up on the request and, if it was sent, told
 * the peer with a {@code $/cancel_request}, which makes a Java peer cancel its handler. The
 * asynchronous API fails its {@code Mono} with the {@link TimeoutException} itself.
 *
 * <p>The sync API throws the other failures of a call as they are: a peer's error as
 * {@link com.agentclientprotocol.sdk.spec.AcpError}, a missing capability as
 * {@link AcpCapabilityException}, and an interrupt of the waiting thread as a
 * {@link java.util.concurrent.CancellationException}, with the thread's interrupt flag set again.
 *
 * @author Mark Pollack
 */
public class AcpTimeoutException extends AcpException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception for a timeout; the message starts {@code "No answer in time"} and names
	 * the timeout.
	 * @param cause the timeout
	 */
	public AcpTimeoutException(TimeoutException cause) {
		super("No answer in time: " + cause.getMessage(), cause);
	}

}
