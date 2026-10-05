/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

import com.agentclientprotocol.sdk.http.server.SseFrame;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Operators;

/**
 * Writes one SSE stream's frames to a chunked Vert.x response: it asks the endpoint for a frame,
 * writes it, and asks for the next once Vert.x reports the write done; it cancels when the client
 * goes away or a write fails, so the endpoint keeps what was not written for the client's next
 * GET. Vert.x responses take no timeout, so the stream lives as long as the connection.
 *
 * @author Mark Pollack
 */
final class VertxSseWriter implements CoreSubscriber<SseFrame> {

	private final HttpServerResponse response;

	private final AtomicBoolean done = new AtomicBoolean(false);

	private volatile Subscription subscription = Operators.emptySubscription();

	VertxSseWriter(HttpServerResponse response) {
		this.response = response;
	}

	@Override
	public void onSubscribe(Subscription s) {
		this.subscription = s;
		response.closeHandler(ignored -> cancel());
		s.request(1);
	}

	@Override
	public void onNext(SseFrame frame) {
		if (done.get()) {
			return;
		}
		response.write(Buffer.buffer(frame.encode())).onComplete(written -> {
			if (written.succeeded()) {
				subscription.request(1);
			}
			else {
				cancel();
			}
		});
	}

	@Override
	public void onError(Throwable error) {
		onComplete();
	}

	@Override
	public void onComplete() {
		if (done.compareAndSet(false, true)) {
			end();
		}
	}

	/** The client went away or a write failed: the endpoint keeps what was not written. */
	void cancel() {
		if (done.compareAndSet(false, true)) {
			subscription.cancel();
			end();
		}
	}

	private void end() {
		if (!response.ended() && !response.closed()) {
			response.end();
		}
	}

}
