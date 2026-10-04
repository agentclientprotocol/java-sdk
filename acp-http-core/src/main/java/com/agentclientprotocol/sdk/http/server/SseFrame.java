/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.nio.charset.StandardCharsets;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;

/**
 * One frame of an SSE stream: an event carrying one JSON-RPC message, or a comment (the
 * stream's opening comment, a keep-alive, or the closing comment of a shutdown). The endpoint
 * creates them; a host writes {@link #encode()} as it is.
 *
 * @param data the event's data, one JSON-RPC message, or the comment's text
 * @param comment whether this frame is a comment, which clients ignore
 * @author Mark Pollack
 */
@UnstableAcpApi
public record SseFrame(String data, boolean comment) {

	/**
	 * Returns an event frame.
	 * @param json the JSON-RPC message, on one line
	 * @return the frame
	 */
	public static SseFrame event(String json) {
		return new SseFrame(json, false);
	}

	/**
	 * Returns a comment frame.
	 * @param text the comment, on one line
	 * @return the frame
	 */
	public static SseFrame comment(String text) {
		return new SseFrame(text, true);
	}

	/**
	 * Returns the frame as written on the wire, in UTF-8: {@code data: <json>} or
	 * {@code : <comment>}, followed by the blank line that ends it.
	 * @return the bytes to write
	 */
	public byte[] encode() {
		return ((comment ? ": " : "data: ") + data + "\n\n").getBytes(StandardCharsets.UTF_8);
	}

}
