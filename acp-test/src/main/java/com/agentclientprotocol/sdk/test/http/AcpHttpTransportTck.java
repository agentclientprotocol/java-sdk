/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.test.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The shared transport TCK: the protocol behaviour every host of the ACP endpoint must show on
 * the wire, whatever its server. Every host (the servlet on each container, the embedded
 * listener, each framework integration) runs it by subclassing it and starting its server in
 * {@link #startHost}; a host that fails a case reimplemented, or failed to apply, a rule of the
 * endpoint's.
 *
 * <p>The cases: the status table of the Streamable HTTP profile; a prompt round trip over
 * Streamable HTTP and over WebSocket with the SDK's Java clients; SSE responses that proxies do
 * not buffer ({@code X-Accel-Buffering: no}, {@code Cache-Control: no-cache}, a first frame at
 * once); an SSE stream that outlives the container's async timeout; concurrent outbound
 * WebSocket sends delivered whole (a container that allows one outstanding write, such as
 * Tomcat, fails a second one); shutdown closing open SSE and WebSocket streams promptly (a
 * closing comment, then close code 1001) without waiting out a framework's graceful timeout;
 * the {@code Origin} check over HTTP and on the WebSocket handshake; the WebSocket's
 * initialize-first rule and size limit; and, for a host that binds its own socket, the loopback
 * default.
 *
 * <p>The suite starts one host for most cases, configured by {@link #standardConfig()}, and a
 * second one for the shutdown case.
 *
 * @author Mark Pollack
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(60)
public abstract class AcpHttpTransportTck {

	/** The origin the standard configuration lists besides the loopback ones. */
	public static final String ALLOWED_ORIGIN = "https://allowed.example";

	/** The inbound limit of the standard configuration. */
	public static final long MAX_MESSAGE_BYTES = 64 * 1024;

	/** The async timeout a host should give its container, when it can set one. */
	public static final Duration CONTAINER_ASYNC_TIMEOUT = Duration.ofSeconds(1);

	private static final Duration TIMEOUT = Duration.ofSeconds(15);

	private static final AcpJsonMapper JSON = AcpJsonMapper.createDefault();

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	/**
	 * The status table's client. HTTP/1.1: over HTTP/2 a server that refuses a request before
	 * reading its body (415, 413) may reset the stream, and the client then reports the reset
	 * rather than the status, whichever arrives first. The statuses are the same on both.
	 */
	private static final HttpClient HTTP_1_1 = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(TIMEOUT)
		.build();

	/** The threads the agent sends concurrent updates from. */
	private static final Scheduler SENDERS = Schedulers.newParallel("tck-senders", 4, true);

	private @Nullable Host host;

	/**
	 * A running host of the endpoint.
	 */
	public interface Host extends AutoCloseable {

		/**
		 * Returns the endpoint's HTTP URI, such as {@code http://127.0.0.1:8080/acp}.
		 * @return the endpoint
		 */
		URI endpoint();

		/**
		 * Stops the host as its framework does when the application stops, the endpoint's
		 * drain included, and returns once it has stopped.
		 * @throws Exception if stopping fails
		 */
		void stop() throws Exception;

		@Override
		default void close() throws Exception {
			stop();
		}

	}

	/**
	 * What a host is started with.
	 * @param agents the agent factory to serve
	 * @param options the endpoint's options; a framework host maps them onto its properties
	 * @param containerAsyncTimeout the async request timeout to give the container, where the
	 * host can set one (Tomcat's connector, {@code spring.mvc.async.request-timeout}); the
	 * endpoint must not let it cut an SSE stream
	 */
	public record HostConfig(AcpAgentFactory agents, StreamableHttpAcpAgentTransportOptions options,
			Duration containerAsyncTimeout) {
	}

	/**
	 * Starts the host under test, listening on an ephemeral port. A framework host whose test
	 * runs one application may return that application each time, configured like
	 * {@link #standardConfig()} with an agent that behaves like {@link #agentFactory()}.
	 * @param config what to serve
	 * @return the running host
	 * @throws Exception if it cannot start
	 */
	protected abstract Host startHost(HostConfig config) throws Exception;

	/**
	 * Returns whether the host serves WebSocket on the endpoint's path; the WebSocket cases are
	 * skipped otherwise.
	 * @return true by default
	 */
	protected boolean supportsWebSocket() {
		return true;
	}

	/**
	 * Returns whether the host binds a socket of its own (the embedded listener) rather than
	 * mounting in a framework's server; only such a host runs the bind-default case.
	 * @return false by default
	 */
	protected boolean bindsItsOwnSocket() {
		return false;
	}

	/**
	 * The standard configuration: the TCK's agent, a 250 ms keep-alive, a 64 KB inbound limit,
	 * {@value #ALLOWED_ORIGIN} allowed, a 3 second shutdown timeout, and a 1 second container
	 * async timeout.
	 * @return the configuration
	 */
	public static HostConfig standardConfig() {
		return new HostConfig(agentFactory(),
				StreamableHttpAcpAgentTransportOptions.builder()
					.keepAliveInterval(Duration.ofMillis(250))
					.maxPostBodyBytes(MAX_MESSAGE_BYTES)
					.allowedOrigins(List.of(ALLOWED_ORIGIN))
					.shutdownTimeout(Duration.ofSeconds(3))
					.build(),
				CONTAINER_ASYNC_TIMEOUT);
	}

	/**
	 * The TCK's agent: {@code updates:N} as a prompt sends N session updates concurrently, then
	 * ends the turn; {@code hold} waits a minute; any other prompt is echoed as one update.
	 * @return the agent factory
	 */
	public static AcpAgentFactory agentFactory() {
		AtomicInteger sessions = new AtomicInteger();
		return AcpAgentFactory.async(transport -> AcpAgent.async(transport)
			.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok()))
			.newSessionHandler(
					request -> Mono.just(new AcpSchema.NewSessionResponse("s-" + sessions.incrementAndGet(), null, null)))
			.promptHandler((request, context) -> {
				String text = ((AcpSchema.TextContent) request.prompt().get(0)).text();
				if (text.startsWith("updates:")) {
					int count = Integer.parseInt(text.substring("updates:".length()));
					return Flux.range(0, count)
						.flatMap(i -> context.sendMessage("u" + i).subscribeOn(SENDERS), count)
						.then(Mono.just(AcpSchema.PromptResponse.endTurn()));
				}
				if (text.equals("hold")) {
					return Mono.delay(Duration.ofMinutes(1), AcpSchedulers.timeouts())
						.thenReturn(AcpSchema.PromptResponse.endTurn());
				}
				return context.sendMessage("echo " + text).thenReturn(AcpSchema.PromptResponse.endTurn());
			})
			.build());
	}

	@BeforeAll
	void startSharedHost() throws Exception {
		this.host = startHost(standardConfig());
	}

	@AfterAll
	void stopSharedHost() throws Exception {
		Host current = this.host;
		if (current != null) {
			current.stop();
		}
	}

	private URI endpoint() {
		Host current = this.host;
		if (current == null) {
			throw new IllegalStateException("The host did not start");
		}
		return current.endpoint();
	}

	// The protocol

	@Test
	void initializeAnswersWithAConnectionId() throws Exception {
		HttpResponse<String> response = HttpProbes.initialize(endpoint(), null);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Acp-Connection-Id")).hasValueSatisfying(id -> assertThat(id).isNotBlank());
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				type -> assertThat(type).startsWith("application/json"));
		assertThat(response.body()).contains("\"id\":\"probe\"").contains("\"result\"");
	}

	@Test
	void aJavaClientCompletesAPromptOverStreamableHttp() {
		assertPromptRoundTrip(new StreamableHttpAcpClientTransport(endpoint(), JSON), 3);
	}

	@Test
	void aJavaClientCompletesAPromptOverWebSocket() {
		assumeTrue(supportsWebSocket(), "the host serves no WebSocket");
		assertPromptRoundTrip(new WebSocketAcpClientTransport(webSocketUri(endpoint()), JSON), 3);
	}

	@Test
	void theStatusTable() throws Exception {
		URI uri = endpoint();
		assertThat(post(uri, "text/plain", HttpProbes.INITIALIZE, Map.of()).statusCode()).isEqualTo(415);
		assertThat(oversizedPostStatus(uri)).isEqualTo(413);
		assertThat(post(uri, "application/json", "[" + HttpProbes.INITIALIZE + "]", Map.of()).statusCode())
			.isEqualTo(501);
		assertThat(post(uri, "application/json", "not json", Map.of()).statusCode()).isEqualTo(400);
		String notification = "{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\",\"params\":{\"sessionId\":\"s\"}}";
		assertThat(post(uri, "application/json", notification, Map.of()).statusCode()).isEqualTo(400);
		assertThat(post(uri, "application/json", notification, Map.of("Acp-Connection-Id", "unknown")).statusCode())
			.isEqualTo(404);
		assertThat(post(uri, "application/json", HttpProbes.INITIALIZE, Map.of("Acp-Connection-Id", "any"))
			.statusCode()).isEqualTo(400);
		assertThat(send(HttpRequest.newBuilder(uri).GET().header("Accept", "application/json")).statusCode())
			.isEqualTo(406);
		assertThat(send(HttpRequest.newBuilder(uri).GET().header("Accept", "text/event-stream")).statusCode())
			.isEqualTo(400);
		assertThat(send(HttpRequest.newBuilder(uri).DELETE().header("Acp-Connection-Id", "unknown")).statusCode())
			.isEqualTo(404);
		String connectionId = initialize(uri);
		assertThat(send(HttpRequest.newBuilder(uri).DELETE().header("Acp-Connection-Id", connectionId)).statusCode())
			.isEqualTo(202);
		assertThat(send(HttpRequest.newBuilder(uri).DELETE().header("Acp-Connection-Id", connectionId)).statusCode())
			.isEqualTo(404);
		assertThat(send(HttpRequest.newBuilder(uri).method("PUT", HttpRequest.BodyPublishers.noBody())).statusCode())
			.isEqualTo(405);
	}

	// SSE

	@Test
	void sseResponsesAreNotBuffered() throws Exception {
		URI uri = endpoint();
		String connectionId = initialize(uri);
		try (SseStream stream = SseStream.open(uri, connectionId)) {
			assertThat(stream.status()).isEqualTo(200);
			assertThat(stream.header("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/event-stream"));
			assertThat(stream.header("Cache-Control")).hasValue("no-cache");
			assertThat(stream.header("X-Accel-Buffering")).hasValue("no");
			assertThat(stream.header("Acp-Connection-Id")).hasValue(connectionId);
			// The opening comment arrives at once: nothing between the endpoint and the client
			// holds the stream back (a compressing filter or a whole-body wrapper would).
			assertThat(stream.nextLine(Duration.ofSeconds(2))).isEqualTo(": connected");
		}
	}

	@Test
	void anSseStreamOutlivesTheContainerAsyncTimeout() throws Exception {
		URI uri = endpoint();
		String connectionId = initialize(uri);
		try (SseStream stream = SseStream.open(uri, connectionId)) {
			assertThat(stream.status()).isEqualTo(200);
			long deadline = System.nanoTime() + CONTAINER_ASYNC_TIMEOUT.multipliedBy(3).toNanos();
			while (System.nanoTime() < deadline) {
				assertThat(stream.nextLine(Duration.ofSeconds(2))).as("the stream is still open").isNotNull();
			}
			// Still open after three async timeouts: keep-alives keep arriving.
			assertThat(stream.nextLine(Duration.ofSeconds(2))).isNotNull();
		}
	}

	// WebSocket

	@Test
	void concurrentWebSocketSendsAreSerialized() {
		assumeTrue(supportsWebSocket(), "the host serves no WebSocket");
		// 200 updates sent from parallel threads: a host that handed its container a second
		// write before the first completed fails here (Tomcat: TEXT_FULL_WRITING).
		assertPromptRoundTrip(new WebSocketAcpClientTransport(webSocketUri(endpoint()), JSON), 200);
	}

	@Test
	void aWebSocketMustOpenWithInitialize() throws Exception {
		assumeTrue(supportsWebSocket(), "the host serves no WebSocket");
		try (RawWebSocket socket = RawWebSocket.open(endpoint(), null)) {
			socket.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/new\",\"params\":{\"cwd\":\"/\",\"mcpServers\":[]}}");
			assertThat(socket.closeCode()).as("close code (failure: %s)", socket.error()).isEqualTo(1002);
		}
	}

	@Test
	void anOversizedWebSocketMessageClosesWith1009() throws Exception {
		assumeTrue(supportsWebSocket(), "the host serves no WebSocket");
		try (RawWebSocket socket = RawWebSocket.open(endpoint(), null)) {
			socket.send(HttpProbes.INITIALIZE);
			assertThat(socket.nextMessage()).contains("\"result\"");
			socket.send("x".repeat((int) MAX_MESSAGE_BYTES + 1));
			assertThat(socket.closeCode()).as("close code (failure: %s)", socket.error()).isEqualTo(1009);
		}
	}

	// Origin

	@Test
	void aForeignOriginIsRefused() throws Exception {
		assertThat(HttpProbes.initialize(endpoint(), "http://evil.example").statusCode()).isEqualTo(403);
		assertThat(HttpProbes.get(endpoint(), "http://evil.example")).isEqualTo(403);
		if (supportsWebSocket()) {
			assertThat(HttpProbes.webSocketHandshake(endpoint(), "http://evil.example")).isEqualTo(403);
		}
	}

	@Test
	void loopbackListedAndAbsentOriginsAreServed() throws Exception {
		for (String origin : new String[] { "http://localhost:3000", "http://127.0.0.1", "https://[::1]:8443",
				ALLOWED_ORIGIN, null }) {
			assertThat(HttpProbes.initialize(endpoint(), origin).statusCode()).as(String.valueOf(origin)).isEqualTo(200);
			if (supportsWebSocket()) {
				assertThat(HttpProbes.webSocketHandshake(endpoint(), origin)).as(String.valueOf(origin))
					.isEqualTo(HttpProbes.SWITCHING_PROTOCOLS);
			}
		}
	}

	// Bind

	@Test
	void anEmbeddedHostIsNotReachableFromTheNetworkByDefault() throws Exception {
		assumeTrue(bindsItsOwnSocket(), "the host mounts in a framework's server, which binds it");
		Optional<InetAddress> external = Networks.nonLoopbackAddress();
		assumeTrue(external.isPresent(), "this machine has no non-loopback address");
		int port = endpoint().getPort();
		assertThatThrownBy(() -> connect(external.get(), port)).isInstanceOf(IOException.class);
		connect(InetAddress.getLoopbackAddress(), port);
	}

	// Shutdown

	/**
	 * Runs last: a host that cannot start a second server (a framework test runs one
	 * application) may hand back its running one, which this case drains.
	 */
	@Test
	@Order(Integer.MAX_VALUE)
	void shutdownClosesOpenStreamsPromptly() throws Exception {
		Host second = startHost(standardConfig());
		boolean stopped = false;
		try {
			URI uri = second.endpoint();
			String connectionId = initialize(uri);
			SseStream stream = SseStream.open(uri, connectionId);
			assertThat(stream.nextLine(Duration.ofSeconds(2))).isEqualTo(": connected");
			RawWebSocket socket = null;
			if (supportsWebSocket()) {
				socket = RawWebSocket.open(uri, null);
				socket.send(HttpProbes.INITIALIZE);
				assertThat(socket.nextMessage()).contains("\"result\"");
			}
			long start = System.nanoTime();
			second.stop();
			stopped = true;
			Duration took = Duration.ofNanos(System.nanoTime() - start);
			// A framework's graceful shutdown waits up to 30 seconds for open requests; the host
			// drains its streams before that, so it never waits.
			assertThat(took).isLessThan(Duration.ofSeconds(10));
			assertThat(stream.remainingLines(Duration.ofSeconds(5))).contains(": shutting down");
			stream.close();
			if (socket != null) {
				assertThat(socket.closeCode()).as("close code (failure: %s)", socket.error()).isEqualTo(1001);
				socket.close();
			}
		}
		finally {
			if (!stopped) {
				second.stop();
			}
		}
	}

	// Helpers

	private static void assertPromptRoundTrip(AcpClientTransport transport, int updates) {
		List<String> received = new CopyOnWriteArrayList<>();
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).sessionUpdateHandler(notification -> {
			if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
					&& chunk.content() instanceof AcpSchema.TextContent text) {
				received.add(text.text());
			}
			return Mono.empty();
		}).build();
		try {
			assertThat(client.initialize().block(TIMEOUT)).isNotNull();
			String sessionId = Objects
				.requireNonNull(client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT))
				.sessionId();
			AcpSchema.PromptResponse response = Objects.requireNonNull(client
				.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("updates:" + updates))))
				.block(TIMEOUT));
			assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			// Updates are delivered before the prompt's answer on both transports.
			assertThat(received).hasSize(updates).doesNotHaveDuplicates();
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
		}
	}

	private static String initialize(URI uri) throws Exception {
		HttpResponse<String> response = HttpProbes.initialize(uri, null);
		assertThat(response.statusCode()).isEqualTo(200);
		return response.headers().firstValue("Acp-Connection-Id").orElseThrow();
	}

	private static HttpResponse<String> post(URI uri, String contentType, String body, Map<String, String> headers)
			throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri)
			.header("Content-Type", contentType)
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(body));
		headers.forEach(request::header);
		return send(request);
	}

	/**
	 * The status of a POST over the size limit, sent as its headers alone. The endpoint refuses
	 * such a body by its Content-Length without reading it, and some servers (Tomcat as a WebFlux
	 * server, Jetty) then close the connection while the body is still arriving. The reset that
	 * follows can cost a client still sending the 413, which the JDK client reports as an I/O
	 * error ("header parser received no bytes"). With no body sent, nothing is left unread and
	 * the status always arrives.
	 */
	private static int oversizedPostStatus(URI uri) throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), (int) TIMEOUT.toMillis());
			socket.setSoTimeout((int) TIMEOUT.toMillis());
			String request = "POST " + uri.getRawPath() + " HTTP/1.1\r\n" + "Host: " + uri.getHost() + ":" + uri.getPort()
					+ "\r\n" + "Content-Type: application/json\r\n" + "Accept: application/json, text/event-stream\r\n"
					+ "Content-Length: " + (MAX_MESSAGE_BYTES + 1) + "\r\n\r\n";
			socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().flush();
			String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
				.readLine();
			if (status == null || !status.startsWith("HTTP/1.1 ") || status.length() < 12) {
				throw new IOException("No status line for an oversized POST: " + status);
			}
			return Integer.parseInt(status.substring(9, 12));
		}
	}

	private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return HTTP_1_1.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
			.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
	}

	private static URI webSocketUri(URI endpoint) {
		return URI.create(endpoint.toString().replaceFirst("^http", "ws"));
	}

	private static void connect(InetAddress address, int port) throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(address, port), 2000);
		}
	}

	/** An open SSE response, read line by line on a thread of its own. */
	private static final class SseStream implements AutoCloseable {

		private static final String EOF = "\u0000EOF";

		private final HttpResponse<InputStream> response;

		private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

		private final Thread reader;

		private SseStream(HttpResponse<InputStream> response) {
			this.response = response;
			this.reader = new Thread(this::read, "tck-sse-reader");
			this.reader.setDaemon(true);
			this.reader.start();
		}

		static SseStream open(URI uri, String connectionId) throws Exception {
			HttpRequest request = HttpRequest.newBuilder(uri)
				.header("Accept", "text/event-stream")
				.header("Acp-Connection-Id", connectionId)
				.GET()
				.build();
			return new SseStream(HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
				.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
		}

		private void read() {
			try (BufferedReader in = new BufferedReader(
					new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = in.readLine()) != null) {
					if (!line.isEmpty()) {
						lines.add(line);
					}
				}
			}
			catch (IOException e) {
				// The stream ended.
			}
			lines.add(EOF);
		}

		int status() {
			return response.statusCode();
		}

		Optional<String> header(String name) {
			return response.headers().firstValue(name);
		}

		/** The next non-empty line, or null when the stream ended or nothing came in time. */
		@Nullable String nextLine(Duration timeout) throws InterruptedException {
			String line = lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
			return (line == null || line.equals(EOF)) ? null : line;
		}

		/** Every line until the stream ends; fails if it does not end in time. */
		List<String> remainingLines(Duration timeout) throws InterruptedException {
			List<String> rest = new java.util.ArrayList<>();
			long deadline = System.nanoTime() + timeout.toNanos();
			while (true) {
				long left = deadline - System.nanoTime();
				String line = lines.poll(Math.max(0, left), TimeUnit.NANOSECONDS);
				if (line == null) {
					throw new AssertionError("The SSE stream did not end within " + timeout + "; read " + rest);
				}
				if (line.equals(EOF)) {
					return rest;
				}
				rest.add(line);
			}
		}

		@Override
		public void close() {
			try {
				response.body().close();
			}
			catch (IOException e) {
				// already closed
			}
		}

	}

	/** A WebSocket to the endpoint that records what it receives and how it closed. */
	private static final class RawWebSocket implements WebSocket.Listener, AutoCloseable {

		private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

		private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();

		/** Why the socket failed, when it did: a 1006 then says what ended it. */
		private volatile @Nullable Throwable error;

		private final StringBuilder partial = new StringBuilder();

		private @Nullable WebSocket socket;

		static RawWebSocket open(URI endpoint, @Nullable String origin) throws Exception {
			RawWebSocket listener = new RawWebSocket();
			WebSocket.Builder builder = HTTP.newWebSocketBuilder();
			if (origin != null) {
				builder.header("Origin", origin);
			}
			listener.socket = builder.buildAsync(webSocketUri(endpoint), listener)
				.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			return listener;
		}

		void send(String text) throws Exception {
			WebSocket current = socket;
			if (current != null) {
				current.sendText(text, true).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			}
		}

		String nextMessage() throws InterruptedException {
			String message = messages.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			if (message == null) {
				throw new AssertionError("No WebSocket message within " + TIMEOUT);
			}
			return message;
		}

		int closeCode() throws Exception {
			return closeCode.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}

		/** Why the socket failed, or null: reported with a close code of 1006. */
		@Nullable Throwable error() {
			return error;
		}

		@Override
		public @Nullable CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			partial.append(data);
			if (last) {
				messages.add(partial.toString());
				partial.setLength(0);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public @Nullable CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			closeCode.complete(statusCode);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			this.error = error;
			closeCode.complete(1006);
		}

		@Override
		public void close() {
			WebSocket current = socket;
			if (current != null) {
				current.abort();
			}
		}

	}

}
