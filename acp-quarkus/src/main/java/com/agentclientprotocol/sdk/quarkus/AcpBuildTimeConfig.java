/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus;

import com.agentclientprotocol.sdk.integration.AcpTransportType;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * The {@code quarkus.acp.*} configuration fixed when the application is built: whether the
 * application's {@code @AcpAgent} class is served, over which transport, and at which HTTP path.
 * Set these in {@code application.properties} before building; changing them needs a rebuild.
 * Everything read when the application starts (timeouts, endpoint limits, the client) is in
 * {@link AcpRuntimeConfig}.
 *
 * <p>The extension reads this configuration itself; an application rarely injects it. The
 * properties map onto {@code AcpAgentSettings} of {@code acp-integration}, the framework-neutral
 * settings the Spring Boot and Micronaut integrations fill from their own keys. The module's README
 * lists every property and walks through an agent.
 *
 * @author Mark Pollack
 */
@ConfigMapping(prefix = "quarkus.acp")
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public interface AcpBuildTimeConfig {

	/**
	 * Returns the agent's build-time properties, {@code quarkus.acp.agent.*}.
	 * @return the agent settings
	 */
	Agent agent();

	/** The {@code quarkus.acp.agent.*} properties fixed at build time. */
	interface Agent {

		/**
		 * Whether the application's {@code @AcpAgent} bean is served. Off, the bean is an ordinary
		 * bean and no agent transport starts: the application is a client only, and a second
		 * {@code @AcpAgent} class is no longer an error. Maps to {@code AcpAgentSettings.enabled}.
		 * @return whether the agent is served
		 */
		@WithDefault("true")
		boolean enabled();

		/**
		 * Returns the agent transport's build-time properties,
		 * {@code quarkus.acp.agent.transport.*}.
		 * @return the transport settings
		 */
		Transport transport();

	}

	/** The {@code quarkus.acp.agent.transport.*} properties fixed at build time. */
	interface Transport {

		/**
		 * The transport the agent is served over: {@code stdio}, or {@code http} for Streamable
		 * HTTP and WebSocket on the Quarkus HTTP server ({@code websocket} means the same). With
		 * {@code stdio}, the console log goes to standard error, the banner is off and the HTTP
		 * listener is disabled unless set otherwise, because standard output carries the protocol;
		 * the agent reads standard input, and an application bean of type {@code AcpAgentTransport}
		 * replaces stdio. With {@code http}, the agent is served on the Quarkus HTTP server's port
		 * ({@code quarkus.http.port}), not a port of its own. Maps to
		 * {@code AcpAgentSettings.transport}.
		 * @return the transport type
		 */
		@WithDefault("stdio")
		AcpTransportType type();

		/**
		 * Returns the Streamable HTTP endpoint's build-time properties,
		 * {@code quarkus.acp.agent.transport.http.*}.
		 * @return the HTTP settings
		 */
		Http http();

	}

	/** The {@code quarkus.acp.agent.transport.http.*} properties fixed at build time. */
	interface Http {

		/**
		 * The path of the Streamable HTTP endpoint on the Quarkus HTTP server's router, under
		 * {@code quarkus.http.root-path}. WebSocket upgrades are accepted on the same path. Maps
		 * to {@code AcpAgentSettings.Http.path}.
		 * @return the endpoint path
		 */
		@WithDefault("/acp")
		String path();

	}

}
