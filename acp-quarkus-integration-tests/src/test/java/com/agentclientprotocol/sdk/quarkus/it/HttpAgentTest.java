/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.it;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sample agent on the Quarkus HTTP server: the SDK's Streamable HTTP client and its
 * WebSocket client reach it at {@code /acp} on the application's own port.
 */
@QuarkusTest
class HttpAgentTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	@TestHTTPResource("/acp")
	URI endpoint;

	@Test
	void streamableHttpClientPromptsTheAgent() {
		assertPromptRoundTrip(new StreamableHttpAcpClientTransport(endpoint, AcpJsonMapper.createDefault()));
	}

	@Test
	void webSocketClientPromptsTheAgent() {
		URI ws = URI.create(endpoint.toString().replaceFirst("^http", "ws"));
		assertPromptRoundTrip(new WebSocketAcpClientTransport(ws, AcpJsonMapper.createDefault()));
	}

	private static void assertPromptRoundTrip(AcpClientTransport transport) {
		List<String> messages = new CopyOnWriteArrayList<>();
		AcpSyncClient client = AcpClient.sync(transport)
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(notification -> {
				if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
						&& chunk.content() instanceof AcpSchema.TextContent text) {
					messages.add(text.text());
				}
			})
			.build();
		try {
			assertThat(client.initialize().protocolVersion()).isEqualTo(AcpSchema.LATEST_PROTOCOL_VERSION);
			String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/tmp", List.of())).sessionId();
			AcpSchema.PromptResponse response = client
				.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("http"))));
			assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(messages).containsExactly("Hello from Quarkus, http! (prompt 1)");
		}
		finally {
			client.closeGracefully();
		}
	}

}
