/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.util.ArrayDeque;
import java.util.concurrent.locks.ReentrantLock;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * One outbound SSE stream of the Streamable HTTP transport (the connection stream or one
 * session stream), with at most one attached subscriber: the frames of the host's response for
 * the GET that opened it.
 *
 * <p>
 * <b>A mailbox, as in the Rust and TypeScript servers.</b> Every event goes into one bounded
 * queue owned by the stream. The attached subscriber takes an event from the queue when the
 * host requests a frame, and holds it as unconfirmed until the host requests again: a host
 * requests the next frame only once the previous write has completed, so a request confirms
 * the frames before it. When a subscriber goes away (the client dropped and the host cancelled,
 * the server detached it for backpressure, or a new GET took the stream over) its unconfirmed
 * events go back to the front of the queue in order, and the next subscriber continues from
 * there. An event whose write was still pending when its subscriber was detached may therefore
 * arrive twice rather than not at all. Events a client discarded after the host's write
 * completed (its reset crossed the data on the wire) cannot be detected here; only event ids
 * and {@code Last-Event-ID}, which the RFD defers, could close that.
 * </p>
 *
 * <p>
 * All state changes happen under the stream's private lock, including the host's request and
 * cancel signals from its own threads, so a reconnect racing the old subscriber's close is
 * serialised. A lock, not a monitor: a host may signal from a virtual thread, which a monitor
 * would pin to its carrier on JDK 21. A frame is handed to the host under the lock; a host's
 * re-entrant request from inside that hand-off is folded into the running loop, never recursed.
 * </p>
 *
 * @author Kaiser Dandangi
 * @author Mark Pollack
 */
final class SseOutboundStream {

	private static final Logger logger = LoggerFactory.getLogger(SseOutboundStream.class);

	/** Commits the response at once, before any event, without emitting an ACP message. */
	static final SseFrame OPEN_COMMENT = SseFrame.comment("connected");

	static final SseFrame KEEP_ALIVE_COMMENT = SseFrame.comment("keep-alive");

	/** The last frame of a stream the endpoint closes while shutting down. */
	static final SseFrame SHUTDOWN_COMMENT = SseFrame.comment("shutting down");

	/** Guards all state of this stream and its subscriber. */
	private final ReentrantLock lock = new ReentrantLock();

	private final int mailboxCapacity;

	private final int maxPendingSseEvents;

	/** Events not yet handed to any subscriber, oldest first. Guarded by {@code lock}. */
	private final ArrayDeque<String> mailbox = new ArrayDeque<>();

	/** Guarded by {@code lock}. */
	private @Nullable Subscriber current;

	/** Guarded by {@code lock}. */
	private boolean closed;

	SseOutboundStream(int mailboxCapacity, int maxPendingSseEvents) {
		this.mailboxCapacity = mailboxCapacity;
		this.maxPendingSseEvents = maxPendingSseEvents;
	}

	/**
	 * Queues an event and hands the attached subscriber as much as it has asked for.
	 * @throws AcpConnectionException when the mailbox is full; the caller closes the connection
	 * rather than drop an event
	 */
	void push(String payload) {
		lock.lock();
		try {
			if (closed) {
				return;
			}
			Subscriber subscriber = current;
			if (mailbox.size() + (subscriber != null ? subscriber.unconfirmed.size() : 0) >= mailboxCapacity) {
				throw new AcpConnectionException("Outbound SSE replay buffer exceeded " + mailboxCapacity + " events");
			}
			mailbox.addLast(payload);
			if (subscriber == null) {
				return;
			}
			if (mailbox.size() > maxPendingSseEvents) {
				// The attached client is not reading. Detach it; the events stay queued for the
				// next GET, and a full mailbox then closes the connection.
				logger.warn("Closing backpressured SSE subscriber after {} pending events", maxPendingSseEvents);
				detach(subscriber);
				return;
			}
			subscriber.drain();
		}
		finally {
			lock.unlock();
		}
	}

	/**
	 * The frames of a new GET on this stream: an opening comment, then the queued events as the
	 * host asks for them. One subscriber per stream: a new GET takes the stream over from a
	 * previous one the server may not yet know is dead (proxy drop, client restart), completing
	 * the old one's frames. Rust and TypeScript answer 409 instead; taking over is friendlier to a
	 * reconnecting client and never duplicates events. A closed stream's frames complete at once.
	 */
	Flux<SseFrame> subscribe() {
		return Flux.create(this::attach);
	}

	private void attach(FluxSink<SseFrame> sink) {
		lock.lock();
		try {
			if (closed) {
				// DELETE may close the connection after the GET was answered.
				sink.complete();
				return;
			}
			Subscriber previous = current;
			if (previous != null) {
				logger.debug("New SSE subscriber replaces the attached one");
				detach(previous);
			}
			Subscriber subscriber = new Subscriber(sink);
			current = subscriber;
			subscriber.comments.addLast(OPEN_COMMENT);
			sink.onCancel(() -> detach(subscriber));
			// Called at once with any demand already signalled, then on every request.
			sink.onRequest(subscriber::request);
		}
		finally {
			lock.unlock();
		}
	}

	void keepAlive() {
		lock.lock();
		try {
			Subscriber subscriber = current;
			if (!closed && subscriber != null) {
				subscriber.sendKeepAlive();
			}
		}
		finally {
			lock.unlock();
		}
	}

	/** Closes the stream: its subscriber's frames complete and queued events are dropped. */
	void close() {
		close(false);
	}

	/**
	 * Closes the stream as {@link #close()} does, after handing the subscriber a closing comment
	 * if it has asked for a frame: the endpoint is shutting down.
	 */
	void closeForShutdown() {
		close(true);
	}

	private void close(boolean shutdownComment) {
		lock.lock();
		try {
			if (closed) {
				return;
			}
			closed = true;
			Subscriber subscriber = current;
			if (subscriber != null) {
				if (shutdownComment) {
					subscriber.comments.clear();
					subscriber.comments.addLast(SHUTDOWN_COMMENT);
					subscriber.drainCommentsOnly();
				}
				detach(subscriber);
			}
			mailbox.clear();
		}
		finally {
			lock.unlock();
		}
	}

	boolean isClosed() {
		lock.lock();
		try {
			return closed;
		}
		finally {
			lock.unlock();
		}
	}

	/** Detaches a subscriber if it is still attached, and completes its frames. */
	private void detach(Subscriber subscriber) {
		lock.lock();
		try {
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
			subscriber.sink.complete();
		}
		finally {
			lock.unlock();
		}
	}

	/**
	 * One GET's frames. Holds no events of its own except comments and the events handed to the
	 * host whose write has not been confirmed; every method runs under the stream's lock.
	 */
	private final class Subscriber {

		private final FluxSink<SseFrame> sink;

		/** Comments (open, keep-alive, shutdown) not yet handed over; they go before events. */
		private final ArrayDeque<SseFrame> comments = new ArrayDeque<>();

		/** Events handed to the host, oldest first, whose write is not yet confirmed. */
		private final ArrayDeque<String> unconfirmed = new ArrayDeque<>();

		/** Frames the host has asked for and not yet been given. */
		private long demand;

		private boolean detached;

		/** Set while frames are being handed over, so a re-entrant request does not recurse. */
		private boolean draining;

		Subscriber(FluxSink<SseFrame> sink) {
			this.sink = sink;
		}

		void request(long n) {
			lock.lock();
			try {
				if (detached) {
					return;
				}
				// The host asks for more only once what it was given has been written.
				unconfirmed.clear();
				demand = (demand + n < 0) ? Long.MAX_VALUE : demand + n;
				drain();
			}
			finally {
				lock.unlock();
			}
		}

		void sendKeepAlive() {
			if (!detached && comments.isEmpty() && mailbox.isEmpty()) {
				comments.addLast(KEEP_ALIVE_COMMENT);
				drain();
			}
		}

		void drain() {
			drain(true);
		}

		void drainCommentsOnly() {
			drain(false);
		}

		private void drain(boolean events) {
			if (draining) {
				return;
			}
			draining = true;
			try {
				while (!detached && demand > 0) {
					SseFrame frame = nextFrame(events);
					if (frame == null) {
						return;
					}
					demand--;
					sink.next(frame);
				}
			}
			finally {
				draining = false;
			}
		}

		/** The next comment, else the next mailbox event (then unconfirmed); null when none. */
		private @Nullable SseFrame nextFrame(boolean events) {
			SseFrame comment = comments.pollFirst();
			if (comment != null) {
				return comment;
			}
			if (!events) {
				return null;
			}
			String payload = mailbox.pollFirst();
			if (payload == null) {
				return null;
			}
			unconfirmed.addLast(payload);
			return SseFrame.event(payload);
		}

	}

}
