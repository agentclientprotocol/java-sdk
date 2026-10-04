/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;
import java.util.List;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A builder agent is complete by construction: it needs a prompt handler, and without an
 * initialize handler it answers {@code initialize} with what its handlers imply, as an annotated
 * agent does, instead of "Method not found". Without a new-session handler it answers
 * {@code session/new} with a fresh session id, as an annotated agent does.
 */
class BuilderDefaultsTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void aPromptOnlyAgentAnswersInitialize() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.promptHandler((request, context) -> PromptResponse.endTurn())
			.build();
		InitializeResponse response = initialize(pair, agent);

		assertThat(response.protocolVersion()).isEqualTo(AcpSchema.LATEST_PROTOCOL_VERSION);
		assertThat(response.agentCapabilities().loadSession()).isFalse();
		assertThat(response.agentCapabilities().sessionCapabilities()).isNull();
	}

	@Test
	void theDefaultInitializeAdvertisesTheRegisteredHandlers() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.promptHandler((request, context) -> Mono.just(PromptResponse.endTurn()))
			.loadSessionHandler(request -> Mono.just(new AcpSchema.LoadSessionResponse(null)))
			.listSessionsHandler(request -> Mono.just(new AcpSchema.ListSessionsResponse(List.of())))
			.closeSessionHandler(request -> Mono.just(new AcpSchema.CloseSessionResponse(null)))
			.logoutHandler(request -> Mono.just(new AcpSchema.LogoutResponse(null)))
			.build();
		InitializeResponse response = initialize(pair, new AcpSyncAgent(agent));

		AcpSchema.AgentCapabilities capabilities = response.agentCapabilities();
		assertThat(capabilities.loadSession()).isTrue();
		assertThat(capabilities.sessionCapabilities().list()).isNotNull();
		assertThat(capabilities.sessionCapabilities().close()).isNotNull();
		assertThat(capabilities.sessionCapabilities().resume()).isNull();
		assertThat(capabilities.auth().logout()).isNotNull();
	}

	/** Any of the three provider methods advertises providers, as for an annotated agent. */
	@Test
	void anyProviderHandlerAdvertisesProviders() {
		InMemoryTransportPair setOnly = InMemoryTransportPair.create();
		AcpSyncAgent setter = AcpAgent.sync(setOnly.agentTransport())
			.promptHandler((request, context) -> PromptResponse.endTurn())
			.setProviderHandler(request -> new AcpSchema.SetProviderResponse())
			.build();
		assertThat(initialize(setOnly, setter).agentCapabilities().providers()).isNotNull();

		InMemoryTransportPair disableOnly = InMemoryTransportPair.create();
		AcpSyncAgent disabler = AcpAgent.sync(disableOnly.agentTransport())
			.promptHandler((request, context) -> PromptResponse.endTurn())
			.disableProviderHandler(request -> new AcpSchema.DisableProviderResponse())
			.build();
		assertThat(initialize(disableOnly, disabler).agentCapabilities().providers()).isNotNull();
	}

	@Test
	void theDefaultInitializeSendsTheBuildersAgentInfo() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.agentInfo(new AcpSchema.Implementation("builder-agent", "3.1"))
			.promptHandler((request, context) -> PromptResponse.endTurn())
			.build();

		assertThat(initialize(pair, agent).agentInfo()).isEqualTo(new AcpSchema.Implementation("builder-agent", "3.1"));
	}

	// ACP spec 7628b153: initialization.mdx:245, "As a baseline, all Agents MUST support
	// session/new, session/prompt, session/cancel, and session/update"
	@Test
	void aBuilderAgentWithoutANewSessionHandlerAnswersSessionNewWithAFreshId() {
		InMemoryTransportPair syncPair = InMemoryTransportPair.create();
		AcpSyncAgent syncAgent = AcpAgent.sync(syncPair.agentTransport())
			.promptHandler((request, context) -> PromptResponse.endTurn())
			.build();
		NewSessionResponse first = newSession(syncPair, syncAgent);

		InMemoryTransportPair asyncPair = InMemoryTransportPair.create();
		AcpAsyncAgent asyncAgent = AcpAgent.async(asyncPair.agentTransport())
			.promptHandler((request, context) -> Mono.just(PromptResponse.endTurn()))
			.build();
		NewSessionResponse second = newSession(asyncPair, new AcpSyncAgent(asyncAgent));

		assertThat(first.sessionId()).isNotBlank();
		assertThat(second.sessionId()).isNotBlank().isNotEqualTo(first.sessionId());
		assertThat(first.modes()).isNull();
		assertThat(first.configOptions()).isNull();
	}

	@Test
	void aNewSessionHandlerReplacesTheDefault() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.promptHandler((request, context) -> PromptResponse.endTurn())
			.newSessionHandler(request -> new NewSessionResponse("mine"))
			.build();

		assertThat(newSession(pair, agent).sessionId()).isEqualTo("mine");
	}

	@Test
	void anAgentWithoutAPromptHandlerIsRefused() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		assertThatThrownBy(() -> AcpAgent.sync(pair.agentTransport()).build())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("promptHandler");
		assertThatThrownBy(() -> AcpAgent.async(pair.agentTransport()).build())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("promptHandler");
	}

	private static NewSessionResponse newSession(InMemoryTransportPair pair, AcpSyncAgent agent) {
		agent.start();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		try {
			client.initialize().block(TIMEOUT);
			return client.newSession(new AcpSchema.NewSessionRequest("/tmp")).block(TIMEOUT);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully();
		}
	}

	private static InitializeResponse initialize(InMemoryTransportPair pair, AcpSyncAgent agent) {
		agent.start();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		try {
			return client.initialize().block(TIMEOUT);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully();
		}
	}

}
