/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.util.ArrayList;
import java.util.List;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic tests of {@link SseOutboundStream}'s mailbox and backpressure rules. The host
 * side is a fake subscriber the test drives: it asks for a frame as a host does once the
 * previous write completed, or holds its demand as a host whose output is not ready.
 */
class SseOutboundStreamTest {

	private static final String OPEN = ": connected";

	@Test
	void eventsPushedWithoutSubscriberAreDeliveredToTheNextSubscriber() {
		SseOutboundStream stream = new SseOutboundStream(4, 4);
		stream.push("{\"n\":1}");
		stream.push("{\"n\":2}");

		FakeHost client = FakeHost.writing(stream);

		assertThat(client.frames).containsExactly(OPEN, "{\"n\":1}", "{\"n\":2}");

		stream.push("{\"n\":3}");
		assertThat(client.frames).containsExactly(OPEN, "{\"n\":1}", "{\"n\":2}", "{\"n\":3}");
		assertThat(client.completed).isFalse();
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
	void backpressuredSubscriberIsClosedAndItsUndeliveredEventsReachTheNextSubscriber() {
		SseOutboundStream stream = new SseOutboundStream(4, 2);

		// Never asks: every event stays queued.
		FakeHost slow = FakeHost.stalled(stream);
		stream.push("\"queued\"");
		assertThat(slow.frames).isEmpty();
		stream.push("\"second\"");
		assertThat(slow.completed).isFalse();

		// More than maxPendingSseEvents events now wait on a subscriber that does not read:
		// it is detached, and every event stays queued for the next one.
		stream.push("\"overflow\"");
		assertThat(slow.completed).isTrue();

		stream.push("\"later\"");
		FakeHost next = FakeHost.writing(stream);
		assertThat(next.frames).as("nothing the slow subscriber never wrote is lost, and order is kept")
			.containsExactly(OPEN, "\"queued\"", "\"second\"", "\"overflow\"", "\"later\"");
		assertThat(next.completed).isFalse();
	}

	@Test
	void replayIntoASubscriberThatCannotWriteLosesNothing() {
		SseOutboundStream stream = new SseOutboundStream(8, 2);
		stream.push("\"a\"");
		stream.push("\"b\"");
		stream.push("\"c\"");
		stream.push("\"d\"");

		FakeHost.stalled(stream);
		FakeHost next = FakeHost.writing(stream);

		assertThat(next.frames).containsExactly(OPEN, "\"a\"", "\"b\"", "\"c\"", "\"d\"");
	}

	/**
	 * The host reports the old connection's failure on its own thread after the client has
	 * already reconnected. The late cancel must not strand or reorder anything, and must not
	 * detach the new subscriber.
	 */
	@Test
	void aLateCancelOfTheOldSubscriberDoesNotDisturbTheNewOne() {
		SseOutboundStream stream = new SseOutboundStream(8, 8);
		FakeHost old = FakeHost.stalled(stream);
		stream.push("\"a\"");
		stream.push("\"b\"");

		FakeHost reconnected = FakeHost.writing(stream);
		stream.push("\"c\"");
		old.cancel();
		stream.push("\"d\"");

		assertThat(reconnected.frames).containsExactly(OPEN, "\"a\"", "\"b\"", "\"c\"", "\"d\"");
		assertThat(reconnected.completed).isFalse();
	}

	@Test
	void anEventPushedAfterTheSubscriberWentAwayIsKept() {
		SseOutboundStream stream = new SseOutboundStream(8, 8);
		FakeHost first = FakeHost.stalled(stream);
		first.cancel();
		stream.push("\"x\"");

		FakeHost next = FakeHost.writing(stream);
		assertThat(next.frames).containsExactly(OPEN, "\"x\"");
	}

	/**
	 * A frame handed to the host whose write did not complete (no further request came) before
	 * the client went away is kept, ahead of later ones: it may arrive twice, never not at all.
	 */
	@Test
	void anUnconfirmedEventIsKeptInOrder() {
		SseOutboundStream stream = new SseOutboundStream(8, 8);
		FakeHost pending = FakeHost.stalled(stream);
		pending.request(2);
		stream.push("\"update\"");
		assertThat(pending.frames).containsExactly(OPEN, "\"update\"");
		stream.push("\"result\"");
		pending.cancel();

		FakeHost next = FakeHost.writing(stream);
		assertThat(next.frames).containsExactly(OPEN, "\"update\"", "\"result\"");
	}

	/** A frame whose write completed (the host asked again) is not replayed. */
	@Test
	void aConfirmedEventIsNotReplayed() {
		SseOutboundStream stream = new SseOutboundStream(8, 8);
		FakeHost first = FakeHost.stalled(stream);
		first.request(2);
		stream.push("\"a\"");
		first.request(1);
		first.cancel();

		FakeHost next = FakeHost.writing(stream);
		stream.push("\"b\"");
		assertThat(first.frames).containsExactly(OPEN, "\"a\"");
		assertThat(next.frames).containsExactly(OPEN, "\"b\"");
	}

	@Test
	void keepAliveIsSentOnlyToAnIdleSubscriberThatAsked() {
		SseOutboundStream stream = new SseOutboundStream(8, 8);
		FakeHost idle = FakeHost.writing(stream);
		stream.keepAlive();
		assertThat(idle.frames).containsExactly(OPEN, ": keep-alive");

		FakeHost stalled = FakeHost.stalled(stream);
		stream.keepAlive();
		assertThat(stalled.frames).isEmpty();
	}

	@Test
	void closingCompletesTheSubscriberAndASubscriberOfAClosedStream() {
		SseOutboundStream stream = new SseOutboundStream(8, 8);
		FakeHost attached = FakeHost.writing(stream);
		stream.push("\"a\"");
		stream.close();
		assertThat(attached.completed).isTrue();
		assertThat(stream.isClosed()).isTrue();
		stream.push("\"ignored\"");

		FakeHost late = FakeHost.writing(stream);
		assertThat(late.frames).isEmpty();
		assertThat(late.completed).isTrue();
	}

	@Test
	void aShutdownGivesTheSubscriberAClosingComment() {
		SseOutboundStream stream = new SseOutboundStream(8, 8);
		FakeHost attached = FakeHost.writing(stream);
		stream.closeForShutdown();
		assertThat(attached.frames).containsExactly(OPEN, ": shutting down");
		assertThat(attached.completed).isTrue();
		stream.closeForShutdown();
	}

	@Test
	void aNewSubscriberTakesTheStreamOver() {
		SseOutboundStream stream = new SseOutboundStream(8, 8);
		FakeHost first = FakeHost.writing(stream);
		FakeHost second = FakeHost.writing(stream);
		stream.push("\"a\"");
		assertThat(first.completed).isTrue();
		assertThat(second.frames).containsExactly(OPEN, "\"a\"");
	}

	/** A host's request from inside a frame's hand-off continues the loop rather than recursing. */
	@Test
	void manyQueuedEventsDoNotRecurse() {
		SseOutboundStream stream = new SseOutboundStream(100_000, 100_000);
		for (int i = 0; i < 50_000; i++) {
			stream.push(Integer.toString(i));
		}
		FakeHost client = FakeHost.writing(stream);
		assertThat(client.frames).hasSize(50_001);
	}

	/**
	 * A host as the test drives it: {@code writing} asks for the next frame as each one is
	 * handed over, as a host whose writes complete at once does; {@code stalled} asks for
	 * nothing until the test says so.
	 */
	private static final class FakeHost implements CoreSubscriber<SseFrame> {

		final List<String> frames = new ArrayList<>();

		final boolean autoRequest;

		Subscription subscription;

		boolean completed;

		private FakeHost(boolean autoRequest) {
			this.autoRequest = autoRequest;
		}

		static FakeHost writing(SseOutboundStream stream) {
			FakeHost host = new FakeHost(true);
			stream.subscribe().subscribe(host);
			return host;
		}

		static FakeHost stalled(SseOutboundStream stream) {
			FakeHost host = new FakeHost(false);
			stream.subscribe().subscribe(host);
			return host;
		}

		@Override
		public void onSubscribe(Subscription s) {
			this.subscription = s;
			if (autoRequest) {
				s.request(1);
			}
		}

		@Override
		public void onNext(SseFrame frame) {
			frames.add(frame.comment() ? ": " + frame.data() : frame.data());
			if (autoRequest) {
				subscription.request(1);
			}
		}

		@Override
		public void onError(Throwable error) {
			completed = true;
		}

		@Override
		public void onComplete() {
			completed = true;
		}

		void request(long n) {
			subscription.request(n);
		}

		void cancel() {
			subscription.cancel();
		}

	}

}
