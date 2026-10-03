/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sync builders run their handlers on an executor the application chooses (virtual threads, a
 * framework's worker pool), instead of the SDK's static unbounded pools; without one they keep
 * using the SDK's default pool. The default pools are no longer public constants of the entry
 * point interfaces.
 */
class HandlerExecutorTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static ExecutorService named(String name) {
		return Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			return t;
		});
	}

	@Test
	void syncAgentAndClientHandlersRunOnTheGivenExecutors() {
		ExecutorService agentPool = named("app-agent-pool");
		ExecutorService clientPool = named("app-client-pool");
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AtomicReference<String> promptThread = new AtomicReference<>();
		AtomicReference<String> updateThread = new AtomicReference<>();
		AtomicReference<String> extThread = new AtomicReference<>();
		AtomicReference<AcpSyncAgent> self = new AtomicReference<>();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.handlerExecutor(agentPool)
			.initializeHandler(req -> AcpSchema.InitializeResponse.ok())
			.newSessionHandler(req -> new AcpSchema.NewSessionResponse("s1", null, null))
			.promptHandler((request, context) -> {
				promptThread.set(Thread.currentThread().getName());
				context.sendMessage("hi");
				self.get().sendExtRequest("_x/where", Map.of());
				return AcpSchema.PromptResponse.endTurn();
			})
			.build();
		AcpSyncClient client = AcpClient.sync(pair.clientTransport())
			.handlerExecutor(clientPool)
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(n -> updateThread.set(Thread.currentThread().getName()))
			.extRequestHandler("_x/where", params -> {
				extThread.set(Thread.currentThread().getName());
				return Map.of();
			})
			.build();
		self.set(agent);
		try {
			agent.start();
			client.initialize();
			client.newSession(new AcpSchema.NewSessionRequest("/w", List.of()));
			client.prompt(new AcpSchema.PromptRequest("s1", List.of(new AcpSchema.TextContent("go"))));

			assertThat(promptThread.get()).isEqualTo("app-agent-pool");
			assertThat(updateThread.get()).isEqualTo("app-client-pool");
			assertThat(extThread.get()).isEqualTo("app-client-pool");
		}
		finally {
			client.close();
			agent.close();
			pair.closeGracefully().block(TIMEOUT);
			agentPool.shutdownNow();
			clientPool.shutdownNow();
		}
	}

	@Test
	void theDefaultPoolsAreNotPublicConstants() {
		for (Class<?> type : List.of(AcpAgent.class, AcpClient.class)) {
			for (Field field : type.getDeclaredFields()) {
				assertThat(field.getType().getName()).as(type.getSimpleName() + "." + field.getName())
					.isNotEqualTo("reactor.core.scheduler.Scheduler");
				assertThat(Modifier.isPublic(field.getModifiers()) && field.getName().startsWith("DEFAULT_"))
					.as(type.getSimpleName() + "." + field.getName())
					.isFalse();
			}
		}
	}

}
