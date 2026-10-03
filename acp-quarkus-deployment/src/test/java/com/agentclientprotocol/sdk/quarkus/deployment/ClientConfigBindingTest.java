/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import com.agentclientprotocol.sdk.quarkus.ClientTransportType;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpStdioAgentHost;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code quarkus.acp.client.*} setting binds; an explicit transport type wins over
 * the URIs that would otherwise select one. With no {@code @AcpAgent} the application is a
 * client only: no agent is served.
 */
class ClientConfigBindingTest {

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest().withEmptyApplication()
		.overrideConfigKey("quarkus.acp.client.request-timeout", "9s")
		.overrideConfigKey("quarkus.acp.client.transport.type", "stdio")
		.overrideConfigKey("quarkus.acp.client.transport.stdio.command", "my-agent")
		.overrideConfigKey("quarkus.acp.client.transport.stdio.args", "--acp,--verbose")
		.overrideConfigKey("quarkus.acp.client.transport.stdio.env.AGENT_MODE", "test")
		.overrideConfigKey("quarkus.acp.client.transport.websocket.uri", "ws://localhost:9/acp")
		.overrideConfigKey("quarkus.acp.client.transport.websocket.connect-timeout", "3s")
		.overrideConfigKey("quarkus.acp.client.transport.http.uri", "http://localhost:9/acp")
		.overrideConfigKey("quarkus.acp.client.capabilities.read-text-file", "true")
		.overrideConfigKey("quarkus.acp.client.capabilities.write-text-file", "true")
		.overrideConfigKey("quarkus.acp.client.capabilities.terminal", "true");

	@Inject
	AcpRuntimeConfig config;

	@Inject
	AcpClientTransport transport;

	@Test
	void everyClientSettingBinds() {
		AcpRuntimeConfig.Client client = config.client();
		assertThat(client.requestTimeout()).isEqualTo(Duration.ofSeconds(9));
		assertThat(client.transport().type()).contains(ClientTransportType.STDIO);
		assertThat(client.transport().stdio().command()).contains("my-agent");
		assertThat(client.transport().stdio().args()).contains(List.of("--acp", "--verbose"));
		assertThat(client.transport().stdio().env()).isEqualTo(Map.of("AGENT_MODE", "test"));
		assertThat(client.transport().websocket().uri()).contains(URI.create("ws://localhost:9/acp"));
		assertThat(client.transport().websocket().connectTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(client.transport().http().uri()).contains(URI.create("http://localhost:9/acp"));
		assertThat(client.capabilities().readTextFile()).isTrue();
		assertThat(client.capabilities().writeTextFile()).isTrue();
		assertThat(client.capabilities().terminal()).isTrue();
	}

	@Test
	void explicitTypeWins() {
		assertThat(transport).isInstanceOf(StdioAcpClientTransport.class);
	}

	@Test
	void clientOnlyApplicationServesNoAgent() {
		assertThat(Arc.container().instance(AcpStdioAgentHost.class).isAvailable()).isFalse();
	}

}
