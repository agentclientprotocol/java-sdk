/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A builder handler takes the agent it serves as a second parameter, so it can send session
 * updates (a {@code session/load} replay, an update after a config change) without capturing the
 * built agent in an {@code AtomicReference}.
 */
class AgentAwareHandlerTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final InMemoryTransportPair pair = InMemoryTransportPair.create();

	private final List<String> updates = new CopyOnWriteArrayList<>();

	@AfterEach
	void close() {
		pair.closeGracefully().block(TIMEOUT);
	}

	private static String text(AcpSchema.SessionNotification notification) {
		return (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
				&& chunk.content() instanceof AcpSchema.TextContent text) ? text.text() : "?";
	}

	private AcpSyncClient client() {
		AcpSyncClient client = AcpClient.sync(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(n -> updates.add(n.sessionId() + ":" + text(n)))
			.build();
		client.initialize();
		return client;
	}

	@Test
	void asyncHandlerReceivesTheBuiltAgent() {
		AtomicReference<AcpAsyncAgent> received = new AtomicReference<>();
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn()))
			.setSessionConfigOptionHandler((request, self) -> {
				received.set(self);
				return self
					.sendSessionUpdate(request.sessionId(),
							new AcpSchema.AgentMessageChunk(new AcpSchema.TextContent("changed")))
					.thenReturn(new AcpSchema.SetSessionConfigOptionResponse(List.of()));
			})
			.build();
		agent.start().block(TIMEOUT);
		try (AcpSyncClient client = client()) {
			client.setSessionConfigOption(AcpSchema.SetSessionConfigOptionRequest.select("s1", "model", "fast"));

			assertThat(received.get()).isSameAs(agent);
			assertThat(updates).containsExactly("s1:changed");
		}
		finally {
			agent.close();
		}
	}

	@Test
	void syncHandlerReceivesTheBuiltAgentAndItsReplayPrecedesTheAnswer() {
		AtomicReference<AcpSyncAgent> received = new AtomicReference<>();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.promptHandler((request, context) -> AcpSchema.PromptResponse.endTurn())
			.loadSessionHandler((request, self) -> {
				received.set(self);
				self.sendSessionUpdate(request.sessionId(),
						new AcpSchema.AgentMessageChunk(new AcpSchema.TextContent("first")));
				self.sendSessionUpdate(request.sessionId(),
						new AcpSchema.AgentMessageChunk(new AcpSchema.TextContent("second")));
				return new AcpSchema.LoadSessionResponse(null);
			})
			.build();
		agent.start();
		try (AcpSyncClient client = client()) {
			client.loadSession(new AcpSchema.LoadSessionRequest("s2", "/w", List.of()));

			assertThat(received.get()).isSameAs(agent);
			assertThat(updates).containsExactly("s2:first", "s2:second");
		}
		finally {
			agent.close();
		}
	}

	@Test
	void oneArgumentHandlersStillPickTheirOwnInterface() {
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.promptHandler((request, context) -> AcpSchema.PromptResponse.endTurn())
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse("s3", null, null))
			.build();
		agent.start();
		try (AcpSyncClient client = client()) {
			assertThat(client.newSession(new AcpSchema.NewSessionRequest("/w", List.of())).sessionId()).isEqualTo("s3");
		}
		finally {
			agent.close();
		}
	}

}
