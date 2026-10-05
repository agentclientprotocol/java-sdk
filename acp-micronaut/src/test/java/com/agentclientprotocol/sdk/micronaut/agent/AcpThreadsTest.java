/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.util.VirtualThreads;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * On JDK 21 the agent's handlers, its listener and the client's network transport all run on
 * Micronaut's virtual-thread executor, so the SDK creates no pool of its own.
 */
class AcpThreadsTest {

	@ParameterizedTest
	@ValueSource(strings = { "http", "websocket" })
	void onJdk21TheSdkCreatesNoPoolOfItsOwn(String type) {
		assumeTrue(VirtualThreads.isSupported(), "virtual threads need JDK 21");
		Set<Thread> before = Thread.getAllStackTraces().keySet();
		Set<String> created = new HashSet<>();
		try (ApplicationContext agent = ApplicationContext.run(Map.of(TestAgents.ECHO, "true",
				"acp.agent.transport.type", type, "acp.agent.transport.http.listener.port", "0"))) {
			int port = agent.getBean(AcpAgentRuntime.class).port().orElseThrow();
			String uri = (type.equals("http") ? "http" : "ws") + "://localhost:" + port + "/acp";
			try (ApplicationContext client = ApplicationContext.run(Map.of("acp.client.transport.type", type,
					"acp.client.transport." + type + ".uri", uri))) {
				AcpSyncClient acp = client.getBean(AcpSyncClient.class);
				acp.initialize();
				String sessionId = acp.newSession(new AcpSchema.NewSessionRequest("/tmp", List.of())).sessionId();
				AcpSchema.PromptResponse response = acp
					.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("hi"))));
				assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
				created.addAll(newPlatformThreads(before));
			}
			assertThat(agent.getBean(TestAgents.EchoAgent.class).promptThreads).singleElement()
				.satisfies(thread -> assertThat(VirtualThreads.isVirtual(thread)).as("handler on %s", thread).isTrue());
		}
		// What remains is not a pool of the SDK's: Jetty's VirtualThreadPool parks one platform
		// thread while the listener runs, the JDK's HttpClient has one selector thread, and the
		// SDK's one JVM-wide timer serves every timeout.
		assertThat(created).allMatch(name -> name.equals("jetty-virtual-thread-pool-keepalive")
				|| name.equals("acp-timeout") || name.matches("HttpClient-\\d+-SelectorManager"), "allowed")
			.contains("jetty-virtual-thread-pool-keepalive");
		assertThat(created.stream().filter(name -> name.startsWith("HttpClient-"))).hasSizeLessThanOrEqualTo(1);
	}

	private static Set<String> newPlatformThreads(Set<Thread> before) {
		return Thread.getAllStackTraces()
			.keySet()
			.stream()
			.filter(thread -> !before.contains(thread))
			.map(Thread::getName)
			.filter(name -> Stream.of("acp-", "qtp", "HttpClient-", "Scheduler-", "jetty-").anyMatch(name::startsWith))
			.collect(Collectors.toSet());
	}

}
