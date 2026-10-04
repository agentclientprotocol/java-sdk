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
 * The ACP settings fixed when the application is built: whether an {@code @AcpAgent} is
 * served, over which transport, and at which HTTP path. The rest is in
 * {@link AcpRuntimeConfig}.
 *
 * @author Mark Pollack
 */
@ConfigMapping(prefix = "quarkus.acp")
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public interface AcpBuildTimeConfig {

	/**
	 * The agent side.
	 * @return the agent settings
	 */
	Agent agent();

	/** Agent settings fixed at build time. */
	interface Agent {

		/**
		 * Whether the application's {@code @AcpAgent} bean is served. Off, the bean is an
		 * ordinary bean and no agent transport starts.
		 * @return whether the agent is served
		 */
		@WithDefault("true")
		boolean enabled();

		/**
		 * The agent transport.
		 * @return the transport settings
		 */
		Transport transport();

	}

	/** The agent transport fixed at build time. */
	interface Transport {

		/**
		 * The transport the agent is served over: {@code stdio}, or {@code http} for
		 * Streamable HTTP and WebSocket on the Quarkus HTTP server ({@code websocket} means
		 * the same). With {@code stdio}, the console log goes to standard error, the banner
		 * is off and the HTTP listener is disabled unless set otherwise, because standard
		 * output carries the protocol.
		 * @return the transport type
		 */
		@WithDefault("stdio")
		AcpTransportType type();

		/**
		 * The Streamable HTTP endpoint.
		 * @return the HTTP settings
		 */
		Http http();

	}

	/** The Streamable HTTP endpoint fixed at build time. */
	interface Http {

		/**
		 * The path of the Streamable HTTP endpoint on the Quarkus HTTP server, relative to
		 * the servlet context path. WebSocket upgrades are accepted on the same path.
		 * @return the endpoint path
		 */
		@WithDefault("/acp")
		String path();

	}

}
