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
 * {@code quarkus.acp.handler-executor=virtual} opts the handlers in to Quarkus' virtual-thread
 * executor on JDK 21 and later; before JDK 21 they stay on its worker pool, as with {@code managed}.
 */
class VirtualHandlerExecutorTest {

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(ThreadAgent.class, InMemoryAgentTransport.class))
		.overrideConfigKey("quarkus.acp.agent.shutdown-on-transport-end", "false")
		.overrideConfigKey("quarkus.acp.handler-executor", "virtual");

	@Inject
	InMemoryAgentTransport transport;

	@Test
	void handlersRunOnQuarkusVirtualThreads() {
		AcpSyncClient client = transport.client();
		client.initialize();
		String sessionId = client.newSession(InMemoryAgentTransport.newSession()).sessionId();
		client.prompt(InMemoryAgentTransport.prompt(sessionId, "hello"));
		assertThat(Arc.container().instance(ThreadAgent.class).get().threads).singleElement().satisfies(thread -> {
			if (VirtualThreads.isSupported()) {
				assertThat(VirtualThreads.isVirtual(thread)).as("virtual: %s", thread).isTrue();
				assertThat(thread.getName()).startsWith("quarkus-virtual-thread-");
			}
			else {
				assertThat(thread.getName()).startsWith("executor-thread-");
			}
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
