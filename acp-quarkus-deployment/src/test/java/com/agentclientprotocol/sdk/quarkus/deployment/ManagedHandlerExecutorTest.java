/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.util.VirtualThreads;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code quarkus.acp.handler-executor=managed} keeps the handlers on Quarkus' worker pool, through
 * the {@code ManagedExecutor}, on every JDK, for handlers that need the contexts it propagates.
 */
class ManagedHandlerExecutorTest {

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(ThreadAgent.class, InMemoryAgentTransport.class))
		.overrideConfigKey("quarkus.acp.agent.shutdown-on-transport-end", "false")
		.overrideConfigKey("quarkus.acp.handler-executor", "managed");

	@Inject
	InMemoryAgentTransport transport;

	@Test
	void handlersRunOnTheManagedExecutor() {
		AcpSyncClient client = transport.client();
		client.initialize();
		String sessionId = client.newSession(InMemoryAgentTransport.newSession()).sessionId();
		client.prompt(InMemoryAgentTransport.prompt(sessionId, "hello"));
		assertThat(Arc.container().instance(ThreadAgent.class).get().threads).singleElement().satisfies(thread -> {
			assertThat(VirtualThreads.isVirtual(thread)).as("virtual: %s", thread).isFalse();
			assertThat(thread.getName()).startsWith("executor-thread-");
		});
	}

	@AcpAgent(name = "threads")
	public static class ThreadAgent {

		final List<Thread> threads = new CopyOnWriteArrayList<>();

		@Prompt
		AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
			threads.add(Thread.currentThread());
			return AcpSchema.PromptResponse.endTurn();
		}

	}

}
