/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.PromptContext;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Authenticate;
import com.agentclientprotocol.sdk.annotation.Cancel;
import com.agentclientprotocol.sdk.annotation.CloseSession;
import com.agentclientprotocol.sdk.annotation.DeleteSession;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.ListProviders;
import com.agentclientprotocol.sdk.annotation.ListSessions;
import com.agentclientprotocol.sdk.annotation.LoadSession;
import com.agentclientprotocol.sdk.annotation.Logout;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.SetProvider;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.annotation.ResumeSession;
import com.agentclientprotocol.sdk.annotation.SetSessionMode;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AuthenticateRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.AuthenticateResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.CancelNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.CloseSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.CloseSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.DeleteSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.DeleteSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ListProvidersRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.ListProvidersResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ListSessionsRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.ListSessionsResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.LoadSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.LogoutRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.LogoutResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.LoadSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ProviderInfo;
import com.agentclientprotocol.sdk.spec.AcpSchema.ResumeSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.ResumeSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionInfo;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetProviderRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetProviderResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.StopReason;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;

import reactor.core.publisher.Mono;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link AcpAgentSupport}.
 */
class AcpAgentSupportTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

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
		InitializeResponse initResp = client.initialize().block(TIMEOUT);
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
				ctx.sendMessage("Got it!");
				return PromptResponse.endTurn();
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

		client.initialize().block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
		client.prompt(new PromptRequest("capture-session", List.of(new TextContent("Test message")))).block(TIMEOUT);

		assertThat(receivedSessionId.get()).isEqualTo("capture-session");
		assertThat(receivedPrompt.get()).contains("Test message");
	}

	/**
	 * A {@code @Prompt} method's String is the agent's reply: the client receives it as an
	 * agent message chunk of the turn, then the turn ends.
	 */
	@Test
	void aStringReturnedByAPromptMethodReachesTheClientAsAMessageChunk() throws Exception {
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
				return "hello";
			}

		}

		agentSupport = AcpAgentSupport.create(new StringReturningAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();

		List<SessionNotification> updates = new CopyOnWriteArrayList<>();
		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.sessionUpdateConsumer(notification -> {
					updates.add(notification);
					return Mono.empty();
				})
				.build();

		client.initialize().block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
		PromptResponse resp = client.prompt(new PromptRequest("string-session", List.of(new TextContent("test"))))
				.block(TIMEOUT);

		assertThat(resp.stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(updates).singleElement().satisfies(notification -> {
			assertThat(notification.sessionId()).isEqualTo("string-session");
			assertThat(notification.update()).isInstanceOfSatisfying(AgentMessageChunk.class,
					chunk -> assertThat(chunk.content()).isEqualTo(new TextContent("hello")));
		});
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

		client.initialize().block(TIMEOUT);
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

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
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

		client.initialize().block(TIMEOUT);
		LoadSessionResponse resp = client.loadSession(new LoadSessionRequest("existing-session", "/workspace", List.of()))
				.block(TIMEOUT);

		assertThat(loadedSessionId.get()).isEqualTo("existing-session");
		assertThat(resp).isNotNull();
	}

	@Test
	void requestHandlerWithoutResultIsRejectedWhenBuilt() {
		// A void handler for a request used to produce no JSON-RPC response at all, then an
		// error at every call. It is now rejected when the agent is built.
		@AcpAgent
		class VoidSetModeAgent {

			@SetSessionMode
			void setMode(SetSessionModeRequest req) {
			}

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
			}

		}

		assertThatThrownBy(() -> AcpAgentSupport.create(new VoidSetModeAgent())
			.transport(transportPair.agentTransport())
			.build()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("setMode")
			.hasMessageContaining("void")
			.hasMessageContaining(SetSessionModeResponse.class.getSimpleName());
	}

	@Test
	void handlerReturningWrongTypeIsRejectedWhenBuilt() {
		// A handler whose result is not the method's response type used to fail at every call.
		@AcpAgent
		class WrongTypeSetModeAgent {

			@SetSessionMode
			String setMode(SetSessionModeRequest req) {
				return "code";
			}

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
			}

		}

		assertThatThrownBy(() -> AcpAgentSupport.create(new WrongTypeSetModeAgent())
			.transport(transportPair.agentTransport())
			.build()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("setMode")
			.hasMessageContaining("String")
			.hasMessageContaining(SetSessionModeResponse.class.getSimpleName());
	}

	@Test
	void requestVetoedByInterceptorAnswersWithError() throws Exception {
		// A preInvoke veto on a request used to produce no JSON-RPC response at all.
		AcpInterceptor vetoSetMode = new AcpInterceptor() {
			@Override
			public boolean preInvoke(AcpInvocationContext context) {
				return !"session/set_mode".equals(context.getAcpMethod());
			}
		};

		@AcpAgent
		class SetModeAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@NewSession
			NewSessionResponse newSession() {
				return new NewSessionResponse("s", null, null);
			}

			@SetSessionMode
			SetSessionModeResponse setMode(SetSessionModeRequest req) {
				return new SetSessionModeResponse();
			}

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
			}

		}

		agentSupport = AcpAgentSupport.create(new SetModeAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.interceptor(vetoSetMode)
				.build();
		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport()).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);

		assertThatThrownBy(() -> client.setSessionMode(new SetSessionModeRequest("s", "code")).block(TIMEOUT))
			.hasMessageContaining("produced no response");
	}

	@Test
	void afterCompletionRunsExactlyOncePerInvocationOnEveryPath() throws Exception {
		// A veto (or a preInvoke failure) used to run afterCompletion twice: inside
		// applyPreInvoke and again from the invocation's finally block.
		Map<String, AtomicInteger> completions = new ConcurrentHashMap<>();
		AcpInterceptor counting = new AcpInterceptor() {
			@Override
			public int getOrder() {
				return 0;
			}

			@Override
			public void afterCompletion(AcpInvocationContext context) {
				completions.computeIfAbsent(context.getAcpMethod(), m -> new AtomicInteger()).incrementAndGet();
			}
		};
		AcpInterceptor vetoOrFail = new AcpInterceptor() {
			@Override
			public int getOrder() {
				return 1;
			}

			@Override
			public boolean preInvoke(AcpInvocationContext context) {
				if ("session/close".equals(context.getAcpMethod())) {
					throw new IllegalStateException("preInvoke failed");
				}
				return !"session/set_mode".equals(context.getAcpMethod());
			}
		};

		@AcpAgent
		class LifecycleAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@NewSession
			NewSessionResponse newSession() {
				return new NewSessionResponse("s", null, null);
			}

			@LoadSession
			LoadSessionResponse load(LoadSessionRequest req) {
				throw new IllegalStateException("handler failed");
			}

			@SetSessionMode
			SetSessionModeResponse setMode(SetSessionModeRequest req) {
				return new SetSessionModeResponse();
			}

			@CloseSession
			CloseSessionResponse close(CloseSessionRequest req) {
				return new CloseSessionResponse();
			}

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
			}

		}

		agentSupport = AcpAgentSupport.create(new LifecycleAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.interceptor(counting)
				.interceptor(vetoOrFail)
				.build();
		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport()).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);

		client.newSession(new NewSessionRequest("/", List.of())).block(TIMEOUT);
		assertThatThrownBy(() -> client.loadSession(new LoadSessionRequest("s", "/", List.of())).block(TIMEOUT))
			.hasMessage("Internal error");
		assertThatThrownBy(() -> client.setSessionMode(new SetSessionModeRequest("s", "code")).block(TIMEOUT))
			.hasMessageContaining("produced no response");
		assertThatThrownBy(() -> client.closeSession(new CloseSessionRequest("s")).block(TIMEOUT))
			.hasMessage("Internal error");

		assertThat(completions).containsOnlyKeys("initialize", "session/new", "session/load", "session/set_mode",
				"session/close");
		assertThat(completions.values()).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
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

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
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

		client.initialize().block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
		client.setSessionMode(new SetSessionModeRequest("mode-session", "code-review")).block(TIMEOUT);

		assertThat(receivedModeId.get()).isEqualTo("code-review");
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

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
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

		client.initialize().block(TIMEOUT);
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

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
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

		client.initialize().block(TIMEOUT);
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

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
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

		client.initialize().block(TIMEOUT);
		CloseSessionResponse resp = client.closeSession(new CloseSessionRequest("session-to-close"))
				.block(TIMEOUT);

		assertThat(closedSessionId.get()).isEqualTo("session-to-close");
		assertThat(resp).isNotNull();
	}

	@Test
	void promptHandlerTakingAsyncPromptContextIsInvoked() throws Exception {
		// PromptContextResolver accepted a PromptContext parameter, but only the sync context
		// was ever supplied, so the call failed with an ArgumentResolutionException.
		AtomicReference<String> receivedSessionId = new AtomicReference<>();
		List<String> updates = new java.util.concurrent.CopyOnWriteArrayList<>();

		@AcpAgent
		class AsyncContextAgent {

			@NewSession
			NewSessionResponse newSession() {
				return new NewSessionResponse("async-context-session", null, null);
			}

			@Prompt
			Mono<PromptResponse> prompt(PromptRequest req, PromptContext ctx) {
				receivedSessionId.set(ctx.getSessionId());
				return ctx.sendMessage("from the async context").then(Mono.just(PromptResponse.endTurn()));
			}

		}

		agentSupport = AcpAgentSupport.create(new AsyncContextAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.sessionUpdateConsumer(notification -> {
					updates.add(notification.update().toString());
					return Mono.empty();
				})
				.build();

		client.initialize().block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
		PromptResponse resp = client
				.prompt(new PromptRequest("async-context-session", List.of(new TextContent("Hi"))))
				.block(TIMEOUT);

		assertThat(resp.stopReason()).isEqualTo(PromptResponse.endTurn().stopReason());
		assertThat(receivedSessionId.get()).isEqualTo("async-context-session");
		assertThat(updates).anySatisfy(update -> assertThat(update).contains("from the async context"));
	}

	@Test
	void authenticateHandlerInvoked() throws Exception {
		AtomicReference<String> methodId = new AtomicReference<>();

		@AcpAgent
		class AuthenticateAgent {

			@Authenticate
			AuthenticateResponse authenticate(AuthenticateRequest req) {
				methodId.set(req.methodId());
				return new AuthenticateResponse();
			}

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
			}

		}

		agentSupport = AcpAgentSupport.create(new AuthenticateAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		client.initialize().block(TIMEOUT);
		AuthenticateResponse resp = client.authenticate(new AuthenticateRequest("api-key")).block(TIMEOUT);

		assertThat(methodId.get()).isEqualTo("api-key");
		assertThat(resp).isNotNull();
	}

	@Test
	void logoutHandlerInvoked() throws Exception {
		AtomicReference<Boolean> loggedOut = new AtomicReference<>(false);

		@AcpAgent
		class LogoutAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@Logout
			LogoutResponse logout(LogoutRequest req) {
				loggedOut.set(true);
				return new LogoutResponse();
			}

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
			}

		}

		agentSupport = AcpAgentSupport.create(new LogoutAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		client.initialize().block(TIMEOUT);
		LogoutResponse resp = client.logout(new LogoutRequest()).block(TIMEOUT);

		assertThat(loggedOut.get()).isTrue();
		assertThat(resp).isNotNull();
	}

	@Test
	void deleteSessionHandlerInvoked() throws Exception {
		AtomicReference<String> deletedSessionId = new AtomicReference<>();

		@AcpAgent
		class DeleteSessionAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@DeleteSession
			DeleteSessionResponse deleteSession(DeleteSessionRequest req) {
				deletedSessionId.set(req.sessionId());
				return new DeleteSessionResponse();
			}

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
			}

		}

		agentSupport = AcpAgentSupport.create(new DeleteSessionAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		client.initialize().block(TIMEOUT);
		DeleteSessionResponse resp = client.deleteSession(new DeleteSessionRequest("session-to-delete"))
				.block(TIMEOUT);

		assertThat(deletedSessionId.get()).isEqualTo("session-to-delete");
		assertThat(resp).isNotNull();
	}

	@Test
	void listProvidersHandlerInvoked() throws Exception {
		@AcpAgent
		class ListProvidersAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@ListProviders
			ListProvidersResponse listProviders(ListProvidersRequest req) {
				return new ListProvidersResponse(List.of(new ProviderInfo("openai", List.of("openai"), false, null)));
			}

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
			}

		}

		agentSupport = AcpAgentSupport.create(new ListProvidersAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		client.initialize().block(TIMEOUT);
		ListProvidersResponse resp = client.listProviders(new ListProvidersRequest()).block(TIMEOUT);

		assertThat(resp).isNotNull();
		assertThat(resp.providers()).hasSize(1);
		assertThat(resp.providers().get(0).providerId()).isEqualTo("openai");
	}

	@Test
	void setProviderHandlerInvoked() throws Exception {
		AtomicReference<String> configuredId = new AtomicReference<>();
		AtomicReference<String> configuredBaseUrl = new AtomicReference<>();

		@AcpAgent
		class SetProviderAgent {

			@Initialize
			InitializeResponse init() {
				return InitializeResponse.ok();
			}

			@SetProvider
			SetProviderResponse setProvider(SetProviderRequest req) {
				configuredId.set(req.providerId());
				configuredBaseUrl.set(req.baseUrl());
				return new SetProviderResponse();
			}

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
			}

		}

		agentSupport = AcpAgentSupport.create(new SetProviderAgent())
				.transport(transportPair.agentTransport())
				.requestTimeout(TIMEOUT)
				.build();

		agentSupport.start();
		Thread.sleep(100);

		client = AcpClient.async(transportPair.clientTransport())
				.requestTimeout(TIMEOUT)
				.build();

		client.initialize().block(TIMEOUT);
		SetProviderResponse resp = client
				.setProvider(new SetProviderRequest("main", "anthropic", "https://api.anthropic.com"))
				.block(TIMEOUT);

		assertThat(configuredId.get()).isEqualTo("main");
		assertThat(configuredBaseUrl.get()).isEqualTo("https://api.anthropic.com");
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

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
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

		client.initialize().block(TIMEOUT);
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
