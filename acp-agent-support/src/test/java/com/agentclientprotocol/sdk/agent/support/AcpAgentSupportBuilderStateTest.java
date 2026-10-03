/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code AcpAgentSupport.Builder} refuses the states that would silently build a useless agent,
 * and {@code run()} on the builder builds, starts and blocks until the transport ends, as the
 * documentation's bootstrap snippet {@code create(bean).transport(..).run()} expects.
 */
class AcpAgentSupportBuilderStateTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@AcpAgent
	static class EchoAgent {

		@Prompt
		PromptResponse prompt() {
			return PromptResponse.endTurn();
		}

	}

	@Test
	void buildingWithoutAnAgentBeanIsRefused() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		assertThatThrownBy(() -> AcpAgentSupport.builder().transport(pair.agentTransport()).build())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("@AcpAgent")
			.hasMessageContaining("create(");
		assertThatThrownBy(() -> AcpAgentSupport.builder().buildFactory())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("@AcpAgent");
	}

	@Test
	void aFactoryWithATransportSetIsRefused() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		assertThatThrownBy(() -> AcpAgentSupport.create(new EchoAgent()).transport(pair.agentTransport()).buildFactory())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("transport(")
			.hasMessageContaining("build()");
	}

	@Test
	void runOnTheBuilderServesUntilTheTransportEnds() throws Exception {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAgentSupport.Builder builder = AcpAgentSupport.create(new EchoAgent())
			.transport(pair.agentTransport())
			.requestTimeout(TIMEOUT);
		CompletableFuture<Void> running = CompletableFuture.runAsync(builder::run);
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		assertThat(client.initialize().block(TIMEOUT).protocolVersion()).isEqualTo(1);
		assertThat(running).isNotDone();

		client.closeGracefully().block(TIMEOUT);
		pair.closeGracefully().block(TIMEOUT);

		running.get(5, TimeUnit.SECONDS);
	}

}
