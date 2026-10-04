/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcpAgentSettingsTest {

	@Test
	void defaultsLeaveEveryLimitToTheSdk() {
		AcpAgentSettings settings = AcpAgentSettings.builder().build();
		assertThat(settings.enabled()).isTrue();
		assertThat(settings.requestTimeout()).isNull();
		assertThat(settings.cancelGracePeriod()).isNull();
		assertThat(settings.maxPromptDuration()).isNull();
		assertThat(settings.shutdownOnTransportEnd()).isTrue();
		assertThat(settings.transport()).isEqualTo(AcpTransportType.STDIO);
		assertThat(settings.servesHttp()).isFalse();
		assertThat(settings.http().path()).isEqualTo("/acp");
		assertThat(settings.http().listener().port()).isEqualTo(8080);
		assertThat(settings.toOptions(true)).isEqualTo(StreamableHttpAcpAgentTransportOptions.defaults());
		assertThat(settings.toOptions(false)).isEqualTo(StreamableHttpAcpAgentTransportOptions.defaults());
		assertThat(AcpAgentSettings.from(MapSettingsSource.of(), "acp.agent")).isEqualTo(settings);
	}

	@Test
	void everyKeyBinds() {
		AcpAgentSettings settings = AcpAgentSettings.from(MapSettingsSource.of("acp.agent.enabled", "FALSE",
				"acp.agent.request-timeout", "7s", "acp.agent.cancel-grace-period", "PT3S",
				"acp.agent.max-prompt-duration", "2m", "acp.agent.shutdown-on-transport-end", "false",
				"acp.agent.transport.type", "WebSocket", "acp.agent.transport.http.path", "/agents/acp",
				"acp.agent.transport.http.max-post-body-size", "2MB", "acp.agent.transport.http.keep-alive-interval",
				"20000", "acp.agent.transport.http.mailbox-capacity", "11",
				"acp.agent.transport.http.max-pending-sse-events", "12",
				"acp.agent.transport.http.max-web-socket-pending-frames", "13",
				"acp.agent.transport.http.max-provisional-sessions", "14", "acp.agent.transport.http.shutdown-timeout",
				"1h", "acp.agent.transport.http.listener.port", "0",
				"acp.agent.transport.http.listener.max-concurrent-streams-per-connection", "15"), "acp.agent.");
		assertThat(settings.enabled()).isFalse();
		assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(7));
		assertThat(settings.cancelGracePeriod()).isEqualTo(Duration.ofSeconds(3));
		assertThat(settings.maxPromptDuration()).isEqualTo(Duration.ofMinutes(2));
		assertThat(settings.shutdownOnTransportEnd()).isFalse();
		assertThat(settings.transport()).isEqualTo(AcpTransportType.WEBSOCKET);
		assertThat(settings.servesHttp()).isTrue();
		assertThat(settings.http().path()).isEqualTo("/agents/acp");
		assertThat(settings.http().listener().port()).isZero();

		StreamableHttpAcpAgentTransportOptions listener = settings.toOptions(true);
		assertThat(listener.maxPostBodyBytes()).isEqualTo(2L * 1024 * 1024);
		assertThat(listener.keepAliveInterval()).isEqualTo(Duration.ofSeconds(20));
		assertThat(listener.mailboxCapacity()).isEqualTo(11);
		assertThat(listener.maxPendingSseEvents()).isEqualTo(12);
		assertThat(listener.maxWebSocketPendingFrames()).isEqualTo(13);
		assertThat(listener.maxProvisionalSessions()).isEqualTo(14);
		assertThat(listener.shutdownTimeout()).isEqualTo(Duration.ofHours(1));
		assertThat(listener.maxConcurrentStreamsPerConnection()).isEqualTo(15);
		// Inside a framework's server the listener's own stream limit means nothing.
		assertThat(settings.toOptions(false).maxConcurrentStreamsPerConnection())
			.isEqualTo(StreamableHttpAcpAgentTransportOptions.defaults().maxConcurrentStreamsPerConnection());
	}

	@Test
	void unitsOfDurationsAndSizes() {
		assertThat(agent("acp.request-timeout", "250ms").requestTimeout()).isEqualTo(Duration.ofMillis(250));
		assertThat(agent("acp.request-timeout", "1d").requestTimeout()).isEqualTo(Duration.ofDays(1));
		assertThat(agent("acp.request-timeout", " ").requestTimeout()).isNull();
		assertThat(bytes("512")).isEqualTo(512);
		assertThat(bytes("512B")).isEqualTo(512);
		assertThat(bytes("4k")).isEqualTo(4096);
		assertThat(bytes("4KB")).isEqualTo(4096);
		assertThat(bytes("3M")).isEqualTo(3L * 1024 * 1024);
		assertThat(bytes("1GB")).isEqualTo(1024L * 1024 * 1024);
		assertThat(bytes("1g")).isEqualTo(1024L * 1024 * 1024);
	}

	@Test
	void aValueThatDoesNotParseNamesItsKey() {
		assertThatThrownBy(() -> agent("acp.enabled", "yes")).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("acp.enabled=yes is not true or false");
		assertThatThrownBy(() -> agent("acp.request-timeout", "soon"))
			.hasMessage("acp.request-timeout=soon is not a duration such as 30s or PT30S");
		assertThatThrownBy(() -> agent("acp.request-timeout", "5 fortnights"))
			.hasMessage("acp.request-timeout=5 fortnights is not a duration such as 30s or PT30S");
		assertThatThrownBy(() -> agent("acp.request-timeout", "PT5X"))
			.hasMessage("acp.request-timeout=PT5X is not a duration such as 30s or PT30S");
		assertThatThrownBy(() -> agent("acp.transport.http.mailbox-capacity", "many"))
			.hasMessage("acp.transport.http.mailbox-capacity=many is not an integer");
		assertThatThrownBy(() -> agent("acp.transport.type", "pigeon"))
			.hasMessage("acp.transport.type=pigeon is not stdio, websocket or http");
		assertThatThrownBy(() -> bytes("lots")).hasMessageContaining("is not a size such as 16MB");
		assertThatThrownBy(() -> bytes("2TB")).hasMessageContaining("is not a size such as 16MB");
	}

	@Test
	void anEmptyPrefixReadsBareKeys() {
		assertThat(AcpAgentSettings.from(MapSettingsSource.of("transport.type", "http"), "").transport())
			.isEqualTo(AcpTransportType.HTTP);
	}

	@Test
	void transportTypesParseInAnyCase() {
		assertThat(AcpTransportType.parse(" Http ")).isEqualTo(AcpTransportType.HTTP);
		assertThat(AcpTransportType.STDIO.value()).isEqualTo("stdio");
		assertThatThrownBy(() -> AcpTransportType.parse("udp")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("use stdio, websocket or http");
	}

	@Test
	void partsAreRequired() {
		AcpAgentSettings.Builder builder = AcpAgentSettings.builder();
		assertThatThrownBy(() -> builder.transport(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> builder.path(null)).isInstanceOf(NullPointerException.class);
	}

	private static AcpAgentSettings agent(String key, String value) {
		return AcpAgentSettings.from(MapSettingsSource.of(key, value), "acp");
	}

	private static long bytes(String value) {
		return agent("acp.transport.http.max-post-body-size", value).http().limits().maxPostBodyBytes();
	}

}
