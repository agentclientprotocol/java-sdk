/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests for the WebSocket upgrade path on the Streamable HTTP transport.
 */
class StreamableHttpAcpAgentTransportWebSocketIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void constructorValidatesRequiredArguments() {
		AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();
		AcpAgentFactory agentFactory = simpleAgentFactory();

		assertThatThrownBy(() -> new StreamableHttpAcpAgentTransport(0, jsonMapper, agentFactory))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Port");
		assertThatThrownBy(() -> new StreamableHttpAcpAgentTransport(8080, "", jsonMapper, agentFactory))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Path");
		assertThatThrownBy(() -> new StreamableHttpAcpAgentTransport(8080, null, agentFactory))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("JsonMapper");
		assertThatThrownBy(() -> new StreamableHttpAcpAgentTransport(8080, jsonMapper, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("agentFactory");
	}

	@Test
	void handshakeReturnsConnectionIdHeader() throws Exception {
		try (FixtureServer server = FixtureServer.start(simpleAgentFactory());
				Socket socket = new Socket("127.0.0.1", server.port())) {
			socket.setSoTimeout((int) TIMEOUT.toMillis());

			String key = Base64.getEncoder()
				.encodeToString(UUID.randomUUID().toString().substring(0, 16).getBytes(StandardCharsets.UTF_8));
			PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
			writer.print("GET /acp HTTP/1.1\r\n");
			writer.print("Host: 127.0.0.1:" + server.port() + "\r\n");
			writer.print("Upgrade: websocket\r\n");
			writer.print("Connection: Upgrade\r\n");
			writer.print("Sec-WebSocket-Key: " + key + "\r\n");
			writer.print("Sec-WebSocket-Version: 13\r\n");
			writer.print("\r\n");
			writer.flush();

			List<String> responseLines = readHttpHeaders(socket);

			assertThat(responseLines.get(0)).contains("101");
			assertThat(responseLines.stream()
				.map(line -> line.toLowerCase(Locale.ROOT))
				.anyMatch(line -> line.startsWith("acp-connection-id:"))).isTrue();
		}
	}

	@Test
	void javaClientCanTalkToStreamableWebSocketUpgrade() throws Exception {
		AtomicReference<AcpSchema.SessionNotification> receivedUpdate = new AtomicReference<>();

		try (FixtureServer server = FixtureServer.start(simpleAgentFactory())) {
			AcpAsyncClient client = AcpClient
				.async(new WebSocketAcpClientTransport(server.endpoint(), AcpJsonMapper.createDefault()))
				.sessionUpdateConsumer(update -> {
					receivedUpdate.set(update);
					return Mono.empty();
				})
				.requestTimeout(TIMEOUT)
				.build();
			try {
				client.initialize(new AcpSchema.InitializeRequest(
						AcpSchema.LATEST_PROTOCOL_VERSION, new AcpSchema.ClientCapabilities()))
					.block(TIMEOUT);
				AcpSchema.NewSessionResponse session = client
					.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()))
					.block(TIMEOUT);
				AcpSchema.PromptResponse prompt = client
					.prompt(new AcpSchema.PromptRequest(session.sessionId(),
							List.of(new AcpSchema.TextContent("hello over ws"))))
					.block(TIMEOUT);

				assertThat(session.sessionId()).startsWith("sess-");
				assertThat(prompt.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
				assertThat(receivedUpdate.get()).isNotNull();
			}
			finally {
				client.closeGracefully().block(TIMEOUT);
			}
		}
	}

	@Test
	void permissionRequestRoundTripsOverStreamableWebSocketUpgrade() throws Exception {
		AtomicInteger permissionRequests = new AtomicInteger();
		AcpAgentFactory agentFactory = AcpAgentFactory.async(transport -> AcpAgent.async(transport)
			.initializeHandler(request -> Mono.just(new AcpSchema.InitializeResponse(
					AcpSchema.LATEST_PROTOCOL_VERSION, new AcpSchema.AgentCapabilities(true, null, null), List.of())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("permission-session", null, null)))
			.promptHandler((request, context) -> context.askPermission("streamable websocket edit")
				.map(allowed -> {
					assertThat(allowed).isTrue();
					return AcpSchema.PromptResponse.endTurn();
				}))
			.build());

		try (FixtureServer server = FixtureServer.start(agentFactory)) {
			AcpAsyncClient client = AcpClient
				.async(new WebSocketAcpClientTransport(server.endpoint(), AcpJsonMapper.createDefault()))
				.requestPermissionHandler(request -> {
					permissionRequests.incrementAndGet();
					return Mono.just(new AcpSchema.RequestPermissionResponse(
							new AcpSchema.PermissionSelected("allow")));
				})
				.requestTimeout(TIMEOUT)
				.build();
			try {
				client.initialize(new AcpSchema.InitializeRequest(
						AcpSchema.LATEST_PROTOCOL_VERSION, new AcpSchema.ClientCapabilities()))
					.block(TIMEOUT);
				client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
				AcpSchema.PromptResponse prompt = client
					.prompt(new AcpSchema.PromptRequest("permission-session",
							List.of(new AcpSchema.TextContent("please ask permission"))))
					.block(TIMEOUT);

				assertThat(prompt.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
				assertThat(permissionRequests.get()).isEqualTo(1);
			}
			finally {
				client.closeGracefully().block(TIMEOUT);
			}
		}
	}

	@Test
	void supportsMultipleConcurrentWebSocketClients() throws Exception {
		try (FixtureServer server = FixtureServer.start(simpleAgentFactory())) {
			AcpAsyncClient firstClient = AcpClient
				.async(new WebSocketAcpClientTransport(server.endpoint(), AcpJsonMapper.createDefault()))
				.requestTimeout(TIMEOUT)
				.build();
			AcpAsyncClient secondClient = AcpClient
				.async(new WebSocketAcpClientTransport(server.endpoint(), AcpJsonMapper.createDefault()))
				.requestTimeout(TIMEOUT)
				.build();
			try {
				firstClient.initialize(new AcpSchema.InitializeRequest(
						AcpSchema.LATEST_PROTOCOL_VERSION, new AcpSchema.ClientCapabilities()))
					.block(TIMEOUT);
				secondClient.initialize(new AcpSchema.InitializeRequest(
						AcpSchema.LATEST_PROTOCOL_VERSION, new AcpSchema.ClientCapabilities()))
					.block(TIMEOUT);

				AcpSchema.NewSessionResponse firstSession = firstClient
					.newSession(new AcpSchema.NewSessionRequest("/workspace/one", List.of()))
					.block(TIMEOUT);
				AcpSchema.NewSessionResponse secondSession = secondClient
					.newSession(new AcpSchema.NewSessionRequest("/workspace/two", List.of()))
					.block(TIMEOUT);

				assertThat(firstSession.sessionId()).isNotEqualTo(secondSession.sessionId());
				assertThat(server.transport().activeConnectionCount()).isEqualTo(2);
			}
			finally {
				firstClient.closeGracefully().block(TIMEOUT);
				secondClient.closeGracefully().block(TIMEOUT);
			}
		}
	}

	@Test
	void rejectsNonInitializeFirstMessage() throws Exception {
		try (FixtureServer server = FixtureServer.start(simpleAgentFactory())) {
			CloseRecordingListener listener = new CloseRecordingListener();
			WebSocket webSocket = HttpClient.newHttpClient()
				.newWebSocketBuilder()
				.connectTimeout(TIMEOUT)
				.buildAsync(server.endpoint(), listener)
				.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

			assertThat(listener.openLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
			webSocket.sendText("""
					{"jsonrpc":"2.0","id":"new-1","method":"session/new","params":{"cwd":"/workspace","mcpServers":[]}}
					""", true).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

			assertThat(listener.closeLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
			assertThat(listener.closeCode.get()).isEqualTo(StatusCode.PROTOCOL);
			assertEventuallyNoConnections(server.transport());
		}
	}

	private static AcpAgentFactory simpleAgentFactory() {
		AtomicInteger sessionCounter = new AtomicInteger();
		return AcpAgentFactory.async(transport -> AcpAgent.async(transport)
			.initializeHandler(request -> Mono.just(new AcpSchema.InitializeResponse(
					AcpSchema.LATEST_PROTOCOL_VERSION, new AcpSchema.AgentCapabilities(true, null, null), List.of())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse(
					"sess-" + sessionCounter.incrementAndGet(), null, null)))
			.promptHandler((request, context) -> context.sendMessage("hello from streamable websocket")
				.thenReturn(AcpSchema.PromptResponse.endTurn()))
			.build());
	}

	private static List<String> readHttpHeaders(Socket socket) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
		List<String> lines = new ArrayList<>();
		String line;
		while ((line = reader.readLine()) != null && !line.isEmpty()) {
			lines.add(line);
		}
		return lines;
	}

	private static void assertEventuallyNoConnections(StreamableHttpAcpAgentTransport transport) throws InterruptedException {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			if (transport.activeConnectionCount() == 0) {
				return;
			}
			Thread.sleep(25);
		}
		assertThat(transport.activeConnectionCount()).isEqualTo(0);
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException e) {
			throw new IllegalStateException("Unable to allocate a free port", e);
		}
	}

	private static final class FixtureServer implements AutoCloseable {

		private final StreamableHttpAcpAgentTransport transport;

		private FixtureServer(StreamableHttpAcpAgentTransport transport) {
			this.transport = transport;
		}

		static FixtureServer start(AcpAgentFactory agentFactory) {
			StreamableHttpAcpAgentTransport transport = new StreamableHttpAcpAgentTransport(
					freePort(), AcpJsonMapper.createDefault(), agentFactory);
			transport.start().block(TIMEOUT);
			return new FixtureServer(transport);
		}

		int port() {
			return transport.getPort();
		}

		URI endpoint() {
			return URI.create("ws://127.0.0.1:" + transport.getPort() + "/acp");
		}

		StreamableHttpAcpAgentTransport transport() {
			return transport;
		}

		@Override
		public void close() {
			transport.closeGracefully().block(TIMEOUT);
		}

	}

	private static final class CloseRecordingListener implements WebSocket.Listener {

		private final CountDownLatch openLatch = new CountDownLatch(1);

		private final CountDownLatch closeLatch = new CountDownLatch(1);

		private final AtomicInteger closeCode = new AtomicInteger();

		@Override
		public void onOpen(WebSocket webSocket) {
			openLatch.countDown();
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			webSocket.request(1);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			closeCode.set(statusCode);
			closeLatch.countDown();
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			closeLatch.countDown();
		}

	}

}
