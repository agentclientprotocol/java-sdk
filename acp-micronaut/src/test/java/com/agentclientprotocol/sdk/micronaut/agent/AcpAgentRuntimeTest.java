/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.agentclientprotocol.sdk.micronaut.Eventually.eventually;

/**
 * The {@code @AcpAgent} bean served over an application transport bean (here in memory,
 * in place of stdio), driven by an SDK client: the bean is found, its injected dependency,
 * interceptor and argument resolver beans take part, and Publisher, Flux and Mono returns
 * are answered.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Property(name = TestAgents.ECHO, value = "true")
@Property(name = TestAgents.IN_MEMORY, value = "true")
class AcpAgentRuntimeTest {

	@Inject
	InMemoryTransportPair pair;

	@Inject
	AcpAgentRuntime runtime;

	@Inject
	TestAgents.RecordingInterceptor interceptor;

	@Inject
	TestAgents.EchoAgent agent;

	private final List<String> chunks = new CopyOnWriteArrayList<>();

	/** One client: the in-memory transport connects once, for the context's life. */
	private AcpSyncClient acp;

	private AcpSchema.InitializeResponse init;

	@BeforeAll
	void connect() {
		acp = AcpClient.sync(pair.clientTransport())
			.requestTimeout(Duration.ofSeconds(10))
			.sessionUpdateHandler(notification -> {
				if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
						&& chunk.content() instanceof AcpSchema.TextContent text) {
					chunks.add(text.text());
				}
			})
			.build();
		init = acp.initialize();
	}

	@Test
	void servesTheAgentBeanWithItsDependenciesInterceptorsAndResolvers() {
		assertThat(init.protocolVersion()).isEqualTo(1);
		// Derived from the bean's annotations: agentInfo from @AcpAgent(name, version), and a
		// capability for each declared handler that needs one (@CloseSession here, no @LoadSession).
		assertThat(init.agentInfo()).isNotNull();
		assertThat(init.agentInfo().name()).isEqualTo("micronaut-test-agent");
		assertThat(init.agentInfo().version()).isEqualTo("1.2.3");
		assertThat(init.agentCapabilities().sessionCapabilities()).isNotNull();
		assertThat(init.agentCapabilities().sessionCapabilities().close()).isNotNull();
		assertThat(init.agentCapabilities().sessionCapabilities().list()).isNull();
		assertThat(init.agentCapabilities().loadSession()).isNotEqualTo(Boolean.TRUE);

		// @NewSession returns a Flux: a Publisher of one response
		String sessionId = acp.newSession(new AcpSchema.NewSessionRequest("/tmp", List.of())).sessionId();
		assertThat(sessionId).isNotBlank();

		AcpSchema.PromptResponse response = acp
			.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("hello"))));
		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		eventually(Duration.ofSeconds(5), () -> assertThat(String.join("", chunks))
			// the injected Prefix bean, the prompt, and the LocaleResolver bean's argument
			.isEqualTo("echo: hello [fr-CA]"));

		// @SetSessionMode returns a Mono
		assertThat(acp.setSessionMode(new AcpSchema.SetSessionModeRequest(sessionId, "any"))).isNotNull();

		// The derived initialize answer (no @Initialize declared) is intercepted like any handler.
		assertThat(interceptor.methods).contains("initialize", "session/new", "session/prompt", "session/set_mode");
		assertThat(runtime.isRunning()).isTrue();
		assertThat(runtime.port()).isEmpty();

		// The handler ran on Micronaut's virtual-thread executor where the JVM has one (JDK 21+),
		// else on the SDK's own pool.
		assertThat(agent.promptThreads).hasSize(1).allSatisfy(thread -> {
			if (Runtime.version().feature() >= 21) {
				assertThat(isVirtual(thread)).as("a virtual thread: %s", thread).isTrue();
			}
			else {
				assertThat(thread.getName()).startsWith("acp-agent-sync-handler");
			}
		});
	}

	@Test
	void anEmptyPublisherIsNoResponse() {
		assertThatThrownBy(() -> acp.sendExtRequest("_test/empty", Map.of())).isInstanceOf(AcpError.class)
			.hasMessageContaining("produced no response")
			.satisfies(error -> assertThat(((AcpError) error).getCode()).isEqualTo(-32603));
	}

	static boolean isVirtual(Thread thread) {
		try {
			return (boolean) Thread.class.getMethod("isVirtual").invoke(thread);
		}
		catch (ReflectiveOperationException ex) {
			return false; // JDK 17: no virtual threads
		}
	}

}
