/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.transport.WebSocketAcpAgentTransport;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for WebSocket client-agent communication.
 *
 * <p>
 * These tests verify that the WebSocket transport correctly handles the full
 * ACP protocol lifecycle over a real network connection.
 * </p>
 *
 * <p>
 * <b>Note:</b> Currently disabled pending Jetty 12 WebSocket configuration fixes.
 * The WebSocket transports are functional but require additional server setup.
 * </p>
 */
// @Disabled - Re-enabled to investigate WebSocket issues
class WebSocketClientAgentTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final int TEST_PORT = 18080; // High port to avoid conflicts

	private McpJsonMapper jsonMapper;

	private WebSocketAcpAgentTransport agentTransport;

	private WebSocketAcpClientTransport clientTransport;

	private AcpAsyncAgent agent;

	private AcpAsyncClient client;

	@BeforeEach
	void setUp() {
		jsonMapper = McpJsonMapper.getDefault();
	}

	@AfterEach
	void tearDown() {
		if (client != null) {
			try {
				client.closeGracefully().block(Duration.ofSeconds(5));
			}
			catch (Exception ignored) {
			}
		}
		if (agent != null) {
			try {
				agent.closeGracefully().block(Duration.ofSeconds(5));
			}
			catch (Exception ignored) {
			}
		}
	}

	@Test
	void initializeHandshakeSucceeds() throws Exception {
		// Set up agent
		agentTransport = new WebSocketAcpAgentTransport(TEST_PORT, jsonMapper);
		agent = AcpAgent.async(agentTransport)
			.initializeHandler(request -> Mono.just(new AcpSchema.InitializeResponse(
				1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("test-session", null, null)))
			.promptHandler((request, updater) -> Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)))
			.build();

		agent.start().block(TIMEOUT);
		Thread.sleep(500); // Allow server to fully start

		// Set up client
		URI serverUri = URI.create("ws://localhost:" + TEST_PORT + "/acp");
		clientTransport = new WebSocketAcpClientTransport(serverUri, jsonMapper);
		client = AcpClient.async(clientTransport).requestTimeout(TIMEOUT).build();

		// Test initialize
		AcpSchema.InitializeResponse response = client
			.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()))
			.block(TIMEOUT);

		assertThat(response).isNotNull();
		assertThat(response.protocolVersion()).isEqualTo(1);
	}

	@Test
	void sessionCreationWorks() throws Exception {
		// Set up agent
		agentTransport = new WebSocketAcpAgentTransport(TEST_PORT + 1, jsonMapper);
		agent = AcpAgent.async(agentTransport)
			.initializeHandler(request -> Mono.just(new AcpSchema.InitializeResponse(
				1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(request -> {
				assertThat(request.cwd()).isEqualTo("/workspace");
				return Mono.just(new AcpSchema.NewSessionResponse("ws-session-123", null, null));
			})
			.promptHandler((request, updater) -> Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)))
			.build();

		agent.start().block(TIMEOUT);
		Thread.sleep(200);

		// Set up client
		URI serverUri = URI.create("ws://localhost:" + (TEST_PORT + 1) + "/acp");
		clientTransport = new WebSocketAcpClientTransport(serverUri, jsonMapper);
		client = AcpClient.async(clientTransport).requestTimeout(TIMEOUT).build();

		// Initialize and create session
		client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);

		AcpSchema.NewSessionResponse session = client
			.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()))
			.block(TIMEOUT);

		assertThat(session).isNotNull();
		assertThat(session.sessionId()).isEqualTo("ws-session-123");
	}

	@Test
	void promptResponseFlowWorks() throws Exception {
		// Set up agent
		agentTransport = new WebSocketAcpAgentTransport(TEST_PORT + 2, jsonMapper);
		agent = AcpAgent.async(agentTransport)
			.initializeHandler(request -> Mono.just(new AcpSchema.InitializeResponse(
				1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("prompt-session", null, null)))
			.promptHandler((request, updater) -> {
				// Verify we received the prompt
				assertThat(request.prompt()).hasSize(1);
				AcpSchema.TextContent text = (AcpSchema.TextContent) request.prompt().get(0);
				assertThat(text.text()).isEqualTo("Hello from WebSocket!");
				return Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
			})
			.build();

		agent.start().block(TIMEOUT);
		Thread.sleep(200);

		// Set up client
		URI serverUri = URI.create("ws://localhost:" + (TEST_PORT + 2) + "/acp");
		clientTransport = new WebSocketAcpClientTransport(serverUri, jsonMapper);
		client = AcpClient.async(clientTransport).requestTimeout(TIMEOUT).build();

		// Full flow
		client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);
		client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

		AcpSchema.PromptResponse response = client
			.prompt(new AcpSchema.PromptRequest("prompt-session",
				List.of(new AcpSchema.TextContent("Hello from WebSocket!"))))
			.block(TIMEOUT);

		assertThat(response).isNotNull();
		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
	}

	@Test
	void sessionUpdatesStreamCorrectly() throws Exception {
		// Set up agent with streaming updates
		agentTransport = new WebSocketAcpAgentTransport(TEST_PORT + 3, jsonMapper);
		agent = AcpAgent.async(agentTransport)
			.initializeHandler(request -> Mono.just(new AcpSchema.InitializeResponse(
				1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("stream-session", null, null)))
			.promptHandler((request, updater) -> {
				// Send streaming updates before response
				return updater.sendUpdate("stream-session",
						new AcpSchema.AgentThoughtChunk("agent_thought_chunk",
							new AcpSchema.TextContent("Thinking...")))
					.then(updater.sendUpdate("stream-session",
						new AcpSchema.AgentMessageChunk("agent_message_chunk",
							new AcpSchema.TextContent("Hello!"))))
					.then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
			})
			.build();

		agent.start().block(TIMEOUT);
		Thread.sleep(200);

		// Set up client with update consumer
		AtomicReference<AcpSchema.SessionNotification> receivedUpdate = new AtomicReference<>();
		URI serverUri = URI.create("ws://localhost:" + (TEST_PORT + 3) + "/acp");
		clientTransport = new WebSocketAcpClientTransport(serverUri, jsonMapper);
		client = AcpClient.async(clientTransport)
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(notification -> {
				receivedUpdate.set(notification);
				return Mono.empty();
			})
			.build();

		// Full flow
		client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);
		client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
		client.prompt(new AcpSchema.PromptRequest("stream-session",
			List.of(new AcpSchema.TextContent("Trigger updates")))).block(TIMEOUT);

		// Verify we received at least one update
		assertThat(receivedUpdate.get()).isNotNull();
	}

	@Test
	void agentToClientFileReadWorks() throws Exception {
		// Use AtomicReference for agent self-reference
		AtomicReference<AcpAsyncAgent> agentRef = new AtomicReference<>();

		agentTransport = new WebSocketAcpAgentTransport(TEST_PORT + 4, jsonMapper);
		agent = AcpAgent.async(agentTransport)
			.initializeHandler(request -> Mono.just(new AcpSchema.InitializeResponse(
				1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("file-session", null, null)))
			.promptHandler((request, updater) -> {
				// Request file from client
				return agentRef.get()
					.readTextFile(new AcpSchema.ReadTextFileRequest("file-session", "/src/Main.java", null, null))
					.flatMap(fileResponse -> {
						assertThat(fileResponse.content()).contains("public class Main");
						return Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
					});
			})
			.build();
		agentRef.set(agent);

		agent.start().block(TIMEOUT);
		Thread.sleep(500); // Allow server to fully start

		// Set up client with file handler
		URI serverUri = URI.create("ws://localhost:" + (TEST_PORT + 4) + "/acp");
		clientTransport = new WebSocketAcpClientTransport(serverUri, jsonMapper);
		client = AcpClient.async(clientTransport)
			.requestTimeout(TIMEOUT)
			.readTextFileHandler(params -> {
				return Mono.just(new AcpSchema.ReadTextFileResponse("public class Main { }"));
			})
			.build();

		// Full flow - advertise file read capability
		AcpSchema.FileSystemCapability fsCaps = new AcpSchema.FileSystemCapability(true, false);
		AcpSchema.ClientCapabilities caps = new AcpSchema.ClientCapabilities(fsCaps, false);
		client.initialize(new AcpSchema.InitializeRequest(1, caps)).block(TIMEOUT);
		client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

		AcpSchema.PromptResponse response = client
			.prompt(new AcpSchema.PromptRequest("file-session",
				List.of(new AcpSchema.TextContent("Read a file"))))
			.block(TIMEOUT);

		assertThat(response).isNotNull();
		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
	}

}
