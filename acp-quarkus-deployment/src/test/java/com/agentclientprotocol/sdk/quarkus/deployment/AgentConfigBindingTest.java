/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.time.Duration;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.integration.AcpTransportType;
import com.agentclientprotocol.sdk.quarkus.AcpBuildTimeConfig;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpVertxHost;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code quarkus.acp.agent.*} setting binds, and reaches the SDK: the HTTP limits
 * become the endpoint's transport options. The extension's Quarkus HTTP defaults yield to
 * the application's own settings.
 */
class AgentConfigBindingTest {

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(ConfiguredAgent.class))
		.overrideConfigKey("quarkus.acp.agent.enabled", "true")
		.overrideConfigKey("quarkus.acp.agent.transport.type", "http")
		.overrideConfigKey("quarkus.acp.agent.transport.http.path", "/configured")
		.overrideConfigKey("quarkus.acp.agent.request-timeout", "42s")
		.overrideConfigKey("quarkus.acp.agent.cancel-grace-period", "7s")
		.overrideConfigKey("quarkus.acp.agent.max-prompt-duration", "5m")
		.overrideConfigKey("quarkus.acp.agent.shutdown-on-transport-end", "false")
		.overrideConfigKey("quarkus.acp.agent.transport.http.max-post-body-size", "2M")
		.overrideConfigKey("quarkus.acp.agent.transport.http.keep-alive-interval", "3s")
		.overrideConfigKey("quarkus.acp.agent.transport.http.mailbox-capacity", "11")
		.overrideConfigKey("quarkus.acp.agent.transport.http.max-pending-sse-events", "12")
		.overrideConfigKey("quarkus.acp.agent.transport.http.max-web-socket-pending-frames", "13")
		.overrideConfigKey("quarkus.acp.agent.transport.http.max-provisional-sessions", "14")
		.overrideConfigKey("quarkus.acp.agent.transport.http.shutdown-timeout", "4s")
		.overrideConfigKey("quarkus.acp.agent.transport.http.web-socket-idle-timeout", "45m")
		.overrideConfigKey("quarkus.acp.agent.transport.http.initialize-timeout", "9s")
		.overrideConfigKey("quarkus.http.limits.max-concurrent-streams", "77");

	@Inject
	AcpBuildTimeConfig buildTime;

	@Inject
	AcpRuntimeConfig runtime;

	@Inject
	AcpVertxHost endpoint;

	@Test
	void everyAgentSettingBinds() {
		assertThat(buildTime.agent().enabled()).isTrue();
		assertThat(buildTime.agent().transport().type()).isEqualTo(AcpTransportType.HTTP);
		assertThat(buildTime.agent().transport().http().path()).isEqualTo("/configured");

		AcpRuntimeConfig.Agent agent = runtime.agent();
		assertThat(agent.requestTimeout()).contains(Duration.ofSeconds(42));
		assertThat(agent.cancelGracePeriod()).contains(Duration.ofSeconds(7));
		assertThat(agent.maxPromptDuration()).contains(Duration.ofMinutes(5));
		assertThat(agent.shutdownOnTransportEnd()).isFalse();
	}

	@Test
	void httpLimitsBecomeTheEndpointOptions() {
		StreamableHttpAcpAgentTransportOptions options = endpoint.endpoint().options();
		assertThat(options.maxPostBodyBytes()).isEqualTo(2L * 1024 * 1024);
		assertThat(options.keepAliveInterval()).isEqualTo(Duration.ofSeconds(3));
		assertThat(options.mailboxCapacity()).isEqualTo(11);
		assertThat(options.maxPendingSseEvents()).isEqualTo(12);
		assertThat(options.maxWebSocketPendingFrames()).isEqualTo(13);
		assertThat(options.maxProvisionalSessions()).isEqualTo(14);
		assertThat(options.shutdownTimeout()).isEqualTo(Duration.ofSeconds(4));
		assertThat(options.webSocketIdleTimeout()).isEqualTo(Duration.ofMinutes(45));
		assertThat(options.initializeTimeout()).isEqualTo(Duration.ofSeconds(9));
	}

	@Test
	void quarkusHttpLimitsDefaultToTheSdkButTheApplicationWins() {
		var config = ConfigProvider.getConfig();
		assertThat(config.getValue("quarkus.http.limits.max-body-size", String.class)).isEqualTo("16M");
		assertThat(config.getValue("quarkus.http.websocket-server.max-message-size", Integer.class))
			.isEqualTo(16 * 1024 * 1024);
		assertThat(config.getValue("quarkus.http.limits.max-concurrent-streams", Long.class)).isEqualTo(77L);
	}

	@AcpAgent
	public static class ConfiguredAgent {

		@Prompt
		AcpSchema.PromptResponse prompt() {
			return AcpSchema.PromptResponse.endTurn();
		}

	}

}
