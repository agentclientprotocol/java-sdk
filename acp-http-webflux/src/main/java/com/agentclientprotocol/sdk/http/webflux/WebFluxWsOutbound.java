/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.webflux;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.ReentrantLock;

import com.agentclientprotocol.sdk.http.server.AcpWsOutbound;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * Sends the endpoint's frames through a WebFlux {@link WebSocketSession}. WebFlux sends from a
 * publisher and reports no completion per frame, so the publisher's demand stands for it: a
 * frame is handed over only when WebFlux has asked for one, and its send completes then. The
 * endpoint sends the next frame only once the previous send completed, so while the client
 * reads nothing, WebFlux stops asking, the frames wait in the endpoint's bounded queue, and the
 * endpoint's rule for a full queue applies. Nothing is queued here beyond the one frame waiting
 * for demand.
 *
 * @author Mark Pollack
 */
final class WebFluxWsOutbound implements AcpWsOutbound {

	private static final Logger logger = LoggerFactory.getLogger(WebFluxWsOutbound.class);

	private final WebSocketSession session;

	/** Guards the fields below. A lock, not a monitor: senders may be virtual threads. */
	private final ReentrantLock lock = new ReentrantLock();

	private final Flux<String> frames;

	private @Nullable FluxSink<String> sink;

	/** Frames WebFlux asked for and has not been given. Guarded by {@code lock}. */
	private long demand;

	/** The frame waiting for demand, and its send. Guarded by {@code lock}. */
	private @Nullable String waiting;

	private @Nullable CompletableFuture<Void> waitingSent;

	/** Whether the socket is closing or closed. Guarded by {@code lock}. */
	private boolean closed;

	WebFluxWsOutbound(WebSocketSession session) {
		this.session = session;
		this.frames = Flux.create(this::attach, FluxSink.OverflowStrategy.ERROR);
	}

	/**
	 * Returns the frames to send, for {@link WebSocketSession#send}: one subscriber.
	 * @return the frames
	 */
	Flux<String> frames() {
		return frames;
	}

	private void attach(FluxSink<String> attached) {
		attached.onDispose(this::disposed);
		lock.lock();
		try {
			this.sink = attached;
		}
		finally {
			lock.unlock();
		}
		// Called at once with the demand so far, which sends a frame already waiting.
		attached.onRequest(this::request);
	}

	@Override
	public CompletionStage<Void> sendText(String text) {
		FluxSink<String> target;
		lock.lock();
		try {
			if (closed) {
				return CompletableFuture.failedFuture(new IllegalStateException("The WebSocket is closed"));
			}
			if (waiting != null) {
				return CompletableFuture.failedFuture(
						new IllegalStateException("A frame is already waiting to be sent; send one at a time"));
			}
			if (sink == null || demand == 0) {
				CompletableFuture<Void> sent = new CompletableFuture<>();
				waiting = text;
				waitingSent = sent;
				return sent;
			}
			demand--;
			target = sink;
		}
		finally {
			lock.unlock();
		}
		target.next(text);
		return CompletableFuture.completedFuture(null);
	}

	private void request(long n) {
		String text;
		CompletableFuture<Void> sent;
		FluxSink<String> target;
		lock.lock();
		try {
			demand = demand + n < 0 ? Long.MAX_VALUE : demand + n;
			text = waiting;
			sent = waitingSent;
			target = sink;
			if (text == null || sent == null || target == null || closed) {
				return;
			}
			demand--;
			waiting = null;
			waitingSent = null;
		}
		finally {
			lock.unlock();
		}
		target.next(text);
		// The endpoint's next send may run here, on WebFlux's request.
		sent.complete(null);
	}

	/**
	 * Closes the socket with the endpoint's code, and only then completes the frames: completed
	 * first, WebFlux would end the session without a close code (1005).
	 */
	@Override
	public CompletionStage<Void> close(int code, String reason) {
		if (!markClosed()) {
			return CompletableFuture.completedFuture(null);
		}
		CompletableFuture<Void> sent = new CompletableFuture<>();
		session.close(CloseStatus.create(code, reason))
			.doFinally(signal -> {
				complete();
				sent.complete(null);
			})
			.subscribe(ignored -> {
			}, error -> logger.debug("Closing an ACP WebSocket failed: {}", error.toString()));
		return sent;
	}

	/** The socket closed: no frame will be sent any more. */
	void closed() {
		markClosed();
		complete();
	}

	/** Marks the socket closed and fails the waiting send. Returns whether this call closed it. */
	private boolean markClosed() {
		CompletableFuture<Void> sent;
		lock.lock();
		try {
			if (closed) {
				return false;
			}
			closed = true;
			sent = waitingSent;
			waiting = null;
			waitingSent = null;
		}
		finally {
			lock.unlock();
		}
		if (sent != null) {
			sent.completeExceptionally(new IllegalStateException("The WebSocket is closed"));
		}
		return true;
	}

	private void complete() {
		FluxSink<String> target;
		lock.lock();
		try {
			target = sink;
		}
		finally {
			lock.unlock();
		}
		if (target != null) {
			target.complete();
		}
	}

	/** WebFlux stopped sending (the socket closed or the send failed). */
	private void disposed() {
		markClosed();
	}

}
