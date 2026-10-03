/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.net.URI;
import java.time.Duration;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
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

}
