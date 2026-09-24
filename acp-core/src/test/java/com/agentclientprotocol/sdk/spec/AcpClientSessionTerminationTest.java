/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A transport that terminates (peer gone, stream failed for good) must fail the session's
 * pending requests at once, and every later request, instead of letting each one wait out
 * the request timeout.
 */
class AcpClientSessionTerminationTest {

	/** Accepts every message, answers none, and terminates when told. */
	static class TerminatingTransport implements AcpClientTransport {

		final Sinks.One<Void> termination = Sinks.one();

		@Override
		public Mono<Void> connect(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> awaitTermination() {
			return termination.asMono();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return null;
		}

	}

	@Test
	void transportFailureFailsPendingAndLaterRequestsImmediately() {
		TerminatingTransport transport = new TerminatingTransport();
		// A long timeout: if termination is not surfaced, the assertions below time out first.
		AcpClientSession session = new AcpClientSession(Duration.ofMinutes(5), transport, Map.of(), Map.of(),
				Function.identity());

		Mono<AcpSchema.InitializeResponse> pending = session
			.sendRequest(AcpSchema.METHOD_INITIALIZE, null, new TypeRef<AcpSchema.InitializeResponse>() {
			})
			.cache();
		pending.subscribe(v -> {
		}, e -> {
		});

		transport.termination.tryEmitError(new IllegalStateException("SSE stream closed unexpectedly"));

		assertThatThrownBy(() -> pending.block(Duration.ofSeconds(2))).hasMessageContaining("terminated");
		assertThatThrownBy(() -> session
			.sendRequest(AcpSchema.METHOD_SESSION_NEW, null, new TypeRef<AcpSchema.NewSessionResponse>() {
			})
			.block(Duration.ofSeconds(2))).hasMessageContaining("SSE stream closed unexpectedly");
	}

	@Test
	void peerCloseFailsPendingRequestsImmediately() {
		TerminatingTransport transport = new TerminatingTransport();
		AcpClientSession session = new AcpClientSession(Duration.ofMinutes(5), transport, Map.of(), Map.of(),
				Function.identity());

		Mono<AcpSchema.InitializeResponse> pending = session
			.sendRequest(AcpSchema.METHOD_INITIALIZE, null, new TypeRef<AcpSchema.InitializeResponse>() {
			})
			.cache();
		pending.subscribe(v -> {
		}, e -> {
		});

		transport.termination.tryEmitEmpty();

		assertThatThrownBy(() -> pending.block(Duration.ofSeconds(2))).hasMessageContaining("terminated");
	}

}
