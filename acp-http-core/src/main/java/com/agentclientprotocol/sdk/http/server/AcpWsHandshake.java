/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.time.Duration;
import java.util.Map;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;

/**
 * The endpoint's decision on a WebSocket upgrade request, from
 * {@link AcpHttpEndpoint#webSocketHandshake}: either refuse it with an HTTP reply, or accept it.
 *
 * @author Mark Pollack
 */
@UnstableAcpApi
public sealed interface AcpWsHandshake permits AcpWsHandshake.Refused, AcpWsHandshake.Accepted {

	/**
	 * The upgrade is refused: the host writes {@code reply} instead of upgrading, as for 403
	 * (a foreign {@code Origin}) or 503 (the endpoint is shutting down).
	 * @param reply the HTTP reply to write
	 */
	record Refused(AcpHttpReply reply) implements AcpWsHandshake {
	}

	/**
	 * The upgrade is accepted. The host completes it with the response headers of
	 * {@link #headers()}, applies the limits below to the socket, and once the socket is open
	 * calls {@link #open} exactly once. If the upgrade fails in the host after this decision,
	 * it calls nothing: no connection exists until {@link #open}.
	 */
	non-sealed interface Accepted extends AcpWsHandshake {

		/**
		 * Returns the headers of the upgrade response, such as {@code Acp-Connection-Id}.
		 * @return the headers, by name
		 */
		Map<String, String> headers();

		/**
		 * Returns the largest text message the socket accepts, for the container's own limit;
		 * a larger one closes the socket with 1009.
		 * @return the limit in bytes
		 */
		long maxTextMessageBytes();

		/**
		 * Returns the idle timeout for a container that has one of its own: five seconds past
		 * the endpoint's {@code webSocketIdleTimeout}. The endpoint closes an idle connection
		 * itself (1001) on every host; the container's timeout only backs it up.
		 * @return the container's idle timeout
		 */
		Duration idleTimeout();

		/**
		 * Starts the connection on the open socket: creates its agent and returns the handler
		 * the host gives every socket event to. Frames may arrive at once; the endpoint keeps
		 * them until the agent has started.
		 * @param outbound the host's side of the socket
		 * @return the handler for the socket's events
		 */
		AcpWsHandler open(AcpWsOutbound outbound);

	}

}
