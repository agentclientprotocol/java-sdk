/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.json.TypeRef;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The request/response plumbing both session sides share: which requests are waiting, what
 * a response or a failure does to them, and what is never sent.
 */
class OutboundMessagesTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final TypeRef<String> STRING = new TypeRef<>() {
	};

	/** Records what is sent; delivers nothing. */
	static class RecordingTransport implements AcpTransport {

		final List<AcpSchema.JSONRPCMessage> sent = new CopyOnWriteArrayList<>();

		@Override
		public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
			this.sent.add(message);
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return (T) data;
		}

		AcpSchema.JSONRPCRequest lastRequest() {
			return (AcpSchema.JSONRPCRequest) this.sent.get(this.sent.size() - 1);
		}

	}

	private final RecordingTransport transport = new RecordingTransport();

	private final AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();

	private OutboundMessages outbound(Duration requestTimeout) {
		return new OutboundMessages(this.transport, requestTimeout, this.failure::get,
				cause -> new IllegalStateException("not connected: " + cause.getMessage(), cause), "agent");
	}

	private static AcpSchema.JSONRPCResponse success(@Nullable Object id, Object result) {
		return new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, id, result, null);
	}

	@Test
	void timedOutRequestNoLongerWaitsForAResponse() {
		OutboundMessages outbound = outbound(Duration.ofMillis(50));

		StepVerifier.create(outbound.sendRequest("slow", "params", STRING))
			.expectError(TimeoutException.class)
			.verify(TIMEOUT);

		assertThat(outbound.pendingRequests()).as("a timed-out request must not stay registered").isZero();
	}

	@Test
	void cancelledRequestNoLongerWaitsForAResponse() {
		OutboundMessages outbound = outbound(TIMEOUT);

		Disposable subscription = outbound.sendRequest("slow", "params", STRING).subscribe();
		assertThat(outbound.pendingRequests()).isEqualTo(1);

		subscription.dispose();

		assertThat(outbound.pendingRequests()).as("a cancelled request must not stay registered").isZero();
	}

	@Test
	void answeredRequestNoLongerWaitsForAResponse() {
		OutboundMessages outbound = outbound(TIMEOUT);

		StepVerifier.create(outbound.sendRequest("echo", "params", STRING))
			.then(() -> outbound.complete(success(this.transport.lastRequest().id(), "answer")))
			.expectNext("answer")
			.verifyComplete();

		assertThat(outbound.pendingRequests()).isZero();
	}

	@Test
	void requestAfterTheTransportFailedIsRefusedWithoutBeingSent() {
		OutboundMessages outbound = outbound(TIMEOUT);
		this.failure.set(new IllegalStateException("peer gone"));

		assertThatThrownBy(() -> outbound.sendRequest("echo", "params", STRING).block(TIMEOUT))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("not connected: peer gone");

		assertThat(this.transport.sent).as("nothing goes on the wire of a failed transport").isEmpty();
		assertThat(outbound.pendingRequests()).isZero();
	}

	@Test
	void failureRecordedWhileRegisteringRefusesTheRequestWithoutSendingIt() {
		// The failure lands between the first check and the registration: the second
		// check must catch it, or the request waits out its timeout on a dead transport.
		IllegalStateException late = new IllegalStateException("peer gone");
		AtomicInteger checks = new AtomicInteger();
		OutboundMessages outbound = new OutboundMessages(this.transport, TIMEOUT,
				() -> checks.getAndIncrement() == 0 ? null : late,
				cause -> new IllegalStateException("not connected: " + cause.getMessage(), cause), "agent");

		assertThatThrownBy(() -> outbound.sendRequest("echo", "params", STRING).block(Duration.ofSeconds(2)))
			.hasMessage("not connected: peer gone");

		assertThat(this.transport.sent).isEmpty();
		assertThat(outbound.pendingRequests()).isZero();
	}

	@Test
	void requestTheTransportFailsToSendFailsWithThatError() {
		IllegalStateException sendFailure = new IllegalStateException("broken pipe");
		RecordingTransport failingTransport = new RecordingTransport() {
			@Override
			public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
				return Mono.error(sendFailure);
			}
		};
		OutboundMessages outbound = new OutboundMessages(failingTransport, TIMEOUT, () -> null,
				IllegalStateException::new, "agent");

		assertThatThrownBy(() -> outbound.sendRequest("echo", "params", STRING).block(Duration.ofSeconds(2)))
			.isSameAs(sendFailure);
		assertThat(outbound.pendingRequests()).isZero();
	}

	@Test
	void notificationAfterTheTransportFailedIsRefusedWithoutBeingSent() {
		OutboundMessages outbound = outbound(TIMEOUT);
		this.failure.set(new IllegalStateException("peer gone"));

		assertThatThrownBy(() -> outbound.sendNotification("note", Map.of()).block(TIMEOUT))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("not connected: peer gone");

		assertThat(this.transport.sent).isEmpty();
	}

	@Test
	void responseWithoutIdOrForAnUnknownIdLeavesPendingRequestsAlone() {
		OutboundMessages outbound = outbound(TIMEOUT);

		StepVerifier.create(outbound.sendRequest("echo", "params", STRING)).then(() -> {
			outbound.complete(success(null, "stray"));
			outbound.complete(success("no-such-request", "stray"));
			assertThat(outbound.pendingRequests()).isEqualTo(1);
			outbound.complete(success(this.transport.lastRequest().id(), "answer"));
		}).expectNext("answer").verifyComplete();
	}

	@Test
	void voidRequestCompletesEmptyWhateverTheResult() {
		OutboundMessages outbound = outbound(TIMEOUT);

		StepVerifier.create(outbound.sendRequest("fire", "params", new TypeRef<Void>() {
		})).then(() -> outbound.complete(success(this.transport.lastRequest().id(), Map.of())))
			.verifyComplete();
	}

	@Test
	void dismissFailsEveryPendingRequestNamingThePeerAndCause() {
		OutboundMessages outbound = outbound(TIMEOUT);
		IllegalStateException cause = new IllegalStateException("stream closed");

		Mono<String> first = outbound.sendRequest("a", "params", STRING).cache();
		Mono<String> second = outbound.sendRequest("b", "params", STRING).cache();
		first.subscribe(v -> {
		}, e -> {
		});
		second.subscribe(v -> {
		}, e -> {
		});
		assertThat(outbound.pendingRequests()).isEqualTo(2);

		outbound.dismissPending(cause);

		for (Mono<String> request : List.of(first, second)) {
			assertThatThrownBy(() -> request.block(TIMEOUT)).hasMessage("ACP session with agent terminated")
				.hasCause(cause);
		}
		assertThat(outbound.pendingRequests()).isZero();
	}

}
