/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.time.Duration;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An annotated agent is {@link AutoCloseable}: try-with-resources closes it, and closing ends its
 * transport.
 */
class AcpAgentSupportLifecycleTest {

	@AcpAgent
	static class Agent {

		@Prompt
		PromptResponse prompt(PromptRequest request) {
			return PromptResponse.endTurn();
		}

	}

	@Test
	void tryWithResourcesClosesTheAgentAndItsTransport() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAgentSupport closed;
		try (AcpAgentSupport support = AcpAgentSupport.create(new Agent()).transport(pair.agentTransport()).build()) {
			support.start();
			closed = support;
		}
		// The transport has ended, so waiting for it returns at once.
		closed.getAgent().async().awaitTermination().block(Duration.ofSeconds(5));
		assertThat(closed).isInstanceOf(AutoCloseable.class);
		pair.closeGracefully().block(Duration.ofSeconds(5));
	}

}
