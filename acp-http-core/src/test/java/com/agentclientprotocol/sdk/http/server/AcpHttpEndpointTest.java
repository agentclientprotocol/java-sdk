/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The endpoint driven in process, as a host would, with fake exchanges, SSE subscribers and
 * WebSocket outbounds: the status table, the routing of a session's messages, the WebSocket
 * rules and the drain. Every host also runs the transport TCK over a real server.
 */
class AcpHttpEndpointTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":"init","method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}""";

	private final AtomicInteger sessions = new AtomicInteger();

	private final AcpAgentFactory agents = AcpAgentFactory.async(transport -> AcpAgent.async(transport)
		.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok()))
		.newSessionHandler(
				request -> Mono.just(new AcpSchema.NewSessionResponse("s-" + sessions.incrementAndGet(), null, null)))
		.promptHandler((request, context) -> context.sendMessage("hello").thenReturn(AcpSchema.PromptResponse.endTurn()))
		.build());

	private final AcpHttpEndpoint endpoint = AcpHttpEndpoint.create(AcpJsonMapper.createDefault(), agents,
			StreamableHttpAcpAgentTransportOptions.builder()
				.maxPostBodyBytes(1024)
				.keepAliveInterval(Duration.ofMillis(50))
				.shutdownTimeout(Duration.ofSeconds(2))
				.build());

	@AfterEach
	void close() {
		endpoint.closeGracefully().block(TIMEOUT);
	}

	@Test
	void theStatusTable() {
		assertThat(status(post("text/plain", INITIALIZE))).isEqualTo(415);
		assertThat(status(post("application/json", "x".repeat(1025)))).isEqualTo(413);
		assertThat(status(post("application/json", INITIALIZE).header("Content-Length", "5000"))).isEqualTo(413);
		assertThat(status(post("application/json", "[" + INITIALIZE + "]"))).isEqualTo(501);
		assertThat(status(post("application/json", "not json"))).isEqualTo(400);
		assertThat(status(post("application/json", "{\"jsonrpc\":\"2.0\",\"method\":\"x\"}"))).isEqualTo(400);
		assertThat(status(post("application/json", "{\"jsonrpc\":\"2.0\",\"method\":\"x\"}").header("Acp-Connection-Id",
				"nope")))
			.isEqualTo(404);
		assertThat(status(post("application/json", INITIALIZE).header("Acp-Connection-Id", "any"))).isEqualTo(400);
		assertThat(status(new FakeExchange("GET").header("Accept", "application/json"))).isEqualTo(406);
		assertThat(status(new FakeExchange("GET").header("Accept", "text/event-stream"))).isEqualTo(400);
		assertThat(status(new FakeExchange("DELETE").header("Acp-Connection-Id", "nope"))).isEqualTo(404);
		AcpHttpReply put = endpoint.handle(new FakeExchange("PUT")).block(TIMEOUT);
		assertThat(put.status()).isEqualTo(405);
		assertThat(put.headers()).containsEntry("Allow", "GET, POST, DELETE");
		assertThat(status(post("application/json", INITIALIZE).header("Origin", "http://evil.example"))).isEqualTo(403);
	}

	@Test
	void aSessionRoundTripsOverPostAndSse() {
		endpoint.start();
		endpoint.start();
		AcpHttpReply.Body initialized = (AcpHttpReply.Body) endpoint.handle(post("application/json", INITIALIZE))
			.block(TIMEOUT);
		assertThat(initialized.status()).isEqualTo(200);
		assertThat(initialized.contentType()).isEqualTo("application/json");
		String connection = initialized.headers().get("Acp-Connection-Id");
		assertThat(endpoint.activeConnectionCount()).isEqualTo(1);

		AcpHttpReply.EventStream stream = (AcpHttpReply.EventStream) endpoint
			.handle(new FakeExchange("GET").header("Accept", "text/event-stream").header("Acp-Connection-Id", connection))
			.block(TIMEOUT);
		assertThat(stream.headers()).containsEntry("Cache-Control", "no-cache")
			.containsEntry("X-Accel-Buffering", "no")
			.containsEntry("Acp-Connection-Id", connection);
		Frames frames = new Frames();
		stream.frames().subscribe(frames);

		assertThat(status(post("application/json",
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/new\",\"params\":{\"cwd\":\"/\",\"mcpServers\":[]}}")
			.header("Acp-Connection-Id", connection))).isEqualTo(202);
		awaitTrue(() -> frames.events().stream().anyMatch(event -> event.contains("s-1")));

		// A JSON object that is no valid request is answered -32600 on the connection stream.
		assertThat(status(post("application/json", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":7}").header("Acp-Connection-Id",
				connection))).isEqualTo(202);
		awaitTrue(() -> frames.events().stream().anyMatch(event -> event.contains("-32600")));
		awaitTrue(() -> frames.comments().contains("keep-alive"));

		assertThat(status(new FakeExchange("DELETE").header("Acp-Connection-Id", connection))).isEqualTo(202);
		awaitTrue(() -> frames.completed);
		assertThat(endpoint.activeConnectionCount()).isZero();
	}

	@Test
	void anInitializeTheAgentFailsIsAnswered500() {
		AcpHttpEndpoint failing = AcpHttpEndpoint.create(AcpJsonMapper.createDefault(), transport -> {
			throw new IllegalStateException("no agent");
		}, StreamableHttpAcpAgentTransportOptions.defaults());
		List<Throwable> errors = new CopyOnWriteArrayList<>();
		failing.setExceptionHandler(errors::add);
		assertThat(failing.handle(post("application/json", INITIALIZE)).block(TIMEOUT).status()).isEqualTo(500);
		assertThat(errors).isNotEmpty();
	}

	@Test
	void aWebSocketMustOpenWithInitializeAndStayUnderTheLimit() {
		FakeSocket socket = open();
		socket.handler.onText("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/new\",\"params\":{}}");
		assertThat(socket.closeCode.get()).isEqualTo(1002);

		FakeSocket big = open();
		big.handler.onText(INITIALIZE);
		awaitTrue(() -> big.sent.size() == 1);
		big.handler.onText("é".repeat(600));
		assertThat(big.closeCode.get()).isEqualTo(1009);
	}

	@Test
	void aWebSocketAnswersAndRefusesASecondInitialize() {
		FakeSocket socket = open();
		socket.handler.onText(INITIALIZE);
		awaitTrue(() -> socket.sent.size() == 1);
		assertThat(socket.sent.get(0)).contains("\"result\"");
		socket.handler.onText(INITIALIZE);
		awaitTrue(() -> socket.sent.size() == 2);
		assertThat(socket.sent.get(1)).contains("-32600");
		socket.handler.onText("not json");
		awaitTrue(() -> socket.sent.size() == 3);
		socket.handler.onClose(1000, "bye");
		awaitTrue(() -> endpoint.activeConnectionCount() == 0);
	}

	@Test
	void aFailedSendClosesTheSocketWith1011() {
		FakeSocket socket = open();
		socket.failSends = true;
		socket.handler.onText(INITIALIZE);
		awaitTrue(() -> socket.closeCode.get() != null);
		assertThat(socket.closeCode.get()).isEqualTo(1011);
		socket.handler.onError(new IllegalStateException("late"));
	}

	@Test
	void aSocketErrorClosesTheConnection() {
		FakeSocket socket = open();
		socket.handler.onError(new IllegalStateException("corrupt frame"));
		assertThat(socket.closeCode.get()).isEqualTo(1011);
	}

	@Test
	void aForeignOriginHandshakeIsRefused() {
		AcpWsHandshake handshake = endpoint
			.webSocketHandshake(new FakeExchange("GET").header("Upgrade", "websocket").header("Origin", "http://evil.example"));
		assertThat(handshake).isInstanceOfSatisfying(AcpWsHandshake.Refused.class,
				refused -> assertThat(refused.reply().status()).isEqualTo(403));
	}

	@Test
	void drainingClosesStreamsAndSocketsAndRefusesNewOnes() {
		AcpHttpReply.Body initialized = (AcpHttpReply.Body) endpoint.handle(post("application/json", INITIALIZE))
			.block(TIMEOUT);
		String connection = initialized.headers().get("Acp-Connection-Id");
		AcpHttpReply.EventStream stream = (AcpHttpReply.EventStream) endpoint
			.handle(new FakeExchange("GET").header("Accept", "text/event-stream").header("Acp-Connection-Id", connection))
			.block(TIMEOUT);
		Frames frames = new Frames();
		stream.frames().subscribe(frames);
		FakeSocket socket = open();
		socket.handler.onText(INITIALIZE);
		awaitTrue(() -> socket.sent.size() == 1);

		endpoint.closeGracefully().block(TIMEOUT);

		assertThat(frames.comments()).contains("shutting down");
		assertThat(frames.completed).isTrue();
		assertThat(socket.closeCode.get()).isEqualTo(1001);
		assertThat(status(post("application/json", INITIALIZE))).isEqualTo(503);
		assertThat(endpoint.webSocketHandshake(new FakeExchange("GET").header("Upgrade", "websocket")))
			.isInstanceOf(AcpWsHandshake.Refused.class);
	}

	@Test
	void theExchangeRecognisesAnUpgrade() {
		assertThat(new FakeExchange("GET").header("Upgrade", " WebSocket ").isWebSocketUpgrade()).isTrue();
		assertThat(new FakeExchange("GET").isWebSocketUpgrade()).isFalse();
		assertThat(SseFrame.comment("x").encode()).isEqualTo(": x\n\n".getBytes(StandardCharsets.UTF_8));
		assertThat(SseFrame.event("{}").encode()).isEqualTo("data: {}\n\n".getBytes(StandardCharsets.UTF_8));
	}

	private static void awaitTrue(java.util.function.BooleanSupplier condition) {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Condition not met within " + TIMEOUT);
			}
			java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
		}
	}

	private FakeSocket open() {
		AcpWsHandshake handshake = endpoint.webSocketHandshake(new FakeExchange("GET").header("Upgrade", "websocket"));
		AcpWsHandshake.Accepted accepted = (AcpWsHandshake.Accepted) handshake;
		assertThat(accepted.headers()).containsKey("Acp-Connection-Id");
		assertThat(accepted.maxTextMessageBytes()).isEqualTo(1024);
		assertThat(accepted.idleTimeout()).isPositive();
		FakeSocket socket = new FakeSocket();
		socket.handler = accepted.open(socket);
		return socket;
	}

	private int status(FakeExchange exchange) {
		return endpoint.handle(exchange).block(TIMEOUT).status();
	}

	private static FakeExchange post(String contentType, String body) {
		return new FakeExchange("POST").header("Content-Type", contentType).body(body);
	}

	private static final class FakeExchange implements AcpHttpExchange {

		private final String method;

		private final Map<String, String> headers = new HashMap<>();

		private byte[] body = new byte[0];

		FakeExchange(String method) {
			this.method = method;
		}

		FakeExchange header(String name, String value) {
			headers.put(name.toLowerCase(Locale.ROOT), value);
			return this;
		}

		FakeExchange body(String text) {
			this.body = text.getBytes(StandardCharsets.UTF_8);
			return this;
		}

		@Override
		public String method() {
			return method;
		}

		@Override
		public @Nullable String header(String name) {
			return headers.get(name.toLowerCase(Locale.ROOT));
		}

		@Override
		public Mono<byte[]> body(long maxBytes) {
			return Mono.just(body);
		}

		@Override
		public @Nullable Principal principal() {
			return null;
		}

	}

	/** A host that writes every frame at once and asks for the next. */
	private static final class Frames implements CoreSubscriber<SseFrame> {

		private final List<SseFrame> received = new CopyOnWriteArrayList<>();

		private volatile @Nullable Subscription subscription;

		private volatile boolean completed;

		@Override
		public void onSubscribe(Subscription s) {
			this.subscription = s;
			s.request(1);
		}

		@Override
		public void onNext(SseFrame frame) {
			received.add(frame);
			Subscription current = subscription;
			if (current != null) {
				current.request(1);
			}
		}

		@Override
		public void onError(Throwable error) {
			completed = true;
		}

		@Override
		public void onComplete() {
			completed = true;
		}

		List<String> events() {
			List<String> events = new ArrayList<>();
			received.stream().filter(frame -> !frame.comment()).forEach(frame -> events.add(frame.data()));
			return events;
		}

		List<String> comments() {
			List<String> comments = new ArrayList<>();
			received.stream().filter(SseFrame::comment).forEach(frame -> comments.add(frame.data()));
			return comments;
		}

	}

	private static final class FakeSocket implements AcpWsOutbound {

		final List<String> sent = new CopyOnWriteArrayList<>();

		final AtomicReference<@Nullable Integer> closeCode = new AtomicReference<>();

		volatile boolean failSends;

		AcpWsHandler handler;

		@Override
		public CompletionStage<Void> sendText(String text) {
			if (failSends) {
				return CompletableFuture.failedFuture(new IllegalStateException("broken pipe"));
			}
			sent.add(text);
			// Completes later, as a container does, so the next frame waits for it.
			return CompletableFuture.runAsync(() -> {
			});
		}

		@Override
		public void close(int code, String reason) {
			closeCode.compareAndSet(null, code);
		}

	}

}
