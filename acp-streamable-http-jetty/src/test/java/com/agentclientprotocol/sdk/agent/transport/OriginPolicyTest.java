/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.test.http.HttpProbes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code Origin} rule every host applies, and the allowed-origins option that widens it.
 */
class OriginPolicyTest {

	private static final StreamableHttpAcpAgentTransportOptions DEFAULTS = StreamableHttpAcpAgentTransportOptions
		.defaults();

	@ParameterizedTest
	@ValueSource(strings = { "http://localhost", "http://localhost:3000", "https://localhost:8443", "HTTP://LOCALHOST",
			"http://127.0.0.1", "http://127.0.0.1:1", "http://[::1]", "https://[::1]:8443", "http://localhost/" })
	void loopbackOriginsAreAllowed(String origin) {
		assertThat(DEFAULTS.isOriginAllowed(origin)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://evil.example", "null", "file://", "http://localhost.evil.example",
			"http://127.0.0.1.evil.example", "ws://localhost", "http://user@localhost", "http://localhost/path",
			"http://127.0.0.2", "not a uri", "" })
	void otherOriginsAreRefused(String origin) {
		assertThat(DEFAULTS.isOriginAllowed(origin)).isFalse();
	}

	@Test
	void noOriginIsAllowed() {
		assertThat(DEFAULTS.isOriginAllowed(null)).isTrue();
	}

	@Test
	void listedOriginsAreAllowedIgnoringCaseAndATrailingSlash() {
		StreamableHttpAcpAgentTransportOptions options = StreamableHttpAcpAgentTransportOptions.builder()
			.allowedOrigins(List.of("https://App.Example.com/"))
			.build();
		assertThat(options.allowedOrigins()).containsExactly("https://app.example.com");
		assertThat(options.isOriginAllowed("https://app.example.com")).isTrue();
		assertThat(options.isOriginAllowed("HTTPS://APP.EXAMPLE.COM")).isTrue();
		assertThat(options.isOriginAllowed("https://app.example.com:8443")).isFalse();
		assertThat(options.isOriginAllowed("http://evil.example")).isFalse();
	}

	@Test
	void aWildcardAllowsAnyOrigin() {
		StreamableHttpAcpAgentTransportOptions options = StreamableHttpAcpAgentTransportOptions.builder()
			.allowedOrigins(Set.of("*"))
			.build();
		assertThat(options.isOriginAllowed("http://evil.example")).isTrue();
		assertThat(options.isOriginAllowed("null")).isTrue();
	}

	@Test
	void theListenerServesAListedOriginOverHttpAndWebSocket() throws Exception {
		StreamableHttpAcpAgentTransport listener = new StreamableHttpAcpAgentTransport(0, "/acp",
				AcpJsonMapper.createDefault(), ListenerBindTest.AGENTS,
				StreamableHttpAcpAgentTransportOptions.builder().allowedOrigins(List.of("https://app.example.com")).build());
		listener.start().block(Duration.ofSeconds(10));
		try {
			URI endpoint = URI.create("http://127.0.0.1:" + listener.getPort() + "/acp");
			assertThat(HttpProbes.initialize(endpoint, "https://app.example.com").statusCode()).isEqualTo(200);
			assertThat(HttpProbes.webSocketHandshake(endpoint, "https://app.example.com"))
				.isEqualTo(HttpProbes.SWITCHING_PROTOCOLS);
			assertThat(HttpProbes.initialize(endpoint, "https://other.example.com").statusCode()).isEqualTo(403);
			assertThat(HttpProbes.webSocketHandshake(endpoint, "https://other.example.com")).isEqualTo(403);
		}
		finally {
			listener.closeGracefully().block(Duration.ofSeconds(10));
		}
	}

}
