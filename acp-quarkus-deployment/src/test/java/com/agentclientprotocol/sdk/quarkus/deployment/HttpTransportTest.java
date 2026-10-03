/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpHttpAgentHost;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.quarkus.test.QuarkusUnitTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent over Streamable HTTP and WebSocket at a configured path on the Quarkus HTTP
 * server. Connections a client leaves open are closed when the application stops, the
 * connection threads end, and nothing logs a warning or an error on the server.
 */
class HttpTransportTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final List<LogRecord> serverWarnings = new CopyOnWriteArrayList<>();

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(HttpAgent.class))
		.overrideConfigKey("quarkus.acp.agent.transport.type", "http")
		.overrideConfigKey("quarkus.acp.agent.transport.http.path", "/agents/acp")
		.overrideConfigKey("quarkus.acp.agent.transport.http.shutdown-timeout", "2s")
		.setLogRecordPredicate(record -> record.getLevel().intValue() >= Level.WARNING.intValue()
				&& !record.getLoggerName().startsWith("com.agentclientprotocol.sdk.client")
				&& !record.getLoggerName().startsWith("com.agentclientprotocol.sdk.spec.AcpClientSession")
				&& !record.getLoggerName().startsWith("com.agentclientprotocol.sdk.spec.PendingResponses"))
		.assertLogRecords(serverWarnings::addAll)
		.setAfterUndeployListener(() -> {
			assertThat(serverWarnings).extracting(LogRecord::getMessage).isEmpty();
			Threads.assertNoConnectionThreadsRemain();
		});

	@TestHTTPResource("/agents/acp")
	URI endpoint;

	@Inject
	AcpHttpAgentHost host;

	@Test
	void streamableHttpClient() {
		roundTrip(new StreamableHttpAcpClientTransport(endpoint, AcpJsonMapper.createDefault()), true);
	}

	@Test
	void webSocketClient() {
		roundTrip(new WebSocketAcpClientTransport(webSocketUri(), AcpJsonMapper.createDefault()), true);
	}

	@Test
	void oneMegabytePromptOverBothTransports() {
		String big = "x".repeat(1024 * 1024);
		roundTrip(new StreamableHttpAcpClientTransport(endpoint, AcpJsonMapper.createDefault()), true, big);
		roundTrip(new WebSocketAcpClientTransport(webSocketUri(), AcpJsonMapper.createDefault()), true, big);
	}

	@Test
	void connectionsLeftOpenAreCountedAndClosedAtShutdown() {
		roundTrip(new StreamableHttpAcpClientTransport(endpoint, AcpJsonMapper.createDefault()), false);
		roundTrip(new WebSocketAcpClientTransport(webSocketUri(), AcpJsonMapper.createDefault()), false);
		assertThat(host.activeConnectionCount()).isGreaterThanOrEqualTo(2);
	}

	@Test
	void otherPathsAreNotTheAgent() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(endpoint.resolve("/acp")).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(404);
	}

	private URI webSocketUri() {
		return URI.create(endpoint.toString().replaceFirst("^http", "ws"));
	}

	private static void roundTrip(AcpClientTransport transport, boolean close) {
		roundTrip(transport, close, "ping");
	}

	private static void roundTrip(AcpClientTransport transport, boolean close, String prompt) {
		List<String> messages = new CopyOnWriteArrayList<>();
		AcpSyncClient client = AcpClient.sync(transport).requestTimeout(TIMEOUT).sessionUpdateConsumer(notification -> {
			if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
					&& chunk.content() instanceof AcpSchema.TextContent text) {
				messages.add(text.text());
			}
		}).build();
		client.initialize();
		String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/tmp", List.of())).sessionId();
		AcpSchema.PromptResponse response = client
			.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent(prompt))));
		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(messages).containsExactly("pong " + prompt.length());
		if (close) {
			client.closeGracefully();
		}
	}

	@AcpAgent
	public static class HttpAgent {

		@Prompt
		AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
			AcpSchema.TextContent text = (AcpSchema.TextContent) request.prompt().get(0);
			context.sendMessage("pong " + text.text().length());
			return AcpSchema.PromptResponse.endTurn();
		}

	}

}
