package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.RepeatedTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduction for issue #14: AcpSyncAgent + AcpSyncClient over InMemoryTransportPair, one
 * session, three sequential prompts, run under {@code taskset -c 0}. The report says the
 * second or third prompt never returns and the request timeout fires.
 */
class MultiPromptSyncSessionTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@RepeatedTest(5)
	void threeSequentialPromptsOnOneSessionAllReturn() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of()))
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse("session-14", null, null))
			.promptHandler((request, context) -> new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN))
			.build();
		agent.start();

		AcpSyncClient client = AcpClient.sync(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		try {
			client.initialize();
			String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).sessionId();
			for (int i = 1; i <= 3; i++) {
				long t0 = System.nanoTime();
				AcpSchema.PromptResponse response = client.prompt(new AcpSchema.PromptRequest(sessionId,
						List.of(new AcpSchema.TextContent("prompt " + i))));
				System.out.printf("prompt %d returned %s in %d ms%n", i, response.stopReason(),
						(System.nanoTime() - t0) / 1_000_000);
				assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			}
		}
		finally {
			client.closeGracefully();
			agent.closeGracefully();
			pair.closeGracefully().block(TIMEOUT);
		}
	}

	@RepeatedTest(5)
	void threeSequentialStreamingPromptsOnOneSessionAllReturn() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of()))
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse("session-14", null, null))
			.promptHandler((request, context) -> {
				for (int chunk = 0; chunk < 3; chunk++) {
					context.sendUpdate(request.sessionId(),
							new AcpSchema.AgentMessageChunk("agent_message_chunk", new AcpSchema.TextContent("chunk " + chunk)));
				}
				return new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN);
			})
			.build();
		agent.start();

		List<String> updates = new CopyOnWriteArrayList<>();
		AcpSyncClient client = AcpClient.sync(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(notification -> updates.add(notification.update().getClass().getSimpleName()))
			.build();
		try {
			client.initialize();
			String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).sessionId();
			for (int i = 1; i <= 3; i++) {
				long t0 = System.nanoTime();
				AcpSchema.PromptResponse response = client.prompt(new AcpSchema.PromptRequest(sessionId,
						List.of(new AcpSchema.TextContent("prompt " + i))));
				System.out.printf("streaming prompt %d returned %s in %d ms (%d updates so far)%n", i,
						response.stopReason(), (System.nanoTime() - t0) / 1_000_000, updates.size());
				assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			}
		}
		finally {
			client.closeGracefully();
			agent.closeGracefully();
			pair.closeGracefully().block(TIMEOUT);
		}
	}

}
