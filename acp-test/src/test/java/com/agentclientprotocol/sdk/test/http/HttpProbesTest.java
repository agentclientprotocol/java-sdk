/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.test.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The probes send what they say and report the status the server answers, against the JDK's own
 * HTTP server, which refuses every WebSocket upgrade.
 */
class HttpProbesTest {

	private final List<String> origins = new CopyOnWriteArrayList<>();

	private HttpServer server;

	private URI endpoint;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/acp", exchange -> {
			String origin = exchange.getRequestHeaders().getFirst("Origin");
			origins.add(String.valueOf(origin));
			byte[] body = exchange.getRequestBody().readAllBytes();
			int status = "POST".equals(exchange.getRequestMethod())
					&& new String(body, StandardCharsets.UTF_8).equals(HttpProbes.INITIALIZE) ? 200 : 403;
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
		});
		server.start();
		endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/acp");
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void initializePostsTheRequestWithTheOrigin() throws Exception {
		assertThat(HttpProbes.initialize(endpoint, "http://localhost").statusCode()).isEqualTo(200);
		assertThat(HttpProbes.initialize(endpoint, null).statusCode()).isEqualTo(200);
		assertThat(origins).containsExactly("http://localhost", "null");
	}

	@Test
	void getReportsTheStatus() throws Exception {
		assertThat(HttpProbes.get(endpoint, "http://evil.example")).isEqualTo(403);
		assertThat(HttpProbes.get(endpoint, null)).isEqualTo(403);
	}

	@Test
	void aRefusedHandshakeReportsItsStatus() throws Exception {
		assertThat(HttpProbes.webSocketHandshake(endpoint, "http://evil.example")).isEqualTo(403);
		assertThat(HttpProbes.webSocketHandshake(endpoint, null)).isEqualTo(403);
	}

	@Test
	void aFailedConnectionIsAnIoException() {
		URI nowhere = URI.create("http://127.0.0.1:1/acp");
		assertThatThrownBy(() -> HttpProbes.get(nowhere, null)).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> HttpProbes.webSocketHandshake(nowhere, null)).isInstanceOf(IOException.class);
	}

}
