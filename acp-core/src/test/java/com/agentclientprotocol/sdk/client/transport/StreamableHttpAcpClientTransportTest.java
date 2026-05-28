/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.agentclientprotocol.sdk.AcpTestFixtures;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StreamableHttpAcpClientTransport}.
 */
class StreamableHttpAcpClientTransportTest {

	private AcpJsonMapper jsonMapper;

	@BeforeEach
	void setUp() {
		jsonMapper = AcpJsonMapper.createDefault();
	}

	@Test
	void constructorValidatesEndpointUri() {
		assertThatThrownBy(() -> new StreamableHttpAcpClientTransport(null, jsonMapper))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("endpointUri");
	}

	@Test
	void constructorValidatesJsonMapper() {
		assertThatThrownBy(
				() -> new StreamableHttpAcpClientTransport(URI.create("https://localhost:8443/acp"), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("JsonMapper");
	}

	@Test
	void constructorRejectsNonHttpSchemes() {
		assertThatThrownBy(() -> new StreamableHttpAcpClientTransport(URI.create("ws://localhost:8080/acp"), jsonMapper))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("http or https");
	}

	@Test
	void constructorAcceptsCustomHttpClient() {
		HttpClient httpClient = mock(HttpClient.class);

		StreamableHttpAcpClientTransport transport = new StreamableHttpAcpClientTransport(
				URI.create("https://localhost:8443/acp"), jsonMapper, httpClient);

		assertThat(transport).isNotNull();
	}

	@Test
	void routingModeIsConfigurable() {
		StreamableHttpAcpClientTransport transport = new StreamableHttpAcpClientTransport(
				URI.create("https://localhost:8443/acp"), jsonMapper)
			.routingMode(StreamableHttpAcpClientTransport.RoutingMode.STRICT);

		assertThat(transport).isNotNull();
	}

	@Test
	void defaultAcpPathIsCorrect() {
		assertThat(StreamableHttpAcpClientTransport.DEFAULT_ACP_PATH).isEqualTo("/acp");
	}

	@Test
	void strictRoutingRejectsUnknownOutboundMethods() {
		StreamableHttpAcpClientTransport transport = new StreamableHttpAcpClientTransport(
				URI.create("https://localhost:8443/acp"), jsonMapper)
			.routingMode(StreamableHttpAcpClientTransport.RoutingMode.STRICT);

		transport.connect(message -> Mono.empty()).block();

		assertThatThrownBy(() -> transport
			.sendMessage(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION, "extension/custom",
					Map.of("sessionId", "session-1")))
			.block())
			.hasMessageContaining("No explicit routing rule for outbound method extension/custom");
	}

	@Test
	void concurrentSessionLoadsReuseInFlightSessionStreamOpen() throws Exception {
		HttpClient httpClient = mock(HttpClient.class);
		AtomicInteger sessionGetCount = new AtomicInteger();
		CountDownLatch sessionGetStarted = new CountDownLatch(1);
		CompletableFuture<HttpResponse<InputStream>> sessionStreamResponse = new CompletableFuture<>();

		when(httpClient.sendAsync(any(), any())).thenAnswer(invocation -> {
			HttpRequest request = invocation.getArgument(0);
			if ("POST".equals(request.method())
					&& request.headers().firstValue("Acp-Connection-Id").isEmpty()) {
				String initializeResponse = jsonMapper.writeValueAsString(AcpTestFixtures
					.createJsonRpcResponse("init-1", AcpTestFixtures.createInitializeResponse()));
				return CompletableFuture.completedFuture(response(200,
						Map.of("Content-Type", "application/json", "Acp-Connection-Id", "conn-1"),
						initializeResponse));
			}
			if ("GET".equals(request.method())
					&& request.headers().firstValue("Acp-Session-Id").isEmpty()) {
				return CompletableFuture.completedFuture(
						response(200, Map.of("Content-Type", "text/event-stream"), emptyBody()));
			}
			if ("GET".equals(request.method())) {
				sessionGetCount.incrementAndGet();
				sessionGetStarted.countDown();
				return sessionStreamResponse;
			}
			if ("POST".equals(request.method())) {
				return CompletableFuture.completedFuture(response(202, Map.of(), null));
			}
			return CompletableFuture.completedFuture(response(202, Map.of(), null));
		});

		StreamableHttpAcpClientTransport transport = new StreamableHttpAcpClientTransport(
				URI.create("https://localhost:8443/acp"), jsonMapper, httpClient);
		transport.setExceptionHandler(error -> {
		});
		transport.connect(message -> Mono.empty()).block();
		transport.sendMessage(AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_INITIALIZE, "init-1",
				AcpTestFixtures.createInitializeRequest()))
			.block();

		CompletableFuture<Void> loads = Mono.when(
				transport.sendMessage(AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_SESSION_LOAD, "load-1",
						new AcpSchema.LoadSessionRequest("sess-1", "/workspace", List.of()))),
				transport.sendMessage(AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_SESSION_LOAD, "load-2",
						new AcpSchema.LoadSessionRequest("sess-1", "/workspace", List.of()))))
			.toFuture();

		assertThat(sessionGetStarted.await(1, TimeUnit.SECONDS)).isTrue();
		assertThat(sessionGetCount).hasValue(1);

		sessionStreamResponse.complete(response(200, Map.of("Content-Type", "text/event-stream"), emptyBody()));
		loads.get(1, TimeUnit.SECONDS);
	}

	@Test
	void sessionNewResponseDoesNotBlockConnectionReaderWhileSessionStreamOpens() throws Exception {
		HttpClient httpClient = mock(HttpClient.class);
		PipedInputStream connectionStreamBody = new PipedInputStream();
		PipedOutputStream connectionStreamWriter = new PipedOutputStream(connectionStreamBody);
		CompletableFuture<HttpResponse<InputStream>> sessionStreamResponse = new CompletableFuture<>();
		BlockingQueue<AcpSchema.JSONRPCMessage> inboundMessages = new LinkedBlockingQueue<>();

		when(httpClient.sendAsync(any(), any())).thenAnswer(invocation -> {
			HttpRequest request = invocation.getArgument(0);
			if ("POST".equals(request.method())
					&& request.headers().firstValue("Acp-Connection-Id").isEmpty()) {
				String initializeResponse = jsonMapper.writeValueAsString(AcpTestFixtures
					.createJsonRpcResponse("init-1", AcpTestFixtures.createInitializeResponse()));
				return CompletableFuture.completedFuture(response(200,
						Map.of("Content-Type", "application/json", "Acp-Connection-Id", "conn-1"),
						initializeResponse));
			}
			if ("GET".equals(request.method())
					&& request.headers().firstValue("Acp-Session-Id").isEmpty()) {
				return CompletableFuture.completedFuture(
						response(200, Map.of("Content-Type", "text/event-stream"), connectionStreamBody));
			}
			if ("GET".equals(request.method())) {
				return sessionStreamResponse;
			}
			return CompletableFuture.completedFuture(response(202, Map.of(), null));
		});

		StreamableHttpAcpClientTransport transport = new StreamableHttpAcpClientTransport(
				URI.create("https://localhost:8443/acp"), jsonMapper, httpClient);
		try {
			transport.connect(message -> message.doOnNext(inboundMessages::add).then(Mono.empty())).block();
			transport.sendMessage(AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_INITIALIZE, "init-1",
					AcpTestFixtures.createInitializeRequest()))
				.block();
			awaitResponse(inboundMessages, "init-1");

			transport.sendMessage(AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_SESSION_NEW, "new-1",
					AcpTestFixtures.createNewSessionRequest("/workspace")))
				.block();
			transport.sendMessage(new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "ping-1",
					"extension/ping", Map.of()))
				.block();

			writeSse(connectionStreamWriter,
					AcpTestFixtures.createJsonRpcResponse("new-1",
							new AcpSchema.NewSessionResponse("sess-1", null, null)));
			writeSse(connectionStreamWriter, AcpTestFixtures.createJsonRpcResponse("ping-1", Map.of()));

			assertThat(awaitResponse(inboundMessages, "ping-1")).isNotNull();
			assertThat(inboundMessages.stream()
				.filter(AcpSchema.JSONRPCResponse.class::isInstance)
				.map(AcpSchema.JSONRPCResponse.class::cast)
				.noneMatch(response -> "new-1".equals(response.id()))).isTrue();

			sessionStreamResponse.complete(response(200, Map.of("Content-Type", "text/event-stream"), emptyBody()));
			assertThat(awaitResponse(inboundMessages, "new-1")).isNotNull();
		}
		finally {
			connectionStreamWriter.close();
			transport.close();
		}
	}

	@Test
	void sessionNewErrorResponseIsDeliveredWithoutOpeningSessionStream() throws Exception {
		HttpClient httpClient = mock(HttpClient.class);
		PipedInputStream connectionStreamBody = new PipedInputStream();
		PipedOutputStream connectionStreamWriter = new PipedOutputStream(connectionStreamBody);
		AtomicInteger sessionGetCount = new AtomicInteger();
		BlockingQueue<AcpSchema.JSONRPCMessage> inboundMessages = new LinkedBlockingQueue<>();

		when(httpClient.sendAsync(any(), any())).thenAnswer(invocation -> {
			HttpRequest request = invocation.getArgument(0);
			if ("POST".equals(request.method())
					&& request.headers().firstValue("Acp-Connection-Id").isEmpty()) {
				String initializeResponse = jsonMapper.writeValueAsString(AcpTestFixtures
					.createJsonRpcResponse("init-1", AcpTestFixtures.createInitializeResponse()));
				return CompletableFuture.completedFuture(response(200,
						Map.of("Content-Type", "application/json", "Acp-Connection-Id", "conn-1"),
						initializeResponse));
			}
			if ("GET".equals(request.method())
					&& request.headers().firstValue("Acp-Session-Id").isEmpty()) {
				return CompletableFuture.completedFuture(
						response(200, Map.of("Content-Type", "text/event-stream"), connectionStreamBody));
			}
			if ("GET".equals(request.method())) {
				sessionGetCount.incrementAndGet();
				return CompletableFuture.completedFuture(
						response(200, Map.of("Content-Type", "text/event-stream"), emptyBody()));
			}
			return CompletableFuture.completedFuture(response(202, Map.of(), null));
		});

		StreamableHttpAcpClientTransport transport = new StreamableHttpAcpClientTransport(
				URI.create("https://localhost:8443/acp"), jsonMapper, httpClient);
		try {
			transport.connect(message -> message.doOnNext(inboundMessages::add).then(Mono.empty())).block();
			transport.sendMessage(AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_INITIALIZE, "init-1",
					AcpTestFixtures.createInitializeRequest()))
				.block();
			awaitResponse(inboundMessages, "init-1");

			transport.sendMessage(AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_SESSION_NEW, "new-1",
					AcpTestFixtures.createNewSessionRequest("/workspace")))
				.block();
			writeSse(connectionStreamWriter,
					new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, "new-1", null,
							new AcpSchema.JSONRPCError(-32000, "agent rejected session", null)));

			AcpSchema.JSONRPCResponse response = awaitResponse(inboundMessages, "new-1");
			assertThat(response.error()).isNotNull();
			assertThat(response.error().message()).isEqualTo("agent rejected session");
			assertThat(sessionGetCount).hasValue(0);
		}
		finally {
			connectionStreamWriter.close();
			transport.close();
		}
	}

	@Test
	void concurrentSessionSseEventsAreSerializedIntoInboundSink() throws Exception {
		HttpClient httpClient = mock(HttpClient.class);
		Map<String, PipedOutputStream> sessionWriters = new ConcurrentHashMap<>();
		BlockingQueue<AcpSchema.JSONRPCMessage> inboundMessages = new LinkedBlockingQueue<>();

		when(httpClient.sendAsync(any(), any())).thenAnswer(invocation -> {
			HttpRequest request = invocation.getArgument(0);
			if ("POST".equals(request.method())
					&& request.headers().firstValue("Acp-Connection-Id").isEmpty()) {
				String initializeResponse = jsonMapper.writeValueAsString(AcpTestFixtures
					.createJsonRpcResponse("init-1", AcpTestFixtures.createInitializeResponse()));
				return CompletableFuture.completedFuture(response(200,
						Map.of("Content-Type", "application/json", "Acp-Connection-Id", "conn-1"),
						initializeResponse));
			}
			if ("GET".equals(request.method())
					&& request.headers().firstValue("Acp-Session-Id").isEmpty()) {
				return CompletableFuture.completedFuture(
						response(200, Map.of("Content-Type", "text/event-stream"), emptyBody()));
			}
			if ("GET".equals(request.method())) {
				String sessionId = request.headers().firstValue("Acp-Session-Id").orElseThrow();
				try {
					PipedInputStream sessionBody = new PipedInputStream(16 * 1024);
					PipedOutputStream writer = new PipedOutputStream(sessionBody);
					sessionWriters.put(sessionId, writer);
					return CompletableFuture.completedFuture(
							response(200, Map.of("Content-Type", "text/event-stream"), sessionBody));
				}
				catch (Exception e) {
					CompletableFuture<HttpResponse<InputStream>> failed = new CompletableFuture<>();
					failed.completeExceptionally(e);
					return failed;
				}
			}
			return CompletableFuture.completedFuture(response(202, Map.of(), null));
		});

		StreamableHttpAcpClientTransport transport = new StreamableHttpAcpClientTransport(
				URI.create("https://localhost:8443/acp"), jsonMapper, httpClient);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			transport.connect(message -> message.doOnNext(inboundMessages::add).then(Mono.empty())).block();
			transport.sendMessage(AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_INITIALIZE, "init-1",
					AcpTestFixtures.createInitializeRequest()))
				.block();
			awaitResponse(inboundMessages, "init-1");

			transport.sendMessage(AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_SESSION_LOAD, "load-1",
					new AcpSchema.LoadSessionRequest("sess-1", "/workspace/one", List.of())))
				.block();
			transport.sendMessage(AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_SESSION_LOAD, "load-2",
					new AcpSchema.LoadSessionRequest("sess-2", "/workspace/two", List.of())))
				.block();

			Future<?> first = executor.submit(() -> {
				writeSessionUpdates(sessionWriters.get("sess-1"), "sess-1", 50);
				return null;
			});
			Future<?> second = executor.submit(() -> {
				writeSessionUpdates(sessionWriters.get("sess-2"), "sess-2", 50);
				return null;
			});
			first.get(1, TimeUnit.SECONDS);
			second.get(1, TimeUnit.SECONDS);

			awaitNotifications(inboundMessages, 100);
		}
		finally {
			sessionWriters.values().forEach(writer -> {
				try {
					writer.close();
				}
				catch (Exception ignored) {
				}
			});
			executor.shutdownNow();
			transport.close();
		}
	}

	@Test
	void malformedSseEventDoesNotStopConnectionReader() throws Exception {
		HttpClient httpClient = mock(HttpClient.class);
		PipedInputStream connectionStreamBody = new PipedInputStream();
		PipedOutputStream connectionStreamWriter = new PipedOutputStream(connectionStreamBody);
		BlockingQueue<AcpSchema.JSONRPCMessage> inboundMessages = new LinkedBlockingQueue<>();

		when(httpClient.sendAsync(any(), any())).thenAnswer(invocation -> {
			HttpRequest request = invocation.getArgument(0);
			if ("POST".equals(request.method())
					&& request.headers().firstValue("Acp-Connection-Id").isEmpty()) {
				String initializeResponse = jsonMapper.writeValueAsString(AcpTestFixtures
					.createJsonRpcResponse("init-1", AcpTestFixtures.createInitializeResponse()));
				return CompletableFuture.completedFuture(response(200,
						Map.of("Content-Type", "application/json", "Acp-Connection-Id", "conn-1"),
						initializeResponse));
			}
			if ("GET".equals(request.method())
					&& request.headers().firstValue("Acp-Session-Id").isEmpty()) {
				return CompletableFuture.completedFuture(
						response(200, Map.of("Content-Type", "text/event-stream"), connectionStreamBody));
			}
			return CompletableFuture.completedFuture(response(202, Map.of(), null));
		});

		StreamableHttpAcpClientTransport transport = new StreamableHttpAcpClientTransport(
				URI.create("https://localhost:8443/acp"), jsonMapper, httpClient);
		try {
			transport.connect(message -> message.doOnNext(inboundMessages::add).then(Mono.empty())).block();
			transport.sendMessage(AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_INITIALIZE, "init-1",
					AcpTestFixtures.createInitializeRequest()))
				.block();
			awaitResponse(inboundMessages, "init-1");

			writeRawSse(connectionStreamWriter, "{ nope");
			transport.sendMessage(new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "ping-1",
					"extension/ping", Map.of()))
				.block();
			writeSse(connectionStreamWriter, AcpTestFixtures.createJsonRpcResponse("ping-1", Map.of()));

			assertThat(awaitResponse(inboundMessages, "ping-1")).isNotNull();
		}
		finally {
			connectionStreamWriter.close();
			transport.close();
		}
	}

	private void writeSessionUpdates(PipedOutputStream writer, String sessionId, int count) throws Exception {
		for (int i = 0; i < count; i++) {
			writeSse(writer, new AcpSchema.JSONRPCNotification(AcpSchema.METHOD_SESSION_UPDATE,
					new AcpSchema.SessionNotification(sessionId,
							new AcpSchema.AgentMessageChunk("agent_message_chunk",
									new AcpSchema.TextContent(sessionId + "-" + i)))));
		}
	}

	private void writeSse(PipedOutputStream writer, AcpSchema.JSONRPCMessage message) throws Exception {
		writer.write(("data: " + jsonMapper.writeValueAsString(message) + "\n\n").getBytes(StandardCharsets.UTF_8));
		writer.flush();
	}

	private void writeRawSse(PipedOutputStream writer, String data) throws Exception {
		writer.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
		writer.flush();
	}

	private AcpSchema.JSONRPCResponse awaitResponse(BlockingQueue<AcpSchema.JSONRPCMessage> messages, Object id)
			throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (System.nanoTime() < deadline) {
			AcpSchema.JSONRPCMessage message = messages.poll(50, TimeUnit.MILLISECONDS);
			if (message instanceof AcpSchema.JSONRPCResponse response && id.equals(response.id())) {
				return response;
			}
		}
		throw new AssertionError("Timed out waiting for response " + id);
	}

	private void awaitNotifications(BlockingQueue<AcpSchema.JSONRPCMessage> messages, int expected) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		int count = 0;
		while (System.nanoTime() < deadline) {
			AcpSchema.JSONRPCMessage message = messages.poll(50, TimeUnit.MILLISECONDS);
			if (message instanceof AcpSchema.JSONRPCNotification notification
					&& AcpSchema.METHOD_SESSION_UPDATE.equals(notification.method())) {
				count++;
				if (count == expected) {
					return;
				}
			}
		}
		throw new AssertionError("Timed out waiting for " + expected + " notifications; received " + count);
	}

	private InputStream emptyBody() {
		return new ByteArrayInputStream(new byte[0]);
	}

	private <T> HttpResponse<T> response(int statusCode, Map<String, String> headers, T body) {
		HttpResponse<T> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(statusCode);
		when(response.headers()).thenReturn(HttpHeaders.of(headers.entrySet()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, entry -> List.of(entry.getValue()))),
				(name, value) -> true));
		when(response.body()).thenReturn(body);
		return response;
	}

}
