/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client session's error, close and failure paths: a pending request fails at once
 * when the session can no longer answer it, a failing notification handler does not stop
 * the ones after it, and an unsupported agent request is answered with a precise error.
 */
class AcpClientSessionLifecycleTest {

	/** Long enough that a request which is not failed at once times out the assertion first. */
	private static final Duration LONG_TIMEOUT = Duration.ofMinutes(5);

	private static final Duration WAIT = Duration.ofSeconds(2);

	private static final TypeRef<AcpSchema.InitializeResponse> INITIALIZE = new TypeRef<>() {
	};

	/** A transport whose connection fails when the test says so. */
	static class FailingConnectTransport implements AcpClientTransport {

		final Sinks.One<Void> connection = Sinks.one();

		@Override
		public Mono<Void> connect(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
			return this.connection.asMono();
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
			throw new UnsupportedOperationException();
		}

	}

	private static Mono<AcpSchema.InitializeResponse> pendingInitialize(AcpClientSession session) {
		Mono<AcpSchema.InitializeResponse> pending = session.sendRequest(AcpSchema.METHOD_INITIALIZE,
				new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()), INITIALIZE)
			.cache();
		pending.subscribe(v -> {
		}, e -> {
		});
		return pending;
	}

	@Test
	void constructorRejectsMissingArguments() {
		var transport = new MockAcpClientTransport();
		assertThatThrownBy(() -> new AcpClientSession(null, transport, Map.of(), Map.of(), Function.identity()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AcpClientSession(LONG_TIMEOUT, null, Map.of(), Map.of(), Function.identity()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AcpClientSession(LONG_TIMEOUT, transport, null, Map.of(), Function.identity()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AcpClientSession(LONG_TIMEOUT, transport, Map.of(), null, Function.identity()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void connectionThatFailsLaterFailsPendingAndLaterRequestsAtOnce() {
		var transport = new FailingConnectTransport();
		var session = new AcpClientSession(LONG_TIMEOUT, transport, Map.of(), Map.of(), Function.identity());
		Mono<AcpSchema.InitializeResponse> pending = pendingInitialize(session);

		IllegalStateException cause = new IllegalStateException("socket reset");
		transport.connection.tryEmitError(cause);

		assertThatThrownBy(() -> pending.block(WAIT)).hasMessage("ACP session with agent terminated").hasCause(cause);
		assertThatThrownBy(() -> pendingInitialize(session).block(WAIT)).isInstanceOf(IllegalStateException.class)
			.hasMessage("ACP client transport is not connected: socket reset");
	}

	@Test
	void requestAfterThePeerClosedFailsAtOnce() {
		var transport = new AcpClientSessionTerminationTest.TerminatingTransport();
		var session = new AcpClientSession(LONG_TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		transport.termination.tryEmitEmpty();

		assertThatThrownBy(() -> pendingInitialize(session).block(WAIT)).isInstanceOf(IllegalStateException.class)
			.hasMessage("ACP client transport is not connected: ACP client transport terminated");
	}

	@Test
	void closeFailsPendingRequestsAtOnce() {
		var session = new AcpClientSession(LONG_TIMEOUT, new MockAcpClientTransport(), Map.of(), Map.of(),
				Function.identity());
		Mono<AcpSchema.InitializeResponse> pending = pendingInitialize(session);

		session.close();

		assertThatThrownBy(() -> pending.block(WAIT)).hasMessage("ACP session with agent terminated");
	}

	@Test
	void gracefulCloseFailsPendingRequestsAtOnce() {
		var session = new AcpClientSession(LONG_TIMEOUT, new MockAcpClientTransport(), Map.of(), Map.of(),
				Function.identity());
		Mono<AcpSchema.InitializeResponse> pending = pendingInitialize(session);

		session.closeGracefully().block(WAIT);

		assertThatThrownBy(() -> pending.block(WAIT)).hasMessage("ACP session with agent terminated");
	}

	@Test
	void failingNotificationHandlerDoesNotStopLaterNotifications() {
		var transport = new MockAcpClientTransport();
		List<Object> delivered = new CopyOnWriteArrayList<>();
		AcpClientSession.NotificationHandler handler = params -> {
			delivered.add(params);
			return delivered.size() == 1 ? Mono.error(new IllegalStateException("handler failed")) : Mono.empty();
		};
		var session = new AcpClientSession(LONG_TIMEOUT, transport, Map.of(),
				Map.of(AcpSchema.METHOD_SESSION_UPDATE, handler), Function.identity());

		transport.simulateIncomingMessage(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
				AcpSchema.METHOD_SESSION_UPDATE, Map.of("n", 1)));
		transport.simulateIncomingMessage(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
				AcpSchema.METHOD_SESSION_UPDATE, Map.of("n", 2)));
		session.closeGracefully().block(WAIT);

		assertThat(delivered).containsExactly(Map.of("n", 1), Map.of("n", 2));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|',
			value = { "fs/read_text_file         | File system read not supported   | fs.readTextFile capability",
					"fs/write_text_file        | File system write not supported  | fs.writeTextFile capability",
					"session/request_permission| Permission request not supported | requestPermissionHandler",
					"terminal/create           | Terminal not supported           | terminal capability",
					"terminal/output           | Terminal not supported           | terminal capability",
					"terminal/release          | Terminal not supported           | terminal capability",
					"terminal/wait_for_exit    | Terminal not supported           | terminal capability",
					"terminal/kill             | Terminal not supported           | terminal capability" })
	void unsupportedAgentRequestIsAnsweredWithMethodNotFound(String method, String message, String reason) {
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(LONG_TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		transport.simulateIncomingMessage(
				new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "req-1", method, Map.of()));

		var response = (AcpSchema.JSONRPCResponse) transport.getLastSentMessage();
		assertThat(response.id()).isEqualTo("req-1");
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(AcpErrorCodes.METHOD_NOT_FOUND);
		assertThat(response.error().message()).isEqualTo(message);
		assertThat(response.error().data()).isInstanceOfSatisfying(Map.class,
				data -> assertThat(data.get("reason").toString()).contains(reason));
		session.close();
	}

	@Test
	void unknownAgentRequestIsAnsweredWithMethodNotFoundNamingIt() {
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(LONG_TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		transport.simulateIncomingMessage(
				new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "req-1", "_vendor/thing", null));

		var response = (AcpSchema.JSONRPCResponse) transport.getLastSentMessage();
		assertThat(response.error().code()).isEqualTo(AcpErrorCodes.METHOD_NOT_FOUND);
		assertThat(response.error().message()).isEqualTo("Method not found: _vendor/thing");
		assertThat(response.error().data()).isNull();
		session.close();
	}

}
