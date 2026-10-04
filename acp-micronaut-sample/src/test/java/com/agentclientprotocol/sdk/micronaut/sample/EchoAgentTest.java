/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.sample;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.micronaut.agent.AcpAgentRuntime;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The sample agent in-process, over WebSocket, with a configured greeting. */
@MicronautTest
@Property(name = "acp.agent.transport.type", value = "http")
@Property(name = "acp.agent.transport.http.port", value = "0")
@Property(name = "sample.prefix", value = "hi: ")
class EchoAgentTest {

	@Inject
	AcpAgentRuntime runtime;

	@Test
	void answersOverWebSocketWithTheConfiguredPrefix() {
		List<String> chunks = new CopyOnWriteArrayList<>();
		URI uri = URI.create("ws://localhost:" + runtime.port().orElseThrow() + "/acp");
		AcpSyncClient client = AcpClient.sync(new WebSocketAcpClientTransport(uri, AcpJsonMapper.createDefault()))
			.requestTimeout(Duration.ofSeconds(10))
			.sessionUpdateHandler(notification -> {
				if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
						&& chunk.content() instanceof AcpSchema.TextContent text) {
					chunks.add(text.text());
				}
			})
			.build();
		try {
			AcpSchema.InitializeResponse init = client.initialize();
			// agentInfo comes from @AcpAgent(name, version) on the bean's class
			assertThat(init.agentInfo()).isNotNull();
			assertThat(init.agentInfo().name()).isEqualTo("micronaut-echo-agent");
			assertThat(init.agentInfo().version()).isEqualTo("1.0.0");
			String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/tmp", List.of())).sessionId();
			client.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("there"))));
			long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
			while (!String.join("", chunks).equals("hi: there") && System.nanoTime() < deadline) {
				Thread.onSpinWait();
			}
			assertThat(String.join("", chunks)).isEqualTo("hi: there");
		}
		finally {
			client.closeGracefully();
		}
	}

}
