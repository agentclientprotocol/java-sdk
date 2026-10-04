/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

import com.agentclientprotocol.sdk.http.server.SseFrame;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;

/**
 * Writes one SSE stream's frames to a servlet async response with non-blocking output: it asks
 * the endpoint for a frame only when the output is ready and the previous write has completed,
 * writes and flushes each frame, and cancels when the client goes away or a write fails, so the
 * endpoint keeps what was not written for the client's next GET.
 *
 * <p>
 * <b>What "completed" means.</b> A write is pending while {@code isReady()} is false after its
 * flush; the container then calls {@code onWritePossible} on success or {@code onError} on
 * failure. A write whose flush completes at once may still have failed: a container can accept
 * a write to a stream the client has already reset and report it only on the next write
 * (Jetty's HTTP/2 output does). So before asking for the next frame an empty write checks the
 * stream; a request is the endpoint's confirmation that the frames before it were written.
 * </p>
 *
 * <p>
 * The writer's lock guards its own state and the output; it is never held while calling the
 * endpoint, which hands frames over under a lock of its own.
 * </p>
 *
 * @author Mark Pollack
 */
final class ServletSseWriter implements CoreSubscriber<SseFrame>, WriteListener, AsyncListener {

	private static final byte[] NO_BYTES = new byte[0];

	private final ReentrantLock lock = new ReentrantLock();

	private final AsyncContext asyncContext;

	private final ServletOutputStream output;

	/** Guarded by {@code lock}. */
	private @Nullable Subscription subscription;

	/** A frame was asked for and has not arrived. Guarded by {@code lock}. */
	private boolean requested;

	/** A frame was written and not yet flushed. Guarded by {@code lock}. */
	private boolean flushPending;

	/** A flush completed whose stream an empty write has not yet checked. Guarded by {@code lock}. */
	private boolean probeNeeded;

	/** Guarded by {@code lock}. */
	private boolean done;

	ServletSseWriter(AsyncContext asyncContext, ServletOutputStream output) {
		this.asyncContext = asyncContext;
		this.output = output;
	}

	@Override
	public void onSubscribe(Subscription s) {
		lock.lock();
		try {
			this.subscription = s;
		}
		finally {
			lock.unlock();
		}
		asyncContext.addListener(this);
		// The container calls onWritePossible once the output can take the first frame.
		output.setWriteListener(this);
	}

	@Override
	public void onNext(SseFrame frame) {
		Step step;
		lock.lock();
		try {
			requested = false;
			if (done) {
				return;
			}
			// The frame was asked for while the output was ready, so it may be written now.
			output.write(frame.encode());
			flushPending = true;
			step = readyForNext() ? Step.REQUEST : Step.WAIT;
		}
		catch (IOException | IllegalStateException e) {
			step = stopLocked();
		}
		finally {
			lock.unlock();
		}
		take(step);
	}

	@Override
	public void onWritePossible() {
		Step step;
		lock.lock();
		try {
			if (done) {
				return;
			}
			step = readyForNext() ? Step.REQUEST : Step.WAIT;
		}
		catch (IOException | IllegalStateException e) {
			step = stopLocked();
		}
		finally {
			lock.unlock();
		}
		take(step);
	}

	/**
	 * Advances as far as the output allows, each step only once {@code isReady()} said so (a
	 * non-blocking output refuses a write or flush while one is pending): flush the written frame,
	 * check the flushed stream with an empty write, then decide to ask for the next frame. When
	 * the output is not ready the container calls {@code onWritePossible} later.
	 * @return whether to ask for the next frame now
	 */
	private boolean readyForNext() throws IOException {
		while (output.isReady()) {
			if (flushPending) {
				output.flush();
				flushPending = false;
				probeNeeded = true;
			}
			else if (probeNeeded) {
				output.write(NO_BYTES);
				probeNeeded = false;
			}
			else if (requested) {
				return false;
			}
			else {
				requested = true;
				return true;
			}
		}
		return false;
	}

	/** What to do once the lock is released: the endpoint is never called under it. */
	private enum Step {

		REQUEST, WAIT, CANCEL_AND_COMPLETE, NONE

	}

	/** Under the lock: marks the writer done; the caller then cancels and completes. */
	private Step stopLocked() {
		if (done) {
			return Step.NONE;
		}
		done = true;
		return Step.CANCEL_AND_COMPLETE;
	}

	private void take(Step step) {
		Subscription current;
		lock.lock();
		try {
			current = subscription;
		}
		finally {
			lock.unlock();
		}
		switch (step) {
			case REQUEST -> {
				if (current != null) {
					current.request(1);
				}
			}
			case CANCEL_AND_COMPLETE -> {
				// The client is gone: the endpoint keeps what was not written for its next GET.
				if (current != null) {
					current.cancel();
				}
				StreamableHttpAcpServlet.complete(asyncContext);
			}
			default -> {
				// Wait for onWritePossible, or nothing left to do.
			}
		}
	}

	@Override
	public void onError(Throwable error) {
		// The endpoint's frames failed, or (WriteListener) the container failed a write.
		Step step;
		lock.lock();
		try {
			step = stopLocked();
		}
		finally {
			lock.unlock();
		}
		take(step);
	}

	@Override
	public void onComplete() {
		lock.lock();
		try {
			if (done) {
				return;
			}
			done = true;
		}
		finally {
			lock.unlock();
		}
		StreamableHttpAcpServlet.complete(asyncContext);
	}

	/** The container ended the response (client gone, completed, timed out). */
	private void stop() {
		Step step;
		lock.lock();
		try {
			step = stopLocked();
		}
		finally {
			lock.unlock();
		}
		take(step);
	}

	@Override
	public void onComplete(AsyncEvent event) {
		stop();
	}

	@Override
	public void onTimeout(AsyncEvent event) {
		stop();
	}

	@Override
	public void onError(AsyncEvent event) {
		stop();
	}

	@Override
	public void onStartAsync(AsyncEvent event) {
		event.getAsyncContext().addListener(this);
	}

}
