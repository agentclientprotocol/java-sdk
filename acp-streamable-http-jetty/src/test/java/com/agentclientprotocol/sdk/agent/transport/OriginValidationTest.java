/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.net.URI;
import java.time.Duration;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.http.HttpProbes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A browser page of another origin cannot use a local agent: the listener (its servlet for
 * HTTP, its upgrade handler for WebSocket) answers a foreign {@code Origin} with 403. Before,
 * any origin was served, so a web page the user visited could drive the agent on localhost.
 */
class OriginValidationTest {

	private static final String FOREIGN = "http://evil.example";

	private static StreamableHttpAcpAgentTransport listener;

	private static URI endpoint;

	@BeforeAll
	static void start() {
		listener = new StreamableHttpAcpAgentTransport(0, AcpAgentFactory.sync(transport -> AcpAgent.sync(transport)
			.initializeHandler(request -> AcpSchema.InitializeResponse.ok())
			.promptHandler((request, context) -> AcpSchema.PromptResponse.endTurn())
			.build()));
		listener.start().block(Duration.ofSeconds(10));
		endpoint = URI.create("http://127.0.0.1:" + listener.getPort() + "/acp");
	}

	@AfterAll
	static void stop() {
		listener.closeGracefully().block(Duration.ofSeconds(10));
	}

	@Test
	void aForeignOriginIsRefusedOverHttp() throws Exception {
		assertThat(HttpProbes.initialize(endpoint, FOREIGN).statusCode()).isEqualTo(403);
		assertThat(HttpProbes.get(endpoint, FOREIGN)).isEqualTo(403);
	}

	@Test
	void aForeignOriginIsRefusedOnTheWebSocketHandshake() throws Exception {
		assertThat(HttpProbes.webSocketHandshake(endpoint, FOREIGN)).isEqualTo(403);
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://localhost:3000", "https://localhost", "http://127.0.0.1:8080", "http://[::1]:9" })
	void aLoopbackOriginIsServed(String origin) throws Exception {
		assertThat(HttpProbes.initialize(endpoint, origin).statusCode()).isEqualTo(200);
		assertThat(HttpProbes.webSocketHandshake(endpoint, origin)).isEqualTo(HttpProbes.SWITCHING_PROTOCOLS);
	}

	@Test
	void noOriginIsServed() throws Exception {
		assertThat(HttpProbes.initialize(endpoint, null).statusCode()).isEqualTo(200);
		assertThat(HttpProbes.webSocketHandshake(endpoint, null)).isEqualTo(HttpProbes.SWITCHING_PROTOCOLS);
	}

}
