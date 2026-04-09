/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test suite for {@link AcpAsyncClient} verifying the high-level client API methods.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
class AcpAsyncClientTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final AcpSchema.InitializeResponse MOCK_INIT_RESPONSE = new AcpSchema.InitializeResponse(1,
			new AcpSchema.AgentCapabilities(null, null, null), List.of());

	@Test
	void testConstructorWithNullSession() {
		var transport = new MockAcpClientTransport();
		assertThatThrownBy(() -> new AcpAsyncClient(null, transport)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Session must not be null");
	}

	@Test
	void testConstructorWithNullTransport() {
		var transport = new MockAcpClientTransport();
		var session = new com.agentclientprotocol.sdk.spec.AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(),
				Function.identity());
		assertThatThrownBy(() -> new AcpAsyncClient(session, null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Transport must not be null");
	}

	@Test
	void testBuilderWithNullTransport() {
		assertThatThrownBy(() -> AcpClient.async(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Transport must not be null");
	}

	@Test
	void testBuilderWithNullTimeout() {
		var transport = new MockAcpClientTransport();
		assertThatThrownBy(() -> AcpClient.async(transport).requestTimeout(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Request timeout must not be null");
	}

	@Test
	void testInitialize() {
		// Set up mock transport that responds to initialize
		var transport = createMockTransportWithInitialize();

		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		AcpSchema.InitializeRequest request = new AcpSchema.InitializeRequest(1,
				new AcpSchema.ClientCapabilities(null, null));

		StepVerifier.create(client.initialize(request)).consumeNextWith(response -> {
			assertThat(response).isNotNull();
			assertThat(response.protocolVersion()).isEqualTo(1);
			assertThat(response.agentCapabilities()).isNotNull();
		}).verifyComplete();

		client.close();
	}

	@Test
	void testInitializeWithNullRequest() {
		var transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		assertThatThrownBy(() -> client.initialize(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Initialize request must not be null");

		client.close();
	}

	@Test
	void testAuthenticate() {
		var transport = createMockTransportWithAuthMethods();

		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		AcpSchema.AuthenticateRequest authRequest = new AcpSchema.AuthenticateRequest("bearer");

		StepVerifier.create(client.authenticate(authRequest)).consumeNextWith(response -> {
			assertThat(response).isNotNull();
		}).verifyComplete();

		client.close();
	}

	@Test
	void testAuthenticateWithNullRequest() {
		var transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		assertThatThrownBy(() -> client.authenticate(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Authenticate request must not be null");

		client.close();
	}

	@Test
	void testNewSession() {
		var transport = createMockTransportWithSession();

		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		AcpSchema.NewSessionRequest sessionRequest = new AcpSchema.NewSessionRequest("/workspace", List.of());

		StepVerifier.create(client.newSession(sessionRequest)).consumeNextWith(response -> {
			assertThat(response).isNotNull();
			assertThat(response.sessionId()).isNotNull().isNotEmpty();
		}).verifyComplete();

		client.close();
	}

	@Test
	void testNewSessionWithNullRequest() {
		var transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		assertThatThrownBy(() -> client.newSession(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("New session request must not be null");

		client.close();
	}

	@Test
	void testLoadSession() {
		var transport = createMockTransportWithLoadSession();

		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		AcpSchema.LoadSessionRequest loadRequest = new AcpSchema.LoadSessionRequest("session-123", null, List.of());

		StepVerifier.create(client.loadSession(loadRequest)).consumeNextWith(response -> {
			assertThat(response).isNotNull();
		}).verifyComplete();

		client.close();
	}

	@Test
	void testLoadSessionWithNullRequest() {
		var transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		assertThatThrownBy(() -> client.loadSession(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Load session request must not be null");

		client.close();
	}

	@Test
	void testSetSessionMode() {
		var transport = createMockTransportWithSetMode();

		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		AcpSchema.SetSessionModeRequest modeRequest = new AcpSchema.SetSessionModeRequest("session-123", "code");

		StepVerifier.create(client.setSessionMode(modeRequest)).consumeNextWith(response -> {
			assertThat(response).isNotNull();
		}).verifyComplete();

		client.close();
	}

	@Test
	void testSetSessionModeWithNullRequest() {
		var transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		assertThatThrownBy(() -> client.setSessionMode(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Set session mode request must not be null");

		client.close();
	}

	@Test
	void testPrompt() {
		var transport = createMockTransportWithPrompt();

		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		AcpSchema.PromptRequest promptRequest = new AcpSchema.PromptRequest("session-123",
				List.of(new AcpSchema.TextContent("Fix the bug")));

		StepVerifier.create(client.prompt(promptRequest)).consumeNextWith(response -> {
			assertThat(response).isNotNull();
			assertThat(response.stopReason()).isNotNull();
		}).verifyComplete();

		client.close();
	}

	@Test
	void testPromptWithNullRequest() {
		var transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		assertThatThrownBy(() -> client.prompt(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Prompt request must not be null");

		client.close();
	}

	@Test
	void testCancel() {
		var transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		AcpSchema.CancelNotification cancelNotification = new AcpSchema.CancelNotification("session-123");

		StepVerifier.create(client.cancel(cancelNotification)).verifyComplete();

		// Verify notification was sent
		assertThat(transport.getLastSentMessage()).isInstanceOf(AcpSchema.JSONRPCNotification.class);
		AcpSchema.JSONRPCNotification notification = transport.getLastSentMessageAsNotification();
		assertThat(notification.method()).isEqualTo(AcpSchema.METHOD_SESSION_CANCEL);

		client.close();
	}

	@Test
	void testCancelWithNullNotification() {
		var transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		assertThatThrownBy(() -> client.cancel(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Cancel notification must not be null");

		client.close();
	}

	@Test
	void testSessionUpdateNotificationHandling() {
		Sinks.One<AcpSchema.SessionNotification> receivedNotification = Sinks.one();

		var transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport)
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(notification -> {
				receivedNotification.tryEmitValue(notification);
				return Mono.empty();
			})
			.build();

		// Simulate incoming session update notification
		AcpSchema.SessionNotification sessionUpdate = new AcpSchema.SessionNotification("session-123",
				new AcpSchema.UserMessageChunk("userMessage", new AcpSchema.TextContent("Hello")));
		AcpSchema.JSONRPCNotification notification = new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
				AcpSchema.METHOD_SESSION_UPDATE, sessionUpdate);

		transport.simulateIncomingMessage(notification);

		// Verify the consumer received the notification
		AcpSchema.SessionNotification received = receivedNotification.asMono().block(Duration.ofSeconds(1));
		assertThat(received).isNotNull();
		assertThat(received.sessionId()).isEqualTo("session-123");

		client.close();
	}

	@Test
	void testGracefulShutdown() {
		var transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		StepVerifier.create(client.closeGracefully()).verifyComplete();
	}

	// Helper methods to create mock transports with auto-responses

	private MockAcpClientTransport createMockTransportWithInitialize() {
		return new MockAcpClientTransport((t, msg) -> {
			if (msg instanceof AcpSchema.JSONRPCRequest request
					&& AcpSchema.METHOD_INITIALIZE.equals(request.method())) {
				t.simulateIncomingMessage(
						new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), MOCK_INIT_RESPONSE, null));
			}
		});
	}

	private MockAcpClientTransport createMockTransportWithAuthMethods() {
		return new MockAcpClientTransport((t, msg) -> {
			if (msg instanceof AcpSchema.JSONRPCRequest request) {
				if (AcpSchema.METHOD_AUTHENTICATE.equals(request.method())) {
					AcpSchema.AuthenticateResponse authResponse = new AcpSchema.AuthenticateResponse();
					t.simulateIncomingMessage(
							new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), authResponse, null));
				}
			}
		});
	}

	private MockAcpClientTransport createMockTransportWithSession() {
		return new MockAcpClientTransport((t, msg) -> {
			if (msg instanceof AcpSchema.JSONRPCRequest request) {
				if (AcpSchema.METHOD_SESSION_NEW.equals(request.method())) {
					AcpSchema.NewSessionResponse sessionResponse = new AcpSchema.NewSessionResponse("session-abc123", null,
							null);
					t.simulateIncomingMessage(
							new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), sessionResponse, null));
				}
			}
		});
	}

	private MockAcpClientTransport createMockTransportWithLoadSession() {
		return new MockAcpClientTransport((t, msg) -> {
			if (msg instanceof AcpSchema.JSONRPCRequest request) {
				if (AcpSchema.METHOD_SESSION_LOAD.equals(request.method())) {
					AcpSchema.LoadSessionResponse loadResponse = new AcpSchema.LoadSessionResponse(null, null);
					t.simulateIncomingMessage(
							new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), loadResponse, null));
				}
			}
		});
	}

	private MockAcpClientTransport createMockTransportWithSetMode() {
		return new MockAcpClientTransport((t, msg) -> {
			if (msg instanceof AcpSchema.JSONRPCRequest request) {
				if (AcpSchema.METHOD_SESSION_SET_MODE.equals(request.method())) {
					AcpSchema.SetSessionModeResponse modeResponse = new AcpSchema.SetSessionModeResponse();
					t.simulateIncomingMessage(
							new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), modeResponse, null));
				}
			}
		});
	}

	private MockAcpClientTransport createMockTransportWithPrompt() {
		return new MockAcpClientTransport((t, msg) -> {
			if (msg instanceof AcpSchema.JSONRPCRequest request) {
				if (AcpSchema.METHOD_SESSION_PROMPT.equals(request.method())) {
					AcpSchema.PromptResponse promptResponse = new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN);
					t.simulateIncomingMessage(
							new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), promptResponse, null));
				}
			}
		});
	}

}
