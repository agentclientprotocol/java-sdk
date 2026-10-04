/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.time.Duration;
import java.util.Map;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.integration.AcpTransportType;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every {@code acp.agent.*} setting binds, and the defaults hold when none is set. */
class AcpAgentConfigurationTest {

	@Test
	void defaults() {
		try (ApplicationContext context = ApplicationContext.run()) {
			AcpAgentConfiguration config = context.getBean(AcpAgentConfiguration.class);
			assertThat(config.isEnabled()).isTrue();
			assertThat(config.getRequestTimeout()).isEqualTo(Duration.ofSeconds(60));
			assertThat(config.getCancelGracePeriod()).isEqualTo(Duration.ofSeconds(60));
			assertThat(config.getMaxPromptDuration()).isEqualTo(Duration.ZERO);
			assertThat(config.isShutdownOnTransportEnd()).isTrue();
			assertThat(config.getShutdownTimeout()).isEqualTo(Duration.ofSeconds(10));
			assertThat(config.getTransport().getType()).isEqualTo(AcpTransportType.STDIO);
			AcpAgentConfiguration.Transport.Http http = config.getTransport().getHttp();
			assertThat(http.getPort()).isEqualTo(8080);
			assertThat(http.getHost()).isNull();
			assertThat(http.getAllowedOrigins()).isEmpty();
			assertThat(http.getPath()).isEqualTo("/acp");
			assertThat(http.getMaxPostBodySize()).isNull();
			assertThat(http.getKeepAliveInterval()).isNull();
			assertThat(http.getMailboxCapacity()).isNull();
			assertThat(http.getMaxPendingSseEvents()).isNull();
			assertThat(http.getMaxWebSocketPendingFrames()).isNull();
			assertThat(http.getMaxProvisionalSessions()).isNull();
			assertThat(http.getMaxConcurrentStreamsPerConnection()).isNull();
			assertThat(http.getShutdownTimeout()).isNull();
			// unset limits keep the SDK's defaults
			assertThat(config.toSettings().toOptions(true)).isEqualTo(StreamableHttpAcpAgentTransportOptions.defaults());
		}
	}

	@Test
	void everySettingBinds() {
		Map<String, Object> properties = Map.ofEntries(Map.entry("acp.agent.enabled", "false"),
				Map.entry("acp.agent.request-timeout", "7s"), Map.entry("acp.agent.cancel-grace-period", "3s"),
				Map.entry("acp.agent.max-prompt-duration", "2m"),
				Map.entry("acp.agent.shutdown-on-transport-end", "false"), Map.entry("acp.agent.shutdown-timeout", "6s"),
				Map.entry("acp.agent.transport.type", "http"), Map.entry("acp.agent.transport.http.port", "9123"),
				Map.entry("acp.agent.transport.http.path", "/agents/acp"),
				Map.entry("acp.agent.transport.http.max-post-body-size", "2MB"),
				Map.entry("acp.agent.transport.http.keep-alive-interval", "20s"),
				Map.entry("acp.agent.transport.http.mailbox-capacity", "11"),
				Map.entry("acp.agent.transport.http.max-pending-sse-events", "12"),
				Map.entry("acp.agent.transport.http.max-web-socket-pending-frames", "13"),
				Map.entry("acp.agent.transport.http.max-provisional-sessions", "14"),
				Map.entry("acp.agent.transport.http.max-concurrent-streams-per-connection", "15"),
				Map.entry("acp.agent.transport.http.shutdown-timeout", "4s"),
				Map.entry("acp.agent.transport.http.host", "0.0.0.0"),
				Map.entry("acp.agent.transport.http.allowed-origins", "https://a.example,https://b.example"));
		try (ApplicationContext context = ApplicationContext.run(properties)) {
			AcpAgentConfiguration config = context.getBean(AcpAgentConfiguration.class);
			assertThat(config.isEnabled()).isFalse();
			assertThat(config.getRequestTimeout()).isEqualTo(Duration.ofSeconds(7));
			assertThat(config.getCancelGracePeriod()).isEqualTo(Duration.ofSeconds(3));
			assertThat(config.getMaxPromptDuration()).isEqualTo(Duration.ofMinutes(2));
			assertThat(config.isShutdownOnTransportEnd()).isFalse();
			assertThat(config.getShutdownTimeout()).isEqualTo(Duration.ofSeconds(6));
			assertThat(config.getTransport().getType()).isEqualTo(AcpTransportType.HTTP);
			AcpAgentConfiguration.Transport.Http http = config.getTransport().getHttp();
			assertThat(http.getPort()).isEqualTo(9123);
			assertThat(http.getPath()).isEqualTo("/agents/acp");

			StreamableHttpAcpAgentTransportOptions options = config.toSettings().toOptions(true);
			assertThat(options.maxPostBodyBytes()).isEqualTo(2L * 1024 * 1024);
			assertThat(options.keepAliveInterval()).isEqualTo(Duration.ofSeconds(20));
			assertThat(options.mailboxCapacity()).isEqualTo(11);
			assertThat(options.maxPendingSseEvents()).isEqualTo(12);
			assertThat(options.maxWebSocketPendingFrames()).isEqualTo(13);
			assertThat(options.maxProvisionalSessions()).isEqualTo(14);
			assertThat(options.maxConcurrentStreamsPerConnection()).isEqualTo(15);
			assertThat(options.shutdownTimeout()).isEqualTo(Duration.ofSeconds(4));
			assertThat(options.host()).isEqualTo("0.0.0.0");
			assertThat(options.allowedOrigins()).containsExactlyInAnyOrder("https://a.example", "https://b.example");
		}
	}

	@Test
	void transportTypeInLowerOrUpperCase() {
		for (String value : new String[] { "stdio", "HTTP", "websocket", "WEBSOCKET" }) {
			try (ApplicationContext context = ApplicationContext.run(Map.of("acp.agent.transport.type", value,
					"acp.agent.enabled", "false"))) {
				assertThat(context.getBean(AcpAgentConfiguration.class).getTransport().getType().name())
					.isEqualToIgnoringCase(value);
			}
		}
	}

	/** The type binds in any case, as {@code AcpTransportType} reads it everywhere else. */
	@Test
	void theTransportTypeBindsInAnyCase() {
		for (Map.Entry<String, AcpTransportType> value : Map
			.of("WebSocket", AcpTransportType.WEBSOCKET, "Http", AcpTransportType.HTTP, " StdIO ", AcpTransportType.STDIO)
			.entrySet()) {
			try (ApplicationContext context = ApplicationContext
				.run(Map.of("acp.agent.transport.type", value.getKey()))) {
				assertThat(context.getBean(AcpAgentConfiguration.class).getTransport().getType()).as(value.getKey())
					.isEqualTo(value.getValue());
			}
		}
		assertThatThrownBy(() -> ApplicationContext.run(Map.of("acp.agent.transport.type", "pigeon")))
			.hasMessageContaining("Unknown ACP transport type 'pigeon': use stdio, websocket or http");
	}

}
