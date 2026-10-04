/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WebSocketAcpClientTransport}.
 */
class WebSocketAcpClientTransportTest {

	private AcpJsonMapper jsonMapper;

	@BeforeEach
	void setUp() {
		jsonMapper = AcpJsonMapper.createDefault();
	}

	@Test
	void constructorValidatesServerUri() {
		assertThatThrownBy(() -> new WebSocketAcpClientTransport(null, jsonMapper))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("serverUri");
	}

	@Test
	void constructorValidatesJsonMapper() {
		assertThatThrownBy(() -> new WebSocketAcpClientTransport(URI.create("ws://localhost:8080/acp"), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("JsonMapper");
	}

	@Test
	void connectTimeoutIsConfigurable() {
		URI serverUri = URI.create("ws://localhost:8080/acp");
		WebSocketAcpClientTransport transport = new WebSocketAcpClientTransport(serverUri, jsonMapper)
			.connectTimeout(Duration.ofSeconds(60));

		assertThat(transport).isNotNull();
	}

	@Test
	void closeGracefullyCompletesWithoutConnection() {
		URI serverUri = URI.create("ws://localhost:8080/acp");
		WebSocketAcpClientTransport transport = new WebSocketAcpClientTransport(serverUri, jsonMapper);

		// Should complete without error even when not connected
		transport.closeGracefully().block(Duration.ofSeconds(5));
	}

	@Test
	void defaultAcpPathIsCorrect() {
		assertThat(WebSocketAcpClientTransport.DEFAULT_ACP_PATH).isEqualTo("/acp");
	}

	@Test
	void failedConnectAllowsRetry() {
		URI serverUri = URI.create("ws://nonexistent:9999/acp");
		WebSocketAcpClientTransport transport = new WebSocketAcpClientTransport(serverUri, jsonMapper);

		// First connect attempt (will fail due to nonexistent server)
		try {
			transport.connect(msg -> Mono.empty()).block(Duration.ofMillis(100));
		}
		catch (Exception ignored) {
			// Expected to fail
		}

		// Second connect should also attempt (not throw "Already connected")
		// because failed connections reset the connected flag
		try {
			transport.connect(msg -> Mono.empty()).block(Duration.ofMillis(100));
		}
		catch (Exception e) {
			// Should fail with connection/timeout error, not "Already connected"
			// Note: Reactor throws IllegalStateException for timeouts, so we check the message
			if (e instanceof IllegalStateException) {
				assertThat(e.getMessage()).doesNotContain("Already connected");
			}
		}
	}

	/**
	 * The transport's own HTTP client runs on an executor the transport created; closing the
	 * transport shuts it down, so its threads do not linger (about 60 seconds) after close.
	 */
	@Test
	void closeShutsDownTheExecutorOfItsOwnHttpClient() throws Exception {
		WebSocketAcpClientTransport transport = new WebSocketAcpClientTransport(URI.create("ws://127.0.0.1:1/acp"),
				jsonMapper);
		try {
			transport.connect(msg -> Mono.empty()).block(Duration.ofSeconds(10));
		}
		catch (RuntimeException expected) {
			// nothing listens on port 1
		}
		java.lang.reflect.Field field = WebSocketAcpClientTransport.class.getDeclaredField("httpClient");
		field.setAccessible(true);
		java.net.http.HttpClient httpClient = (java.net.http.HttpClient) field.get(transport);
		java.util.concurrent.ExecutorService executor = (java.util.concurrent.ExecutorService) httpClient.executor()
			.orElseThrow();
		assertThat(executor.isShutdown()).isFalse();

		transport.closeGracefully().block(Duration.ofSeconds(10));

		assertThat(executor.isShutdown()).isTrue();
	}

	/**
	 * The JDK's HttpClient has no default headers, so before the customizer there was no way
	 * to send an API key or a bearer token with the handshake. A real handshake against a
	 * server that records it and refuses it: the header is on the wire.
	 */
	@Test
	void theWebSocketCustomizerAddsHeadersToTheHandshake() throws Exception {
		List<String> authorization = new CopyOnWriteArrayList<>();
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/acp", exchange -> {
			authorization.addAll(exchange.getRequestHeaders().getOrDefault("Authorization", List.of()));
			exchange.sendResponseHeaders(401, -1);
			exchange.close();
		});
		server.start();
		WebSocketAcpClientTransport transport = new WebSocketAcpClientTransport(
				URI.create("ws://127.0.0.1:" + server.getAddress().getPort() + "/acp"), jsonMapper)
			.webSocketCustomizer(builder -> builder.header("Authorization", "Bearer token"));
		transport.setExceptionHandler(error -> {
		});
		try {
			assertThatThrownBy(() -> transport.connect(msg -> Mono.empty()).block(Duration.ofSeconds(10)))
				.isNotNull();

			assertThat(authorization).containsExactly("Bearer token");
		}
		finally {
			transport.closeGracefully().block(Duration.ofSeconds(10));
			server.stop(0);
		}
	}

	/**
	 * A customizer that throws, or sets a header the JDK reserves for the handshake, fails
	 * that connect with its own error, and the connect may be tried again.
	 */
	@Test
	void aWebSocketCustomizerThatThrowsFailsTheConnectWhichMayBeRetried() {
		AtomicBoolean signedIn = new AtomicBoolean();
		WebSocketAcpClientTransport transport = new WebSocketAcpClientTransport(URI.create("ws://127.0.0.1:1/acp"),
				jsonMapper)
			.webSocketCustomizer(builder -> {
				if (!signedIn.get()) {
					throw new IllegalStateException("not signed in");
				}
			});
		transport.setExceptionHandler(error -> {
		});
		try {
			assertThatThrownBy(() -> transport.connect(msg -> Mono.empty()).block(Duration.ofSeconds(10)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("not signed in");

			signedIn.set(true);
			// Nothing listens on port 1, so this attempt fails too, but by trying to connect.
			assertThatThrownBy(() -> transport.connect(msg -> Mono.empty()).block(Duration.ofSeconds(10)))
				.satisfies(error -> assertThat(error.getMessage()).doesNotContain("Already connected")
					.doesNotContain("not signed in"));
		}
		finally {
			transport.closeGracefully().block(Duration.ofSeconds(10));
		}
	}

	@Test
	void aReservedHandshakeHeaderFailsTheConnect() {
		WebSocketAcpClientTransport transport = new WebSocketAcpClientTransport(URI.create("ws://127.0.0.1:1/acp"),
				jsonMapper)
			.webSocketCustomizer(builder -> builder.header("Sec-WebSocket-Key", "forged"));
		transport.setExceptionHandler(error -> {
		});
		try {
			assertThatThrownBy(() -> transport.connect(msg -> Mono.empty()).block(Duration.ofSeconds(10)))
				.isInstanceOf(IllegalArgumentException.class);
		}
		finally {
			transport.closeGracefully().block(Duration.ofSeconds(10));
		}
	}

	@Test
	void webSocketCustomizerRejectsNull() {
		WebSocketAcpClientTransport transport = new WebSocketAcpClientTransport(URI.create("ws://127.0.0.1:1/acp"),
				jsonMapper);

		assertThatThrownBy(() -> transport.webSocketCustomizer(null)).isInstanceOf(IllegalArgumentException.class);
	}

}
