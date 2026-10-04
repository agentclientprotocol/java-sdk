/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.integration.AcpAgentDiscovery.AgentCandidate;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class AcpClientsTest {

	private final InMemoryTransportPair pair = InMemoryTransportPair.create();

	@Test
	void buildsOneClientWithTheSettingsAndCustomizersInOrder() throws Exception {
		AcpAgentSupport agent = agent();
		agent.start();
		List<String> customized = new ArrayList<>();
		List<String> chunks = new CopyOnWriteArrayList<>();
		AcpClientSettings settings = AcpClientSettings.builder()
			.requestTimeout(Duration.ofSeconds(10))
			.capabilities(new AcpClientSettings.Capabilities(false, false, true, false, false, false))
			.build();
		AcpAsyncClient async = AcpClients.async(pair.clientTransport(), settings,
				List.of(spec -> customized.add("first"), spec -> {
					customized.add("second");
					spec.sessionUpdateConsumer(notification -> {
						if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
								&& chunk.content() instanceof AcpSchema.TextContent text) {
							chunks.add(text.text());
						}
						return Mono.empty();
					});
				}));
		assertThat(customized).containsExactly("first", "second");
		AcpSyncClient sync = AcpClients.sync(async);
		sync.initialize();
		String session = sync.newSession(new AcpSchema.NewSessionRequest("/", List.of())).sessionId();
		sync.prompt(new AcpSchema.PromptRequest(session, List.of(new AcpSchema.TextContent("hello"))));
		assertThat(chunks).containsExactly("echo: hello!");

		AcpClientHost host = new AcpClientHost(async);
		assertThat(host.closeGracefully()).isSameAs(host.closeGracefully());
		host.closeGracefully().toCompletableFuture().get(10, TimeUnit.SECONDS);
		host.close(Duration.ofSeconds(1));
		agent.close();
	}

	@Test
	void unsetRequestTimeoutKeepsTheSdkDefault() {
		AcpAsyncClient async = AcpClients.async(pair.clientTransport(), AcpClientSettings.builder().build(), List.of());
		new AcpClientHost(async).close(Duration.ofSeconds(5));
	}

	@Test
	void theDefaultConsumerLogsAndCompletes() {
		AcpSchema.SessionNotification notification = new AcpSchema.SessionNotification("s",
				new AcpSchema.AgentMessageChunk(new AcpSchema.TextContent("x")));
		assertThat(AcpClients.logSessionUpdate(notification).block()).isNull();
	}

	@Test
	void aCloseThatDoesNotFinishInTimeClosesAtOnce() {
		AtomicInteger closes = new AtomicInteger();
		AcpClientTransport stuck = new StuckClientTransport(pair.clientTransport(), closes);
		AcpAsyncClient async = AcpClients.async(stuck, AcpClientSettings.builder().build(), List.of());
		new AcpClientHost(async).close(Duration.ofMillis(50));
		assertThat(closes).hasPositiveValue();
	}

	private AcpAgentSupport agent() {
		return AcpAgents
			.builder(new AgentCandidate<>("echo", TestAgents.EchoAgent.class, TestAgents.EchoAgent::new),
					AcpAgentSettings.builder().build(), List.of(), List.of(new TestAgents.SuffixResolver()),
					List.of(new TestAgents.ReplyHandler()))
			.transport(pair.agentTransport())
			.build();
	}

	/** A client transport whose graceful close never finishes; counts its immediate closes. */
	private record StuckClientTransport(AcpClientTransport delegate, AtomicInteger closes)
			implements AcpClientTransport {

		@Override
		public Mono<Void> connect(
				java.util.function.Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
			return delegate.connect(handler);
		}

		@Override
		public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
			return delegate.sendMessage(message);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.never();
		}

		@Override
		public void close() {
			closes.incrementAndGet();
		}

		@Override
		public <T> T unmarshalFrom(Object data, com.agentclientprotocol.sdk.json.TypeRef<T> typeRef) {
			return delegate.unmarshalFrom(data, typeRef);
		}

	}

}
