/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.it;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.util.VirtualThreads;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Quarkus client bean, configured from {@code quarkus.acp.client.transport.http.uri}
 * and customized by a bean, against the application's own agent over Streamable HTTP.
 */
@QuarkusTest
@WithTestResource(value = FreeTestPort.class, scope = TestResourceScope.GLOBAL)
class ClientBeanTest {

	@Inject
	AcpSyncClient client;

	@Inject
	RecordingCustomizer customizer;

	@Inject
	PromptThreadInterceptor interceptor;

	@Test
	void injectedClientPromptsTheAgent() {
		assertThat(client.initialize().protocolVersion()).isEqualTo(AcpSchema.LATEST_PROTOCOL_VERSION);
		String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/tmp", List.of())).sessionId();
		AcpSchema.PromptResponse response = client
			.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("bean"))));
		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(customizer.messages).containsExactly("Hello from Quarkus, bean! (prompt 1)");

		// quarkus.acp.handler-executor=virtual, the default: on JDK 21 the handler ran on Quarkus'
		// virtual-thread executor, and so does the client's transport, which created no pool of
		// its own; before JDK 21 the ManagedExecutor's worker pool.
		assertThat(interceptor.promptThreads).singleElement().satisfies(thread -> {
			if (VirtualThreads.isSupported()) {
				assertThat(VirtualThreads.isVirtual(thread)).as("virtual: %s", thread).isTrue();
			}
			else {
				assertThat(thread.getName()).startsWith("executor-thread-");
			}
		});
		// What remains of the SDK in this JVM is not a pool: the JDK's HttpClient keeps one selector
		// thread, and the SDK's one JVM-wide timer serves every timeout. The agent is served by
		// Quarkus' own HTTP server.
		Set<String> sdkThreads = Thread.getAllStackTraces()
			.keySet()
			.stream()
			.map(Thread::getName)
			.filter(name -> Stream.of("acp-", "qtp", "HttpClient-", "Scheduler-", "jetty-").anyMatch(name::startsWith))
			.collect(Collectors.toSet());
		if (VirtualThreads.isSupported()) {
			assertThat(sdkThreads).allMatch(name -> name.equals("acp-timeout")
					|| name.matches("HttpClient-\\d+-SelectorManager"), "allowed");
		}
	}

}
