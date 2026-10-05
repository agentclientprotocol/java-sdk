/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.util.concurrent.CompletionStage;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;

/**
 * The host's side of one accepted WebSocket: the endpoint sends frames and closes the socket
 * through it. Both methods are non-blocking.
 *
 * <p>The endpoint sends one frame at a time: it calls {@link #sendText} again only once the
 * stage of the previous call has completed, so a host needs no queue of its own and a container
 * that allows one outstanding write (Jakarta WebSocket's {@code RemoteEndpoint.Async}, Tomcat)
 * is never given a second one. The bound on queued frames, and the close that follows a failed
 * or overflowing send, are the endpoint's.
 *
 * @author Mark Pollack
 */
@UnstableAcpApi
public interface AcpWsOutbound {

	/**
	 * Sends one text frame.
	 * @param text the frame, one JSON-RPC message
	 * @return a stage that completes when the container has written the frame, or completes
	 * exceptionally when the write failed
	 */
	CompletionStage<Void> sendText(String text);

	/**
	 * Closes the socket with a close frame. The endpoint chooses the code: 1000 normal, 1001
	 * going away (shutdown), 1002 protocol error, 1009 message too big, 1011 server error.
	 * <p>A graceful shutdown waits for the returned stage, bounded by the shutdown timeout,
	 * before it reports the endpoint closed: a container may send the close frame
	 * asynchronously, and one stopped before the frame has gone out drops the connection
	 * without it (the client sees 1006).
	 * @param code the close code
	 * @param reason the close reason, short
	 * @return a stage that completes once the close frame has been sent, or the socket has
	 * closed; it may complete exceptionally when the close failed
	 */
	CompletionStage<Void> close(int code, String reason);

}
