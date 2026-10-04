/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.integration.AcpAgentDiscovery.AgentCandidate;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AcpAgentsTest {

	@Test
	void assemblesTheUserClassOnTheInstanceWithTheExtensionBeans() {
		TestAgents.ProxiedEchoAgent proxy = new TestAgents.ProxiedEchoAgent();
		TestAgents.RecordingInterceptor interceptor = new TestAgents.RecordingInterceptor();
		AcpAgentSettings settings = AcpAgentSettings.builder()
			.requestTimeout(Duration.ofSeconds(5))
			.cancelGracePeriod(Duration.ofSeconds(1))
			.maxPromptDuration(Duration.ofMinutes(1))
			.build();
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAgentSupport agent = AcpAgents
			.builder(new AgentCandidate<>("echo", TestAgents.EchoAgent.class, () -> proxy), settings,
					List.of(interceptor), List.of(new TestAgents.SuffixResolver()), List.of(new TestAgents.ReplyHandler()))
			.transport(pair.agentTransport())
			.build();
		agent.start();
		try {
			assertThat(TestAgents.roundTrip(pair.clientTransport())).containsExactly("echo: hello!");
			// Discovered on the user class, invoked on the instance: the "proxy" ran.
			assertThat(proxy.calls).hasValue(1);
			assertThat(interceptor.methods).contains("session/prompt");
		}
		finally {
			agent.close();
		}
	}

	@Test
	void unsetTimeoutsKeepTheSdkDefaults() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAgentSupport agent = AcpAgents
			.builder(new AgentCandidate<>("echo", TestAgents.EchoAgent.class, TestAgents.EchoAgent::new),
					AcpAgentSettings.builder().build(), List.of(), List.of(new TestAgents.SuffixResolver()),
					List.of(new TestAgents.ReplyHandler()))
			.transport(pair.agentTransport())
			.build();
		agent.start();
		try {
			assertThat(TestAgents.roundTrip(pair.clientTransport())).containsExactly("echo: hello!");
		}
		finally {
			agent.close();
		}
	}

	@Test
	void handlersRunOnTheGivenExecutor() {
		ExecutorService executor = Executors.newCachedThreadPool(runnable -> new Thread(runnable, "framework-worker"));
		TestAgents.EchoAgent echo = new TestAgents.EchoAgent();
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAgentSupport agent = AcpAgents
			.builder(new AgentCandidate<>("echo", TestAgents.EchoAgent.class, () -> echo),
					AcpAgentSettings.builder().build(), List.of(), List.of(new TestAgents.SuffixResolver()),
					List.of(new TestAgents.ReplyHandler()), executor)
			.transport(pair.agentTransport())
			.build();
		agent.start();
		try {
			assertThat(TestAgents.roundTrip(pair.clientTransport())).containsExactly("echo: hello!");
			assertThat(echo.threads).containsExactly("framework-worker");
		}
		finally {
			agent.close();
			executor.shutdownNow();
		}
	}

}
