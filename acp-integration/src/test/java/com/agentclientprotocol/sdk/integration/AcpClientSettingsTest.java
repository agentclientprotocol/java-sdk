/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcpClientSettingsTest {

	@Test
	void defaults() {
		AcpClientSettings settings = AcpClientSettings.builder().build();
		assertThat(settings.requestTimeout()).isNull();
		assertThat(settings.promptTimeout()).isNull();
		assertThat(settings.transport()).isNull();
		assertThat(settings.stdio().command()).isNull();
		assertThat(settings.stdio().args()).isEmpty();
		assertThat(settings.stdio().env()).isEmpty();
		assertThat(settings.websocket().uri()).isNull();
		assertThat(settings.websocket().connectTimeout()).isEqualTo(Duration.ofSeconds(10));
		assertThat(settings.http().uri()).isNull();
		assertThat(settings.capabilities()).isEqualTo(AcpClientSettings.Capabilities.NONE);
		assertThat(settings.hasTransport()).isFalse();
		assertThat(settings.closeTimeout()).isEqualTo(Duration.ofSeconds(40));
		assertThat(AcpClientSettings.from(MapSettingsSource.of(), "acp.client")).isEqualTo(settings);
	}

	@Test
	void everyKeyBinds() {
		AcpClientSettings settings = AcpClientSettings.from(MapSettingsSource.of("acp.client.request-timeout", "45s",
				"acp.client.prompt-timeout", "10m", "acp.client.transport.type", "stdio",
				"acp.client.transport.stdio.command", "my-agent", "acp.client.transport.stdio.args", "--acp, --verbose",
				"acp.client.transport.stdio.env.AGENT_MODE", "test", "acp.client.transport.websocket.uri",
				"ws://localhost:9/acp", "acp.client.transport.websocket.connect-timeout", "3s",
				"acp.client.transport.http.uri", "http://localhost:9/acp", "acp.client.capabilities.read-text-file",
				"true", "acp.client.capabilities.write-text-file", "true", "acp.client.capabilities.terminal", "true",
				"acp.client.capabilities.elicitation-form", "true", "acp.client.capabilities.elicitation-url", "true",
				"acp.client.capabilities.boolean-config-options", "true"), "acp.client");
		assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(45));
		assertThat(settings.promptTimeout()).isEqualTo(Duration.ofMinutes(10));
		assertThat(settings.transport()).isEqualTo(AcpTransportType.STDIO);
		assertThat(settings.stdio().command()).isEqualTo("my-agent");
		assertThat(settings.stdio().args()).containsExactly("--acp", "--verbose");
		assertThat(settings.stdio().env()).isEqualTo(Map.of("AGENT_MODE", "test"));
		assertThat(settings.websocket().uri()).isEqualTo(URI.create("ws://localhost:9/acp"));
		assertThat(settings.websocket().connectTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(settings.http().uri()).isEqualTo(URI.create("http://localhost:9/acp"));
		assertThat(settings.capabilities())
			.isEqualTo(new AcpClientSettings.Capabilities(true, true, true, true, true, true));
		assertThat(settings.hasTransport()).isTrue();
		assertThat(settings.closeTimeout()).isEqualTo(Duration.ofSeconds(55));
	}

	@Test
	void anyTransportSettingCounts() {
		assertThat(AcpClientSettings.builder().transport(AcpTransportType.HTTP).build().hasTransport()).isTrue();
		assertThat(AcpClientSettings.builder().stdioCommand("agent").build().hasTransport()).isTrue();
		assertThat(AcpClientSettings.builder().websocketUri(URI.create("ws://h/acp")).build().hasTransport()).isTrue();
		assertThat(AcpClientSettings.builder().httpUri(URI.create("http://h/acp")).build().hasTransport()).isTrue();
	}

	@Test
	void aUriThatDoesNotParseNamesItsKey() {
		assertThatThrownBy(() -> AcpClientSettings.from(MapSettingsSource.of("c.transport.http.uri", "http://a b"), "c"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("c.transport.http.uri=http://a b is not a URI");
	}

	@Test
	void capabilitiesAsTheProtocolWritesThem() {
		AcpSchema.ClientCapabilities none = AcpClientSettings.Capabilities.NONE.toClientCapabilities();
		assertThat(none.fs().readTextFile()).isFalse();
		assertThat(none.fs().writeTextFile()).isFalse();
		assertThat(none.terminal()).isFalse();
		assertThat(none.elicitation()).isNull();
		assertThat(none.session()).isNull();

		AcpSchema.ClientCapabilities all = new AcpClientSettings.Capabilities(true, true, true, true, false, true)
			.toClientCapabilities();
		assertThat(all.fs().readTextFile()).isTrue();
		assertThat(all.fs().writeTextFile()).isTrue();
		assertThat(all.terminal()).isTrue();
		assertThat(all.elicitation().form()).isNotNull();
		assertThat(all.elicitation().url()).isNull();
		assertThat(all.session()).isEqualTo(AcpSchema.ClientSessionCapabilities.withBooleanConfigOptions());

		AcpSchema.ClientCapabilities url = new AcpClientSettings.Capabilities(false, false, false, false, true, false)
			.toClientCapabilities();
		assertThat(url.elicitation().form()).isNull();
		assertThat(url.elicitation().url()).isNotNull();
	}

	@Test
	void stdioListsAreCopied() {
		List<String> args = new java.util.ArrayList<>(List.of("a"));
		AcpClientSettings settings = AcpClientSettings.builder().stdioArgs(args).build();
		args.add("b");
		assertThat(settings.stdio().args()).containsExactly("a");
	}

	@Test
	void partsAreRequired() {
		AcpClientSettings.Builder builder = AcpClientSettings.builder();
		assertThatThrownBy(() -> builder.websocketConnectTimeout(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> builder.capabilities(null)).isInstanceOf(NullPointerException.class);
	}

}
