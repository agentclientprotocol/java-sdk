/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests against the in-repo TypeScript Streamable HTTP fixture client.
 */
class StreamableHttpAcpAgentTransportIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final Path FIXTURE_DIR = Path.of("..", "test-fixtures", "streamable-http-client").normalize();

	private static final Path GOLDEN_DIR = FIXTURE_DIR.resolve("golden");

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@Test
	void happyPathMatchesFixtureTranscript() throws Exception {
		try (FixtureServer server = FixtureServer.start(StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE)) {
			JsonNode transcript = FixtureClient.run(server.endpoint(), "happy-path");
			assertTranscriptMatches(transcript, "happy-path.json");
		}
	}

	@Test
	void permissionRoundTripMatchesFixtureTranscript() throws Exception {
		try (FixtureServer server = FixtureServer.start(StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE)) {
			JsonNode transcript = FixtureClient.run(server.endpoint(), "permission-round-trip");
			assertTranscriptMatches(transcript, "permission-round-trip.json");
		}
	}

	@Test
	void compatibleModeAllowsSessionLoadPreopen() throws Exception {
		try (FixtureServer server = FixtureServer.start(StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE)) {
			JsonNode transcript = FixtureClient.run(server.endpoint(), "session-load");
			assertTranscriptMatches(transcript, "session-load.json");
		}
	}

	@Test
	void supportsTwoLogicalSessions() throws Exception {
		try (FixtureServer server = FixtureServer.start(StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE)) {
			JsonNode transcript = FixtureClient.run(server.endpoint(), "two-sessions");
			assertTranscriptMatches(transcript, "two-sessions.json");
		}
	}

	@Test
	void wrongStreamResponseIsRejected() throws Exception {
		try (FixtureServer server = FixtureServer.start(StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE)) {
			JsonNode transcript = FixtureClient.run(server.endpoint(), "wrong-stream-response");
			assertTranscriptMatches(transcript, "wrong-stream-response.json");
		}
	}

	@Test
	void validationFailuresMatchFixtureTranscript() throws Exception {
		try (FixtureServer server = FixtureServer.start(StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE)) {
			JsonNode transcript = FixtureClient.run(server.endpoint(), "validation-failures");
			assertTranscriptMatches(transcript, "validation-failures.json");
		}
	}

	@Test
	void javaClientCanTalkToRunningJavaServer() throws Exception {
		try (FixtureServer server = FixtureServer.start(StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE)) {
			AcpAsyncClient client = AcpClient
				.async(new StreamableHttpAcpClientTransport(server.endpoint(), AcpJsonMapper.createDefault()))
				.build();

			client.initialize().block(TIMEOUT);
			AcpSchema.NewSessionResponse session = client
				.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of(), null))
				.block(TIMEOUT);
			AcpSchema.PromptResponse prompt = client
				.prompt(new AcpSchema.PromptRequest(session.sessionId(),
						List.of(new AcpSchema.TextContent("hello")), null))
				.block(TIMEOUT);

			assertThat(session.sessionId()).isEqualTo("sess-1");
			assertThat(prompt.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);

			client.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void strictModeRejectsUnknownSessionStream() throws Exception {
		try (FixtureServer server = FixtureServer.start(StreamableHttpAcpAgentTransport.RoutingMode.STRICT)) {
			HttpResponse<Void> response = HttpClient.newHttpClient()
				.send(HttpRequest.newBuilder(server.endpoint())
					.header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("""
							{"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}
							"""))
					.build(), HttpResponse.BodyHandlers.discarding());
			String connectionId = response.headers().firstValue("Acp-Connection-Id").orElseThrow();
			HttpResponse<Void> unknownSession = HttpClient.newHttpClient()
				.send(HttpRequest.newBuilder(server.endpoint())
					.header("Accept", "text/event-stream")
					.header("Acp-Connection-Id", connectionId)
					.header("Acp-Session-Id", "unknown")
					.GET()
					.build(), HttpResponse.BodyHandlers.discarding());

			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(unknownSession.statusCode()).isEqualTo(404);
		}
	}

	private static void assertTranscriptMatches(JsonNode actual, String goldenName) throws Exception {
		JsonNode expected = OBJECT_MAPPER.readTree(Files.readString(GOLDEN_DIR.resolve(goldenName)));
		assertThat(actual).isEqualTo(expected);
	}

	private static final class FixtureServer implements AutoCloseable {

		private final StreamableHttpAcpAgentTransport transport;

		private FixtureServer(StreamableHttpAcpAgentTransport transport) {
			this.transport = transport;
		}

		static FixtureServer start(StreamableHttpAcpAgentTransport.RoutingMode routingMode) throws Exception {
			AtomicInteger sessionCounter = new AtomicInteger();
			AcpAgentFactory agentFactory = AcpAgentFactory.async(transport -> AcpAgent.async(transport)
				.initializeHandler(request -> Mono.just(new AcpSchema.InitializeResponse(
						AcpSchema.LATEST_PROTOCOL_VERSION, new AcpSchema.AgentCapabilities(true, null, null), null)))
				.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse(
						"sess-" + sessionCounter.incrementAndGet(), null, null)))
				.loadSessionHandler(request -> Mono.just(new AcpSchema.LoadSessionResponse(null, null)))
				.promptHandler((request, context) -> {
					Mono<Void> work = request.text().contains("permission")
							? context.askPermission("fixture permission").then()
							: Mono.empty();
					return work.then(context.sendMessage("hello"))
						.thenReturn(AcpSchema.PromptResponse.endTurn());
				})
				.build());
			StreamableHttpAcpAgentTransport transport = new StreamableHttpAcpAgentTransport(
					freePort(), AcpJsonMapper.createDefault(), agentFactory).routingMode(routingMode);
			transport.start().block(TIMEOUT);
			return new FixtureServer(transport);
		}

		URI endpoint() {
			return URI.create("http://127.0.0.1:" + transport.getPort() + "/acp");
		}

		@Override
		public void close() {
			transport.closeGracefully().block(TIMEOUT);
		}

		private static int freePort() throws IOException {
			try (ServerSocket socket = new ServerSocket(0)) {
				return socket.getLocalPort();
			}
		}

	}

	private static final class FixtureClient {

		static JsonNode run(URI endpoint, String scenario) throws Exception {
			Process process = new ProcessBuilder("node", "dist/client.js", "--endpoint", endpoint.toString(), "--scenario",
					scenario)
				.directory(FIXTURE_DIR.toFile())
				.redirectErrorStream(true)
				.start();
			try (BufferedReader stdout = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String output = stdout.lines().reduce("", (left, right) -> left + right + System.lineSeparator());
				if (!process.waitFor(5, TimeUnit.SECONDS)) {
					process.destroyForcibly();
					throw new IllegalStateException("Fixture client timed out");
				}
				if (process.exitValue() != 0) {
					throw new IllegalStateException("Fixture client failed: " + output);
				}
				return OBJECT_MAPPER.readTree(output);
			}
		}

	}

}
