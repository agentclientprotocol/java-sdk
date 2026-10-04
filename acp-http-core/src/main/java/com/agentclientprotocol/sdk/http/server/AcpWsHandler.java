/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import org.jspecify.annotations.Nullable;

/**
 * The endpoint's side of one accepted WebSocket: the host passes it every event of the socket,
 * from its I/O or container thread. No method blocks; the agent's work runs on the SDK's own
 * schedulers.
 *
 * @author Mark Pollack
 */
@UnstableAcpApi
public interface AcpWsHandler {

	/**
	 * A text frame arrived, whole. The endpoint checks its size against the inbound limit
	 * too, so a host whose container already enforces that limit loses nothing.
	 * @param text the frame
	 */
	void onText(String text);

	/**
	 * The socket closed, by the client, by the container or after
	 * {@link AcpWsOutbound#close}.
	 * @param code the close code, or 1006 when the connection dropped without one
	 * @param reason the close reason, if any
	 */
	void onClose(int code, @Nullable String reason);

	/**
	 * The socket failed, such as on a corrupt frame. The endpoint closes the connection; the
	 * host still reports the close that follows through {@link #onClose}.
	 * @param error the error
	 */
	void onError(Throwable error);

}
