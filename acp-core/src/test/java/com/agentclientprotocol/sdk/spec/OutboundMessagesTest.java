/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundMessagesTest {

	/** A peer that accepts every message and never answers. */
	static class SilentTransport implements AcpTransport {

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
			return Mono.empty();
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			throw new UnsupportedOperationException();
		}

	}

	/** Found by PendingResponsesLincheckTest: a timed-out request stayed pending until the session ended. */
	@Test
	void aTimedOutRequestIsNoLongerPending() {
		OutboundMessages outbound = new OutboundMessages(new SilentTransport(), Duration.ofMillis(50), () -> null,
				IllegalStateException::new, "agent");

		StepVerifier.create(outbound.sendRequest("fs/read_text_file", "params", new TypeRef<String>() {
		})).expectError(TimeoutException.class).verify(Duration.ofSeconds(5));

		assertThat(outbound.pendingCount()).isZero();
	}

	@Test
	void aCancelledRequestIsNoLongerPending() {
		OutboundMessages outbound = new OutboundMessages(new SilentTransport(), Duration.ofSeconds(30), () -> null,
				IllegalStateException::new, "agent");

		StepVerifier.create(outbound.sendRequest("fs/read_text_file", "params", new TypeRef<String>() {
		})).expectSubscription().thenCancel().verify(Duration.ofSeconds(5));

		assertThat(outbound.pendingCount()).isZero();
	}

}
