/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import io.quarkus.runtime.configuration.MemorySize;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AcpHttpEndpointTest {

	@Test
	void unsetLimitsKeepTheSdkDefaults() {
		AgentHttp none = new AgentHttp(Optional.empty(), Optional.empty(), OptionalInt.empty(), OptionalInt.empty(),
				OptionalInt.empty(), OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.empty());
		assertThat(AcpVertxHost.options(none)).isEqualTo(StreamableHttpAcpAgentTransportOptions.defaults());
	}

	@Test
	void setLimitsReachTheOptions() {
		AgentHttp all = new AgentHttp(Optional.of(new MemorySize(java.math.BigInteger.valueOf(1024))),
				Optional.of(Duration.ofSeconds(2)), OptionalInt.of(3), OptionalInt.of(4), OptionalInt.of(5),
				OptionalInt.of(6), Optional.of(Duration.ofSeconds(7)), Optional.of(Duration.ofMinutes(8)),
				Optional.of(Duration.ofSeconds(9)), Optional.of(java.util.List.of("https://app.example.com")));
		StreamableHttpAcpAgentTransportOptions options = AcpVertxHost.options(all);
		assertThat(options.maxPostBodyBytes()).isEqualTo(1024);
		assertThat(options.keepAliveInterval()).isEqualTo(Duration.ofSeconds(2));
		assertThat(options.mailboxCapacity()).isEqualTo(3);
		assertThat(options.maxPendingSseEvents()).isEqualTo(4);
		assertThat(options.maxWebSocketPendingFrames()).isEqualTo(5);
		assertThat(options.maxProvisionalSessions()).isEqualTo(6);
		assertThat(options.shutdownTimeout()).isEqualTo(Duration.ofSeconds(7));
		assertThat(options.webSocketIdleTimeout()).isEqualTo(Duration.ofMinutes(8));
		assertThat(options.initializeTimeout()).isEqualTo(Duration.ofSeconds(9));
		assertThat(options.allowedOrigins()).containsExactly("https://app.example.com");
		assertThat(options.host()).as("the Quarkus server keeps its own bind address").isNull();
	}

	@Test
	void configBuildersAddLowOrdinalDefaults() {
		var stdio = new AcpStdioConfigBuilder().configBuilder(new io.smallrye.config.SmallRyeConfigBuilder()).build();
		assertThat(stdio.getValue("quarkus.log.console.stderr", Boolean.class)).isTrue();
		assertThat(stdio.getValue("quarkus.http.host-enabled", Boolean.class)).isFalse();
		var http = new AcpHttpConfigBuilder().configBuilder(new io.smallrye.config.SmallRyeConfigBuilder()).build();
		assertThat(http.getValue("quarkus.http.limits.max-body-size", String.class)).isEqualTo("16M");
		assertThat(http.getConfigSources()).anyMatch(source -> source.getOrdinal() == AcpStdioConfigBuilder.ORDINAL);
	}

	record AgentHttp(Optional<MemorySize> maxPostBodySize, Optional<Duration> keepAliveInterval,
			OptionalInt mailboxCapacity, OptionalInt maxPendingSseEvents, OptionalInt maxWebSocketPendingFrames,
			OptionalInt maxProvisionalSessions, Optional<Duration> shutdownTimeout,
			Optional<Duration> webSocketIdleTimeout, Optional<Duration> initializeTimeout,
			Optional<java.util.List<String>> allowedOrigins) implements AcpRuntimeConfig.AgentHttp {
	}

}
