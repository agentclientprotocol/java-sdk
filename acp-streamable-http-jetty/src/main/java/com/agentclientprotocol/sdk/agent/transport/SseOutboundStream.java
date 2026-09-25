/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One outbound SSE stream of the Streamable HTTP transport (the connection stream or
 * one session stream), with at most one attached {@link SseSubscriber} writing to a
 * servlet async response.
 *
 * <p>
 * <b>A mailbox, as in the Rust and TypeScript servers.</b> Every event goes into one
 * bounded queue owned by the stream. An attached subscriber removes an event only at the
 * moment it writes it to the response, so an event is never owned by a subscriber: when a
 * subscriber goes away (the client dropped, the server closed it for backpressure, a new
 * GET took the stream over, or the container reported an error on another thread) nothing
 * it had not written is lost or reordered, and the next subscriber continues from the
 * same queue. All state changes happen under this stream's monitor, including the
 * container's asynchronous callbacks, so a reconnect racing the old subscriber's close is
 * serialised. Bytes already written to a connection that then died can still be lost;
 * only event ids and {@code Last-Event-ID}, which the RFD defers, could close that.
 * </p>
 *
 * @author Kaiser Dandangi
 */
final class SseOutboundStream {

	private static final Logger logger = LoggerFactory.getLogger(SseOutboundStream.class);

	private static final byte[] SSE_KEEP_ALIVE_COMMENT = ": keep-alive\n\n".getBytes(StandardCharsets.UTF_8);

	// Commits the SSE response when there are no queued events, without emitting an ACP message.
	private static final byte[] SSE_OPEN_COMMENT = ": connected\n\n".getBytes(StandardCharsets.UTF_8);

	private final int mailboxCapacity;

	private final int maxPendingSseEvents;

	/** Events not yet written to any subscriber, oldest first. Guarded by {@code this}. */
	private final ArrayDeque<String> mailbox = new ArrayDeque<>();

	/** Guarded by {@code this}. */
	private SseSubscriber current;

	/** Guarded by {@code this}. */
	private boolean closed;

	SseOutboundStream(int mailboxCapacity, int maxPendingSseEvents) {
		this.mailboxCapacity = mailboxCapacity;
		this.maxPendingSseEvents = maxPendingSseEvents;
	}

	/**
	 * Queues an event and writes as much as the attached subscriber can take.
	 * @throws AcpConnectionException when the mailbox is full; the caller closes the
	 * connection rather than drop an event
	 */
	synchronized void push(String payload) {
		if (closed) {
			return;
		}
		if (mailbox.size() == mailboxCapacity) {
			throw new AcpConnectionException("Outbound SSE replay buffer exceeded " + mailboxCapacity + " events");
		}
		mailbox.addLast(payload);
		if (current == null) {
			return;
		}
		if (mailbox.size() > maxPendingSseEvents) {
			// The attached client is not reading. Detach it; the events stay queued for the
			// next GET, and a full mailbox then closes the connection.
			logger.warn("Closing backpressured SSE subscriber after {} pending events", maxPendingSseEvents);
			detach(current);
			return;
		}
		current.drain();
	}

	synchronized void subscribe(AsyncContext asyncContext, HttpServletResponse response) throws IOException {
		if (closed) {
			// DELETE may close the connection after GET has started async processing.
			// Complete that request instead of leaving its response open indefinitely.
			asyncContext.complete();
			return;
		}
		// One subscriber per stream: a new GET takes the stream over from a previous
		// one that the server may not yet know is dead (proxy drop, client restart).
		// Rust and TypeScript answer 409 instead; taking over is friendlier to a
		// reconnecting client and never duplicates events.
		if (current != null) {
			logger.debug("New SSE subscriber replaces the attached one");
			detach(current);
		}
		SseSubscriber subscriber = new SseSubscriber(asyncContext, response);
		current = subscriber;
		subscriber.start();
		subscriber.drain();
	}

	synchronized void keepAlive() {
		if (!closed && current != null) {
			current.sendKeepAlive();
		}
	}

	synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		if (current != null) {
			detach(current);
		}
		mailbox.clear();
	}

	/** Detaches a subscriber if it is still the attached one, and completes its response. */
	private synchronized void detach(SseSubscriber subscriber) {
		if (subscriber.detached) {
			return;
		}
		subscriber.detached = true;
		if (current == subscriber) {
			current = null;
		}
		try {
			subscriber.asyncContext.complete();
		}
		catch (IllegalStateException ignored) {
			// already completed by the container
		}
	}

	/**
	 * Writes to one servlet async response. Holds no events of its own except comments;
	 * every method runs under the enclosing stream's monitor.
	 */
	private final class SseSubscriber implements AsyncListener, WriteListener {

		private final AsyncContext asyncContext;

		private final ServletOutputStream output;

		/** Comments (open, keep-alive) not yet written; written before queued events. */
		private final ArrayDeque<byte[]> comments = new ArrayDeque<>();

		private boolean detached;

		private boolean flushPending;

		SseSubscriber(AsyncContext asyncContext, HttpServletResponse response) throws IOException {
			this.asyncContext = asyncContext;
			this.output = response.getOutputStream();
		}

		void start() {
			asyncContext.addListener(this);
			comments.addLast(SSE_OPEN_COMMENT);
			output.setWriteListener(this);
		}

		void sendKeepAlive() {
			if (!detached && comments.isEmpty() && mailbox.isEmpty()) {
				comments.addLast(SSE_KEEP_ALIVE_COMMENT);
				drain();
			}
		}

		void drain() {
			synchronized (SseOutboundStream.this) {
				if (detached) {
					return;
				}
				try {
					flushIfReady();
					while (!detached && output.isReady()) {
						byte[] bytes = comments.pollFirst();
						String payload = null;
						if (bytes == null) {
							payload = mailbox.pollFirst();
							if (payload == null) {
								break;
							}
							bytes = ("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8);
						}
						try {
							output.write(bytes);
						}
						catch (IOException | IllegalStateException e) {
							if (payload != null) {
								// The write failed, so the event did not go out: keep it first in line.
								mailbox.addFirst(payload);
							}
							throw e;
						}
						flushPending = true;
					}
					flushIfReady();
				}
				catch (IOException | IllegalStateException e) {
					detach(this);
				}
			}
		}

		private void flushIfReady() throws IOException {
			if (flushPending && output.isReady()) {
				output.flush();
				flushPending = false;
			}
		}

		@Override
		public void onWritePossible() {
			drain();
		}

		@Override
		public void onError(Throwable error) {
			detach(this);
		}

		@Override
		public void onComplete(AsyncEvent event) {
			detach(this);
		}

		@Override
		public void onTimeout(AsyncEvent event) {
			detach(this);
		}

		@Override
		public void onError(AsyncEvent event) {
			detach(this);
		}

		@Override
		public void onStartAsync(AsyncEvent event) {
			event.getAsyncContext().addListener(this);
		}

	}

}
