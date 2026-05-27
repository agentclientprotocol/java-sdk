/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.AcpTestFixtures;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests against an in-process Java Streamable HTTP fixture server.
 */
class StreamableHttpAcpClientTransportIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String CONNECTION_ID = "conn-test";

	private static final String CONNECTION_STREAM = "connection";

	private static final String CONTENT_TYPE_JSON = "application/json";

	private static final String CONTENT_TYPE_EVENT_STREAM = "text/event-stream";

	private static final AcpJsonMapper JSON_MAPPER = AcpJsonMapper.createDefault();

	@Test
	void happyPathUsesConnectionAndSessionStreams() throws Exception {
		try (FixtureServer fixture = FixtureServer.start()) {
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
			assertThat(fixture.connectionStreamOpened()).isTrue();
			assertThat(fixture.sessionStreamOpened("sess-1")).isTrue();

			client.closeGracefully().block(TIMEOUT);
			assertThat(fixture.deleteReceived()).isTrue();
		}
	}

	@Test
	void permissionRequestRoundTripsOnSessionStream() throws Exception {
		try (FixtureServer fixture = FixtureServer.start()) {
			AtomicInteger permissionRequests = new AtomicInteger();
			AcpAsyncClient client = newClient(fixture.endpoint())
				.requestPermissionHandler(request -> {
					permissionRequests.incrementAndGet();
					return Mono.just(new AcpSchema.RequestPermissionResponse(
							new AcpSchema.PermissionSelected("allow")));
				})
				.build();

			client.initialize().block(TIMEOUT);
			AcpSchema.NewSessionResponse session = client
				.newSession(AcpTestFixtures.createNewSessionRequest("/workspace"))
				.block(TIMEOUT);
			AcpSchema.PromptResponse prompt = client
				.prompt(AcpTestFixtures.createPromptRequest(session.sessionId(), "needs permission"))
				.block(TIMEOUT);

			assertThat(prompt.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(permissionRequests).hasValue(1);
			assertThat(fixture.permissionResponseReceived()).isTrue();

			client.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void loadSessionOpensSessionStreamBeforePosting() throws Exception {
		try (FixtureServer fixture = FixtureServer.start()) {
			AcpAsyncClient client = newClient(fixture.endpoint()).build();

			client.initialize().block(TIMEOUT);
			AcpSchema.LoadSessionResponse response = client
				.loadSession(new AcpSchema.LoadSessionRequest("sess-load", "/workspace", List.of()))
				.block(TIMEOUT);

			assertThat(response).isNotNull();
			assertThat(fixture.sessionLoadStreamWasOpenBeforePost()).isTrue();

			client.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void supportsTwoConcurrentLogicalSessions() throws Exception {
		try (FixtureServer fixture = FixtureServer.start()) {
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
			assertThat(fixture.sessionStreamOpened("sess-1")).isTrue();
			assertThat(fixture.sessionStreamOpened("sess-2")).isTrue();

			client.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void wrongStreamResponseFailsPendingExchange() throws Exception {
		try (FixtureServer fixture = FixtureServer.start()) {
			fixture.routePromptResponsesOnConnectionStream();
			AcpAsyncClient client = newClient(fixture.endpoint()).build();

			client.initialize().block(TIMEOUT);
			AcpSchema.NewSessionResponse session = client
				.newSession(AcpTestFixtures.createNewSessionRequest("/workspace"))
				.block(TIMEOUT);

			assertThatThrownBy(() -> client
				.prompt(AcpTestFixtures.createPromptRequest(session.sessionId(), "wrong stream"))
				.block(TIMEOUT))
				.hasMessageContaining("arrived on RouteScope");

			client.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void initializeRequiresConnectionIdHeader() throws Exception {
		try (FixtureServer fixture = FixtureServer.start()) {
			fixture.omitConnectionIdOnInitialize();
			AcpAsyncClient client = newClient(fixture.endpoint()).build();

			assertThatThrownBy(() -> client.initialize().block(TIMEOUT))
				.isInstanceOf(AcpConnectionException.class)
				.hasMessageContaining("Initialize response missing Acp-Connection-Id");

			client.closeGracefully().onErrorResume(ignored -> Mono.empty()).block(TIMEOUT);
		}
	}

	private AcpClient.AsyncSpec newClient(URI endpoint) {
		StreamableHttpAcpClientTransport transport = new StreamableHttpAcpClientTransport(endpoint,
				AcpJsonMapper.createDefault());
		return AcpClient.async(transport).requestTimeout(TIMEOUT);
	}

	private static final class FixtureServer implements AutoCloseable {

		private final HttpServer server;

		private final ExecutorService executor;

		private final Map<String, SseStream> streams = new ConcurrentHashMap<>();

		private final AtomicInteger sessionCounter = new AtomicInteger();

		private final AtomicBoolean deleteReceived = new AtomicBoolean(false);

		private final AtomicBoolean omitConnectionIdOnInitialize = new AtomicBoolean(false);

		private final AtomicBoolean routePromptResponsesOnConnectionStream = new AtomicBoolean(false);

		private final AtomicBoolean permissionResponseReceived = new AtomicBoolean(false);

		private final AtomicBoolean sessionLoadStreamWasOpenBeforePost = new AtomicBoolean(false);

		private final CompletableFuture<AcpSchema.JSONRPCResponse> permissionResponse = new CompletableFuture<>();

		private FixtureServer(HttpServer server, ExecutorService executor) {
			this.server = server;
			this.executor = executor;
		}

		static FixtureServer start() throws Exception {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", freePort()), 0);
			ExecutorService executor = Executors.newCachedThreadPool();
			FixtureServer fixture = new FixtureServer(server, executor);
			server.createContext("/acp", fixture::handle);
			server.setExecutor(executor);
			server.start();
			return fixture;
		}

		URI endpoint() {
			return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/acp");
		}

		boolean connectionStreamOpened() {
			return streams.containsKey(CONNECTION_STREAM);
		}

		boolean sessionStreamOpened(String sessionId) {
			return streams.containsKey(sessionKey(sessionId));
		}

		boolean deleteReceived() {
			return deleteReceived.get();
		}

		boolean permissionResponseReceived() {
			return permissionResponseReceived.get();
		}

		boolean sessionLoadStreamWasOpenBeforePost() {
			return sessionLoadStreamWasOpenBeforePost.get();
		}

		void omitConnectionIdOnInitialize() {
			omitConnectionIdOnInitialize.set(true);
		}

		void routePromptResponsesOnConnectionStream() {
			routePromptResponsesOnConnectionStream.set(true);
		}

		private void handle(HttpExchange exchange) throws IOException {
			switch (exchange.getRequestMethod()) {
				case "POST" -> handlePost(exchange);
				case "GET" -> handleGet(exchange);
				case "DELETE" -> handleDelete(exchange);
				default -> writeText(exchange, 405, "method not allowed");
			}
		}

		private void handlePost(HttpExchange exchange) throws IOException {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			AcpSchema.JSONRPCMessage message;
			try {
				message = AcpSchema.deserializeJsonRpcMessage(JSON_MAPPER, body);
			}
			catch (Exception e) {
				writeText(exchange, 400, "invalid json-rpc");
				return;
			}

			if (message instanceof AcpSchema.JSONRPCRequest request
					&& AcpSchema.METHOD_INITIALIZE.equals(request.method())) {
				if (!omitConnectionIdOnInitialize.get()) {
					exchange.getResponseHeaders().add("Acp-Connection-Id", CONNECTION_ID);
				}
				writeJson(exchange, 200, response(request.id(), AcpSchema.InitializeResponse.ok()));
				return;
			}

			String connectionId = exchange.getRequestHeaders().getFirst("Acp-Connection-Id");
			if (!CONNECTION_ID.equals(connectionId)) {
				writeText(exchange, 400, "Acp-Connection-Id header required");
				return;
			}

			String sessionId = exchange.getRequestHeaders().getFirst("Acp-Session-Id");
			exchange.sendResponseHeaders(202, -1);
			exchange.close();
			handleAcceptedMessage(message, sessionId);
		}

		private void handleAcceptedMessage(AcpSchema.JSONRPCMessage message, String sessionHeader) {
			if (message instanceof AcpSchema.JSONRPCResponse response) {
				permissionResponseReceived.set(true);
				permissionResponse.complete(response);
				return;
			}
			if (!(message instanceof AcpSchema.JSONRPCRequest request)) {
				return;
			}

			switch (request.method()) {
				case AcpSchema.METHOD_SESSION_NEW -> {
					String sessionId = "sess-" + sessionCounter.incrementAndGet();
					send(CONNECTION_STREAM, response(request.id(),
							new AcpSchema.NewSessionResponse(sessionId, null, null)));
				}
				case AcpSchema.METHOD_SESSION_LOAD -> {
					String sessionId = sessionId(request.params());
					sessionLoadStreamWasOpenBeforePost.set(sessionStreamOpened(sessionId));
					send(CONNECTION_STREAM, response(request.id(), new AcpSchema.LoadSessionResponse(null, null)));
				}
				case AcpSchema.METHOD_SESSION_PROMPT -> handlePrompt(request, sessionHeader);
				default -> send(CONNECTION_STREAM, response(request.id(), Map.of()));
			}
		}

		private void handlePrompt(AcpSchema.JSONRPCRequest request, String sessionHeader) {
			String sessionId = sessionId(request.params());
			if (routePromptResponsesOnConnectionStream.get()) {
				send(CONNECTION_STREAM, response(request.id(), AcpSchema.PromptResponse.endTurn()));
				return;
			}

			if (requestText(request.params()).contains("permission")) {
				String permissionId = "permission-1";
				send(sessionKey(sessionId), new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, permissionId,
						AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, new AcpSchema.RequestPermissionRequest(sessionId,
								new AcpSchema.ToolCallUpdate("tool-1", "Edit file", AcpSchema.ToolKind.EDIT,
										AcpSchema.ToolCallStatus.PENDING, null, null, null, null),
								List.of(new AcpSchema.PermissionOption("allow", "Allow", AcpSchema.PermissionOptionKind.ALLOW_ONCE)))));
				try {
					permissionResponse.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
				}
				catch (Exception e) {
					throw new AssertionError("Timed out waiting for permission response", e);
				}
			}
			else {
				send(sessionKey(sessionId), new AcpSchema.JSONRPCNotification(AcpSchema.METHOD_SESSION_UPDATE,
						new AcpSchema.SessionNotification(sessionId,
								new AcpSchema.AgentMessageChunk("agent_message_chunk", new AcpSchema.TextContent("hello")))));
			}
			send(sessionKey(sessionId), response(request.id(), AcpSchema.PromptResponse.endTurn()));
		}

		private void handleGet(HttpExchange exchange) throws IOException {
			if (!accepts(exchange, CONTENT_TYPE_EVENT_STREAM)) {
				writeText(exchange, 406, "client must accept text/event-stream");
				return;
			}
			if (!CONNECTION_ID.equals(exchange.getRequestHeaders().getFirst("Acp-Connection-Id"))) {
				writeText(exchange, 400, "Acp-Connection-Id header required");
				return;
			}

			String sessionId = exchange.getRequestHeaders().getFirst("Acp-Session-Id");
			String key = sessionId == null ? CONNECTION_STREAM : sessionKey(sessionId);
			exchange.getResponseHeaders().add("Content-Type", CONTENT_TYPE_EVENT_STREAM);
			exchange.sendResponseHeaders(200, 0);
			SseStream stream = new SseStream(exchange.getResponseBody());
			streams.put(key, stream);
			stream.run();
		}

		private void handleDelete(HttpExchange exchange) throws IOException {
			deleteReceived.set(true);
			streams.values().forEach(SseStream::close);
			writeText(exchange, 202, "");
		}

		private void send(String key, AcpSchema.JSONRPCMessage message) {
			try {
				SseStream stream = awaitStream(key);
				stream.send(JSON_MAPPER.writeValueAsString(message));
			}
			catch (Exception e) {
				throw new AssertionError("Failed to send SSE message on " + key, e);
			}
		}

		private SseStream awaitStream(String key) throws InterruptedException {
			long deadline = System.nanoTime() + TIMEOUT.toNanos();
			while (System.nanoTime() < deadline) {
				SseStream stream = streams.get(key);
				if (stream != null) {
					return stream;
				}
				Thread.sleep(10);
			}
			throw new AssertionError("Timed out waiting for SSE stream " + key);
		}

		private static AcpSchema.JSONRPCResponse response(Object id, Object result) {
			return new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, id, result, null);
		}

		private static boolean accepts(HttpExchange exchange, String expected) {
			return exchange.getRequestHeaders()
				.getOrDefault("Accept", List.of())
				.stream()
				.map(String::toLowerCase)
				.anyMatch(value -> value.contains(expected));
		}

		private static String sessionId(Object params) {
			Object sessionId = JSON_MAPPER.convertValue(params, Map.class).get("sessionId");
			return sessionId == null ? null : sessionId.toString();
		}

		private static String requestText(Object params) {
			Map<?, ?> map = JSON_MAPPER.convertValue(params, Map.class);
			Object prompt = map.get("prompt");
			return prompt == null ? "" : prompt.toString();
		}

		private static String sessionKey(String sessionId) {
			return "session:" + sessionId;
		}

		private static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
			byte[] bytes = JSON_MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", CONTENT_TYPE_JSON);
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		}

		private static void writeText(HttpExchange exchange, int status, String body) throws IOException {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		}

		private static int freePort() throws IOException {
			try (ServerSocket socket = new ServerSocket(0)) {
				return socket.getLocalPort();
			}
		}

		@Override
		public void close() {
			streams.values().forEach(SseStream::close);
			server.stop(0);
			executor.shutdownNow();
		}

	}

	private static final class SseStream {

		private static final String CLOSE = "__close__";

		private final OutputStream outputStream;

		private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

		private final AtomicBoolean closed = new AtomicBoolean(false);

		private SseStream(OutputStream outputStream) {
			this.outputStream = outputStream;
		}

		void send(String json) {
			queue.add(json);
		}

		void close() {
			if (closed.compareAndSet(false, true)) {
				queue.offer(CLOSE);
				try {
					outputStream.close();
				}
				catch (IOException ignored) {
				}
			}
		}

		void run() {
			try {
				while (true) {
					String json = queue.take();
					if (CLOSE.equals(json)) {
						return;
					}
					byte[] bytes = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
					outputStream.write(bytes);
					outputStream.flush();
				}
			}
			catch (Exception ignored) {
			}
			finally {
				try {
					outputStream.close();
				}
				catch (IOException ignored) {
				}
			}
		}

	}

}
