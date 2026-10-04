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
import com.agentclientprotocol.sdk.integration.AcpClientSettings;
import com.agentclientprotocol.sdk.integration.AcpTransportType;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code quarkus.acp.client.*} onto the SDK's settings, and the SDK's transport rule over them. */
class AcpSettingsTest {

	private static final URI WS = URI.create("ws://localhost:9/acp");

	private static final URI HTTP = URI.create("http://localhost:9/acp");

	@Test
	void everyClientSettingIsBound() {
		AcpClientSettings settings = AcpSettings.client(client(AcpTransportType.STDIO, "agent", WS, HTTP));
		assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(4));
		assertThat(settings.promptTimeout()).isEqualTo(Duration.ofMinutes(5));
		assertThat(settings.transport()).isEqualTo(AcpTransportType.STDIO);
		assertThat(settings.stdio().command()).isEqualTo("agent");
		assertThat(settings.stdio().args()).containsExactly("--acp");
		assertThat(settings.stdio().env()).isEqualTo(Map.of("MODE", "test"));
		assertThat(settings.websocket().uri()).isEqualTo(WS);
		assertThat(settings.websocket().connectTimeout()).isEqualTo(Duration.ofSeconds(1));
		assertThat(settings.http().uri()).isEqualTo(HTTP);
	}

	@Test
	void anExplicitTypeWins() {
		assertThat(producers(client(AcpTransportType.HTTP, "agent", WS, HTTP)).acpClientTransport())
			.isInstanceOf(StreamableHttpAcpClientTransport.class);
	}

	@Test
	void theOneConfiguredIsInferred() {
		assertThat(producers(client(null, null, WS, null)).acpClientTransport())
			.isInstanceOf(WebSocketAcpClientTransport.class);
		assertThat(producers(client(null, null, null, HTTP)).acpClientTransport())
			.isInstanceOf(StreamableHttpAcpClientTransport.class);
		assertThat(producers(client(null, "agent", null, null)).acpClientTransport())
			.isInstanceOf(StdioAcpClientTransport.class);
	}

	@Test
	void severalWithoutATypeFailNamingThem() {
		assertThatThrownBy(() -> producers(client(null, "agent", WS, HTTP)).acpClientTransport())
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Several ACP client transports are configured [STDIO, WEBSOCKET, HTTP]; "
					+ "choose one with quarkus.acp.client.transport.type");
	}

	@Test
	void nothingConfiguredNamesTheProperties() {
		assertThatThrownBy(() -> producers(client(null, null, null, null)).acpClientTransport())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("quarkus.acp.client.transport.stdio.command")
			.hasMessageContaining("quarkus.acp.client.transport.websocket.uri")
			.hasMessageContaining("quarkus.acp.client.transport.http.uri");
	}

	@Test
	void anExplicitTypeWithoutItsSettingNamesIt() {
		assertThatThrownBy(() -> producers(client(AcpTransportType.WEBSOCKET, null, null, HTTP)).acpClientTransport())
			.hasMessage("quarkus.acp.client.transport.type=websocket requires quarkus.acp.client.transport.websocket.uri");
	}

	private static AcpClientProducers producers(AcpRuntimeConfig.Client client) {
		AcpRuntimeConfig config = mock(AcpRuntimeConfig.class);
		when(config.client()).thenReturn(client);
		return new AcpClientProducers(config, new AcpExecutors(java.util.concurrent.ForkJoinPool.commonPool()));
	}

	private static AcpRuntimeConfig.Client client(AcpTransportType type, String command, URI ws, URI http) {
		AcpRuntimeConfig.Client client = mock(AcpRuntimeConfig.Client.class, Answers.RETURNS_DEEP_STUBS);
		when(client.requestTimeout()).thenReturn(Optional.of(Duration.ofSeconds(4)));
		when(client.promptTimeout()).thenReturn(Optional.of(Duration.ofMinutes(5)));
		when(client.transport()).thenReturn(new Transport(Optional.ofNullable(type),
				new Stdio(Optional.ofNullable(command), Optional.of(List.of("--acp")), Map.of("MODE", "test")),
				new WebSocket(Optional.ofNullable(ws)), new Http(Optional.ofNullable(http))));
		return client;
	}

	record Transport(Optional<AcpTransportType> type, AcpRuntimeConfig.Stdio stdio,
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
