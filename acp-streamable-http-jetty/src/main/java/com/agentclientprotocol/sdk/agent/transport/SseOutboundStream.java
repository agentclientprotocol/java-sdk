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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One outbound SSE stream of the Streamable HTTP transport (the connection stream or
 * one session stream), with at most one attached {@link SseSubscriber} writing to a
 * servlet async response.
 *
 * <p>
 * <b>A mailbox, as in the Rust and TypeScript servers.</b> Every event goes into one
 * bounded queue owned by the stream. An attached subscriber takes an event from the queue
 * when it writes it, but holds it as unconfirmed until the write has completed without
 * error: when a subscriber goes away (the client dropped, the server closed it for
 * backpressure, a new GET took the stream over, or the container reported an error on
 * another thread) its unconfirmed events go back to the front of the queue in order, and
 * the next subscriber continues from there. All state changes happen under this stream's
 * monitor, including the container's asynchronous callbacks, so a reconnect racing the old
 * subscriber's close is serialised.
 * </p>
 *
 * <p>
 * <b>What "completed" means.</b> A write is pending while {@code isReady()} returns false
 * after its flush; the container then calls {@code onWritePossible} on success or
 * {@code onError} on failure. A write whose flush completes at once may still have failed:
 * a container can accept a write to a stream the client has already reset and report it
 * only on the next write (Jetty's HTTP/2 output does). So once a flush has completed, an
 * empty write checks the stream before the events in it are confirmed. An event whose
 * write was still pending when its subscriber was detached is returned too, so it may
 * arrive twice rather than not at all. Events whose write the container did complete, but which a client then discarded (its reset crossed the
 * data on the wire), cannot be detected here; only event ids and {@code Last-Event-ID},
 * which the RFD defers, could close that.
 * </p>
 *
 * @author Kaiser Dandangi
 */
final class SseOutboundStream {

	private static final Logger logger = LoggerFactory.getLogger(SseOutboundStream.class);

	private static final byte[] SSE_KEEP_ALIVE_COMMENT = ": keep-alive\n\n".getBytes(StandardCharsets.UTF_8);

	// Commits the SSE response when there are no queued events, without emitting an ACP message.
	private static final byte[] SSE_OPEN_COMMENT = ": connected\n\n".getBytes(StandardCharsets.UTF_8);

	private static final byte[] NO_BYTES = new byte[0];

	private final int mailboxCapacity;

	private final int maxPendingSseEvents;

	/** Events not yet written to any subscriber, oldest first. Guarded by {@code this}. */
	private final ArrayDeque<String> mailbox = new ArrayDeque<>();

	/** Guarded by {@code this}. */
	private @Nullable SseSubscriber current;

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
		if (mailbox.size() + (current != null ? current.unconfirmed.size() : 0) >= mailboxCapacity) {
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

	synchronized boolean isClosed() {
		return closed;
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
		// Not known to have reached the client: back to the front, in order.
		while (!subscriber.unconfirmed.isEmpty()) {
			mailbox.addFirst(subscriber.unconfirmed.removeLast());
		}
		try {
			subscriber.asyncContext.complete();
		}
		catch (IllegalStateException ignored) {
			// already completed by the container
		}
	}

	/**
	 * Writes to one servlet async response. Holds no events of its own except comments and
	 * the events it wrote whose write has not yet completed; every method runs under the
	 * enclosing stream's monitor.
	 */
	private final class SseSubscriber implements AsyncListener, WriteListener {

		private final AsyncContext asyncContext;

		private final ServletOutputStream output;

		/** Comments (open, keep-alive) not yet written; written before queued events. */
		private final ArrayDeque<byte[]> comments = new ArrayDeque<>();

		/**
		 * Events taken from the mailbox and written, oldest first, whose write has not
		 * completed without error. Detaching returns them to the mailbox.
		 */
		private final ArrayDeque<String> unconfirmed = new ArrayDeque<>();

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
					// isReady() true means no write is pending: everything written so far has
					// been flushed and that flush has completed.
					boolean more = true;
					while (more && !detached && output.isReady()) {
						more = writeNext();
					}
				}
				catch (IOException | IllegalStateException e) {
					detach(this);
				}
			}
		}

		/**
		 * Performs the next write this subscriber needs: confirm the events of a completed
		 * flush, write the next comment or event, or flush what was written.
		 * @return false when there is nothing left to write or flush
		 */
		private boolean writeNext() throws IOException {
			if (!flushPending && !unconfirmed.isEmpty()) {
				// A completed flush may still have failed; an empty write surfaces that
				// before its events count as delivered.
				output.write(NO_BYTES);
				unconfirmed.clear();
				return true;
			}
			byte[] frame = nextFrame();
			if (frame != null) {
				output.write(frame);
				flushPending = true;
				return true;
			}
			if (!flushPending) {
				return false;
			}
			output.flush();
			flushPending = false;
			return true;
		}

		/**
		 * The next queued comment, else the next mailbox event as an SSE frame (the event is
		 * then unconfirmed); null when both are empty.
		 */
		private byte @Nullable [] nextFrame() {
			byte[] comment = comments.pollFirst();
			if (comment != null) {
				return comment;
			}
			String payload = mailbox.pollFirst();
			if (payload == null) {
				return null;
			}
			unconfirmed.addLast(payload);
			return ("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8);
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
