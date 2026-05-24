/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests against the Java Streamable HTTP agent transport.
 */
class StreamableHttpAcpAgentTransportIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final AcpJsonMapper JSON_MAPPER = AcpJsonMapper.createDefault();

	@Test
	void javaClientCanTalkToRunningJavaServer() throws Exception {
		try (FixtureServer server = FixtureServer.start(StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE)) {
			AcpAsyncClient client = AcpClient
				.async(new StreamableHttpAcpClientTransport(server.endpoint(), AcpJsonMapper.createDefault()))
				.requestTimeout(TIMEOUT)
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
	void permissionRequestRoundTripsOverSessionStream() throws Exception {
		try (FixtureServer server = FixtureServer.start(StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE)) {
			AtomicInteger permissionRequests = new AtomicInteger();
			AcpAsyncClient client = AcpClient
				.async(new StreamableHttpAcpClientTransport(server.endpoint(), AcpJsonMapper.createDefault()))
				.requestPermissionHandler(request -> {
					permissionRequests.incrementAndGet();
					return Mono.just(new AcpSchema.RequestPermissionResponse(
							new AcpSchema.PermissionSelected("allow")));
				})
				.requestTimeout(TIMEOUT)
				.build();

			client.initialize().block(TIMEOUT);
			AcpSchema.NewSessionResponse session = client
				.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of(), null))
				.block(TIMEOUT);
			AcpSchema.PromptResponse prompt = client
				.prompt(new AcpSchema.PromptRequest(session.sessionId(),
						List.of(new AcpSchema.TextContent("permission please")), null))
				.block(TIMEOUT);

			assertThat(prompt.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(permissionRequests).hasValue(1);

			client.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void compatibleModeAllowsSessionLoadPreopen() throws Exception {
		try (FixtureServer server = FixtureServer.start(StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE)) {
			AcpAsyncClient client = AcpClient
				.async(new StreamableHttpAcpClientTransport(server.endpoint(), AcpJsonMapper.createDefault()))
				.requestTimeout(TIMEOUT)
				.build();

			client.initialize().block(TIMEOUT);
			AcpSchema.LoadSessionResponse response = client
				.loadSession(new AcpSchema.LoadSessionRequest("sess-load", "/workspace", List.of()))
				.block(TIMEOUT);

			assertThat(response).isNotNull();

			client.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void supportsTwoLogicalSessions() throws Exception {
		try (FixtureServer server = FixtureServer.start(StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE)) {
			AcpAsyncClient client = AcpClient
				.async(new StreamableHttpAcpClientTransport(server.endpoint(), AcpJsonMapper.createDefault()))
				.requestTimeout(TIMEOUT)
				.build();

			client.initialize().block(TIMEOUT);
			AcpSchema.NewSessionResponse first = client
				.newSession(new AcpSchema.NewSessionRequest("/workspace/one", List.of(), null))
				.block(TIMEOUT);
			AcpSchema.NewSessionResponse second = client
				.newSession(new AcpSchema.NewSessionRequest("/workspace/two", List.of(), null))
				.block(TIMEOUT);
			AcpSchema.PromptResponse firstPrompt = client
				.prompt(new AcpSchema.PromptRequest(first.sessionId(), List.of(new AcpSchema.TextContent("one")), null))
				.block(TIMEOUT);
			AcpSchema.PromptResponse secondPrompt = client
				.prompt(new AcpSchema.PromptRequest(second.sessionId(), List.of(new AcpSchema.TextContent("two")), null))
				.block(TIMEOUT);

			assertThat(first.sessionId()).isEqualTo("sess-1");
			assertThat(second.sessionId()).isEqualTo("sess-2");
			assertThat(firstPrompt.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(secondPrompt.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);

			client.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void wrongStreamClientResponseIsRejected() throws Exception {
		try (FixtureServer server = FixtureServer.start(StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE)) {
			HttpClient rawClient = HttpClient.newHttpClient();
			HttpResponse<String> initialize = rawClient.send(HttpRequest.newBuilder(server.endpoint())
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("""
						{"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}
						"""))
				.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			String connectionId = initialize.headers().firstValue("Acp-Connection-Id").orElseThrow();
			try (SseReader connectionStream = SseReader.open(rawClient, server.endpoint(), connectionId, null)) {
				postJson(rawClient, server.endpoint(), connectionId, null,
						"""
								{"jsonrpc":"2.0","id":"new-1","method":"session/new","params":{"cwd":"/workspace","mcpServers":[]}}
								""");
				AcpSchema.JSONRPCResponse newSessionResponse = connectionStream.nextResponse();
				AcpSchema.NewSessionResponse session = JSON_MAPPER.convertValue(newSessionResponse.result(),
						new TypeRef<AcpSchema.NewSessionResponse>() {
						});

				try (SseReader sessionStream = SseReader.open(rawClient, server.endpoint(), connectionId,
						session.sessionId())) {
					postJson(rawClient, server.endpoint(), connectionId, session.sessionId(),
							"""
									{"jsonrpc":"2.0","id":"prompt-1","method":"session/prompt","params":{"sessionId":"%s","prompt":[{"type":"text","text":"permission please"}]}}
									""".formatted(session.sessionId()));
					AcpSchema.JSONRPCRequest permissionRequest = sessionStream.nextRequest();
					HttpResponse<String> wrongStreamResponse = postJson(rawClient, server.endpoint(), connectionId, null,
							"""
									{"jsonrpc":"2.0","id":"%s","result":{"outcome":{"outcome":"selected","optionId":"allow"}}}
									""".formatted(permissionRequest.id()));

					assertThat(wrongStreamResponse.statusCode()).isEqualTo(400);
					assertThat(wrongStreamResponse.body()).contains("expected RouteScope");
				}
			}
		}
	}

	@Test
	void validationFailuresUseHttpStatusCodes() throws Exception {
		try (FixtureServer server = FixtureServer.start(StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE)) {
			HttpClient rawClient = HttpClient.newHttpClient();
			HttpResponse<String> unsupportedContentType = rawClient.send(HttpRequest.newBuilder(server.endpoint())
				.header("Content-Type", "text/plain")
				.POST(HttpRequest.BodyPublishers.ofString("{}"))
				.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			HttpResponse<String> batch = rawClient.send(HttpRequest.newBuilder(server.endpoint())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("[]"))
				.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			HttpResponse<String> wrongAccept = rawClient.send(HttpRequest.newBuilder(server.endpoint())
				.header("Accept", "application/json")
				.GET()
				.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			HttpResponse<String> missingConnection = rawClient.send(HttpRequest.newBuilder(server.endpoint())
				.header("Accept", "text/event-stream")
				.GET()
				.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			assertThat(unsupportedContentType.statusCode()).isEqualTo(415);
			assertThat(batch.statusCode()).isEqualTo(501);
			assertThat(wrongAccept.statusCode()).isEqualTo(406);
			assertThat(missingConnection.statusCode()).isEqualTo(400);
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

	private static HttpResponse<String> postJson(HttpClient client, URI endpoint, String connectionId, String sessionId,
			String body) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.header("Acp-Connection-Id", connectionId)
			.POST(HttpRequest.BodyPublishers.ofString(body));
		if (sessionId != null) {
			builder.header("Acp-Session-Id", sessionId);
		}
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

	private static final class SseReader implements AutoCloseable {

		private final BlockingQueue<AcpSchema.JSONRPCMessage> messages = new LinkedBlockingQueue<>();

		private final InputStream body;

		private final ExecutorService executor;

		private SseReader(InputStream body) {
			this.body = body;
			this.executor = Executors.newSingleThreadExecutor(r -> {
				Thread thread = new Thread(r, "streamable-http-test-sse-reader");
				thread.setDaemon(true);
				return thread;
			});
			this.executor.submit(this::readLoop);
		}

		static SseReader open(HttpClient client, URI endpoint, String connectionId, String sessionId) throws Exception {
			HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
				.header("Accept", "text/event-stream")
				.header("Acp-Connection-Id", connectionId)
				.GET();
			if (sessionId != null) {
				builder.header("Acp-Session-Id", sessionId);
			}
			HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
			assertThat(response.statusCode()).isEqualTo(200);
			return new SseReader(response.body());
		}

		AcpSchema.JSONRPCResponse nextResponse() throws Exception {
			return (AcpSchema.JSONRPCResponse) nextMessage();
		}

		AcpSchema.JSONRPCRequest nextRequest() throws Exception {
			return (AcpSchema.JSONRPCRequest) nextMessage();
		}

		private AcpSchema.JSONRPCMessage nextMessage() throws Exception {
			AcpSchema.JSONRPCMessage message = messages.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			assertThat(message).isNotNull();
			return message;
		}

		private void readLoop() {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
				StringBuilder data = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.isEmpty()) {
						dispatch(data);
						data.setLength(0);
					}
					else if (line.startsWith("data:")) {
						data.append(line.substring(5).stripLeading());
					}
				}
			}
			catch (Exception ignored) {
			}
		}

		private void dispatch(StringBuilder data) throws IOException {
			if (!data.isEmpty()) {
				messages.add(AcpSchema.deserializeJsonRpcMessage(JSON_MAPPER, data.toString()));
			}
		}

		@Override
		public void close() throws IOException {
			body.close();
			executor.shutdownNow();
		}

	}

}
