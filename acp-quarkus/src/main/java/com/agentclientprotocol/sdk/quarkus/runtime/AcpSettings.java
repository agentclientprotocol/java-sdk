/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.util.List;

import com.agentclientprotocol.sdk.integration.AcpAgentSettings;
import com.agentclientprotocol.sdk.integration.AcpClientSettings;
import com.agentclientprotocol.sdk.quarkus.AcpBuildTimeConfig;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;

/**
 * Turns the {@code quarkus.acp.*} configuration ({@link AcpBuildTimeConfig},
 * {@link AcpRuntimeConfig}) into the SDK's framework-neutral settings, {@link AcpAgentSettings} and
 * {@link AcpClientSettings}, which the extension's beans hand to {@code acp-integration}. An unset
 * optional property stays unset, so the SDK applies its default. The Quarkus HTTP server serves the
 * endpoint, so the SDK listener's own settings (its port and HTTP/2 stream limit) keep their
 * defaults and are not used. Part of the extension's wiring; an application does not use it
 * directly.
 *
 * @author Mark Pollack
 */
public final class AcpSettings {

	/** The prefix of the client's configuration, named in errors. */
	public static final String CLIENT_PREFIX = "quarkus.acp.client";

	private AcpSettings() {
	}

	/**
	 * Returns the agent settings: the build-time ones (enabled, transport, path) with the run-time
	 * ones (timeouts, shutdown on transport end, endpoint limits).
	 * @param buildTime the settings fixed at build time
	 * @param runtime the settings read at startup
	 * @return the settings
	 */
	public static AcpAgentSettings agent(AcpBuildTimeConfig buildTime, AcpRuntimeConfig runtime) {
		AcpRuntimeConfig.Agent agent = runtime.agent();
		AcpAgentSettings.Builder builder = limits(AcpAgentSettings.builder(), agent.transport().http())
			.enabled(buildTime.agent().enabled())
			.requestTimeout(agent.requestTimeout().orElse(null))
			.cancelGracePeriod(agent.cancelGracePeriod().orElse(null))
			.maxPromptDuration(agent.maxPromptDuration().orElse(null))
			.shutdownOnTransportEnd(agent.shutdownOnTransportEnd())
			.transport(buildTime.agent().transport().type())
			.path(buildTime.agent().transport().http().path());
		return builder.build();
	}

	/**
	 * The endpoint limits onto a builder; each unset one keeps the SDK default.
	 * @param builder the builder
	 * @param http the configured limits
	 * @return the builder
	 */
	static AcpAgentSettings.Builder limits(AcpAgentSettings.Builder builder, AcpRuntimeConfig.AgentHttp http) {
		return builder.maxPostBodyBytes(http.maxPostBodySize().map(size -> size.asLongValue()).orElse(null))
			.keepAliveInterval(http.keepAliveInterval().orElse(null))
			.mailboxCapacity(http.mailboxCapacity().isPresent() ? http.mailboxCapacity().getAsInt() : null)
			.maxPendingSseEvents(http.maxPendingSseEvents().isPresent() ? http.maxPendingSseEvents().getAsInt() : null)
			.maxWebSocketPendingFrames(
					http.maxWebSocketPendingFrames().isPresent() ? http.maxWebSocketPendingFrames().getAsInt() : null)
			.maxProvisionalSessions(
					http.maxProvisionalSessions().isPresent() ? http.maxProvisionalSessions().getAsInt() : null)
			.shutdownTimeout(http.shutdownTimeout().orElse(null))
			.allowedOrigins(http.allowedOrigins().orElse(List.of()));
	}

	/**
	 * Returns the client settings: timeouts, transport and capabilities.
	 * @param client the client configuration
	 * @return the settings
	 */
	public static AcpClientSettings client(AcpRuntimeConfig.Client client) {
		AcpRuntimeConfig.ClientTransport transport = client.transport();
		return AcpClientSettings.builder()
			.requestTimeout(client.requestTimeout().orElse(null))
			.promptTimeout(client.promptTimeout().orElse(null))
			.transport(transport.type().orElse(null))
			.stdioCommand(transport.stdio().command().orElse(null))
			.stdioArgs(transport.stdio().args().orElse(List.of()))
			.stdioEnv(transport.stdio().env())
			.websocketUri(transport.websocket().uri().orElse(null))
			.websocketConnectTimeout(transport.websocket().connectTimeout())
			.httpUri(transport.http().uri().orElse(null))
			.capabilities(capabilities(client.capabilities()))
			.build();
	}

	/**
	 * The capabilities to advertise.
	 * @param capabilities the configured capabilities
	 * @return the capabilities
	 */
	static AcpClientSettings.Capabilities capabilities(AcpRuntimeConfig.Capabilities capabilities) {
		return new AcpClientSettings.Capabilities(capabilities.readTextFile(), capabilities.writeTextFile(),
				capabilities.terminal(), capabilities.elicitationForm(), capabilities.elicitationUrl(),
				capabilities.booleanConfigOptions());
	}

}
