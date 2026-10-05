/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A session's traffic over the endpoint, driven in process as a host would: the session stream a
 * client opens with {@code Acp-Session-Id}, the agent's requests to the client routed to it, the
 * client's responses checked against the session they belong to, {@code session/load} and the
 * header rules of session-bound methods. The transport TCK covers the same on a real server, but
 * it runs in the host modules, so it does not count toward this module's coverage.
 */
class AcpHttpSessionRoutingTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":"init","method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}""";

	private static final AcpJsonMapper JSON = AcpJsonMapper.createDefault();

	private final AtomicInteger sessions = new AtomicInteger();

	private final List<String> outcomes = new CopyOnWriteArrayList<>();

	private final AcpAgentFactory agents = AcpAgentFactory.async(transport -> AcpAgent.async(transport)
		.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok()))
		.newSessionHandler(
				request -> Mono.just(new AcpSchema.NewSessionResponse("s-" + sessions.incrementAndGet(), null, null)))
		.loadSessionHandler(request -> request.sessionId().equals("missing")
				? Mono.error(new IllegalArgumentException("no session missing"))
				: Mono.just(new AcpSchema.LoadSessionResponse(null)))
		.promptHandler((request, context) -> context.sendMessage("hello")
			.then(context.client()
				.requestPermission(new AcpSchema.RequestPermissionRequest(request.sessionId(),
						new AcpSchema.ToolCallUpdate("call-1", "Edit file", AcpSchema.ToolKind.EDIT,
								AcpSchema.ToolCallStatus.PENDING),
						List.of(new AcpSchema.PermissionOption("allow", "Allow",
								AcpSchema.PermissionOptionKind.ALLOW_ONCE)))))
			.doOnNext(response -> outcomes.add(response.outcome().getClass().getSimpleName()))
			.thenReturn(AcpSchema.PromptResponse.endTurn()))
		.build());

	private final AcpHttpEndpoint endpoint = AcpHttpEndpoint.create(JSON, agents,
			StreamableHttpAcpAgentTransportOptions.builder()
				.shutdownTimeout(Duration.ofSeconds(2))
				.maxProvisionalSessions(2)
				.build());

	private String connection;

	private Frames connectionStream;

	@BeforeEach
	void initialize() {
		AcpHttpReply.Body initialized = (AcpHttpReply.Body) endpoint.handle(post(INITIALIZE)).block(TIMEOUT);
		assertThat(initialized.status()).isEqualTo(200);
		connection = initialized.headers().get("Acp-Connection-Id");
		connectionStream = open(null);
	}

	@AfterEach
	void close() {
		endpoint.closeGracefully().block(TIMEOUT);
	}

	@Test
	void theAgentsRequestsGoOnTheSessionStreamAndTheirResponsesMustNameThatSession() {
		String session = newSession(2);
		Frames sessionStream = open(session);

		assertThat(status(post(prompt(3, session)).header("Acp-Session-Id", session))).isEqualTo(202);
		Object permission = awaitRequest(sessionStream, "session/request_permission");
		assertThat(sessionStream.events()).anyMatch(event -> event.contains("hello"));

		// A response under another session's header is refused; under the right one it is taken.
		assertThat(status(post(cancelled(permission)).header("Acp-Session-Id", "s-other"))).isEqualTo(400);
		assertThat(status(post(cancelled(permission)).header("Acp-Session-Id", session))).isEqualTo(202);
		awaitTrue(() -> sessionStream.events().stream().anyMatch(event -> event.contains("end_turn")));

		// A response without the header is taken for the session that asked.
		assertThat(status(post(prompt(4, session)).header("Acp-Session-Id", session))).isEqualTo(202);
		Object second = awaitRequest(sessionStream, "session/request_permission", permission);
		assertThat(status(post(cancelled(second)))).isEqualTo(202);
		awaitTrue(() -> outcomes.size() == 2);

		// A response to no request of the agent's is dropped.
		assertThat(status(post(cancelled("no-such-request")).header("Acp-Session-Id", session))).isEqualTo(202);
		// A notification of the client's reaches the agent on the session.
		assertThat(status(post("{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\",\"params\":{\"sessionId\":\"" + session
				+ "\"}}")
			.header("Acp-Session-Id", session))).isEqualTo(202);
		assertThat(outcomes).containsOnly("PermissionCancelled");
	}

	@Test
	void aSessionBoundMethodNeedsAHeaderThatNamesItsSession() {
		String session = newSession(2);
		assertThat(status(post(prompt(3, session)))).isEqualTo(400);
		assertThat(status(post(prompt(4, session)).header("Acp-Session-Id", "s-other"))).isEqualTo(400);
	}

	/**
	 * A stream for a session the connection does not know yet is provisional (a client resuming
	 * opens it before {@code session/load}), up to the bound; beyond it, 404.
	 */
	@Test
	void sessionStreamsForUnknownSessionsAreBounded() {
		open("s-unknown-1");
		open("s-unknown-2");
		assertThat(endpoint.handle(get().header("Acp-Session-Id", "s-unknown-3")).block(TIMEOUT).status())
			.isEqualTo(404);
	}

	@Test
	void sessionLoadAnswersOnTheConnectionStream() {
		assertThat(status(post(load(5, "s-loaded")))).isEqualTo(202);
		awaitTrue(() -> connectionStream.events()
			.stream()
			.anyMatch(event -> event.contains("\"id\":5") && event.contains("\"result\"")));
		open("s-loaded");

		// A load the agent fails is answered with its error, and leaves no session behind.
		assertThat(status(post(load(6, "missing")))).isEqualTo(202);
		awaitTrue(() -> connectionStream.events()
			.stream()
			.anyMatch(event -> event.contains("\"id\":6") && event.contains("\"error\"")));
	}

	@Test
	void anUnreadableContentLengthIsLeftToTheBodyLimit() {
		assertThat(status(post(prompt(3, "s-x")).header("Content-Length", "many"))).isEqualTo(400);
	}

	private String newSession(int id) {
		assertThat(status(post("{\"jsonrpc\":\"2.0\",\"id\":" + id
				+ ",\"method\":\"session/new\",\"params\":{\"cwd\":\"/\",\"mcpServers\":[]}}")))
			.isEqualTo(202);
		String session = "s-" + sessions.get();
		awaitTrue(() -> connectionStream.events().stream().anyMatch(event -> event.contains(session)));
		return session;
	}

	private Frames open(@Nullable String session) {
		FakeExchange get = get();
		if (session != null) {
			get.header("Acp-Session-Id", session);
		}
		AcpHttpReply reply = endpoint.handle(get).block(TIMEOUT);
		assertThat(reply).isInstanceOf(AcpHttpReply.EventStream.class);
		AcpHttpReply.EventStream stream = (AcpHttpReply.EventStream) reply;
		if (session != null) {
			assertThat(stream.headers()).containsEntry("Acp-Session-Id", session);
		}
		Frames frames = new Frames();
		stream.frames().subscribe(frames);
		return frames;
	}

	/** Waits for a request of the agent's with the given method, other than {@code skip}, and returns its id. */
	private static Object awaitRequest(Frames frames, String method, Object... skip) {
		awaitTrue(() -> request(frames, method, skip).isPresent());
		return request(frames, method, skip).orElseThrow();
	}

	private static Optional<Object> request(Frames frames, String method, Object... skip) {
		return frames.events()
			.stream()
			.map(AcpHttpSessionRoutingTest::parse)
			.filter(message -> method.equals(message.get("method")) && message.get("id") != null)
			.<Object>map(message -> message.get("id"))
			.filter(id -> !List.of(skip).contains(id))
			.findFirst();
	}

	private static Map<?, ?> parse(String json) {
		try {
			Map<?, ?> message = JSON.readValue(json, Map.class);
			return (message != null) ? message : Map.of();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String prompt(int id, String session) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"session/prompt\",\"params\":{\"sessionId\":\""
				+ session + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"hi\"}]}}";
	}

	private static String load(int id, String session) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"session/load\",\"params\":{\"sessionId\":\""
				+ session + "\",\"cwd\":\"/\",\"mcpServers\":[]}}";
	}

	private static String cancelled(Object id) {
		String json = (id instanceof String text) ? "\"" + text + "\"" : String.valueOf(id);
		return "{\"jsonrpc\":\"2.0\",\"id\":" + json + ",\"result\":{\"outcome\":{\"outcome\":\"cancelled\"}}}";
	}

	private int status(FakeExchange exchange) {
		return endpoint.handle(exchange).block(TIMEOUT).status();
	}

	private FakeExchange post(String body) {
		FakeExchange post = new FakeExchange("POST").header("Content-Type", "application/json").body(body);
		return body.equals(INITIALIZE) ? post : post.header("Acp-Connection-Id", connection);
	}

	private FakeExchange get() {
		return new FakeExchange("GET").header("Accept", "text/event-stream").header("Acp-Connection-Id", connection);
	}

	private static void awaitTrue(BooleanSupplier condition) {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Condition not met within " + TIMEOUT);
			}
			LockSupport.parkNanos(10_000_000);
		}
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

		@Override
		public void onSubscribe(Subscription s) {
			s.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(SseFrame frame) {
			received.add(frame);
		}

		@Override
		public void onError(Throwable error) {
		}

		@Override
		public void onComplete() {
		}

		List<String> events() {
			return received.stream().filter(frame -> !frame.comment()).map(SseFrame::data).toList();
		}

	}

}
