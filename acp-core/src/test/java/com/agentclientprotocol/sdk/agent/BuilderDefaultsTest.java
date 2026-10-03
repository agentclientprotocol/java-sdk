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
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A builder agent is complete by construction: it needs a prompt handler, and without an
 * initialize handler it answers {@code initialize} with what its handlers imply, as an annotated
 * agent does, instead of "Method not found".
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
