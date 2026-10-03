/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AcpAgent} factory and the high-level agent API.
 */
class AcpAgentTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void asyncBuilderCreatesAgent() {
		var transportPair = InMemoryTransportPair.create();
		try {
			AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
				.requestTimeout(Duration.ofSeconds(30))
				.initializeHandler(
						request -> Mono.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(),
								List.of())))
				.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("session-1", null, null)))
				.promptHandler((request, updater) -> Mono
					.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)))
				.build();

			assertThat(agent).isNotNull();
			assertThat(agent).isInstanceOf(DefaultAcpAsyncAgent.class);

			agent.close();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void callsToTheClientBeforeStartFailWithIllegalState() {
		var transportPair = InMemoryTransportPair.create();
		try {
			AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport()).promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn())).build();
			List<Mono<?>> calls = List.of(
					agent.sendSessionUpdate("s", new AcpSchema.AgentMessageChunk(new AcpSchema.TextContent("hi"))),
					agent.readTextFile(new AcpSchema.ReadTextFileRequest("s", "/f", null, null)),
					agent.writeTextFile(new AcpSchema.WriteTextFileRequest("s", "/f", "x")),
					agent.createTerminal(new AcpSchema.CreateTerminalRequest("s", "ls", null, null, null, null)),
					agent.getTerminalOutput(new AcpSchema.TerminalOutputRequest("s", "t")),
					agent.releaseTerminal(new AcpSchema.ReleaseTerminalRequest("s", "t")),
					agent.waitForTerminalExit(new AcpSchema.WaitForTerminalExitRequest("s", "t")),
					agent.killTerminal(new AcpSchema.KillTerminalCommandRequest("s", "t")),
					agent.completeElicitation(new AcpSchema.CompleteElicitationNotification("e")));
			for (Mono<?> call : calls) {
				assertThatThrownBy(() -> call.block(TIMEOUT)).isInstanceOf(IllegalStateException.class)
					.hasMessage("Agent not started");
			}
			agent.closeGracefully().block(TIMEOUT);
			agent.close();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void syncBuilderCreatesAgent() {
		var transportPair = InMemoryTransportPair.create();
		try {
			// Sync builder now uses sync handler interfaces (plain return values, no Mono)
			AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
				.requestTimeout(Duration.ofSeconds(30))
				.initializeHandler(
						request -> new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of()))
				.newSessionHandler(request -> new AcpSchema.NewSessionResponse("session-1", null, null))
				.promptHandler((request, updater) -> new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN))
				.build();

			assertThat(agent).isNotNull();
			assertThat(agent.async()).isNotNull();

			agent.close();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void agentHandlesInitializeRequest() throws Exception {
		var transportPair = InMemoryTransportPair.create();
		try {
			AtomicReference<AcpSchema.InitializeRequest> receivedRequest = new AtomicReference<>();

			AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
				.initializeHandler(request -> {
					receivedRequest.set(request);
					return Mono.just(new AcpSchema.InitializeResponse(1,
							new AcpSchema.AgentCapabilities(true, null, null), List.of()));
				})
				.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn())).build();

			agent.start().block(TIMEOUT);
			Thread.sleep(100);

			// Set up client
			CountDownLatch responseLatch = new CountDownLatch(1);
			AtomicReference<AcpSchema.JSONRPCMessage> response = new AtomicReference<>();

			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> {
				response.set(msg);
				responseLatch.countDown();
			}).then(Mono.empty())).subscribe();

			Thread.sleep(50);

			// Send initialize request
			AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "1",
					AcpSchema.METHOD_INITIALIZE, new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()));

			transportPair.clientTransport().sendMessage(request).block(TIMEOUT);

			assertThat(responseLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(response.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);
			AcpSchema.JSONRPCResponse jsonResponse = (AcpSchema.JSONRPCResponse) response.get();
			assertThat(jsonResponse.error()).isNull();
			assertThat(receivedRequest.get()).isNotNull();
			assertThat(receivedRequest.get().protocolVersion()).isEqualTo(1);

			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void agentHandlesNewSessionRequest() throws Exception {
		var transportPair = InMemoryTransportPair.create();
		try {
			AtomicReference<AcpSchema.NewSessionRequest> receivedRequest = new AtomicReference<>();

			AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
				.newSessionHandler(request -> {
					receivedRequest.set(request);
					return Mono.just(new AcpSchema.NewSessionResponse("session-123", null, null));
				})
				.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn())).build();

			agent.start().block(TIMEOUT);
			Thread.sleep(100);

			// Set up client
			CountDownLatch responseLatch = new CountDownLatch(1);
			AtomicReference<AcpSchema.JSONRPCMessage> response = new AtomicReference<>();

			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> {
				response.set(msg);
				responseLatch.countDown();
			}).then(Mono.empty())).subscribe();

			Thread.sleep(50);

			// Send new session request
			AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "1",
					AcpSchema.METHOD_SESSION_NEW, new AcpSchema.NewSessionRequest("/workspace", List.of()));

			transportPair.clientTransport().sendMessage(request).block(TIMEOUT);

			assertThat(responseLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(response.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);
			AcpSchema.JSONRPCResponse jsonResponse = (AcpSchema.JSONRPCResponse) response.get();
			assertThat(jsonResponse.error()).isNull();
			assertThat(receivedRequest.get()).isNotNull();
			assertThat(receivedRequest.get().cwd()).isEqualTo("/workspace");

			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void agentHandlesPromptRequest() throws Exception {
		var transportPair = InMemoryTransportPair.create();
		try {
			AtomicReference<AcpSchema.PromptRequest> receivedRequest = new AtomicReference<>();

			AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
				.promptHandler((request, updater) -> {
					receivedRequest.set(request);
					return Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
				})
				.build();

			agent.start().block(TIMEOUT);
			Thread.sleep(100);

			// Set up client
			CountDownLatch responseLatch = new CountDownLatch(1);
			AtomicReference<AcpSchema.JSONRPCMessage> response = new AtomicReference<>();

			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> {
				response.set(msg);
				responseLatch.countDown();
			}).then(Mono.empty())).subscribe();

			Thread.sleep(50);

			// Send prompt request
			AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "1",
					AcpSchema.METHOD_SESSION_PROMPT,
					new AcpSchema.PromptRequest("session-1", List.of(new AcpSchema.TextContent("Hello"))));

			transportPair.clientTransport().sendMessage(request).block(TIMEOUT);

			assertThat(responseLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(response.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);
			AcpSchema.JSONRPCResponse jsonResponse = (AcpSchema.JSONRPCResponse) response.get();
			assertThat(jsonResponse.error()).isNull();
			assertThat(receivedRequest.get()).isNotNull();
			assertThat(receivedRequest.get().sessionId()).isEqualTo("session-1");

			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void agentHandlesCancelNotification() throws Exception {
		var transportPair = InMemoryTransportPair.create();
		try {
			AtomicReference<AcpSchema.CancelNotification> receivedNotification = new AtomicReference<>();
			CountDownLatch notificationLatch = new CountDownLatch(1);

			AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
				.cancelHandler(notification -> {
					receivedNotification.set(notification);
					notificationLatch.countDown();
					return Mono.empty();
				})
				.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn())).build();

			agent.start().block(TIMEOUT);
			Thread.sleep(100);

			// Set up client
			transportPair.clientTransport().connect(mono -> mono.then(Mono.empty())).subscribe();

			Thread.sleep(50);

			// Send cancel notification
			AcpSchema.JSONRPCNotification notification = new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
					AcpSchema.METHOD_SESSION_CANCEL, new AcpSchema.CancelNotification("session-1"));

			transportPair.clientTransport().sendMessage(notification).block(TIMEOUT);

			assertThat(notificationLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(receivedNotification.get()).isNotNull();
			assertThat(receivedNotification.get().sessionId()).isEqualTo("session-1");

			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void syncAgentDelegatesAsyncOperations() {
		var transportPair = InMemoryTransportPair.create();
		try {
			AtomicReference<AcpSchema.InitializeRequest> receivedRequest = new AtomicReference<>();

			// Sync builder now uses sync handler interfaces (plain return values, no Mono)
			AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
				.initializeHandler(request -> {
					receivedRequest.set(request);
					return new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of());
				})
				.promptHandler((request, context) -> AcpSchema.PromptResponse.endTurn()).build();

			// Verify async() returns the underlying agent
			assertThat(agent.async()).isNotNull();

			agent.close();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void agentCloseGracefullyCompletesWithoutError() throws Exception {
		var transportPair = InMemoryTransportPair.create();

		AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport()).promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn())).build();

		agent.start().block(TIMEOUT);
		Thread.sleep(100);

		// Should complete without error
		agent.closeGracefully().block(TIMEOUT);
		transportPair.closeGracefully().block(TIMEOUT);
	}

	@Test
	void syncAgentGetClientCapabilitiesReturnsNullBeforeInitialization() {
		var transportPair = InMemoryTransportPair.create();
		try {
			AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
				.initializeHandler(
						request -> new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of()))
				.promptHandler((request, context) -> AcpSchema.PromptResponse.endTurn())
				.build();

			// Before initialization, capabilities should be null
			assertThat(agent.getClientCapabilities()).isNull();

			agent.close();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void asyncAgentGetClientCapabilitiesReturnsNullBeforeInitialization() {
		var transportPair = InMemoryTransportPair.create();
		try {
			AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
				.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn()))
				.build();

			// Before initialization, capabilities should be null
			assertThat(agent.getClientCapabilities()).isNull();

			agent.close();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

}
