/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/** The agent's handler methods run on the executor spring.acp.agent.handler-executor selects. */
class AcpAgentHandlerExecutorTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(AcpAgentTransportAutoConfiguration.class, AcpAgentAutoConfiguration.class))
		.withPropertyValues("spring.acp.agent.shutdown-on-transport-end=false");

	@Test
	void aNamedExecutorBean() {
		ExecutorService pool = Executors.newCachedThreadPool(runnable -> new Thread(runnable, "app-pool"));
		try {
			assertThat(promptThread(this.runner.withBean("appPool", ExecutorService.class, () -> pool)
				.withPropertyValues("spring.acp.agent.handler-executor=appPool"))).isEqualTo("app-pool");
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void theApplicationTaskExecutorWithVirtualThreads() {
		assertThat(promptThread(this.runner.withBean("applicationTaskExecutor", SimpleAsyncTaskExecutor.class,
				() -> new SimpleAsyncTaskExecutor("app-task-"))
			.withPropertyValues("spring.threads.virtual.enabled=true"))).startsWith("app-task-");
	}

	@Test
	void withoutVirtualThreadsOrWithNoneTheSdkPool() {
		assertThat(promptThread(this.runner.withBean("applicationTaskExecutor", SimpleAsyncTaskExecutor.class,
				() -> new SimpleAsyncTaskExecutor("app-task-")))).doesNotStartWith("app-task-");
		assertThat(promptThread(this.runner.withBean("applicationTaskExecutor", SimpleAsyncTaskExecutor.class,
				() -> new SimpleAsyncTaskExecutor("app-task-"))
			.withPropertyValues("spring.threads.virtual.enabled=true", "spring.acp.agent.handler-executor=none")))
			.doesNotStartWith("app-task-");
	}

	@Test
	void aBeanThatIsNoExecutorFailsNamingIt() {
		this.runner.withBean(ThreadAgent.class, ThreadAgent::new)
			.withBean("notAnExecutor", String.class, () -> "x")
			.withPropertyValues("spring.acp.agent.handler-executor=notAnExecutor")
			.run(context -> assertThat(context).getFailure()
				.hasStackTraceContaining("spring.acp.agent.handler-executor=notAnExecutor names a java.lang.String"));
	}

	/** The name of the thread the prompt handler ran on. */
	private static String promptThread(ApplicationContextRunner runner) {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		List<String> threads = new CopyOnWriteArrayList<>();
		runner.withBean(ThreadAgent.class, () -> new ThreadAgent(threads))
			.withBean(AcpAgentTransport.class, pair::agentTransport)
			.run(context -> {
				assertThat(context).hasNotFailed();
				AcpSyncClient client = AcpClient.sync(pair.clientTransport()).requestTimeout(Duration.ofSeconds(10)).build();
				try {
					client.initialize();
					String session = client.newSession(new NewSessionRequest("/", List.of())).sessionId();
					client.prompt(new PromptRequest(session, List.of(new TextContent("hi"))));
				}
				finally {
					client.closeGracefully();
				}
			});
		assertThat(threads).hasSize(1);
		return threads.get(0);
	}

	@AcpAgent(name = "thread-agent", version = "1.0")
	static class ThreadAgent {

		private final List<String> threads;

		ThreadAgent() {
			this(new CopyOnWriteArrayList<>());
		}

		ThreadAgent(List<String> threads) {
			this.threads = threads;
		}

		@NewSession
		public NewSessionResponse newSession(NewSessionRequest request) {
			return new NewSessionResponse(UUID.randomUUID().toString(), null, null);
		}

		@Prompt
		public PromptResponse prompt(PromptRequest request, SyncPromptContext context) {
			threads.add(Thread.currentThread().getName());
			return PromptResponse.endTurn();
		}

	}

}
