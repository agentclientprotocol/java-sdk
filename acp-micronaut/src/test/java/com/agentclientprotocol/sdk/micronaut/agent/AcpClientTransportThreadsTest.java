/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.integration.AcpClientCustomizer;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.util.VirtualThreads;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client's network transport runs on Micronaut's virtual-thread executor
 * ({@code TaskExecutors.VIRTUAL}) where the JDK has virtual threads, and on the SDK's own threads
 * where Micronaut has no such executor (JDK 17). The session-update handler of the async client
 * runs on the thread that read the agent's message, so it shows where the transport's work runs:
 * Micronaut names its virtual threads {@code virtual-executor-*}, the SDK names its own
 * {@code acp-*}.
 */
class AcpClientTransportThreadsTest {

	static final String RECORD_THREADS = "test.client.record-threads";

	@ParameterizedTest
	@ValueSource(strings = { "http", "websocket" })
	void theTransportRunsOnMicronautsVirtualThreadsOnJdk21ElseOnTheSdksOwn(String type) {
		try (ApplicationContext agent = ApplicationContext.run(Map.of(TestAgents.ECHO, "true",
				"acp.agent.transport.type", type, "acp.agent.transport.http.listener.port", "0"))) {
			int port = agent.getBean(AcpAgentRuntime.class).port().orElseThrow();
			String uri = (type.equals("http") ? "http" : "ws") + "://localhost:" + port + "/acp";
			try (ApplicationContext client = ApplicationContext.run(Map.of("acp.client.transport.type", type,
					"acp.client.transport." + type + ".uri", uri, RECORD_THREADS, "true"))) {
				AcpSyncClient acp = client.getBean(AcpSyncClient.class);
				acp.initialize();
				String sessionId = acp.newSession(new AcpSchema.NewSessionRequest("/tmp", List.of())).sessionId();
				acp.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("hi"))));
				List<Thread> threads = client.getBean(ThreadRecorder.class).threads;
				assertThat(threads).isNotEmpty().allSatisfy(thread -> {
					assertThat(VirtualThreads.isVirtual(thread)).as("virtual: %s", thread)
						.isEqualTo(VirtualThreads.isSupported());
					assertThat(thread.getName()).as(type)
						.startsWith(VirtualThreads.isSupported() ? "virtual-executor" : "acp-");
				});
			}
		}
	}

	/** Records the threads the session-update handler runs on. */
	@Singleton
	@Requires(property = RECORD_THREADS, value = "true")
	static class ThreadRecorder implements AcpClientCustomizer {

		final List<Thread> threads = new CopyOnWriteArrayList<>();

		@Override
		public void customize(AcpClient.AsyncSpec spec) {
			spec.sessionUpdateHandler(notification -> {
				threads.add(Thread.currentThread());
				return Mono.empty();
			});
		}

	}

}
