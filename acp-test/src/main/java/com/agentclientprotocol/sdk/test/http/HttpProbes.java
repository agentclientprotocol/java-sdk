/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.test.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;

/**
 * Raw requests against an ACP endpoint, answering the HTTP status, for tests of what every host
 * must answer: an {@code initialize} POST and a WebSocket handshake, each with an optional
 * {@code Origin} header as a browser sends it.
 *
 * @author Mark Pollack
 */
public final class HttpProbes {

	/** The status a successful WebSocket handshake answers. */
	public static final int SWITCHING_PROTOCOLS = 101;

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/** A minimal, valid {@code initialize} request. */
	public static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":"probe","method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}""";

	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	private HttpProbes() {
	}

	/**
	 * POSTs an {@code initialize} to the endpoint.
	 * @param endpoint the endpoint, such as {@code http://localhost:8080/acp}
	 * @param origin the {@code Origin} header, or null for none
	 * @return the response
	 * @throws IOException if the request fails
	 * @throws InterruptedException if interrupted
	 */
	public static HttpResponse<String> initialize(URI endpoint, @Nullable String origin)
			throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
			.timeout(TIMEOUT)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(INITIALIZE));
		if (origin != null) {
			request.header("Origin", origin);
		}
		return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * Sends a GET to the endpoint.
	 * @param endpoint the endpoint
	 * @param origin the {@code Origin} header, or null for none
	 * @return the status
	 * @throws IOException if the request fails
	 * @throws InterruptedException if interrupted
	 */
	public static int get(URI endpoint, @Nullable String origin) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
			.timeout(TIMEOUT)
			.header("Accept", "text/event-stream")
			.GET();
		if (origin != null) {
			request.header("Origin", origin);
		}
		return CLIENT.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	/**
	 * Opens a WebSocket to the endpoint and closes it at once.
	 * @param endpoint the endpoint, with an {@code http} or {@code ws} scheme
	 * @param origin the {@code Origin} header, or null for none
	 * @return {@value #SWITCHING_PROTOCOLS} when the handshake succeeded, else the status the
	 * server refused it with
	 * @throws IOException if the connection fails for another reason
	 * @throws InterruptedException if interrupted
	 */
	public static int webSocketHandshake(URI endpoint, @Nullable String origin)
			throws IOException, InterruptedException {
		URI uri = URI.create(endpoint.toString().replaceFirst("^http", "ws"));
		WebSocket.Builder builder = CLIENT.newWebSocketBuilder().connectTimeout(TIMEOUT);
		if (origin != null) {
			builder.header("Origin", origin);
		}
		try {
			WebSocket socket = builder.buildAsync(uri, new WebSocket.Listener() {
			}).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			socket.sendClose(WebSocket.NORMAL_CLOSURE, "probe done").get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			return SWITCHING_PROTOCOLS;
		}
		catch (ExecutionException | CompletionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof WebSocketHandshakeException handshake) {
				return handshake.getResponse().statusCode();
			}
			throw new IOException("WebSocket handshake failed", cause);
		}
		catch (TimeoutException e) {
			throw new IOException("WebSocket handshake timed out", e);
		}
	}

}
