/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
