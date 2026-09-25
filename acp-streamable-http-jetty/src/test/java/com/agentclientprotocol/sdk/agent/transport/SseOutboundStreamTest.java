/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deterministic tests of {@link SseOutboundStream}'s mailbox and backpressure rules. The
 * servlet side is a mocked {@link AsyncContext} and response around a fake
 * {@link ServletOutputStream} whose {@code isReady()} the test controls, so a
 * backpressured subscriber can be created without depending on a real client's
 * flow-control window.
 */
class SseOutboundStreamTest {

	private static final String OPEN = ": connected\n\n";

	@Test
	void eventsPushedWithoutSubscriberAreDeliveredToTheNextSubscriber() throws IOException {
		SseOutboundStream stream = new SseOutboundStream(4, 4);
		stream.push("{\"n\":1}");
		stream.push("{\"n\":2}");

		Attached client = attach(stream, true);

		assertThat(client.output.written()).isEqualTo(OPEN + "data: {\"n\":1}\n\n" + "data: {\"n\":2}\n\n");

		stream.push("{\"n\":3}");
		assertThat(client.output.written())
			.isEqualTo(OPEN + "data: {\"n\":1}\n\n" + "data: {\"n\":2}\n\n" + "data: {\"n\":3}\n\n");
		verify(client.asyncContext, never()).complete();
	}

	@Test
	void mailboxOverflowThrows() {
		SseOutboundStream stream = new SseOutboundStream(2, 4);
		stream.push("a");
		stream.push("b");

		assertThatThrownBy(() -> stream.push("c")).isInstanceOf(AcpConnectionException.class)
			.hasMessageContaining("2 events");
	}

	@Test
	void backpressuredSubscriberIsClosedAndItsUndeliveredEventsReachTheNextSubscriber() throws IOException {
		SseOutboundStream stream = new SseOutboundStream(4, 2);

		// Never ready: the open comment and every event stay queued on the subscriber.
		Attached slow = attach(stream, false);
		stream.push("\"queued\"");
		assertThat(slow.output.written()).isEmpty();
		verify(slow.asyncContext, never()).complete();

		stream.push("\"second\"");
		verify(slow.asyncContext, never()).complete();

		// More than maxPendingSseEvents events are now waiting on a subscriber that does not
		// read: it is detached, and every event stays queued for the next one.
		stream.push("\"overflow\"");
		verify(slow.asyncContext).complete();

		// With no subscriber attached, later events are retained for the next one.
		stream.push("\"later\"");
		assertThat(slow.output.written()).isEmpty();

		Attached next = attach(stream, true);
		assertThat(next.output.written())
			.as("nothing the slow subscriber never wrote is lost, and order is kept")
			.isEqualTo(OPEN + "data: \"queued\"\n\n" + "data: \"second\"\n\n" + "data: \"overflow\"\n\n"
					+ "data: \"later\"\n\n");
		verify(next.asyncContext, never()).complete();
	}

	/** Review probe P1: a subscriber that backs up during the replay must not lose the rest of it. */
	@Test
	void replayIntoASubscriberThatCannotWriteLosesNothing() throws IOException {
		SseOutboundStream stream = new SseOutboundStream(8, 2);
		stream.push("\"a\"");
		stream.push("\"b\"");
		stream.push("\"c\"");
		stream.push("\"d\"");

		attach(stream, false);
		Attached next = attach(stream, true);

		assertThat(next.output.written())
			.isEqualTo(OPEN + "data: \"a\"\n\n" + "data: \"b\"\n\n" + "data: \"c\"\n\n" + "data: \"d\"\n\n");
	}

	/**
	 * Review probe P3: the container reports the old connection's error on its own thread
	 * after the client has already reconnected. The late close must not strand or reorder
	 * anything, and must not detach the new subscriber.
	 */
	@Test
	void aLateErrorOnTheOldSubscriberDoesNotDisturbTheNewOne() throws IOException {
		SseOutboundStream stream = new SseOutboundStream(8, 8);
		Attached old = attach(stream, false);
		stream.push("\"a\"");
		stream.push("\"b\"");

		Attached reconnected = attach(stream, true);
		stream.push("\"c\"");
		old.output.listener.onError(new IOException("connection reset"));
		stream.push("\"d\"");

		assertThat(reconnected.output.written()).isEqualTo(
				OPEN + "data: \"a\"\n\n" + "data: \"b\"\n\n" + "data: \"c\"\n\n" + "data: \"d\"\n\n");
		verify(reconnected.asyncContext, never()).complete();
	}

	/** Review probe P2: an event pushed around a subscriber's close reaches the next subscriber. */
	@Test
	void anEventPushedWhileTheSubscriberIsClosingIsKept() throws IOException {
		SseOutboundStream stream = new SseOutboundStream(8, 8);
		Attached first = attach(stream, false);
		first.output.listener.onError(new IOException("gone"));
		stream.push("\"x\"");

		Attached next = attach(stream, true);
		assertThat(next.output.written()).isEqualTo(OPEN + "data: \"x\"\n\n");
	}

	private static Attached attach(SseOutboundStream stream, boolean ready) throws IOException {
		FakeOutput output = new FakeOutput(ready);
		AsyncContext asyncContext = mock(AsyncContext.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getOutputStream()).thenReturn(output);
		stream.subscribe(asyncContext, response);
		return new Attached(asyncContext, output);
	}

	private record Attached(AsyncContext asyncContext, FakeOutput output) {
	}

	private static final class FakeOutput extends ServletOutputStream {

		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		private final boolean ready;

		FakeOutput(boolean ready) {
			this.ready = ready;
		}

		String written() {
			return bytes.toString(StandardCharsets.UTF_8);
		}

		@Override
		public boolean isReady() {
			return ready;
		}

		WriteListener listener;

		@Override
		public void setWriteListener(WriteListener writeListener) {
			this.listener = writeListener;
		}

		@Override
		public void write(int b) {
			bytes.write(b);
		}

	}

}
