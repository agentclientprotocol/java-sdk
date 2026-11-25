/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.it;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.agent.transport.InMemoryTransportPair;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The order's acceptance test for Micronaut: an {@code @AcpAgent} class, with
 * {@code acp-micronaut-processor} on the processor path and
 * {@code acp.agent.transport.type=in-process}, nothing else, gives the application an initialized
 * client of its own agent: a prompt streams updates to the application's update handler, its
 * permission handler answers, and the context closes cleanly, closing the agent.
 */
class InProcessAcceptanceTest {

	@BeforeEach
	void resetCloses() {
		ClaimsAgent.CLOSES.set(0);
	}

	@Test
	void aPromptStreamsUpdatesAndTheApplicationsHandlerAnswersThePermissionRequest() {
		ApplicationContext context = ApplicationContext.run();
		InMemoryTransportPair pair = context.getBean(InMemoryTransportPair.class);
		try {
			AcpSchema.PromptResponse response = prompt(context.getBean(AcpSyncClient.class));

			assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(context.getBean(Chat.class).messages()).containsExactly("reviewing the claim", "approved");
			assertThat(context.getBean(Reviewer.class).asked()).hasSize(1);
		}
		finally {
			context.close();
		}
		assertThat(context.isRunning()).isFalse();
		assertThat(ClaimsAgent.CLOSES).hasValue(1);
		pair.agentTransport().awaitTermination().block(Duration.ofSeconds(5));
	}

	@Test
	void withoutAPermissionHandlerTheRequestIsRefused() {
		try (ApplicationContext context = ApplicationContext.run(Map.of("test.reviewer", "false"))) {
			assertThat(context.findBean(Reviewer.class)).isEmpty();

			prompt(context.getBean(AcpSyncClient.class));

			assertThat(context.getBean(Chat.class).messages()).containsExactly("reviewing the claim", "refused");
		}
	}

	@Test
	void aClientTransportOfAnotherAgentIsRefused() {
		assertThatThrownBy(() -> ApplicationContext.run(Map.of("acp.client.transport.stdio.command", "some-agent")))
			.hasStackTraceContaining("acp.agent.transport.type=in-process connects the application's client")
			.hasStackTraceContaining("remove acp.client.transport.*");
	}

	private static AcpSchema.PromptResponse prompt(AcpSyncClient client) {
		String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/claims", List.of())).sessionId();
		return client.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("review"))));
	}

}
