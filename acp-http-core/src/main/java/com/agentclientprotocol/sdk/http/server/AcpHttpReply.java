/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import reactor.core.publisher.Flux;

/**
 * What the endpoint answers one HTTP request with; the host writes it as it is. The status,
 * the headers and the body are the endpoint's: a host adds none of its own protocol headers and
 * changes no status.
 *
 * @author Mark Pollack
 */
@UnstableAcpApi
public sealed interface AcpHttpReply permits AcpHttpReply.Body, AcpHttpReply.Empty, AcpHttpReply.EventStream {

	/** The content type of an SSE response. */
	String EVENT_STREAM = "text/event-stream";

	/**
	 * Returns the response status.
	 * @return the status code
	 */
	int status();

	/**
	 * Returns the response headers the endpoint sets, besides the content type.
	 * @return the headers, by name
	 */
	Map<String, String> headers();

	/**
	 * A response with a body: a JSON-RPC answer or a plain-text refusal.
	 * @param status the status code
	 * @param headers the response headers besides the content type
	 * @param contentType the body's content type, such as {@code application/json}
	 * @param body the body, written as UTF-8
	 */
	record Body(int status, Map<String, String> headers, String contentType, String body) implements AcpHttpReply {

		/**
		 * Creates a reply, copying the headers.
		 */
		public Body {
			headers = Map.copyOf(headers);
		}

		/**
		 * Returns the body as UTF-8 bytes.
		 * @return the bytes
		 */
		public byte[] bodyBytes() {
			return body.getBytes(StandardCharsets.UTF_8);
		}

	}

	/**
	 * A response with a status and no body, such as 202 for an accepted message.
	 * @param status the status code
	 * @param headers the response headers
	 */
	record Empty(int status, Map<String, String> headers) implements AcpHttpReply {

		/**
		 * Creates a reply, copying the headers.
		 */
		public Empty {
			headers = Map.copyOf(headers);
		}

	}

	/**
	 * A 200 response of content type {@value #EVENT_STREAM}: an SSE stream that stays open until
	 * the endpoint completes {@code frames}.
	 *
	 * <p>The host sets the status, the content type and {@code headers}, then subscribes to
	 * {@code frames} and writes each frame's {@link SseFrame#encode() bytes}, flushing each one.
	 * It requests a frame only when it can write it, and the next only once the previous write
	 * has completed; a reactive host may let its framework's backpressure decide instead. When
	 * the client goes away or a write fails, it cancels the subscription; when {@code frames}
	 * completes, it completes the response. It must not let its container time the response out
	 * (for a servlet, {@code AsyncContext.setTimeout(0)}): the endpoint's keep-alive comments
	 * keep proxies from cutting the stream.
	 * @param headers the response headers besides the content type, including
	 * {@code Cache-Control: no-cache} and {@code X-Accel-Buffering: no}
	 * @param frames the frames to write, the first an SSE comment that commits the response
	 */
	record EventStream(Map<String, String> headers, Flux<SseFrame> frames) implements AcpHttpReply {

		/**
		 * Creates a reply, copying the headers.
		 */
		public EventStream {
			headers = Map.copyOf(headers);
		}

		@Override
		public int status() {
			return 200;
		}

	}

}
