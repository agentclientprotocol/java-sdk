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
 * An annotated agent chooses the executor its handler methods run on, as a builder agent does.
 */
class AcpAgentSupportHandlerExecutorTest {

	@AcpAgent
	static class ThreadReportingAgent {

		final java.util.concurrent.atomic.AtomicReference<String> thread = new java.util.concurrent.atomic.AtomicReference<>();

		@Prompt
		PromptResponse prompt(PromptRequest request) {
			this.thread.set(Thread.currentThread().getName());
			return PromptResponse.endTurn();
		}

	}

	/** An annotated agent's methods run on the executor given to the builder. */
	@Test
	void handlerMethodsRunOnTheGivenExecutor() {
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "app-annotated-pool");
			t.setDaemon(true);
			return t;
		});
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		ThreadReportingAgent bean = new ThreadReportingAgent();
		AcpAgentSupport support = AcpAgentSupport.create(bean)
			.transport(pair.agentTransport())
			.handlerExecutor(pool)
			.build();
		try {
			support.start();
			com.agentclientprotocol.sdk.client.AcpSyncClient client = com.agentclientprotocol.sdk.client.AcpClient
				.sync(pair.clientTransport())
				.build();
			client.initialize();
			client.newSession(new com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest("/w", java.util.List.of()));
			client.prompt(new PromptRequest("s", java.util.List.of(new com.agentclientprotocol.sdk.spec.AcpSchema.TextContent("go"))));
			client.close();
		}
		finally {
			support.close();
			pool.shutdownNow();
			pair.closeGracefully().block(Duration.ofSeconds(5));
		}
		assertThat(bean.thread.get()).isEqualTo("app-annotated-pool");
	}

}
