/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client transport rule, the same in every framework. */
class AcpClientTransportsTest {

	private static final URI WS = URI.create("ws://localhost:9/acp");

	private static final URI HTTP = URI.create("http://localhost:9/acp");

	@Test
	void noneConfiguredIsNoTransport() {
		assertThat(AcpClientTransports.create(AcpClientSettings.builder().build())).isEmpty();
	}

	@Test
	void theOneConfiguredIsInferred() {
		assertThat(AcpClientTransports.create(settings(null, "agent", null, null)))
			.containsInstanceOf(StdioAcpClientTransport.class);
		assertThat(AcpClientTransports.create(settings(null, null, WS, null)))
			.containsInstanceOf(WebSocketAcpClientTransport.class);
		assertThat(AcpClientTransports.create(settings(null, null, null, HTTP)))
			.containsInstanceOf(StreamableHttpAcpClientTransport.class);
	}

	@Test
	void anExplicitTypeWins() {
		assertThat(AcpClientTransports.create(settings(AcpTransportType.HTTP, "agent", WS, HTTP)))
			.containsInstanceOf(StreamableHttpAcpClientTransport.class);
		assertThat(AcpClientTransports.create(settings(AcpTransportType.STDIO, "agent", WS, HTTP)))
			.containsInstanceOf(StdioAcpClientTransport.class);
		assertThat(AcpClientTransports.create(settings(AcpTransportType.WEBSOCKET, "agent", WS, HTTP)))
			.containsInstanceOf(WebSocketAcpClientTransport.class);
	}

	@Test
	void severalWithoutATypeFailNamingThem() {
		assertThatThrownBy(() -> AcpClientTransports.create(settings(null, "agent", WS, HTTP), "spring.acp.client"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Several ACP client transports are configured [STDIO, WEBSOCKET, HTTP]; "
					+ "choose one with spring.acp.client.transport.type");
		assertThatThrownBy(() -> AcpClientTransports.create(settings(null, null, WS, HTTP)))
			.hasMessageContaining("[WEBSOCKET, HTTP]")
			.hasMessageContaining("acp.client.transport.type");
	}

	@Test
	void anExplicitTypeWithoutItsSettingNamesIt() {
		assertThatThrownBy(() -> AcpClientTransports.create(settings(AcpTransportType.WEBSOCKET, null, null, HTTP), "q"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("q.transport.type=websocket requires q.transport.websocket.uri");
		assertThatThrownBy(() -> AcpClientTransports.create(settings(AcpTransportType.HTTP, null, WS, null), "q"))
			.hasMessage("q.transport.type=http requires q.transport.http.uri");
		assertThatThrownBy(() -> AcpClientTransports.create(settings(AcpTransportType.STDIO, null, WS, null), "q"))
			.hasMessage("q.transport.type=stdio requires q.transport.stdio.command");
	}

	@Test
	void stdioWithoutEnvironment() {
		AcpClientSettings settings = AcpClientSettings.builder().stdioCommand("agent").build();
		assertThat(AcpClientTransports.create(settings)).containsInstanceOf(StdioAcpClientTransport.class);
	}

	private static AcpClientSettings settings(AcpTransportType type, String command, URI ws, URI http) {
		return AcpClientSettings.builder()
			.transport(type)
			.stdioCommand(command)
			.stdioArgs(List.of("--acp"))
			.stdioEnv(Map.of("MODE", "test"))
			.websocketUri(ws)
			.httpUri(http)
			.build();
	}

}
