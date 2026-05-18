/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.AcpTestFixtures;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests against the in-repo TypeScript Streamable HTTP fixture.
 */
class StreamableHttpAcpClientTransportIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final Path FIXTURE_DIR = Path.of("..", "test-fixtures", "streamable-http-server").normalize();

	private static final Path GOLDEN_DIR = FIXTURE_DIR.resolve("golden");

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@Test
	void happyPathMatchesFixtureTranscript() throws Exception {
		try (FixtureServer fixture = FixtureServer.start("happy-path")) {
			CopyOnWriteArrayList<AcpSchema.SessionNotification> updates = new CopyOnWriteArrayList<>();
			AcpAsyncClient client = newClient(fixture.endpoint())
				.sessionUpdateConsumer(notification -> {
					updates.add(notification);
					return Mono.empty();
				})
				.build();

			client.initialize().block(TIMEOUT);
			AcpSchema.NewSessionResponse session = client
				.newSession(AcpTestFixtures.createNewSessionRequest("/workspace"))
				.block(TIMEOUT);
			AcpSchema.PromptResponse prompt = client
				.prompt(AcpTestFixtures.createPromptRequest(session.sessionId(), "hello"))
				.block(TIMEOUT);

			assertThat(session.sessionId()).isEqualTo("sess-1");
			assertThat(prompt.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(updates).hasSize(1);

			client.closeGracefully().block(TIMEOUT);
			fixture.assertTranscriptMatches("happy-path.json");
		}
	}

	@Test
	void permissionRequestRoundTripsOnSessionStream() throws Exception {
		try (FixtureServer fixture = FixtureServer.start("permission-round-trip")) {
			AtomicInteger permissionRequests = new AtomicInteger();
			AcpAsyncClient client = newClient(fixture.endpoint())
				.requestPermissionHandler(request -> {
					permissionRequests.incrementAndGet();
					return Mono.just(new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected("allow")));
				})
				.build();

			client.initialize().block(TIMEOUT);
			AcpSchema.NewSessionResponse session = client
				.newSession(AcpTestFixtures.createNewSessionRequest("/workspace"))
				.block(TIMEOUT);
			AcpSchema.PromptResponse prompt = client
				.prompt(AcpTestFixtures.createPromptRequest(session.sessionId(), "needs permission"))
				.block(TIMEOUT);

			assertThat(session.sessionId()).isEqualTo("sess-permission");
			assertThat(prompt.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(permissionRequests).hasValue(1);

			client.closeGracefully().block(TIMEOUT);
			fixture.assertTranscriptMatches("permission-round-trip.json");
		}
	}

	@Test
	void loadSessionOpensSessionStreamBeforePosting() throws Exception {
		try (FixtureServer fixture = FixtureServer.start("session-load")) {
			AcpAsyncClient client = newClient(fixture.endpoint()).build();

			client.initialize().block(TIMEOUT);
			AcpSchema.LoadSessionResponse response = client
				.loadSession(new AcpSchema.LoadSessionRequest("sess-load", "/workspace", List.of()))
				.block(TIMEOUT);

			assertThat(response).isNotNull();

			client.closeGracefully().block(TIMEOUT);
			fixture.assertTranscriptMatches("session-load.json");
		}
	}

	@Test
	void supportsTwoConcurrentLogicalSessions() throws Exception {
		try (FixtureServer fixture = FixtureServer.start("two-sessions")) {
			AcpAsyncClient client = newClient(fixture.endpoint()).build();

			client.initialize().block(TIMEOUT);
			AcpSchema.NewSessionResponse first = client
				.newSession(AcpTestFixtures.createNewSessionRequest("/workspace/one"))
				.block(TIMEOUT);
			AcpSchema.NewSessionResponse second = client
				.newSession(AcpTestFixtures.createNewSessionRequest("/workspace/two"))
				.block(TIMEOUT);
			AcpSchema.PromptResponse firstPrompt = client
				.prompt(AcpTestFixtures.createPromptRequest(first.sessionId(), "one"))
				.block(TIMEOUT);
			AcpSchema.PromptResponse secondPrompt = client
				.prompt(AcpTestFixtures.createPromptRequest(second.sessionId(), "two"))
				.block(TIMEOUT);

			assertThat(first.sessionId()).isEqualTo("sess-1");
			assertThat(second.sessionId()).isEqualTo("sess-2");
			assertThat(firstPrompt.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(secondPrompt.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);

			client.closeGracefully().block(TIMEOUT);
			fixture.assertTranscriptMatches("two-sessions.json");
		}
	}

	@Test
	void wrongStreamResponseFailsPendingExchange() throws Exception {
		try (FixtureServer fixture = FixtureServer.start("wrong-stream-response")) {
			AcpAsyncClient client = newClient(fixture.endpoint()).build();

			client.initialize().block(TIMEOUT);
			AcpSchema.NewSessionResponse session = client
				.newSession(AcpTestFixtures.createNewSessionRequest("/workspace"))
				.block(TIMEOUT);

			assertThatThrownBy(() -> client
				.prompt(AcpTestFixtures.createPromptRequest(session.sessionId(), "wrong stream"))
				.block(TIMEOUT))
				.isInstanceOf(AcpConnectionException.class)
				.hasMessageContaining("arrived on RouteScope");

			client.closeGracefully().block(TIMEOUT);
			fixture.assertTranscriptMatches("wrong-stream-response.json");
		}
	}

	@Test
	void fixtureRejectsMissingConnectionHeadersCookiesAndSseAccept() throws Exception {
		try (FixtureServer fixture = FixtureServer.start("validation-failures")) {
			HttpClient rawClient = HttpClient.newHttpClient();
			HttpResponse<String> initialize = rawClient.send(HttpRequest.newBuilder(fixture.endpoint())
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("""
						{"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}
						"""))
				.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			String connectionId = initialize.headers().firstValue("Acp-Connection-Id").orElseThrow();
			String cookie = initialize.headers().firstValue("set-cookie").orElseThrow();

			HttpResponse<Void> missingConnection = rawClient.send(HttpRequest.newBuilder(fixture.endpoint())
				.header("Accept", "text/event-stream")
				.header("Cookie", cookie)
				.GET()
				.build(), HttpResponse.BodyHandlers.discarding());
			HttpResponse<Void> missingCookie = rawClient.send(HttpRequest.newBuilder(fixture.endpoint())
				.header("Accept", "text/event-stream")
				.header("Acp-Connection-Id", connectionId)
				.GET()
				.build(), HttpResponse.BodyHandlers.discarding());
			HttpResponse<Void> wrongAccept = rawClient.send(HttpRequest.newBuilder(fixture.endpoint())
				.header("Accept", "application/json")
				.header("Cookie", cookie)
				.header("Acp-Connection-Id", connectionId)
				.GET()
				.build(), HttpResponse.BodyHandlers.discarding());
			HttpResponse<Void> delete = rawClient.send(HttpRequest.newBuilder(fixture.endpoint())
				.header("Cookie", cookie)
				.header("Acp-Connection-Id", connectionId)
				.DELETE()
				.build(), HttpResponse.BodyHandlers.discarding());

			assertThat(initialize.statusCode()).isEqualTo(200);
			assertThat(missingConnection.statusCode()).isEqualTo(400);
			assertThat(missingCookie.statusCode()).isEqualTo(401);
			assertThat(wrongAccept.statusCode()).isEqualTo(406);
			assertThat(delete.statusCode()).isEqualTo(202);

			fixture.assertTranscriptMatches("validation-failures.json");
		}
	}

	private AcpClient.AsyncSpec newClient(URI endpoint) {
		StreamableHttpAcpClientTransport transport = new StreamableHttpAcpClientTransport(endpoint,
				AcpJsonMapper.createDefault());
		return AcpClient.async(transport).requestTimeout(TIMEOUT);
	}

	private static final class FixtureServer implements AutoCloseable {

		private final Process process;

		private final BufferedReader stdout;

		private final URI baseUri;

		private FixtureServer(Process process, BufferedReader stdout, URI baseUri) {
			this.process = process;
			this.stdout = stdout;
			this.baseUri = baseUri;
		}

		static FixtureServer start(String scenario) throws Exception {
			Process process = new ProcessBuilder("node", "dist/server.js", "--scenario", scenario, "--port", "0")
				.directory(FIXTURE_DIR.toFile())
				.redirectErrorStream(true)
				.start();
			BufferedReader stdout = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
			String readyLine = stdout.readLine();
			if (readyLine == null) {
				throw new IllegalStateException("Fixture server exited before becoming ready");
			}
			JsonNode ready = OBJECT_MAPPER.readTree(readyLine);
			if (!"ready".equals(ready.path("status").asText())) {
				throw new IllegalStateException("Fixture server did not become ready: " + readyLine);
			}
			int port = ready.path("port").asInt();
			return new FixtureServer(process, stdout, URI.create("http://127.0.0.1:" + port));
		}

		URI endpoint() {
			return baseUri.resolve("/acp");
		}

		void assertTranscriptMatches(String goldenName) throws Exception {
			HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/__test/transcript")).GET().build();
			HttpResponse<String> response = HttpClient.newHttpClient()
				.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			assertThat(response.statusCode()).isEqualTo(200);

			JsonNode actual = OBJECT_MAPPER.readTree(response.body());
			JsonNode expected = OBJECT_MAPPER.readTree(Files.readString(GOLDEN_DIR.resolve(goldenName)));
			assertThat(actual).isEqualTo(expected);
		}

		@Override
		public void close() throws Exception {
			process.destroy();
			if (!process.waitFor(2, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				process.waitFor(2, TimeUnit.SECONDS);
			}
			try {
				stdout.close();
			}
			catch (IOException ignored) {
			}
		}

	}

}
