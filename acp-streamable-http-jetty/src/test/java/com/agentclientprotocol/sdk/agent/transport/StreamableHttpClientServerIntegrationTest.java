/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SDK's Streamable HTTP client against the SDK's Streamable HTTP server: the scenarios
 * that used to run against a second, hand-written HTTP server in acp-core's tests.
 */
class StreamableHttpClientServerIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static AcpAgentFactory agentFactory() {
		AtomicInteger sessionCounter = new AtomicInteger();
		return AcpAgentFactory.async(transport -> AcpAgent.async(transport)
			.initializeHandler(request -> Mono.just(new AcpSchema.InitializeResponse(
					AcpSchema.LATEST_PROTOCOL_VERSION, new AcpSchema.AgentCapabilities(true, null, null), List.of())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse(
					"sess-" + sessionCounter.incrementAndGet(), null, null)))
			.loadSessionHandler(request -> Mono.just(new AcpSchema.LoadSessionResponse(null, null)))
			.resumeSessionHandler(request -> Mono.just(new AcpSchema.ResumeSessionResponse(null, null)))
			.promptHandler((request, context) -> {
				String text = request.text();
				Mono<Void> work = text.contains("permission") ? context.askPermission("fixture permission").then()
						: Mono.empty();
				String reply = text.startsWith("big:") ? "x".repeat(8 * 1024) : "hello";
				return work.then(context.sendMessage(reply)).thenReturn(AcpSchema.PromptResponse.endTurn());
			})
			.build());
	}

	private static StreamableHttpAcpAgentTransport startServer(StreamableHttpAcpAgentTransportOptions options)
			throws IOException {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		StreamableHttpAcpAgentTransport server = new StreamableHttpAcpAgentTransport(port,
				StreamableHttpAcpAgentTransport.DEFAULT_ACP_PATH, AcpJsonMapper.createDefault(), agentFactory(), options);
		server.start().block(TIMEOUT);
		return server;
	}

	private static URI endpoint(StreamableHttpAcpAgentTransport server) {
		return URI.create("http://127.0.0.1:" + server.getPort() + "/acp");
	}

	private static AcpClient.AsyncSpec client(StreamableHttpAcpAgentTransport server) {
		return AcpClient.async(new StreamableHttpAcpClientTransport(endpoint(server), AcpJsonMapper.createDefault()))
			.requestTimeout(TIMEOUT);
	}

	@Test
	void happyPathStreamsUpdatesAndResponses() throws Exception {
		StreamableHttpAcpAgentTransport server = startServer(StreamableHttpAcpAgentTransportOptions.defaults());
		List<String> updates = new CopyOnWriteArrayList<>();
		AcpAsyncClient client = client(server).sessionUpdateConsumer(notification -> {
			updates.add(notification.sessionId());
			return Mono.empty();
		}).build();
		try {
			client.initialize().block(TIMEOUT);
			AcpSchema.NewSessionResponse session = client
				.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()))
				.block(TIMEOUT);
			AcpSchema.PromptResponse prompt = client
				.prompt(new AcpSchema.PromptRequest(session.sessionId(), List.of(new AcpSchema.TextContent("hi"))))
				.block(TIMEOUT);
			assertThat(prompt.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(updates).containsExactly(session.sessionId());
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			server.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void permissionRequestRoundTripsOnTheSessionStream() throws Exception {
		StreamableHttpAcpAgentTransport server = startServer(StreamableHttpAcpAgentTransportOptions.defaults());
		AcpAsyncClient client = client(server)
			.requestPermissionHandler(request -> Mono.just(new AcpSchema.RequestPermissionResponse(
					new AcpSchema.PermissionSelected("allow"))))
			.build();
		try {
			client.initialize().block(TIMEOUT);
			AcpSchema.NewSessionResponse session = client
				.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()))
				.block(TIMEOUT);
			AcpSchema.PromptResponse prompt = client
				.prompt(new AcpSchema.PromptRequest(session.sessionId(),
						List.of(new AcpSchema.TextContent("needs permission"))))
				.block(TIMEOUT);
			assertThat(prompt.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			server.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void loadAndResumeThenPrompt() throws Exception {
		StreamableHttpAcpAgentTransport server = startServer(StreamableHttpAcpAgentTransportOptions.defaults());
		AcpAsyncClient client = client(server).build();
		try {
			client.initialize().block(TIMEOUT);
			client.loadSession(new AcpSchema.LoadSessionRequest("sess-load", "/workspace", List.of())).block(TIMEOUT);
			AcpSchema.PromptResponse afterLoad = client
				.prompt(new AcpSchema.PromptRequest("sess-load", List.of(new AcpSchema.TextContent("hi"))))
				.block(TIMEOUT);
			assertThat(afterLoad.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);

			client.resumeSession(new AcpSchema.ResumeSessionRequest("sess-resume", "/workspace", List.of())).block(TIMEOUT);
			AcpSchema.PromptResponse afterResume = client
				.prompt(new AcpSchema.PromptRequest("sess-resume", List.of(new AcpSchema.TextContent("hi"))))
				.block(TIMEOUT);
			assertThat(afterResume.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			server.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void twoLogicalSessionsPromptConcurrentlyOnOneConnection() throws Exception {
		StreamableHttpAcpAgentTransport server = startServer(StreamableHttpAcpAgentTransportOptions.defaults());
		AcpAsyncClient client = client(server).build();
		try {
			client.initialize().block(TIMEOUT);
			AcpSchema.NewSessionResponse first = client
				.newSession(new AcpSchema.NewSessionRequest("/one", List.of()))
				.block(TIMEOUT);
			AcpSchema.NewSessionResponse second = client
				.newSession(new AcpSchema.NewSessionRequest("/two", List.of()))
				.block(TIMEOUT);
			assertThat(first.sessionId()).isNotEqualTo(second.sessionId());

			Mono<AcpSchema.PromptResponse> firstPrompt = client
				.prompt(new AcpSchema.PromptRequest(first.sessionId(), List.of(new AcpSchema.TextContent("a"))));
			Mono<AcpSchema.PromptResponse> secondPrompt = client
				.prompt(new AcpSchema.PromptRequest(second.sessionId(), List.of(new AcpSchema.TextContent("b"))));
			List<AcpSchema.PromptResponse> both = Mono.zip(firstPrompt, secondPrompt)
				.map(tuple -> List.of(tuple.getT1(), tuple.getT2()))
				.block(TIMEOUT);
			assertThat(both).allMatch(response -> response.stopReason() == AcpSchema.StopReason.END_TURN);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			server.closeGracefully().block(TIMEOUT);
		}
	}

	/**
	 * The RFD requires HTTP/2 and the listener serves h2c. Over cleartext the JDK client only
	 * offers the h2c upgrade on a request without a body, so a bodiless request goes first;
	 * the POST then reuses the upgraded connection. (Over https, ALPN negotiates HTTP/2 from
	 * the first request.)
	 */
	@Test
	void initializeIsServedOverHttp2() throws Exception {
		StreamableHttpAcpAgentTransport server = startServer(StreamableHttpAcpAgentTransportOptions.defaults());
		try {
			HttpClient raw = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
			HttpResponse<Void> upgrade = raw.send(HttpRequest.newBuilder(endpoint(server)).GET().build(),
					HttpResponse.BodyHandlers.discarding());
			assertThat(upgrade.version()).as("h2c upgrade accepted").isEqualTo(HttpClient.Version.HTTP_2);
			HttpResponse<String> initialize = raw.send(HttpRequest.newBuilder(endpoint(server))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("""
						{"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}
						"""))
				.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			assertThat(initialize.statusCode()).isEqualTo(200);
			assertThat(initialize.version()).isEqualTo(HttpClient.Version.HTTP_2);
		}
		finally {
			server.closeGracefully().block(TIMEOUT);
		}
	}

	/**
	 * Localhost over plain {@code http://} is a first-class deployment (no TLS), and the RFD
	 * requires HTTP/2: every request the SDK client makes, initialize, the POSTs and the
	 * long-lived SSE GETs, must run on HTTP/2 over cleartext.
	 */
	@Test
	void sdkClientUsesHttp2ForEveryRequestOverCleartextHttp() throws Exception {
		StreamableHttpAcpAgentTransport server = startServer(StreamableHttpAcpAgentTransportOptions.defaults());
		RecordingHttpClient http = new RecordingHttpClient(
				HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build());
		AcpAsyncClient client = AcpClient
			.async(new StreamableHttpAcpClientTransport(endpoint(server), AcpJsonMapper.createDefault(), http))
			.requestTimeout(TIMEOUT)
			.build();
		try {
			client.initialize().block(TIMEOUT);
			AcpSchema.NewSessionResponse session = client
				.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()))
				.block(TIMEOUT);
			client.prompt(new AcpSchema.PromptRequest(session.sessionId(), List.of(new AcpSchema.TextContent("hi"))))
				.block(TIMEOUT);

			assertThat(endpoint(server).getScheme()).isEqualTo("http");
			assertThat(http.versions()).as("request methods seen: " + http.methods()).isNotEmpty();
			assertThat(http.methods()).contains("POST", "GET");
			assertThat(http.versions()).as("every response, including SSE streams").containsOnly(HttpClient.Version.HTTP_2);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			server.closeGracefully().block(TIMEOUT);
		}
	}

	/** Delegates to a real client and records the method and negotiated version of every response. */
	private static final class RecordingHttpClient extends HttpClient {

		private final HttpClient delegate;

		private final List<HttpClient.Version> versions = new CopyOnWriteArrayList<>();

		private final List<String> methods = new CopyOnWriteArrayList<>();

		RecordingHttpClient(HttpClient delegate) {
			this.delegate = delegate;
		}

		List<HttpClient.Version> versions() {
			return versions;
		}

		List<String> methods() {
			return methods;
		}

		private <T> HttpResponse<T> record(HttpRequest request, HttpResponse<T> response) {
			methods.add(request.method());
			versions.add(response.version());
			return response;
		}

		@Override
		public java.util.Optional<java.net.CookieHandler> cookieHandler() {
			return delegate.cookieHandler();
		}

		@Override
		public java.util.Optional<Duration> connectTimeout() {
			return delegate.connectTimeout();
		}

		@Override
		public Redirect followRedirects() {
			return delegate.followRedirects();
		}

		@Override
		public java.util.Optional<java.net.ProxySelector> proxy() {
			return delegate.proxy();
		}

		@Override
		public javax.net.ssl.SSLContext sslContext() {
			return delegate.sslContext();
		}

		@Override
		public javax.net.ssl.SSLParameters sslParameters() {
			return delegate.sslParameters();
		}

		@Override
		public java.util.Optional<java.net.Authenticator> authenticator() {
			return delegate.authenticator();
		}

		@Override
		public Version version() {
			return delegate.version();
		}

		@Override
		public java.util.Optional<java.util.concurrent.Executor> executor() {
			return delegate.executor();
		}

		@Override
		public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
				throws IOException, InterruptedException {
			return record(request, delegate.send(request, handler));
		}

		@Override
		public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
				HttpResponse.BodyHandler<T> handler) {
			return delegate.sendAsync(request, handler).thenApply(response -> record(request, response));
		}

		@Override
		public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
				HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
			return delegate.sendAsync(request, handler, pushPromiseHandler)
				.thenApply(response -> record(request, response));
		}

	}

	/** An agent factory that fails must fail the WebSocket upgrade, not leave a half-open connection. */
	@Test
	void webSocketUpgradeFailsCleanlyWhenTheAgentFactoryThrows() throws Exception {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		StreamableHttpAcpAgentTransport server = new StreamableHttpAcpAgentTransport(port,
				AcpJsonMapper.createDefault(), AcpAgentFactory.async(transport -> {
					throw new IllegalStateException("no agent for you");
				}));
		server.start().block(TIMEOUT);
		try {
			AcpAsyncClient client = AcpClient
				.async(new WebSocketAcpClientTransport(URI.create("ws://127.0.0.1:" + port + "/acp"),
						AcpJsonMapper.createDefault()))
				.requestTimeout(Duration.ofSeconds(3))
				.build();
			try {
				assertThatThrownBy(() -> client.initialize().block(TIMEOUT)).isNotNull();
			}
			finally {
				client.close();
			}
			assertThat(server.activeConnectionCount()).isZero();
		}
		finally {
			server.closeGracefully().block(TIMEOUT);
		}
	}

}
