/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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

}
