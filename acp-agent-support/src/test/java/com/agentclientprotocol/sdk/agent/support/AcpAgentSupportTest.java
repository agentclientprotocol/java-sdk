/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Cancel;
import com.agentclientprotocol.sdk.annotation.CloseSession;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.ListSessions;
import com.agentclientprotocol.sdk.annotation.LoadSession;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.annotation.ResumeSession;
import com.agentclientprotocol.sdk.annotation.SetSessionMode;
import com.agentclientprotocol.sdk.annotation.SetSessionModel;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.CancelNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.CloseSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.CloseSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ListSessionsRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.ListSessionsResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.LoadSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.LoadSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ResumeSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.ResumeSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionInfo;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModelRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModelResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AcpAgentSupport}.
 */
class AcpAgentSupportTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private InMemoryTransportPair transportPair;

	private AcpAgentSupport agentSupport;

	private AcpAsyncClient client;

	@BeforeEach
	void setUp() {
		transportPair = InMemoryTransportPair.create();
	}

	@AfterEach
	void tearDown() {
		if (client != null) {
			client.closeGracefully().block(TIMEOUT);
		}
		if (agentSupport != null) {
			agentSupport.close();
		}
	}

	@Test
	void annotationBasedAgentHandlesFullLifecycle() throws Exception {
		// Create annotation-based agent
		agentSupport = AcpAgentSupport.create(new SimpleAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		// Create client
		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		// Initialize
		InitializeResponse initResp = client.initialize(new InitializeRequest(1, null)).block(TIMEOUT);
		assertThat(initResp.protocolVersion()).isEqualTo(1);

		// New session
		NewSessionResponse sessionResp = client.newSession(new NewSessionRequest("/workspace", List.of()))
				.block(TIMEOUT);
		assertThat(sessionResp.sessionId()).isEqualTo("test-session");

		// Prompt
		PromptResponse promptResp = client
				.prompt(new PromptRequest("test-session", List.of(new TextContent("Hello")))).block(TIMEOUT);
		assertThat(promptResp.stopReason()).isNotNull();
	}

	@Test
	void promptHandlerReceivesContextAndRequest() throws Exception {
		AtomicReference<String> receivedPrompt = new AtomicReference<>();
		AtomicReference<String> receivedSessionId = new AtomicReference<>();

		// Agent that captures request data
		@AcpAgent
		class CapturingAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@NewSession
			NewSessionResponse newSession() {
				return new NewSessionResponse("capture-session", null, null);
			}

			@Prompt
			PromptResponse prompt(PromptRequest req, SyncPromptContext ctx) {
				receivedPrompt.set(req.prompt().get(0).toString());
				receivedSessionId.set(ctx.getSessionId());
				return PromptResponse.text("Got it!");
			}

		}

		agentSupport = AcpAgentSupport.create(new CapturingAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		client.initialize(new InitializeRequest(1, null)).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
		client.prompt(new PromptRequest("capture-session", List.of(new TextContent("Test message")))).block(TIMEOUT);

		assertThat(receivedSessionId.get()).isEqualTo("capture-session");
		assertThat(receivedPrompt.get()).contains("Test message");
	}

	@Test
	void stringReturnValueConvertedToPromptResponse() throws Exception {
		@AcpAgent
		class StringReturningAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@NewSession
			NewSessionResponse newSession() {
				return new NewSessionResponse("string-session", null, null);
			}

			@Prompt
			String prompt(PromptRequest req) {
				return "Hello from String!";
			}

		}

		agentSupport = AcpAgentSupport.create(new StringReturningAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		client.initialize(new InitializeRequest(1, null)).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
		PromptResponse resp = client.prompt(new PromptRequest("string-session", List.of(new TextContent("test"))))
				.block(TIMEOUT);

		// String should be converted to PromptResponse with END_TURN
		assertThat(resp.stopReason()).isNotNull();
	}

	@Test
	void voidReturnValueConvertedToEndTurn() throws Exception {
		AtomicReference<Boolean> handlerCalled = new AtomicReference<>(false);

		@AcpAgent
		class VoidReturningAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@NewSession
			NewSessionResponse newSession() {
				return new NewSessionResponse("void-session", null, null);
			}

			@Prompt
			void prompt(PromptRequest req, SyncPromptContext ctx) {
				ctx.sendMessage("Processing...");
				handlerCalled.set(true);
				// No return - should convert to endTurn()
			}

		}

		agentSupport = AcpAgentSupport.create(new VoidReturningAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		client.initialize(new InitializeRequest(1, null)).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
		PromptResponse resp = client.prompt(new PromptRequest("void-session", List.of(new TextContent("test"))))
				.block(TIMEOUT);

		assertThat(handlerCalled.get()).isTrue();
		assertThat(resp.stopReason()).isNotNull();
	}

	@Test
	void loadSessionHandlerInvoked() throws Exception {
		AtomicReference<String> loadedSessionId = new AtomicReference<>();

		@AcpAgent
		class LoadSessionAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@LoadSession
			LoadSessionResponse loadSession(LoadSessionRequest req) {
				loadedSessionId.set(req.sessionId());
				return new LoadSessionResponse(null, null);
			}

		}

		agentSupport = AcpAgentSupport.create(new LoadSessionAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		client.initialize(new InitializeRequest(1, null)).block(TIMEOUT);
		LoadSessionResponse resp = client.loadSession(new LoadSessionRequest("existing-session", "/workspace", List.of()))
				.block(TIMEOUT);

		assertThat(loadedSessionId.get()).isEqualTo("existing-session");
		assertThat(resp).isNotNull();
	}

	@Test
	void setSessionModeHandlerInvoked() throws Exception {
		AtomicReference<String> receivedModeId = new AtomicReference<>();

		@AcpAgent
		class SetModeAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@NewSession
			NewSessionResponse newSession() {
				return new NewSessionResponse("mode-session", null, null);
			}

			@SetSessionMode
			SetSessionModeResponse setMode(SetSessionModeRequest req) {
				receivedModeId.set(req.modeId());
				return new SetSessionModeResponse();
			}

		}

		agentSupport = AcpAgentSupport.create(new SetModeAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		client.initialize(new InitializeRequest(1, null)).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
		client.setSessionMode(new SetSessionModeRequest("mode-session", "code-review")).block(TIMEOUT);

		assertThat(receivedModeId.get()).isEqualTo("code-review");
	}

	@Test
	void setSessionModelHandlerInvoked() throws Exception {
		AtomicReference<String> receivedModelId = new AtomicReference<>();

		@AcpAgent
		class SetModelAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@NewSession
			NewSessionResponse newSession() {
				return new NewSessionResponse("model-session", null, null);
			}

			@SetSessionModel
			SetSessionModelResponse setModel(SetSessionModelRequest req) {
				receivedModelId.set(req.modelId());
				return new SetSessionModelResponse();
			}

		}

		agentSupport = AcpAgentSupport.create(new SetModelAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		client.initialize(new InitializeRequest(1, null)).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
		client.setSessionModel(new SetSessionModelRequest("model-session", "gpt-4")).block(TIMEOUT);

		assertThat(receivedModelId.get()).isEqualTo("gpt-4");
	}

	@Test
	void cancelHandlerInvoked() throws Exception {
		AtomicReference<String> cancelledSessionId = new AtomicReference<>();

		@AcpAgent
		class CancelAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@NewSession
			NewSessionResponse newSession() {
				return new NewSessionResponse("cancel-session", null, null);
			}

			@Cancel
			void onCancel(CancelNotification notification) {
				cancelledSessionId.set(notification.sessionId());
			}

		}

		agentSupport = AcpAgentSupport.create(new CancelAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		client.initialize(new InitializeRequest(1, null)).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
		client.cancel(new CancelNotification("cancel-session")).block(TIMEOUT);

		// Give time for the notification to be processed
		Thread.sleep(100);

		assertThat(cancelledSessionId.get()).isEqualTo("cancel-session");
	}

	@Test
	void listSessionsHandlerInvoked() throws Exception {
		AtomicReference<String> requestedCwd = new AtomicReference<>();

		@AcpAgent
		class ListSessionsAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@ListSessions
			ListSessionsResponse listSessions(ListSessionsRequest req) {
				requestedCwd.set(req.cwd());
				return new ListSessionsResponse(
						List.of(new SessionInfo("session-1", "/workspace")));
			}

		}

		agentSupport = AcpAgentSupport.create(new ListSessionsAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		client.initialize(new InitializeRequest(1, null)).block(TIMEOUT);
		ListSessionsResponse resp = client.listSessions(new ListSessionsRequest("/workspace"))
				.block(TIMEOUT);

		assertThat(requestedCwd.get()).isEqualTo("/workspace");
		assertThat(resp).isNotNull();
		assertThat(resp.sessions()).hasSize(1);
		assertThat(resp.sessions().get(0).sessionId()).isEqualTo("session-1");
	}

	@Test
	void closeSessionHandlerInvoked() throws Exception {
		AtomicReference<String> closedSessionId = new AtomicReference<>();

		@AcpAgent
		class CloseSessionAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@CloseSession
			CloseSessionResponse closeSession(CloseSessionRequest req) {
				closedSessionId.set(req.sessionId());
				return new CloseSessionResponse();
			}

		}

		agentSupport = AcpAgentSupport.create(new CloseSessionAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		client.initialize(new InitializeRequest(1, null)).block(TIMEOUT);
		CloseSessionResponse resp = client.closeSession(new CloseSessionRequest("session-to-close"))
				.block(TIMEOUT);

		assertThat(closedSessionId.get()).isEqualTo("session-to-close");
		assertThat(resp).isNotNull();
	}

	@Test
	void resumeSessionHandlerInvoked() throws Exception {
		AtomicReference<String> resumedSessionId = new AtomicReference<>();

		@AcpAgent
		class ResumeSessionAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@ResumeSession
			ResumeSessionResponse resumeSession(ResumeSessionRequest req) {
				resumedSessionId.set(req.sessionId());
				return new ResumeSessionResponse(null, null);
			}

		}

		agentSupport = AcpAgentSupport.create(new ResumeSessionAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		client.initialize(new InitializeRequest(1, null)).block(TIMEOUT);
		ResumeSessionResponse resp = client
				.resumeSession(new ResumeSessionRequest("existing-session", "/workspace", List.of()))
				.block(TIMEOUT);

		assertThat(resumedSessionId.get()).isEqualTo("existing-session");
		assertThat(resp).isNotNull();
	}

	// Simple test agent
	@AcpAgent(name = "simple-agent", version = "1.0")
	static class SimpleAgent {

		@Initialize
		InitializeResponse init(InitializeRequest req) {
			return InitializeResponse.ok();
		}

		@NewSession
		NewSessionResponse newSession(NewSessionRequest req) {
			return new NewSessionResponse("test-session", null, null);
		}

		@Prompt
		PromptResponse prompt(PromptRequest req, SyncPromptContext context) {
			context.sendMessage("Hello from annotation-based agent!");
			return PromptResponse.endTurn();
		}

	}

}
