/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * One open Server-Sent Events stream of a Streamable HTTP client: reads its events on a
 * thread of its own, turns each into a JSON-RPC message, and hands it to the
 * {@link Listener}. Reconnecting is the listener's business; the stream only reports that
 * it ended without being closed.
 */
final class SseStream {

	private static final Logger logger = LoggerFactory.getLogger(SseStream.class);

	/** Receives what a stream reads, and its unexpected end. */
	interface Listener {

		/** Delivers a message read from the stream of {@code scope}. */
		Mono<Void> onMessage(RouteScope scope, JSONRPCMessage message);

		/** The stream ended, or failed, without having been closed. */
		void onUnexpectedClose(SseStream stream, Throwable error);

		/** Whether the transport is closing, which makes the end of a stream expected. */
		boolean transportClosing();

	}

	private final RouteScope scope;

	private final InputStream body;

	private final AcpJsonMapper jsonMapper;

	private final Listener listener;

	private final AtomicBoolean closed = new AtomicBoolean(false);

	private volatile @Nullable Future<?> readerTask;

	/** Whether this stream carried at least one event; resets the reconnect budget. */
	private volatile boolean delivered;

	/** Reconnects in a row, ending with this stream, that delivered nothing. */
	private volatile int barrenReconnects;

	SseStream(RouteScope scope, InputStream body, AcpJsonMapper jsonMapper, Listener listener) {
		this.scope = scope;
		this.body = body;
		this.jsonMapper = jsonMapper;
		this.listener = listener;
	}

	RouteScope scope() {
		return scope;
	}

	boolean isClosed() {
		return closed.get();
	}

	boolean delivered() {
		return delivered;
	}

	int barrenReconnects() {
		return barrenReconnects;
	}

	void barrenReconnects(int barrenReconnects) {
		this.barrenReconnects = barrenReconnects;
	}

	/**
	 * Starts reading on {@code executor}, whose threads bound the number of open streams.
	 * @throws AcpConnectionException when every thread already reads a stream; the stream
	 * is closed
	 */
	void start(ExecutorService executor, int maxStreams) {
		try {
			this.readerTask = executor.submit(this::readLoop);
		}
		catch (RejectedExecutionException e) {
			close();
			throw new AcpConnectionException("Maximum active SSE streams exceeded: " + maxStreams, e);
		}
	}

	void close() {
		if (closed.compareAndSet(false, true)) {
			try {
				body.close();
			}
			catch (IOException ignored) {
				// Best effort: the stream is abandoned either way, and cancelling the
				// reader below stops the read loop.
			}
			Future<?> task = this.readerTask;
			if (task != null) {
				task.cancel(true);
			}
		}
	}

	private void readLoop() {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
			readEvents(reader);
			if (!closed.get() && !listener.transportClosing()) {
				throw new AcpConnectionException("SSE stream closed unexpectedly: " + scope);
			}
		}
		catch (Exception e) {
			if (!closed.get()) {
				listener.onUnexpectedClose(this, e);
			}
		}
	}

	/**
	 * Reads events until the stream ends or is closed. An event is the {@code data:} lines
	 * up to a blank line; comment lines ({@code :}) and other fields are ignored.
	 */
	private void readEvents(BufferedReader reader) throws IOException {
		StringBuilder dataBuffer = new StringBuilder();
		String line;
		while (!closed.get() && (line = reader.readLine()) != null) {
			if (line.isEmpty()) {
				dispatchEvent(dataBuffer);
				dataBuffer.setLength(0);
			}
			else if (line.startsWith("data:")) {
				if (!dataBuffer.isEmpty()) {
					dataBuffer.append('\n');
				}
				dataBuffer.append(line.substring(5).stripLeading());
			}
		}
		dispatchEvent(dataBuffer);
	}

	private void dispatchEvent(StringBuilder dataBuffer) {
		if (dataBuffer.isEmpty()) {
			return;
		}
		try {
			JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, dataBuffer.toString());
			delivered = true;
			listener.onMessage(scope, message).subscribe(v -> {
			}, error -> {
				if (isLive()) {
					logger.warn("Failed to process SSE event from {}", scope, error);
				}
			});
		}
		catch (Exception e) {
			if (isLive()) {
				logger.warn("Failed to deserialize SSE event from {}", scope, e);
			}
		}
	}

	private boolean isLive() {
		return !closed.get() && !listener.transportClosing();
	}

}
