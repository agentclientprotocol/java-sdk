/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.DisableProvider;
import com.agentclientprotocol.sdk.annotation.ForkSession;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.annotation.SetSessionConfigOption;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema.DisableProviderRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.DisableProviderResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ForkSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.ForkSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.StopReason;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Annotated agents end to end: the unstable handlers not covered elsewhere, reactive
 * return values, the error a client sees when a handler cannot be invoked or answered, and
 * the lifetime of an agent registered by class.
 */
class AcpAgentSupportErrorPathTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final InMemoryTransportPair transportPair = InMemoryTransportPair.create();

	private AcpAgentSupport agentSupport;

	private AcpAsyncClient client;

	@AfterEach
	void tearDown() {
		if (client != null) {
			client.closeGracefully().block(TIMEOUT);
		}
		if (agentSupport != null) {
			agentSupport.close();
		}
	}

	private AcpAsyncClient connect(AcpAgentSupport.Builder builder) {
		agentSupport = builder.transport(transportPair.agentTransport()).requestTimeout(TIMEOUT).build();
		agentSupport.start();
		client = AcpClient.async(transportPair.clientTransport()).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);
		return client;
	}

	private static PromptRequest prompt(String sessionId) {
		return new PromptRequest(sessionId, List.of(new TextContent("hi")));
	}

	@AcpAgent
	static class UnstableHandlersAgent {

		@ForkSession
		ForkSessionResponse fork(ForkSessionRequest request) {
			return new ForkSessionResponse(request.sessionId() + "-fork", null, null, null);
		}

		@SetSessionConfigOption
		SetSessionConfigOptionResponse setConfigOption(SetSessionConfigOptionRequest request) {
			return new SetSessionConfigOptionResponse(List.of());
		}

		@DisableProvider
		DisableProviderResponse disableProvider(DisableProviderRequest request) {
			return new DisableProviderResponse();
		}

	}

	@Test
	void forkSetConfigOptionAndDisableProviderHandlersAreBound() {
		AcpAsyncClient client = connect(AcpAgentSupport.create(new UnstableHandlersAgent()));

		assertThat(client.forkSession(new ForkSessionRequest("s1", "/workspace", List.of())).block(TIMEOUT)
			.sessionId()).isEqualTo("s1-fork");
		assertThat(client.setSessionConfigOption(new SetSessionConfigOptionRequest("s1", "model", "fast", null, null))
			.block(TIMEOUT)
			.configOptions()).isEmpty();
		assertThat(client.disableProvider(new DisableProviderRequest("main")).block(TIMEOUT)).isNotNull();
	}

	@AcpAgent
	static class MonoAgent {

		@Prompt
		Mono<PromptResponse> prompt(PromptRequest request) {
			if ("empty".equals(request.sessionId())) {
				return Mono.empty();
			}
			return Mono.just(PromptResponse.endTurn());
		}

	}

	@Test
	void aMonoReturnValueIsUnwrapped() {
		AcpAsyncClient client = connect(AcpAgentSupport.create(new MonoAgent()));

		assertThat(client.prompt(prompt("s1")).block(TIMEOUT).stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void anEmptyMonoIsAnInternalError() {
		AcpAsyncClient client = connect(AcpAgentSupport.create(new MonoAgent()));

		assertThatThrownBy(() -> client.prompt(prompt("empty")).block(TIMEOUT))
			.isInstanceOfSatisfying(AcpError.class,
					e -> assertThat(e.getCode()).isEqualTo(AcpErrorCodes.INTERNAL_ERROR))
			.hasMessageContaining("produced no response");
	}

	@AcpAgent
	static class CheckedExceptionAgent {

		@Prompt
		PromptResponse prompt(PromptRequest request) throws Exception {
			throw new java.io.IOException("disk gone");
		}

	}

	@Test
	void aCheckedExceptionFromAHandlerFailsTheRequest() {
		AcpAsyncClient client = connect(AcpAgentSupport.create(new CheckedExceptionAgent()));

		assertThatThrownBy(() -> client.prompt(prompt("s1")).block(TIMEOUT))
			.isInstanceOf(AcpError.class)
			.hasMessageContaining("disk gone");
	}

	/** Keeps its sessions in a field, as the module README's complete example does. */
	@AcpAgent
	public static class StatefulAgent {

		static final AtomicInteger instances = new AtomicInteger();

		private final Map<String, String> sessions = new ConcurrentHashMap<>();

		public StatefulAgent() {
			instances.incrementAndGet();
		}

		@NewSession
		NewSessionResponse newSession(NewSessionRequest request) {
			sessions.put("s1", request.cwd());
			return new NewSessionResponse("s1", null, null);
		}

		@Prompt
		PromptResponse prompt(PromptRequest request) {
			if (!sessions.containsKey(request.sessionId())) {
				return PromptResponse.refusal();
			}
			return PromptResponse.endTurn();
		}

	}

	@Test
	void anAgentRegisteredByClassIsOneInstanceThatKeepsItsState() {
		StatefulAgent.instances.set(0);
		AcpAsyncClient client = connect(AcpAgentSupport.create(StatefulAgent.class));

		String sessionId = client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT).sessionId();
		PromptResponse response = client.prompt(prompt(sessionId)).block(TIMEOUT);

		assertThat(response.stopReason()).as("the prompt handler sees the session the new-session handler stored")
			.isEqualTo(StopReason.END_TURN);
		assertThat(StatefulAgent.instances).hasValue(1);
	}

	@Test
	void anAgentRegisteredWithAFactoryIsCreatedOnce() {
		AtomicInteger created = new AtomicInteger();
		AcpAsyncClient client = connect(AcpAgentSupport.builder().agent(StatefulAgent.class, () -> {
			created.incrementAndGet();
			return new StatefulAgent();
		}));

		String sessionId = client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT).sessionId();
		assertThat(client.prompt(prompt(sessionId)).block(TIMEOUT).stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(created).hasValue(1);
	}

	@AcpAgent
	static class NoDefaultConstructorAgent {

		NoDefaultConstructorAgent(String required) {
		}

		@Initialize
		InitializeResponse initialize() {
			return InitializeResponse.ok();
		}

	}

	@Test
	void aClassWithoutANoArgConstructorIsRefused() {
		assertThatThrownBy(() -> AcpAgentSupport.create(NoDefaultConstructorAgent.class))
			.hasMessageContaining("Cannot instantiate")
			.hasMessageContaining(NoDefaultConstructorAgent.class.getName());
	}

	@Test
	void aClassWithoutTheAgentAnnotationIsRefused() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new Object()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("@AcpAgent");
	}

	@Test
	void buildRequiresATransport() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new MonoAgent()).build())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Transport");
	}

	/** Fails its first prompt with an Error, as code compiled against another SDK version can. */
	@AcpAgent
	static class ErrorThrowingAgent {

		private final AtomicInteger prompts = new AtomicInteger();

		@Prompt
		PromptResponse prompt(PromptRequest request) {
			int prompt = prompts.getAndIncrement();
			if (prompt == 0) {
				throw new NoSuchMethodError("secret payload");
			}
			if (prompt == 1) {
				throw new AssertionError("secret payload");
			}
			return PromptResponse.endTurn();
		}

	}

	/**
	 * A handler method that throws an Error is answered -32603 naming the method, and the
	 * connection keeps serving. Before, Reactor rethrew the NoSuchMethodError on the handler
	 * thread and the client waited out its request timeout.
	 */
	@Test
	void aHandlerThrowingAnErrorIsAnsweredInternalErrorAndTheConnectionServesOn() {
		AcpAsyncClient client = connect(AcpAgentSupport.create(new ErrorThrowingAgent()));

		for (int i = 0; i < 2; i++) {
			assertThatThrownBy(() -> client.prompt(prompt("s1")).block(TIMEOUT))
				.isInstanceOfSatisfying(AcpError.class, error -> {
					assertThat(error.getCode()).isEqualTo(AcpErrorCodes.INTERNAL_ERROR);
					assertThat(error.getMessage()).contains("session/prompt").doesNotContain("secret");
				});
		}
		assertThat(client.prompt(prompt("s1")).block(TIMEOUT).stopReason()).isEqualTo(StopReason.END_TURN);
	}

}
