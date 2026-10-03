/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import com.agentclientprotocol.sdk.quarkus.ClientTransportType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcpClientTransportsTest {

	private static final URI WS = URI.create("ws://localhost:9/acp");

	private static final URI HTTP = URI.create("http://localhost:9/acp");

	@Test
	void explicitTypeWins() {
		assertThat(AcpClientTransports.type(transport(ClientTransportType.HTTP, "agent", WS, HTTP)))
			.isEqualTo(ClientTransportType.HTTP);
	}

	@Test
	void inferredInOrderWebSocketThenHttpThenStdio() {
		assertThat(AcpClientTransports.type(transport(null, "agent", WS, HTTP))).isEqualTo(ClientTransportType.WEBSOCKET);
		assertThat(AcpClientTransports.type(transport(null, "agent", null, HTTP))).isEqualTo(ClientTransportType.HTTP);
		assertThat(AcpClientTransports.type(transport(null, "agent", null, null))).isEqualTo(ClientTransportType.STDIO);
	}

	@Test
	void nothingConfiguredNamesTheProperties() {
		assertThatThrownBy(() -> AcpClientTransports.type(transport(null, null, null, null)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("quarkus.acp.client.transport.stdio.command")
			.hasMessageContaining("quarkus.acp.client.transport.websocket.uri")
			.hasMessageContaining("quarkus.acp.client.transport.http.uri");
	}

	@Test
	void createsEachTransport() {
		assertThat(AcpClientTransports.create(transport(null, null, WS, null)))
			.isInstanceOf(WebSocketAcpClientTransport.class);
		assertThat(AcpClientTransports.create(transport(null, null, null, HTTP)))
			.isInstanceOf(StreamableHttpAcpClientTransport.class);
		assertThat(AcpClientTransports.create(transport(null, "agent", null, null)))
			.isInstanceOf(StdioAcpClientTransport.class);
		assertThat(AcpClientTransports.create(new Transport(Optional.empty(),
				new Stdio(Optional.of("agent"), Optional.empty(), Map.of()), new WebSocket(Optional.empty()),
				new Http(Optional.empty()))))
			.isInstanceOf(StdioAcpClientTransport.class);
	}

	@Test
	void explicitTypeWithoutItsSettingNamesIt() {
		assertThatThrownBy(() -> AcpClientTransports.create(transport(ClientTransportType.WEBSOCKET, null, null, HTTP)))
			.hasMessage("quarkus.acp.client.transport.type=websocket requires quarkus.acp.client.transport.websocket.uri");
		assertThatThrownBy(() -> AcpClientTransports.create(transport(ClientTransportType.HTTP, null, WS, null)))
			.hasMessage("quarkus.acp.client.transport.type=http requires quarkus.acp.client.transport.http.uri");
		assertThatThrownBy(() -> AcpClientTransports.create(transport(ClientTransportType.STDIO, null, WS, null)))
			.hasMessage("quarkus.acp.client.transport.type=stdio requires quarkus.acp.client.transport.stdio.command");
	}

	static Transport transport(ClientTransportType type, String command, URI ws, URI http) {
		return new Transport(Optional.ofNullable(type),
				new Stdio(Optional.ofNullable(command), Optional.of(List.of("--acp")), Map.of("MODE", "test")),
				new WebSocket(Optional.ofNullable(ws)), new Http(Optional.ofNullable(http)));
	}

	record Transport(Optional<ClientTransportType> type, AcpRuntimeConfig.Stdio stdio,
			AcpRuntimeConfig.WebSocket websocket, AcpRuntimeConfig.Http http) implements AcpRuntimeConfig.ClientTransport {
	}

	record Stdio(Optional<String> command, Optional<List<String>> args,
			Map<String, String> env) implements AcpRuntimeConfig.Stdio {
	}

	record WebSocket(Optional<URI> uri) implements AcpRuntimeConfig.WebSocket {

		@Override
		public Duration connectTimeout() {
			return Duration.ofSeconds(1);
		}

	}

	record Http(Optional<URI> uri) implements AcpRuntimeConfig.Http {
	}

}
