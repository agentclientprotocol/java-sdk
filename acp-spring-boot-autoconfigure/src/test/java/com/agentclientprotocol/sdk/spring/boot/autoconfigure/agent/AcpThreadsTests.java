/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.StopReason;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spring.boot.autoconfigure.client.AcpClientAutoConfiguration;
import com.agentclientprotocol.sdk.spring.boot.autoconfigure.client.AcpClientTransportAutoConfiguration;
import com.agentclientprotocol.sdk.util.VirtualThreads;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Spring Boot's own opt-in decides the threads: with {@code spring.threads.virtual.enabled=true}
 * on JDK 21 the agent's handlers, its listener and the client's network transport all run on the
 * virtual-thread {@code applicationTaskExecutor}, and the SDK creates no pool of its own; without
 * it they keep platform threads, even on JDK 21.
 */
class AcpThreadsTests {

	private static final String VIRTUAL = "spring.threads.virtual.enabled=true";

	private static ApplicationContextRunner agentRunner() {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class,
					AcpAgentTransportAutoConfiguration.class, AcpAgentAutoConfiguration.class,
					AcpAgentHttpAutoConfiguration.class))
			.withPropertyValues("spring.acp.agent.shutdown-on-transport-end=false",
					"spring.acp.agent.transport.http.listener.port=0");
	}

	private static ApplicationContextRunner clientRunner() {
		return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
				TaskExecutionAutoConfiguration.class, AcpClientTransportAutoConfiguration.class,
				AcpClientAutoConfiguration.class));
	}

	@ParameterizedTest
	@ValueSource(strings = { "http", "websocket" })
	void withVirtualThreadsOnTheSdkCreatesNoPoolOfItsOwn(String type) {
		assumeTrue(VirtualThreads.isSupported(), "virtual threads need JDK 21");
		List<Thread> handlerThreads = new CopyOnWriteArrayList<>();
		Set<Thread> before = Thread.getAllStackTraces().keySet();
		Set<String> created = roundTrip(type, handlerThreads, before, VIRTUAL);

		assertThat(handlerThreads).singleElement().satisfies(thread -> {
			assertThat(VirtualThreads.isVirtual(thread)).as("handler on %s", thread).isTrue();
			assertThat(thread.getName()).startsWith("task-");
		});
		// What remains is not a pool of the SDK's: Jetty's VirtualThreadPool parks one platform
		// thread while the listener runs, the JDK's HttpClient has one selector thread, and the
		// SDK's one JVM-wide timer serves every timeout.
		assertThat(created).allMatch(name -> name.equals("jetty-virtual-thread-pool-keepalive")
				|| name.equals("acp-timeout") || name.matches("HttpClient-\\d+-SelectorManager"), "allowed")
			.contains("jetty-virtual-thread-pool-keepalive");
		assertThat(created.stream().filter(name -> name.startsWith("HttpClient-"))).hasSizeLessThanOrEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "http", "websocket" })
	void withoutVirtualThreadsEverythingStaysOnPlatformThreads(String type) {
		List<Thread> handlerThreads = new CopyOnWriteArrayList<>();
		Set<Thread> before = Thread.getAllStackTraces().keySet();
		Set<String> created = roundTrip(type, handlerThreads, before);

		assertThat(handlerThreads).singleElement().satisfies(thread -> {
			assertThat(VirtualThreads.isVirtual(thread)).as("handler on %s", thread).isFalse();
			assertThat(thread.getName()).isEqualTo("acp-agent-sync-handler");
		});
		assertThat(created).anyMatch(name -> name.startsWith("qtp"));
		assertThat(created).anyMatch(name -> name.startsWith(type.equals("http") ? "acp-streamable-http-" : "acp-ws-client"));
	}

	/** Serves the agent, prompts it from a Spring client, and returns the platform threads created. */
	private static Set<String> roundTrip(String type, List<Thread> handlerThreads, Set<Thread> before,
			String... properties) {
		Set<String> created = new java.util.HashSet<>();
		agentRunner().withBean(ThreadAgent.class, () -> new ThreadAgent(handlerThreads))
			.withPropertyValues(properties)
			.withPropertyValues("spring.acp.agent.transport.type=" + type)
			.run(agentContext -> {
				assertThat(agentContext).hasNotFailed();
				int port = agentContext.getBean(StreamableHttpAcpAgentTransport.class).getPort();
				String scheme = type.equals("http") ? "http" : "ws";
				clientRunner().withPropertyValues(properties)
					.withPropertyValues("spring.acp.client.transport." + type + ".uri=" + scheme + "://localhost:" + port
							+ "/acp")
					.run(clientContext -> {
						assertThat(clientContext).hasNotFailed();
						AcpSyncClient client = clientContext.getBean(AcpSyncClient.class);
						client.initialize();
						NewSessionResponse session = client.newSession(new NewSessionRequest("/workspace", List.of()));
						PromptResponse response = client
							.prompt(new PromptRequest(session.sessionId(), List.of(new TextContent("hello"))));
						assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
						created.addAll(newPlatformThreads(before));
					});
			});
		return created;
	}

	private static Set<String> newPlatformThreads(Set<Thread> before) {
		return Thread.getAllStackTraces()
			.keySet()
			.stream()
			.filter(thread -> !before.contains(thread))
			.map(Thread::getName)
			.filter(name -> Arrays.asList("acp-", "qtp", "HttpClient-", "Scheduler-", "jetty-")
				.stream()
				.anyMatch(name::startsWith))
			.collect(Collectors.toSet());
	}

	@AcpAgent(name = "thread-agent", version = "1.0")
	static class ThreadAgent {

		private final List<Thread> threads;

		ThreadAgent(List<Thread> threads) {
			this.threads = threads;
		}

		@NewSession
		public NewSessionResponse newSession(NewSessionRequest request) {
			return new NewSessionResponse(UUID.randomUUID().toString(), null, null);
		}

		@Prompt
		public PromptResponse prompt(PromptRequest request, SyncPromptContext context) {
			threads.add(Thread.currentThread());
			context.sendMessage("hi");
			return PromptResponse.endTurn();
		}

	}

}
