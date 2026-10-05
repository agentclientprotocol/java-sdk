/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.micronaut.client.TestCustomizers;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static com.agentclientprotocol.sdk.micronaut.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code @AcpAgent} bean served by the Streamable HTTP listener, and a client built by
 * acp-micronaut from {@code acp.client.*} talking to it over Streamable HTTP and over
 * WebSocket on the same endpoint.
 */
@MicronautTest
@Property(name = TestAgents.ECHO, value = "true")
@Property(name = "acp.agent.transport.type", value = "http")
@Property(name = "acp.agent.transport.http.listener.port", value = "0")
@Property(name = "acp.agent.transport.http.path", value = "/agent")
class AcpAgentHttpTest {

	@Inject
	AcpAgentRuntime runtime;

	@Inject
	TestAgents.EchoAgent agent;

	@Test
	void aMicronautClientOverStreamableHttp() {
		int port = runtime.port().orElseThrow();
		assertThat(port).isPositive();
		conversation(Map.of("acp.client.transport.http.uri", "http://localhost:" + port + "/agent",
				"acp.client.capabilities.read-text-file", "true", "acp.client.capabilities.terminal", "true",
				TestCustomizers.SERVES_FILES_AND_TERMINALS, "true"));
		// the configured capabilities, as the agent negotiated them on the wire
		NegotiatedCapabilities caps = agent.clientCapabilities.get(agent.clientCapabilities.size() - 1);
		assertThat(caps.supportsReadTextFile()).isTrue();
		assertThat(caps.supportsWriteTextFile()).isFalse();
		assertThat(caps.supportsTerminal()).isTrue();
	}

	@Test
	void aMicronautClientOverWebSocket() {
		int port = runtime.port().orElseThrow();
		conversation(Map.of("acp.client.transport.websocket.uri", "ws://localhost:" + port + "/agent"));
	}

	/** One client application: initialize, a session, a prompt, then its close. */
	private static void conversation(Map<String, Object> clientProperties) {
		Map<String, Object> properties = new HashMap<>(clientProperties);
		properties.put(TestCustomizers.RECORDING, "true");
		ApplicationContext client = ApplicationContext.run(properties);
		TestCustomizers.Record record = client.getBean(TestCustomizers.Record.class);
		AcpSyncClient acp = client.getBean(AcpSyncClient.class);

		assertThat(acp.initialize().protocolVersion()).isEqualTo(1);
		String sessionId = acp.newSession(new AcpSchema.NewSessionRequest("/tmp", List.of())).sessionId();
		AcpSchema.PromptResponse response = acp
			.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("over the wire"))));
		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		eventually(Duration.ofSeconds(5),
				() -> assertThat(String.join("", record.chunks)).isEqualTo("echo: over the wire [fr-CA]"));
		assertThat(record.customizers).containsExactly("first", "second");

		client.close();
		assertThat(record.transportCloses).hasValue(1);
	}

	@Test
	void websocketTypeServesTheSameListenerAndGracefulShutdownClosesIt() {
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		logs.start();
		root.addAppender(logs);
		try {
			ApplicationContext agent = ApplicationContext.run(Map.of(TestAgents.ECHO, "true",
					"acp.agent.transport.type", "websocket", "acp.agent.transport.http.listener.port", "0",
					"acp.agent.transport.http.shutdown-timeout", "2s",
					"micronaut.lifecycle.graceful-shutdown.enabled", "true"));
			int port = agent.getBean(AcpAgentRuntime.class).port().orElseThrow();
			conversation(Map.of("acp.client.transport.type", "websocket", "acp.client.transport.websocket.uri",
					"ws://localhost:" + port + "/acp"));
			// a connection left open at shutdown
			ApplicationContext openClient = ApplicationContext
				.run(Map.of("acp.client.transport.http.uri", "http://localhost:" + port + "/acp"));
			openClient.getBean(AcpSyncClient.class).initialize();

			agent.close();

			assertThatThrownBy(() -> new Socket("localhost", port).close()).isInstanceOf(IOException.class);
			openClient.close();
		}
		finally {
			root.detachAppender(logs);
		}
		assertThat(logs.list).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)).isEmpty();
	}

}
