/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.client;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.micronaut.TransportType;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every {@code acp.client.*} setting binds; the transport is chosen by the documented rule;
 * an application without client settings gets no client.
 */
class AcpClientBeansTest {

	@Test
	void defaultsAndNoClientWithoutTransportSettings() {
		try (ApplicationContext context = ApplicationContext.run()) {
			AcpClientConfiguration config = context.getBean(AcpClientConfiguration.class);
			assertThat(config.getRequestTimeout()).isEqualTo(Duration.ofSeconds(30));
			assertThat(config.getTransport().getType()).isNull();
			assertThat(config.getTransport().getStdio().getCommand()).isNull();
			assertThat(config.getTransport().getStdio().getArgs()).isEmpty();
			assertThat(config.getTransport().getStdio().getEnv()).isEmpty();
			assertThat(config.getTransport().getWebsocket().getUri()).isNull();
			assertThat(config.getTransport().getWebsocket().getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
			assertThat(config.getTransport().getHttp().getUri()).isNull();
			assertThat(config.getCapabilities().isReadTextFile()).isFalse();
			assertThat(config.getCapabilities().isWriteTextFile()).isFalse();
			assertThat(config.getCapabilities().isTerminal()).isFalse();

			assertThat(context.findBean(AcpClientTransport.class)).isEmpty();
			assertThat(context.findBean(AcpAsyncClient.class)).isEmpty();
			assertThat(context.findBean(AcpSyncClient.class)).isEmpty();
		}
	}

	@Test
	void everySettingBinds() {
		Map<String, Object> properties = Map.ofEntries(Map.entry("acp.client.request-timeout", "45s"),
				Map.entry("acp.client.transport.type", "stdio"),
				Map.entry("acp.client.transport.stdio.command", "my-agent"),
				Map.entry("acp.client.transport.stdio.args", List.of("--acp", "--verbose")),
				Map.entry("acp.client.transport.stdio.env.AGENT_MODE", "test"),
				Map.entry("acp.client.transport.websocket.uri", "ws://localhost:9/acp"),
				Map.entry("acp.client.transport.websocket.connect-timeout", "3s"),
				Map.entry("acp.client.transport.http.uri", "http://localhost:9/acp"),
				Map.entry("acp.client.capabilities.read-text-file", "true"),
				Map.entry("acp.client.capabilities.write-text-file", "true"),
				Map.entry("acp.client.capabilities.terminal", "true"));
		try (ApplicationContext context = ApplicationContext.run(properties)) {
			AcpClientConfiguration config = context.getBean(AcpClientConfiguration.class);
			assertThat(config.getRequestTimeout()).isEqualTo(Duration.ofSeconds(45));
			assertThat(config.getTransport().getType()).isEqualTo(TransportType.STDIO);
			assertThat(config.getTransport().getStdio().getCommand()).isEqualTo("my-agent");
			assertThat(config.getTransport().getStdio().getArgs()).containsExactly("--acp", "--verbose");
			assertThat(config.getTransport().getStdio().getEnv()).containsEntry("AGENT_MODE", "test");
			assertThat(config.getTransport().getWebsocket().getUri()).isEqualTo(URI.create("ws://localhost:9/acp"));
			assertThat(config.getTransport().getWebsocket().getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
			assertThat(config.getTransport().getHttp().getUri()).isEqualTo(URI.create("http://localhost:9/acp"));
			assertThat(config.getCapabilities().isReadTextFile()).isTrue();
			assertThat(config.getCapabilities().isWriteTextFile()).isTrue();
			assertThat(config.getCapabilities().isTerminal()).isTrue();

			// the explicit type wins over the URIs also set
			assertThat(context.getBean(AcpClientTransport.class)).isInstanceOf(StdioAcpClientTransport.class);
		}
	}

	@Test
	void theTransportIsInferredFromTheOneSetting() {
		assertThat(transport(Map.of("acp.client.transport.stdio.command", "my-agent")))
			.isInstanceOf(StdioAcpClientTransport.class);
		assertThat(transport(Map.of("acp.client.transport.websocket.uri", "ws://localhost:9/acp")))
			.isInstanceOf(WebSocketAcpClientTransport.class);
		assertThat(transport(Map.of("acp.client.transport.http.uri", "http://localhost:9/acp")))
			.isInstanceOf(StreamableHttpAcpClientTransport.class);
		assertThat(transport(Map.of("acp.client.transport.type", "HTTP", "acp.client.transport.http.uri",
				"http://localhost:9/acp", "acp.client.transport.websocket.uri", "ws://localhost:9/acp")))
			.isInstanceOf(StreamableHttpAcpClientTransport.class);
	}

	@Test
	void ambiguousOrIncompleteTransportSettingsAreErrors() {
		assertThatThrownBy(() -> transport(Map.of("acp.client.transport.http.uri", "http://localhost:9/acp",
				"acp.client.transport.websocket.uri", "ws://localhost:9/acp")))
			.rootCause()
			.hasMessageContaining("Several ACP client transports are configured [WEBSOCKET, HTTP]")
			.hasMessageContaining("acp.client.transport.type");
		assertThatThrownBy(() -> transport(Map.of("acp.client.transport.websocket.connect-timeout", "1s")))
			.rootCause()
			.hasMessageContaining("An ACP client needs a transport");
		assertThatThrownBy(() -> transport(Map.of("acp.client.transport.type", "http"))).rootCause()
			.hasMessageContaining("acp.client.transport.type=http needs acp.client.transport.http.uri");
		assertThatThrownBy(() -> transport(Map.of("acp.client.transport.type", "websocket"))).rootCause()
			.hasMessageContaining("acp.client.transport.type=websocket needs acp.client.transport.websocket.uri");
		assertThatThrownBy(() -> transport(Map.of("acp.client.transport.type", "stdio"))).rootCause()
			.hasMessageContaining("acp.client.transport.type=stdio needs acp.client.transport.stdio.command");
	}

	@Test
	void oneAsyncClientWithASyncFacadeOverIt() {
		try (ApplicationContext context = ApplicationContext
			.run(Map.of("acp.client.transport.http.uri", "http://localhost:9/acp"))) {
			AcpAsyncClient async = context.getBean(AcpAsyncClient.class);
			assertThat(context.getBean(AcpAsyncClient.class)).isSameAs(async);
			assertThat(context.getBean(AcpSyncClient.class)).isSameAs(context.getBean(AcpSyncClient.class));
		}
	}

	private static AcpClientTransport transport(Map<String, Object> properties) {
		try (ApplicationContext context = ApplicationContext.run(properties)) {
			return context.getBean(AcpClientTransport.class);
		}
	}

}
