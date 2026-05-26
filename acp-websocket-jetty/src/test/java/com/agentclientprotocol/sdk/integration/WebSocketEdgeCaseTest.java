/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.transport.WebSocketAcpAgentTransport;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WebSocket transport edge case tests.
 *
 * Covers: concurrent prompts, cancel mid-prompt, permission requests,
 * write file requests, large messages, and multiple sequential prompts.
 */
class WebSocketEdgeCaseTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@Test
	void concurrentPromptsRejected() throws Exception {
		int port = findFreePort();
		AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();
		CountDownLatch promptStarted = new CountDownLatch(1);
		CountDownLatch promptRelease = new CountDownLatch(1);

		WebSocketAcpAgentTransport agentTransport = new WebSocketAcpAgentTransport(port, jsonMapper);
		AcpAsyncAgent agent = AcpAgent.async(agentTransport)
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> Mono.just(new AcpSchema.InitializeResponse(
				1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(req -> Mono.just(new AcpSchema.NewSessionResponse("s1", null, null)))
			.promptHandler((req, ctx) -> {
				promptStarted.countDown();
				// Block until released
				try { promptRelease.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
				return Mono.just(AcpSchema.PromptResponse.endTurn());
			})
			.build();

		agent.start().block(TIMEOUT);
		Thread.sleep(300);

		WebSocketAcpClientTransport clientTransport = new WebSocketAcpClientTransport(
			URI.create("ws://localhost:" + port + "/acp"), jsonMapper);
		AcpAsyncClient client = AcpClient.async(clientTransport).requestTimeout(TIMEOUT).build();

		try {
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()))
				.block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

			// Fire first prompt (will block in handler)
			var firstPrompt = client.prompt(new AcpSchema.PromptRequest("s1",
				List.of(new AcpSchema.TextContent("first")))).toFuture();

			// Wait for it to start processing
			assertThat(promptStarted.await(5, TimeUnit.SECONDS)).isTrue();

			// Second prompt should fail (either rejected with "active prompt" or timeout)
			assertThatThrownBy(() ->
				client.prompt(new AcpSchema.PromptRequest("s1",
					List.of(new AcpSchema.TextContent("second")))).block(Duration.ofSeconds(3))
			).isNotNull();

			// Release the first prompt
			promptRelease.countDown();
			var result = firstPrompt.get(5, TimeUnit.SECONDS);
			assertThat(result.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void cancelDuringPromptOverWebSocket() throws Exception {
		int port = findFreePort();
		AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();
		CountDownLatch cancelReceived = new CountDownLatch(1);
		CountDownLatch promptStarted = new CountDownLatch(1);

		WebSocketAcpAgentTransport agentTransport = new WebSocketAcpAgentTransport(port, jsonMapper);
		AcpAsyncAgent agent = AcpAgent.async(agentTransport)
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> Mono.just(new AcpSchema.InitializeResponse(
				1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(req -> Mono.just(new AcpSchema.NewSessionResponse("s1", null, null)))
			.promptHandler((req, ctx) -> {
				promptStarted.countDown();
				// Simulate slow work
				return Mono.delay(Duration.ofSeconds(5))
					.then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED)));
			})
			.cancelHandler(notification -> {
				assertThat(notification.sessionId()).isEqualTo("s1");
				cancelReceived.countDown();
				return Mono.empty();
			})
			.build();

		agent.start().block(TIMEOUT);
		Thread.sleep(300);

		WebSocketAcpClientTransport clientTransport = new WebSocketAcpClientTransport(
			URI.create("ws://localhost:" + port + "/acp"), jsonMapper);
		AcpAsyncClient client = AcpClient.async(clientTransport).requestTimeout(TIMEOUT).build();

		try {
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()))
				.block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

			// Start prompt in background
			client.prompt(new AcpSchema.PromptRequest("s1",
				List.of(new AcpSchema.TextContent("slow work")))).subscribe();

			// Wait for prompt to start, then cancel
			assertThat(promptStarted.await(5, TimeUnit.SECONDS)).isTrue();
			client.cancel(new AcpSchema.CancelNotification("s1")).block(TIMEOUT);

			// Verify cancel was received by the agent
			assertThat(cancelReceived.await(5, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void permissionRequestOverWebSocket() throws Exception {
		int port = findFreePort();
		AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();
		AtomicReference<AcpAsyncAgent> agentRef = new AtomicReference<>();
		AtomicReference<AcpSchema.RequestPermissionResponse> permResponse = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		WebSocketAcpAgentTransport agentTransport = new WebSocketAcpAgentTransport(port, jsonMapper);
		AcpAsyncAgent agent = AcpAgent.async(agentTransport)
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> Mono.just(new AcpSchema.InitializeResponse(
				1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(req -> Mono.just(new AcpSchema.NewSessionResponse("s1", null, null)))
			.promptHandler((req, ctx) -> {
				var toolCall = new AcpSchema.ToolCallUpdate("tool-1", "Delete File",
					AcpSchema.ToolKind.DELETE, AcpSchema.ToolCallStatus.PENDING, null, null, null, null);
				var options = List.of(
					new AcpSchema.PermissionOption("allow", "Allow", AcpSchema.PermissionOptionKind.ALLOW_ONCE),
					new AcpSchema.PermissionOption("deny", "Deny", AcpSchema.PermissionOptionKind.REJECT_ONCE));
				return agentRef.get()
					.requestPermission(new AcpSchema.RequestPermissionRequest("s1", toolCall, options))
					.doOnNext(resp -> { permResponse.set(resp); latch.countDown(); })
					.then(Mono.just(AcpSchema.PromptResponse.endTurn()));
			})
			.build();
		agentRef.set(agent);

		agent.start().block(TIMEOUT);
		Thread.sleep(300);

		WebSocketAcpClientTransport clientTransport = new WebSocketAcpClientTransport(
			URI.create("ws://localhost:" + port + "/acp"), jsonMapper);
		AcpAsyncClient client = AcpClient.async(clientTransport)
			.requestTimeout(TIMEOUT)
			.requestPermissionHandler(req -> {
				assertThat(req.toolCall().title()).isEqualTo("Delete File");
				return Mono.just(new AcpSchema.RequestPermissionResponse(
					new AcpSchema.PermissionSelected("allow")));
			})
			.build();

		try {
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()))
				.block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
			client.prompt(new AcpSchema.PromptRequest("s1",
				List.of(new AcpSchema.TextContent("delete something")))).block(TIMEOUT);

			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(permResponse.get().outcome()).isInstanceOf(AcpSchema.PermissionSelected.class);
			assertThat(((AcpSchema.PermissionSelected) permResponse.get().outcome()).optionId())
				.isEqualTo("allow");
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void writeFileRequestOverWebSocket() throws Exception {
		int port = findFreePort();
		AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();
		AtomicReference<AcpAsyncAgent> agentRef = new AtomicReference<>();
		AtomicReference<String> writtenPath = new AtomicReference<>();
		AtomicReference<String> writtenContent = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		WebSocketAcpAgentTransport agentTransport = new WebSocketAcpAgentTransport(port, jsonMapper);
		AcpAsyncAgent agent = AcpAgent.async(agentTransport)
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> Mono.just(new AcpSchema.InitializeResponse(
				1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(req -> Mono.just(new AcpSchema.NewSessionResponse("s1", null, null)))
			.promptHandler((req, ctx) ->
				agentRef.get()
					.writeTextFile(new AcpSchema.WriteTextFileRequest("s1", "/out.txt", "hello world"))
					.doOnSuccess(v -> latch.countDown())
					.then(Mono.just(AcpSchema.PromptResponse.endTurn())))
			.build();
		agentRef.set(agent);

		agent.start().block(TIMEOUT);
		Thread.sleep(300);

		WebSocketAcpClientTransport clientTransport = new WebSocketAcpClientTransport(
			URI.create("ws://localhost:" + port + "/acp"), jsonMapper);
		AcpAsyncClient client = AcpClient.async(clientTransport)
			.requestTimeout(TIMEOUT)
			.writeTextFileHandler(req -> {
				writtenPath.set(req.path());
				writtenContent.set(req.content());
				return Mono.just(new AcpSchema.WriteTextFileResponse());
			})
			.build();

		try {
			var caps = new AcpSchema.ClientCapabilities(
				new AcpSchema.FileSystemCapability(false, true), false);
			client.initialize(new AcpSchema.InitializeRequest(1, caps)).block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
			client.prompt(new AcpSchema.PromptRequest("s1",
				List.of(new AcpSchema.TextContent("write a file")))).block(TIMEOUT);

			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(writtenPath.get()).isEqualTo("/out.txt");
			assertThat(writtenContent.get()).isEqualTo("hello world");
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void largeMessageOverWebSocket() throws Exception {
		int port = findFreePort();
		AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();

		// Build a large prompt (~500KB) to test beyond the old 64KB default
		String largeText = "x".repeat(500_000);

		WebSocketAcpAgentTransport agentTransport = new WebSocketAcpAgentTransport(port, jsonMapper);
		AcpAsyncAgent agent = AcpAgent.async(agentTransport)
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> Mono.just(new AcpSchema.InitializeResponse(
				1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(req -> Mono.just(new AcpSchema.NewSessionResponse("s1", null, null)))
			.promptHandler((req, ctx) -> {
				// Verify the large text arrived intact
				String text = req.text();
				assertThat(text).hasSize(500_000);
				assertThat(text).startsWith("xxx");
				return ctx.sendMessage("received " + text.length())
					.then(Mono.just(AcpSchema.PromptResponse.endTurn()));
			})
			.build();

		agent.start().block(TIMEOUT);
		Thread.sleep(300);

		AtomicReference<String> agentResponse = new AtomicReference<>();
		WebSocketAcpClientTransport clientTransport = new WebSocketAcpClientTransport(
			URI.create("ws://localhost:" + port + "/acp"), jsonMapper);
		AcpAsyncClient client = AcpClient.async(clientTransport)
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(notification -> {
				if (notification.update() instanceof AcpSchema.AgentMessageChunk msg) {
					agentResponse.set(((AcpSchema.TextContent) msg.content()).text());
				}
				return Mono.empty();
			})
			.build();

		try {
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()))
				.block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

			AcpSchema.PromptResponse response = client.prompt(new AcpSchema.PromptRequest("s1",
				List.of(new AcpSchema.TextContent(largeText)))).block(TIMEOUT);

			assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(agentResponse.get()).isEqualTo("received 500000");
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void multipleSequentialPromptsOverWebSocket() throws Exception {
		int port = findFreePort();
		AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();
		CopyOnWriteArrayList<String> receivedPrompts = new CopyOnWriteArrayList<>();

		WebSocketAcpAgentTransport agentTransport = new WebSocketAcpAgentTransport(port, jsonMapper);
		AcpAsyncAgent agent = AcpAgent.async(agentTransport)
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> Mono.just(new AcpSchema.InitializeResponse(
				1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(req -> Mono.just(new AcpSchema.NewSessionResponse("s1", null, null)))
			.promptHandler((req, ctx) -> {
				receivedPrompts.add(req.text());
				return ctx.sendMessage("echo: " + req.text())
					.then(Mono.just(AcpSchema.PromptResponse.endTurn()));
			})
			.build();

		agent.start().block(TIMEOUT);
		Thread.sleep(300);

		WebSocketAcpClientTransport clientTransport = new WebSocketAcpClientTransport(
			URI.create("ws://localhost:" + port + "/acp"), jsonMapper);
		AcpAsyncClient client = AcpClient.async(clientTransport).requestTimeout(TIMEOUT).build();

		try {
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()))
				.block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

			// Send 5 sequential prompts over the same WebSocket connection
			for (int i = 1; i <= 5; i++) {
				AcpSchema.PromptResponse resp = client.prompt(new AcpSchema.PromptRequest("s1",
					List.of(new AcpSchema.TextContent("prompt-" + i)))).block(TIMEOUT);
				assertThat(resp.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			}

			assertThat(receivedPrompts).containsExactly(
				"prompt-1", "prompt-2", "prompt-3", "prompt-4", "prompt-5");
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

}
