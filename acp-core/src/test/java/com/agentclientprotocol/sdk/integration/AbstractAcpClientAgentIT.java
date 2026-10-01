/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract base class for ACP client-agent integration tests.
 *
 * <p>
 * This class provides a framework for testing the full client ↔ agent communication
 * lifecycle across different transport implementations. Subclasses provide the specific
 * transport pair (e.g., in-memory, stdio).
 * </p>
 *
 * <p>
 * The tests verify:
 * <ul>
 * <li>Initialize handshake</li>
 * <li>Session creation</li>
 * <li>Prompt/response flow</li>
 * <li>Session updates (streaming)</li>
 * <li>Agent→Client requests (permissions, file ops)</li>
 * <li>Graceful shutdown</li>
 * </ul>
 *
 * @author Mark Pollack
 */
public abstract class AbstractAcpClientAgentIT {

	protected static final Duration TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Creates the client transport for testing.
	 * @return the client transport
	 */
	protected abstract AcpClientTransport createClientTransport();

	/**
	 * Creates the agent transport for testing.
	 * @return the agent transport
	 */
	protected abstract AcpAgentTransport createAgentTransport();

	/**
	 * Closes the transport pair after tests.
	 */
	protected abstract void closeTransports();

	@Test
	void initializeHandshakeSucceeds() throws Exception {
		AcpClientTransport clientTransport = createClientTransport();
		AcpAgentTransport agentTransport = createAgentTransport();

		try {
			// Build agent with initialize handler
			AcpAsyncAgent agent = AcpAgent.async(agentTransport)
				.requestTimeout(TIMEOUT)
				.initializeHandler(request -> {
					assertThat(request.protocolVersion()).isEqualTo(1);
					return Mono.just(new AcpSchema.InitializeResponse(1,
							new AcpSchema.AgentCapabilities(true, null, null), List.of())); // loadSession=true
				})
				.build();

			// Build client
			AcpAsyncClient client = AcpClient.async(clientTransport).requestTimeout(TIMEOUT).build();

			// Start agent
			agent.start().subscribe();
			Thread.sleep(100);

			// Initialize client
			AcpSchema.InitializeResponse response = client
				.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()))
				.block(TIMEOUT);

			assertThat(response).isNotNull();
			assertThat(response.protocolVersion()).isEqualTo(1);
			assertThat(response.agentCapabilities()).isNotNull();
			assertThat(response.agentCapabilities().loadSession()).isTrue();

			// Cleanup
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			closeTransports();
		}
	}

	@Test
	void sessionCreationWorks() throws Exception {
		AcpClientTransport clientTransport = createClientTransport();
		AcpAgentTransport agentTransport = createAgentTransport();

		try {
			// Build agent with handlers
			AcpAsyncAgent agent = AcpAgent.async(agentTransport)
				.requestTimeout(TIMEOUT)
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
				.newSessionHandler(request -> {
					assertThat(request.cwd()).isEqualTo("/test/workspace");
					return Mono.just(new AcpSchema.NewSessionResponse("session-123", null, null));
				})
				.build();

			// Build client
			AcpAsyncClient client = AcpClient.async(clientTransport).requestTimeout(TIMEOUT).build();

			// Start and initialize
			agent.start().subscribe();
			Thread.sleep(100);
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);

			// Create session
			AcpSchema.NewSessionResponse sessionResponse = client
				.newSession(new AcpSchema.NewSessionRequest("/test/workspace", List.of()))
				.block(TIMEOUT);

			assertThat(sessionResponse).isNotNull();
			assertThat(sessionResponse.sessionId()).isEqualTo("session-123");

			// Cleanup
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			closeTransports();
		}
	}

	@Test
	void sessionResponsesCarryConfigOptions() throws Exception {
		AcpClientTransport clientTransport = createClientTransport();
		AcpAgentTransport agentTransport = createAgentTransport();
		List<AcpSchema.SessionConfigOption> options = List.of(
				new AcpSchema.SessionConfigSelect("model", "Model", "fast",
						List.of(new AcpSchema.SessionConfigSelectOption("fast", "Fast"),
								new AcpSchema.SessionConfigSelectOption("smart", "Smart"))),
				new AcpSchema.SessionConfigBoolean("web", "Web search", true));

		try {
			AcpAsyncAgent agent = AcpAgent.async(agentTransport)
				.requestTimeout(TIMEOUT)
				.initializeHandler(request -> Mono.just(new AcpSchema.InitializeResponse(1,
						new AcpSchema.AgentCapabilities(true, new AcpSchema.SessionCapabilities(null, null, Map.of()),
								null, null, null),
						List.of())))
				.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("s1", null, options)))
				.loadSessionHandler(request -> Mono.just(new AcpSchema.LoadSessionResponse(null, options)))
				.resumeSessionHandler(request -> Mono.just(new AcpSchema.ResumeSessionResponse(null, options)))
				.build();
			AcpAsyncClient client = AcpClient.async(clientTransport).requestTimeout(TIMEOUT).build();
			agent.start().subscribe();
			Thread.sleep(100);
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);

			var created = client.newSession(new AcpSchema.NewSessionRequest("/w", List.of())).block(TIMEOUT);
			var loaded = client.loadSession(new AcpSchema.LoadSessionRequest("s1", "/w", List.of())).block(TIMEOUT);
			var resumed = client.resumeSession(new AcpSchema.ResumeSessionRequest("s1", "/w", List.of()))
				.block(TIMEOUT);

			assertThat(created.configOptions()).isEqualTo(options);
			assertThat(loaded.configOptions()).isEqualTo(options);
			assertThat(resumed.configOptions()).isEqualTo(options);

			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			closeTransports();
		}
	}

	@Test
	void promptResponseFlowWorks() throws Exception {
		AcpClientTransport clientTransport = createClientTransport();
		AcpAgentTransport agentTransport = createAgentTransport();

		try {
			AtomicReference<String> receivedPrompt = new AtomicReference<>();

			// Build agent with handlers
			AcpAsyncAgent agent = AcpAgent.async(agentTransport)
				.requestTimeout(TIMEOUT)
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
				.newSessionHandler(
						request -> Mono.just(new AcpSchema.NewSessionResponse("session-prompt-test", null, null)))
				.promptHandler((request, updater) -> {
					receivedPrompt.set(((AcpSchema.TextContent) request.prompt().get(0)).text());
					return Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
				})
				.build();

			// Build client
			AcpAsyncClient client = AcpClient.async(clientTransport).requestTimeout(TIMEOUT).build();

			// Start, initialize, and create session
			agent.start().subscribe();
			Thread.sleep(100);
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

			// Send prompt
			AcpSchema.PromptResponse promptResponse = client.prompt(new AcpSchema.PromptRequest("session-prompt-test",
					List.of(new AcpSchema.TextContent("Fix the failing tests"))))
				.block(TIMEOUT);

			assertThat(promptResponse).isNotNull();
			assertThat(promptResponse.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(receivedPrompt.get()).isEqualTo("Fix the failing tests");

			// Cleanup
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			closeTransports();
		}
	}

	@Test
	void sessionUpdatesStreamCorrectly() throws Exception {
		AcpClientTransport clientTransport = createClientTransport();
		AcpAgentTransport agentTransport = createAgentTransport();

		try {
			List<AcpSchema.SessionNotification> receivedUpdates = new CopyOnWriteArrayList<>();
			CountDownLatch updateLatch = new CountDownLatch(2);

			// Build agent that sends session updates during prompt
			AcpAsyncAgent agent = AcpAgent.async(agentTransport)
				.requestTimeout(TIMEOUT)
				.initializeHandler(request -> Mono.just(
						new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(true, null, null), List.of())))
				.newSessionHandler(
						request -> Mono.just(new AcpSchema.NewSessionResponse("session-updates", null, null)))
				.promptHandler((request, updater) -> {
					// Send streaming updates during prompt processing
					return updater
						.sendUpdate("session-updates",
								new AcpSchema.AgentThoughtChunk("agent_thought_chunk",
										new AcpSchema.TextContent("Analyzing code...")))
						.then(updater.sendUpdate("session-updates",
								new AcpSchema.AgentMessageChunk("agent_message_chunk",
										new AcpSchema.TextContent("Found the issue"))))
						.then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
				})
				.build();

			// Build client with session update consumer
			AcpAsyncClient client = AcpClient.async(clientTransport)
				.requestTimeout(TIMEOUT)
				.sessionUpdateConsumer(notification -> {
					receivedUpdates.add(notification);
					updateLatch.countDown();
					return Mono.empty();
				})
				.build();

			// Start, initialize, and create session
			agent.start().subscribe();
			Thread.sleep(100);
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

			// Send prompt and verify updates are received
			client.prompt(new AcpSchema.PromptRequest("session-updates", List.of(new AcpSchema.TextContent("Test"))))
				.block(TIMEOUT);

			assertThat(updateLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(receivedUpdates).hasSize(2);
			assertThat(receivedUpdates.get(0).update()).isInstanceOf(AcpSchema.AgentThoughtChunk.class);
			assertThat(receivedUpdates.get(1).update()).isInstanceOf(AcpSchema.AgentMessageChunk.class);

			// Cleanup
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			closeTransports();
		}
	}

	@Test
	void agentToClientPermissionRequestWorks() throws Exception {
		AcpClientTransport clientTransport = createClientTransport();
		AcpAgentTransport agentTransport = createAgentTransport();

		try {
			AtomicReference<AcpSchema.RequestPermissionResponse> permissionResponse = new AtomicReference<>();
			CountDownLatch permissionLatch = new CountDownLatch(1);
			AtomicReference<AcpAsyncAgent> agentRef = new AtomicReference<>();

			// Build agent that requests permission during prompt
			AcpAsyncAgent agent = AcpAgent.async(agentTransport)
				.requestTimeout(TIMEOUT)
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
				.newSessionHandler(
						request -> Mono.just(new AcpSchema.NewSessionResponse("session-permission", null, null)))
				.promptHandler((request, updater) -> {
					// Request permission from client
					AcpSchema.ToolCallUpdate toolCall = new AcpSchema.ToolCallUpdate("tool-123", "Write File", AcpSchema.ToolKind.EDIT, AcpSchema.ToolCallStatus.PENDING);
					List<AcpSchema.PermissionOption> options = List.of(
							new AcpSchema.PermissionOption("allow", "Allow", AcpSchema.PermissionOptionKind.ALLOW_ONCE),
							new AcpSchema.PermissionOption("deny", "Deny", AcpSchema.PermissionOptionKind.REJECT_ONCE));

					return agentRef.get()
						.requestPermission(
								new AcpSchema.RequestPermissionRequest("session-permission", toolCall, options))
						.doOnNext(response -> {
							permissionResponse.set(response);
							permissionLatch.countDown();
						})
						.then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
				})
				.build();
			agentRef.set(agent);

			// Build client with permission handler - using typed handler
			AcpAsyncClient client = AcpClient.async(clientTransport)
				.requestTimeout(TIMEOUT)
				.requestPermissionHandler((AcpSchema.RequestPermissionRequest permRequest) -> {
					assertThat(permRequest.toolCall().title()).isEqualTo("Write File");
					return Mono.just(new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected("allow")));
				})
				.build();

			// Start, initialize, and create session
			agent.start().subscribe();
			Thread.sleep(100);
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

			// Send prompt which triggers permission request
			client.prompt(
					new AcpSchema.PromptRequest("session-permission", List.of(new AcpSchema.TextContent("Test"))))
				.block(TIMEOUT);

			assertThat(permissionLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(permissionResponse.get()).isNotNull();
			assertThat(permissionResponse.get().outcome()).isInstanceOf(AcpSchema.PermissionSelected.class);
			assertThat(((AcpSchema.PermissionSelected) permissionResponse.get().outcome()).optionId())
				.isEqualTo("allow");

			// Cleanup
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			closeTransports();
		}
	}

	@Test
	void agentToClientFileReadWorks() throws Exception {
		AcpClientTransport clientTransport = createClientTransport();
		AcpAgentTransport agentTransport = createAgentTransport();

		try {
			AtomicReference<String> fileContent = new AtomicReference<>();
			CountDownLatch fileLatch = new CountDownLatch(1);
			AtomicReference<AcpAsyncAgent> agentRef = new AtomicReference<>();

			// Build agent that reads file during prompt
			AcpAsyncAgent agent = AcpAgent.async(agentTransport)
				.requestTimeout(TIMEOUT)
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
				.newSessionHandler(
						request -> Mono.just(new AcpSchema.NewSessionResponse("session-file-read", null, null)))
				.promptHandler((request, updater) -> {
					// Read file from client
					return agentRef.get()
						.readTextFile(
								new AcpSchema.ReadTextFileRequest("session-file-read", "/src/Main.java", null, null))
						.doOnNext(response -> {
							fileContent.set(response.content());
							fileLatch.countDown();
						})
						.then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
				})
				.build();
			agentRef.set(agent);

			// Build client with file read handler - using typed handler
			AcpAsyncClient client = AcpClient.async(clientTransport)
				.requestTimeout(TIMEOUT)
				.readTextFileHandler((AcpSchema.ReadTextFileRequest request) -> {
					assertThat(request.path()).isEqualTo("/src/Main.java");
					return Mono.just(new AcpSchema.ReadTextFileResponse("public class Main {}"));
				})
				.build();

			// Start, initialize, and create session
			// Client must advertise file reading capability for agent to use it
			AcpSchema.FileSystemCapability fsCaps = new AcpSchema.FileSystemCapability(true, false);
			AcpSchema.ClientCapabilities clientCaps = new AcpSchema.ClientCapabilities(fsCaps, false);
			agent.start().subscribe();
			Thread.sleep(100);
			client.initialize(new AcpSchema.InitializeRequest(1, clientCaps)).block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

			// Send prompt which triggers file read
			client
				.prompt(new AcpSchema.PromptRequest("session-file-read", List.of(new AcpSchema.TextContent("Test"))))
				.block(TIMEOUT);

			assertThat(fileLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(fileContent.get()).isEqualTo("public class Main {}");

			// Cleanup
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			closeTransports();
		}
	}

	@Test
	void gracefulShutdownWorks() throws Exception {
		AcpClientTransport clientTransport = createClientTransport();
		AcpAgentTransport agentTransport = createAgentTransport();

		try {
			// Build minimal agent
			AcpAsyncAgent agent = AcpAgent.async(agentTransport)
				.requestTimeout(TIMEOUT)
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
				.build();

			// Build client
			AcpAsyncClient client = AcpClient.async(clientTransport).requestTimeout(TIMEOUT).build();

			// Start and initialize
			agent.start().subscribe();
			Thread.sleep(100);
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);

			// Graceful shutdown should complete without error
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			closeTransports();
		}
	}

	@Test
	void cancelNotificationWorks() throws Exception {
		AcpClientTransport clientTransport = createClientTransport();
		AcpAgentTransport agentTransport = createAgentTransport();

		try {
			AtomicReference<String> cancelledSessionId = new AtomicReference<>();
			CountDownLatch cancelLatch = new CountDownLatch(1);

			// Build agent with cancel handler
			AcpAsyncAgent agent = AcpAgent.async(agentTransport)
				.requestTimeout(TIMEOUT)
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
				.newSessionHandler(
						request -> Mono.just(new AcpSchema.NewSessionResponse("session-cancel-test", null, null)))
				.cancelHandler(notification -> {
					cancelledSessionId.set(notification.sessionId());
					cancelLatch.countDown();
					return Mono.empty();
				})
				.build();

			// Build client
			AcpAsyncClient client = AcpClient.async(clientTransport).requestTimeout(TIMEOUT).build();

			// Start, initialize, create session
			agent.start().subscribe();
			Thread.sleep(100);
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

			// Send cancel notification
			client.cancel(new AcpSchema.CancelNotification("session-cancel-test")).block(TIMEOUT);

			assertThat(cancelLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(cancelledSessionId.get()).isEqualTo("session-cancel-test");

			// Cleanup
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			closeTransports();
		}
	}

	@Test
	void forkSessionWorks() throws Exception {
		AcpClientTransport clientTransport = createClientTransport();
		AcpAgentTransport agentTransport = createAgentTransport();

		try {
			AcpAsyncAgent agent = AcpAgent.async(agentTransport)
				.requestTimeout(TIMEOUT)
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
				.newSessionHandler(
						request -> Mono.just(new AcpSchema.NewSessionResponse("session-original", null, null)))
				.forkSessionHandler(request -> {
					assertThat(request.sessionId()).isEqualTo("session-original");
					return Mono.just(new AcpSchema.ForkSessionResponse("session-forked", null));
				})
				.build();

			AcpAsyncClient client = AcpClient.async(clientTransport)
				.requestTimeout(TIMEOUT)
				.build();

			agent.start().subscribe();
			Thread.sleep(100);
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

			AcpSchema.ForkSessionResponse forkResp = client
				.forkSession(new AcpSchema.ForkSessionRequest("session-original", "/workspace", List.of()))
				.block(TIMEOUT);

			assertThat(forkResp).isNotNull();
			assertThat(forkResp.sessionId()).isEqualTo("session-forked");

			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			closeTransports();
		}
	}

	@Test
	void agentToClientElicitationFormWorks() throws Exception {
		AcpClientTransport clientTransport = createClientTransport();
		AcpAgentTransport agentTransport = createAgentTransport();

		try {
			AtomicReference<AcpSchema.CreateElicitationResponse> elicitationResponse = new AtomicReference<>();
			CountDownLatch elicitationLatch = new CountDownLatch(1);
			AtomicReference<AcpAsyncAgent> agentRef = new AtomicReference<>();

			// Build agent that sends a form-mode elicitation during prompt
			AcpAsyncAgent agent = AcpAgent.async(agentTransport)
				.requestTimeout(TIMEOUT)
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
				.newSessionHandler(
						request -> Mono.just(new AcpSchema.NewSessionResponse("session-elicit", null, null)))
				.promptHandler((request, context) -> {
					// Build a form schema asking the user to pick a strategy
					var schema = new AcpSchema.ElicitationSchema(
							Map.of("strategy", AcpSchema.StringPropertySchema.singleSelect("Strategy",
									List.of(new AcpSchema.EnumOption("fast", "Fast"),
											new AcpSchema.EnumOption("safe", "Safe")))),
							List.of("strategy"));

					var elicitRequest = AcpSchema.CreateElicitationRequest.form(
							"session-elicit", "Pick a strategy", schema);

					return agentRef.get()
						.createElicitation(elicitRequest)
						.doOnNext(response -> {
							elicitationResponse.set(response);
							elicitationLatch.countDown();
						})
						.then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
				})
				.build();
			agentRef.set(agent);

			// Build client with elicitation handler that accepts with "fast"
			AcpAsyncClient client = AcpClient.async(clientTransport)
				.requestTimeout(TIMEOUT)
				.createElicitationHandler(elicitRequest -> {
					assertThat(elicitRequest.mode()).isEqualTo("form");
					assertThat(elicitRequest.message()).isEqualTo("Pick a strategy");
					assertThat(elicitRequest.requestedSchema()).isNotNull();
					assertThat(elicitRequest.requestedSchema().properties()).containsKey("strategy");
					return Mono.just(AcpSchema.CreateElicitationResponse.accept(
							Map.of("strategy", "fast")));
				})
				.build();

			// Start, initialize, create session
			agent.start().subscribe();
			Thread.sleep(100);

			// Advertise elicitation capability
			var caps = new AcpSchema.ClientCapabilities(
					new AcpSchema.FileSystemCapability(), false,
					null, null, new AcpSchema.ElicitationCapabilities(), null);
			client.initialize(new AcpSchema.InitializeRequest(1, caps)).block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

			// Send prompt which triggers elicitation
			client.prompt(new AcpSchema.PromptRequest("session-elicit",
					List.of(new AcpSchema.TextContent("Help me choose")))).block(TIMEOUT);

			assertThat(elicitationLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(elicitationResponse.get()).isNotNull();
			assertThat(elicitationResponse.get().action()).isEqualTo(AcpSchema.ElicitationAction.ACCEPT);
			assertThat(elicitationResponse.get().content()).containsEntry("strategy", "fast");

			// Cleanup
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			closeTransports();
		}
	}

	@Test
	void agentToClientElicitationDeclineWorks() throws Exception {
		AcpClientTransport clientTransport = createClientTransport();
		AcpAgentTransport agentTransport = createAgentTransport();

		try {
			AtomicReference<AcpSchema.CreateElicitationResponse> elicitationResponse = new AtomicReference<>();
			CountDownLatch latch = new CountDownLatch(1);
			AtomicReference<AcpAsyncAgent> agentRef = new AtomicReference<>();

			AcpAsyncAgent agent = AcpAgent.async(agentTransport)
				.requestTimeout(TIMEOUT)
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
				.newSessionHandler(
						request -> Mono.just(new AcpSchema.NewSessionResponse("session-decline", null, null)))
				.promptHandler((request, context) -> {
					var schema = new AcpSchema.ElicitationSchema(
							Map.of("name", AcpSchema.StringPropertySchema.text("Name")), null);
					return agentRef.get()
						.createElicitation(AcpSchema.CreateElicitationRequest.form(
								"session-decline", "Enter name:", schema))
						.doOnNext(response -> {
							elicitationResponse.set(response);
							latch.countDown();
						})
						.then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
				})
				.build();
			agentRef.set(agent);

			// Client declines the form
			AcpAsyncClient client = AcpClient.async(clientTransport)
				.requestTimeout(TIMEOUT)
				.createElicitationHandler(req ->
					Mono.just(AcpSchema.CreateElicitationResponse.decline()))
				.build();

			agent.start().subscribe();
			Thread.sleep(100);
			var caps = new AcpSchema.ClientCapabilities(
					new AcpSchema.FileSystemCapability(), false,
					null, null, new AcpSchema.ElicitationCapabilities(), null);
			client.initialize(new AcpSchema.InitializeRequest(1, caps)).block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
			client.prompt(new AcpSchema.PromptRequest("session-decline",
					List.of(new AcpSchema.TextContent("test")))).block(TIMEOUT);

			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(elicitationResponse.get().action()).isEqualTo(AcpSchema.ElicitationAction.DECLINE);
			assertThat(elicitationResponse.get().content()).isNull();

			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			closeTransports();
		}
	}

	@Test
	void agentToClientElicitationCancelWorks() throws Exception {
		AcpClientTransport clientTransport = createClientTransport();
		AcpAgentTransport agentTransport = createAgentTransport();

		try {
			AtomicReference<AcpSchema.CreateElicitationResponse> elicitationResponse = new AtomicReference<>();
			CountDownLatch latch = new CountDownLatch(1);
			AtomicReference<AcpAsyncAgent> agentRef = new AtomicReference<>();

			AcpAsyncAgent agent = AcpAgent.async(agentTransport)
				.requestTimeout(TIMEOUT)
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
				.newSessionHandler(
						request -> Mono.just(new AcpSchema.NewSessionResponse("session-cancel", null, null)))
				.promptHandler((request, context) -> {
					var schema = new AcpSchema.ElicitationSchema(
							Map.of("name", AcpSchema.StringPropertySchema.text("Name")), null);
					return agentRef.get()
						.createElicitation(AcpSchema.CreateElicitationRequest.form(
								"session-cancel", "Enter name:", schema))
						.doOnNext(response -> {
							elicitationResponse.set(response);
							latch.countDown();
						})
						.then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
				})
				.build();
			agentRef.set(agent);

			// Client cancels the form
			AcpAsyncClient client = AcpClient.async(clientTransport)
				.requestTimeout(TIMEOUT)
				.createElicitationHandler(req ->
					Mono.just(AcpSchema.CreateElicitationResponse.cancel()))
				.build();

			agent.start().subscribe();
			Thread.sleep(100);
			var caps = new AcpSchema.ClientCapabilities(
					new AcpSchema.FileSystemCapability(), false,
					null, null, new AcpSchema.ElicitationCapabilities(), null);
			client.initialize(new AcpSchema.InitializeRequest(1, caps)).block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
			client.prompt(new AcpSchema.PromptRequest("session-cancel",
					List.of(new AcpSchema.TextContent("test")))).block(TIMEOUT);

			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(elicitationResponse.get().action()).isEqualTo(AcpSchema.ElicitationAction.CANCEL);
			assertThat(elicitationResponse.get().content()).isNull();

			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			closeTransports();
		}
	}

}
