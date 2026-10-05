/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.integration.AcpAgentDiscovery.AgentCandidate;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AcpTransportThreads#platform()}: an application that has not opted into virtual threads
 * (Spring Boot without {@code spring.threads.virtual.enabled}) gets platform threads on every JDK.
 * On JDK 17 that is all there is; on JDK 21 it is the choice that keeps the network client
 * transports and the listener off the virtual threads they would otherwise default to.
 */
class AcpPlatformThreadsTest {

	private static final AcpAgentSettings SETTINGS = AcpAgentSettings.builder()
		.transport(AcpTransportType.HTTP)
		.listenerPort(0)
		.path("/agents/echo")
		.shutdownTimeout(Duration.ofSeconds(2))
		.build();

	@Test
	void theClientTransportsRunOnPlatformThreadsOnEveryJdk() {
		AcpListenerHost host = new AcpListenerHost(
				AcpListeners.listener(SETTINGS, factory(), AcpTransportThreads.platform()));
		try {
			host.start();
			int port = host.port().orElseThrow();
			URI http = URI.create("http://localhost:" + port + "/agents/echo");
			URI ws = URI.create("ws://localhost:" + port + "/agents/echo");

			Set<String> httpThreads = platformThreadsDuringAPrompt(AcpClientSettings.builder().httpUri(http).build(),
					AcpTransportThreads.platform());
			assertThat(httpThreads).contains("acp-streamable-http-client");

			Set<String> wsThreads = platformThreadsDuringAPrompt(AcpClientSettings.builder().websocketUri(ws).build(),
					AcpTransportThreads.platform());
			assertThat(wsThreads).contains("acp-ws-client");
		}
		finally {
			host.stop(Duration.ofSeconds(10));
		}
	}

	/**
	 * Prompts the echo agent over the transport the settings describe, on the given threads, and
	 * returns the names of the live platform threads while the agent's reply arrives (the JVM
	 * lists no virtual threads there).
	 */
	private static Set<String> platformThreadsDuringAPrompt(AcpClientSettings settings,
			AcpTransportThreads threads) {
		AcpClientTransport transport = AcpClientTransports.create(settings, "acp.client", threads).orElseThrow();
		Set<String> names = new CopyOnWriteArraySet<>();
		AcpSyncClient client = AcpClient.sync(transport)
			.requestTimeout(Duration.ofSeconds(10))
			.sessionUpdateHandler(notification -> names.addAll(Thread.getAllStackTraces()
				.keySet()
				.stream()
				.map(Thread::getName)
				.collect(Collectors.toSet())))
			.build();
		try {
			client.initialize();
			String session = client.newSession(new AcpSchema.NewSessionRequest("/", List.of())).sessionId();
			client.prompt(new AcpSchema.PromptRequest(session, List.of(new AcpSchema.TextContent("hello"))));
		}
		finally {
			client.closeGracefully();
		}
		assertThat(names).isNotEmpty();
		return names;
	}

	private static com.agentclientprotocol.sdk.agent.AcpAgentFactory factory() {
		return AcpAgents
			.builder(new AgentCandidate<>("echo", TestAgents.EchoAgent.class, TestAgents.EchoAgent::new), SETTINGS,
					List.of(), List.of(new TestAgents.SuffixResolver()), List.of(new TestAgents.ReplyHandler()))
			.buildFactory();
	}

}
