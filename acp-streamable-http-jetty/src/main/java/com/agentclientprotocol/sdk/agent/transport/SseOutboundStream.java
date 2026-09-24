/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * one session stream): a bounded mailbox for events produced while no client is
 * attached, plus at most one attached {@link SseSubscriber} writing to a servlet async
 * response.
 *
 * @author Kaiser Dandangi
 */
final class SseOutboundStream {

	private static final Logger logger = LoggerFactory.getLogger(SseOutboundStream.class);

	private static final byte[] SSE_KEEP_ALIVE_COMMENT = ": keep-alive\n\n".getBytes(StandardCharsets.UTF_8);

	// Commits the SSE response when there are no replay events, without emitting an ACP message.
	private static final byte[] SSE_OPEN_COMMENT = ": connected\n\n".getBytes(StandardCharsets.UTF_8);

	private final int mailboxCapacity;

	private final int maxPendingSseEvents;

	private final ArrayDeque<String> replay = new ArrayDeque<>();

	private final List<SseSubscriber> subscribers = new CopyOnWriteArrayList<>();

	private final AtomicBoolean closed = new AtomicBoolean(false);

	SseOutboundStream(int mailboxCapacity, int maxPendingSseEvents) {
		this.mailboxCapacity = mailboxCapacity;
		this.maxPendingSseEvents = maxPendingSseEvents;
	}

	/*
	 * A mailbox, as in the Rust and TypeScript servers: whenever no subscriber is
	 * attached (before the first GET, or after the client's stream dropped and before
	 * it reopens) events are retained, bounded, and delivered on the next attach. A
	 * dropped stream must not lose an accepted prompt's result.
	 */
	synchronized void push(String payload) {
		if (closed.get()) {
			return;
		}
		if (subscribers.isEmpty()) {
			if (replay.size() == mailboxCapacity) {
				throw new AcpConnectionException(
						"Outbound SSE replay buffer exceeded " + mailboxCapacity + " events");
			}
			replay.addLast(payload);
			return;
		}
		subscribers.forEach(subscriber -> subscriber.send(payload));
	}

	synchronized void subscribe(AsyncContext asyncContext, HttpServletResponse response) throws IOException {
		if (closed.get()) {
			// DELETE may close the connection after GET has started async processing.
			// Complete that request instead of leaving its response open indefinitely.
			asyncContext.complete();
			return;
		}
		// One subscriber per stream: a new GET takes the stream over from a previous
		// one that the server may not yet know is dead (proxy drop, client restart).
		// Rust and TypeScript answer 409 instead; taking over is friendlier to a
		// reconnecting client and never duplicates events.
		for (SseSubscriber previous : new ArrayList<>(subscribers)) {
			logger.debug("New SSE subscriber replaces the attached one");
			previous.close();
		}
		SseSubscriber subscriber = new SseSubscriber(this, asyncContext, response);
		subscribers.add(subscriber);
		subscriber.start();
		for (String payload : new ArrayList<>(replay)) {
			subscriber.send(payload);
		}
		replay.clear();
		subscriber.drain();
	}

	void remove(SseSubscriber subscriber) {
		subscribers.remove(subscriber);
	}

	void keepAlive() {
		if (!closed.get()) {
			subscribers.forEach(SseSubscriber::sendKeepAlive);
		}
	}

	synchronized void close() {
		if (closed.compareAndSet(false, true)) {
			subscribers.forEach(SseSubscriber::close);
			subscribers.clear();
			replay.clear();
		}
	}


	private static final class SseSubscriber implements AsyncListener, WriteListener {

		private final SseOutboundStream parent;

		private final AsyncContext asyncContext;

		private final ServletOutputStream output;

		private final ArrayDeque<byte[]> pendingEvents = new ArrayDeque<>();

		private final AtomicBoolean closed = new AtomicBoolean(false);

		private boolean flushPending;

		SseSubscriber(SseOutboundStream parent, AsyncContext asyncContext, HttpServletResponse response) throws IOException {
			this.parent = parent;
			this.asyncContext = asyncContext;
			this.output = response.getOutputStream();
		}

		synchronized void start() {
			asyncContext.addListener(this);
			pendingEvents.addLast(SSE_OPEN_COMMENT);
			output.setWriteListener(this);
		}

		synchronized void send(String payload) {
			if (closed.get()) {
				return;
			}
			if (pendingEvents.size() == parent.maxPendingSseEvents) {
				logger.warn("Closing backpressured SSE subscriber after {} pending events",
						parent.maxPendingSseEvents);
				close();
				return;
			}
			pendingEvents.addLast(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
			drain();
		}

		synchronized void sendKeepAlive() {
			if (closed.get() || !pendingEvents.isEmpty()) {
				return;
			}
			pendingEvents.addLast(SSE_KEEP_ALIVE_COMMENT);
			drain();
		}

		synchronized void drain() {
			try {
				flushIfReady();
				while (!closed.get() && output.isReady()) {
					byte[] event = pendingEvents.pollFirst();
					if (event == null) {
						break;
					}
					output.write(event);
					flushPending = true;
				}
				flushIfReady();
			}
			catch (IOException | IllegalStateException e) {
				close();
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
			close();
		}

		@Override
		public void onComplete(AsyncEvent event) {
			close();
		}

		@Override
		public void onTimeout(AsyncEvent event) {
			close();
		}

		@Override
		public void onError(AsyncEvent event) {
			close();
		}

		@Override
		public void onStartAsync(AsyncEvent event) {
			event.getAsyncContext().addListener(this);
		}

		void close() {
			if (closed.compareAndSet(false, true)) {
				parent.remove(this);
				synchronized (this) {
					pendingEvents.clear();
				}
				try {
					asyncContext.complete();
				}
				catch (IllegalStateException ignored) {
				}
			}
		}

	}

}
