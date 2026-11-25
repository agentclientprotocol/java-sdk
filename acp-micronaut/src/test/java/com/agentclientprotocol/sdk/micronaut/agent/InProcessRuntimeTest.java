/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.agent.transport.InMemoryTransportPair;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runtime in process, and closing an {@code AutoCloseable} agent bean. The acceptance test of an
 * application with an unannotated agent is in acp-micronaut-integration-tests.
 */
class InProcessRuntimeTest {

	@Test
	void inProcessTheApplicationsClientIsInitializedOnceTheAgentHasStarted() {
		try (ApplicationContext context = ApplicationContext
			.run(Map.of(TestAgents.ECHO, "true", "acp.agent.transport.type", "in-process"))) {
			assertThat(context.findBean(InMemoryTransportPair.class)).isPresent();
			AcpSyncClient client = context.getBean(AcpSyncClient.class);

			// No initialize() of the test's own: the runtime did it.
			assertThat(client.newSession(new AcpSchema.NewSessionRequest("/", List.of())).sessionId()).isNotBlank();
			assertThat(context.getBean(AcpAgentRuntime.class).isRunning()).isTrue();
		}
	}

	@Test
	void inProcessWithTheAgentDisabledThereIsNoPairAndNoClient() {
		try (ApplicationContext context = ApplicationContext.run(Map.of(TestAgents.ECHO, "true",
				"acp.agent.transport.type", "in-process", "acp.agent.enabled", "false"))) {
			assertThat(context.findBean(InMemoryTransportPair.class)).isEmpty();
			assertThat(context.findBean(AcpSyncClient.class)).isEmpty();
		}
	}

	@Test
	void anAutoCloseableAgentBeanIsClosedOnceTheAgentHasStopped() {
		TestAgents.ClosingAgent.CLOSES.set(0);
		ApplicationContext.run(Map.of(TestAgents.CLOSING, "true", TestAgents.IN_MEMORY, "true")).close();
		assertThat(TestAgents.ClosingAgent.CLOSES).hasValue(1);
	}

	@Test
	void anAgentBeanThatFailsToCloseDoesNotStopTheContextClosing() {
		TestAgents.ClosingAgent.CLOSES.set(0);
		ApplicationContext context = ApplicationContext
			.run(Map.of(TestAgents.CLOSING, "true", TestAgents.CLOSING_FAILS, "true", TestAgents.IN_MEMORY, "true"));
		context.close();
		assertThat(TestAgents.ClosingAgent.CLOSES).hasValue(1);
		assertThat(context.isRunning()).isFalse();
	}

}
