/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import static com.agentclientprotocol.sdk.micronaut.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Starting and stopping with the application context: the transport's end closes the
 * context, closing the context closes the agent, and the "exactly one agent" rule.
 */
class AcpAgentLifecycleTest {

	@Test
	void theTransportEndingClosesTheApplicationContext() {
		try (ApplicationContext context = ApplicationContext.run(TestAgents.echoInMemory())) {
			InMemoryTransportPair pair = context.getBean(InMemoryTransportPair.class);
			AcpSyncClient client = AcpClient.sync(pair.clientTransport()).requestTimeout(Duration.ofSeconds(10)).build();
			client.initialize();

			// What the end of standard input does for stdio: the agent transport ends by itself
			pair.agentTransport().closeGracefully().block(Duration.ofSeconds(5));

			eventually(Duration.ofSeconds(10), () -> assertThat(context.isRunning()).isFalse());
			eventually(Duration.ofSeconds(5), () -> assertThat(threadNamed("acp-agent-await")).isFalse());
		}
	}

	@Test
	void withShutdownOnTransportEndOffTheContextStaysUp() {
		Map<String, Object> properties = new HashMap<>(TestAgents.echoInMemory());
		properties.put("acp.agent.shutdown-on-transport-end", "false");
		try (ApplicationContext context = ApplicationContext.run(properties)) {
			InMemoryTransportPair pair = context.getBean(InMemoryTransportPair.class);
			pair.agentTransport().closeGracefully().block(Duration.ofSeconds(5));
			eventually(Duration.ofSeconds(5), () -> assertThat(threadNamed("acp-agent-await")).isFalse());
			assertThat(context.isRunning()).isTrue();
		}
	}

	@Test
	void closingTheContextClosesTheAgentOnce() {
		ApplicationContext context = ApplicationContext.run(TestAgents.echoInMemory());
		AcpAgentRuntime runtime = context.getBean(AcpAgentRuntime.class);
		assertThat(runtime.isRunning()).isTrue();
		assertThat(threadNamed("acp-agent-await")).isTrue();

		context.close();

		assertThat(runtime.isRunning()).isFalse();
		runtime.close(); // a second close does nothing
		runtime.start(); // nor does a start after the close
		assertThat(runtime.isRunning()).isFalse();
		assertThat(runtime.shutdownGracefully().toCompletableFuture().isDone()).isTrue();
		eventually(Duration.ofSeconds(5), () -> assertThat(threadNamed("acp-agent-await")).isFalse());
	}

	@Test
	void withoutAnAgentBeanNothingIsServed() {
		try (ApplicationContext context = ApplicationContext.run(Map.of(TestAgents.IN_MEMORY, "true"))) {
			AcpAgentRuntime runtime = context.getBean(AcpAgentRuntime.class);
			assertThat(runtime.isRunning()).isFalse();
			assertThat(runtime.port()).isEmpty();
			assertThat(threadNamed("acp-agent-await")).isFalse();
		}
	}

	@Test
	void disabledLeavesNoRuntime() {
		Map<String, Object> properties = new HashMap<>(TestAgents.echoInMemory());
		properties.put("acp.agent.enabled", "false");
		try (ApplicationContext context = ApplicationContext.run(properties)) {
			assertThat(context.findBean(AcpAgentRuntime.class)).isEmpty();
		}
	}

	@Test
	void moreThanOneAgentBeanIsAnErrorNamingThem() {
		Map<String, Object> properties = new HashMap<>(TestAgents.echoInMemory());
		properties.put(TestAgents.OTHER, "true");
		assertThatThrownBy(() -> ApplicationContext.run(properties)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Found 2 @AcpAgent beans")
			.hasMessageContaining(TestAgents.EchoAgent.class.getName())
			.hasMessageContaining(TestAgents.OtherAgent.class.getName());
	}

	static boolean threadNamed(String name) {
		return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.isAlive() && t.getName().equals(name));
	}

}
