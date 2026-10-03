/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sync facades' lifecycle names line up: {@code awaitTermination()} (not {@code await()}),
 * {@code AutoCloseable} with a graceful, bounded, then forced {@code close()}, and
 * {@code AcpSyncClient.async()} for the asynchronous client behind it.
 */
class SyncLifecycleTest {

	/** An asynchronous agent whose graceful close never completes; records the calls. */
	private static AcpAsyncAgent stuckAgent(List<String> calls) {
		return (AcpAsyncAgent) Proxy.newProxyInstance(AcpAsyncAgent.class.getClassLoader(),
				new Class<?>[] { AcpAsyncAgent.class }, (proxy, method, args) -> {
					calls.add(method.getName());
					return switch (method.getName()) {
						case "closeGracefully" -> Mono.never();
						case "awaitTermination" -> Mono.empty();
						default -> null;
					};
				});
	}

	@Test
	void aSyncAgentIsAutoCloseableAndClosesGracefullyThenForcibly() throws Exception {
		List<String> calls = new CopyOnWriteArrayList<>();
		try (AcpSyncAgent agent = new AcpSyncAgent(stuckAgent(calls), Duration.ofMillis(200))) {
			agent.awaitTermination();
		}
		assertThat(calls).containsExactly("awaitTermination", "closeGracefully", "close");
	}

	@Test
	void aSyncClientGivesItsAsynchronousClient() {
		AcpAsyncClient asyncClient = AcpClient.async(new MockAcpClientTransport()).build();
		AcpSyncClient client = new AcpSyncClient(asyncClient);
		assertThat(client.async()).isSameAs(asyncClient);
		asyncClient.close();
	}

}
