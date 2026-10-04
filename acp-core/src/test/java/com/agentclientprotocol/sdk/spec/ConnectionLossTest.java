/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every failure that means "the connection is gone" is an {@link AcpConnectionException}, on
 * both session sides: a request waiting when the transport ends, and a request sent after it.
 * The transport's own failure stays the cause.
 */
class ConnectionLossTest {

	private static final Duration WAIT = Duration.ofSeconds(2);

	private static final TypeRef<Object> ANY = new TypeRef<>() {
	};

	@Test
	void aClientRequestWaitingWhenThePeerGoesAwayFailsWithAcpConnectionException() {
		AcpClientSessionTerminationTest.TerminatingTransport transport = new AcpClientSessionTerminationTest.TerminatingTransport();
		AcpClientSession session = new AcpClientSession(Duration.ofMinutes(5), transport, Map.of(), Map.of(),
				Function.identity());
		Mono<Object> pending = session.sendRequest("_x/slow", Map.of(), ANY).cache();
		pending.subscribe(v -> {
		}, e -> {
		});

		IllegalStateException cause = new IllegalStateException("agent process exited with code 0");
		transport.termination.tryEmitError(cause);

		assertThatThrownBy(() -> pending.block(WAIT)).isInstanceOf(AcpConnectionException.class)
			.hasCause(cause)
			.hasMessageContaining("terminated");
	}

	@Test
	void aClientRequestSentAfterThePeerWentAwayFailsWithAcpConnectionException() {
		AcpClientSessionTerminationTest.TerminatingTransport transport = new AcpClientSessionTerminationTest.TerminatingTransport();
		AcpClientSession session = new AcpClientSession(Duration.ofMinutes(5), transport, Map.of(), Map.of(),
				Function.identity());

		IllegalStateException cause = new IllegalStateException("agent process exited with code 0");
		transport.termination.tryEmitError(cause);

		assertThatThrownBy(() -> session.sendRequest("_x/later", Map.of(), ANY).block(WAIT))
			.isInstanceOf(AcpConnectionException.class)
			.hasCause(cause);
		assertThatThrownBy(() -> session.sendNotification("_x/note", Map.of()).block(WAIT))
			.isInstanceOf(AcpConnectionException.class)
			.hasCause(cause);
	}

	@Test
	void aClientRequestSentAfterAPeerCloseFailsWithAcpConnectionException() {
		AcpClientSessionTerminationTest.TerminatingTransport transport = new AcpClientSessionTerminationTest.TerminatingTransport();
		AcpClientSession session = new AcpClientSession(Duration.ofMinutes(5), transport, Map.of(), Map.of(),
				Function.identity());

		transport.termination.tryEmitEmpty();

		assertThatThrownBy(() -> session.sendRequest("_x/later", Map.of(), ANY).block(WAIT))
			.isInstanceOf(AcpConnectionException.class);
	}

	@Test
	void anAgentRequestWaitingWhenTheTransportFailsFailsWithAcpConnectionException() {
		FailingLaterAgentTransport transport = new FailingLaterAgentTransport();
		AcpAgentSession session = new AcpAgentSession(Duration.ofMinutes(5), transport, Map.of(), Map.of());
		Mono<Object> pending = session.sendRequest("_x/slow", Map.of(), ANY).cache();
		pending.subscribe(v -> {
		}, e -> {
		});

		IllegalStateException cause = new IllegalStateException("client went away");
		transport.started.tryEmitError(cause);

		assertThatThrownBy(() -> pending.block(WAIT)).isInstanceOf(AcpConnectionException.class).hasCause(cause);
		assertThatThrownBy(() -> session.sendRequest("_x/later", Map.of(), ANY).block(WAIT))
			.isInstanceOf(AcpConnectionException.class)
			.hasCause(cause);
	}

	@Test
	void anAgentRequestWaitingWhenTheSessionClosesFailsWithAcpConnectionException() {
		FailingLaterAgentTransport transport = new FailingLaterAgentTransport();
		AcpAgentSession session = new AcpAgentSession(Duration.ofMinutes(5), transport, Map.of(), Map.of());
		Mono<Object> pending = session.sendRequest("_x/slow", Map.of(), ANY).cache();
		pending.subscribe(v -> {
		}, e -> {
		});

		session.close();

		assertThatThrownBy(() -> pending.block(WAIT)).isInstanceOf(AcpConnectionException.class);
	}

	/** Starts, accepts every message, answers none, and fails when told. */
	static class FailingLaterAgentTransport implements AcpAgentTransport {

		final Sinks.One<Void> started = Sinks.one();

		@Override
		public Mono<Void> start(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
			return started.asMono();
		}

		@Override
		public Mono<Void> awaitTermination() {
			return Mono.never();
		}

		@Override
		public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
			return Mono.empty();
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

}
