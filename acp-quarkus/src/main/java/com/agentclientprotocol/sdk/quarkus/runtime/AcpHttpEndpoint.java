/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.integration.AcpAgentSettings;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import jakarta.inject.Singleton;

/**
 * What the Streamable HTTP servlet ({@link AcpHttpServlet}) and the WebSocket route
 * ({@link AcpWebSocketRoute}) share: one agent factory over the {@code @AcpAgent} bean, the JSON
 * mapper, and the endpoint limits from {@code quarkus.acp.agent.transport.http.*}. The extension
 * adds it for an HTTP agent. Part of the extension's wiring; an application does not use it
 * directly.
 *
 * @author Mark Pollack
 */
@Singleton
public class AcpHttpEndpoint {

	private final AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();

	private final AcpAgentFactory agentFactory;

	private final StreamableHttpAcpAgentTransportOptions options;

	AcpHttpEndpoint(AcpAgentAssembly assembly, AcpRuntimeConfig config) {
		this.agentFactory = assembly.factory();
		this.options = options(config.agent().transport().http());
	}

	/**
	 * The SDK options for the configured limits; an unset limit keeps the SDK default.
	 * @param http the configured limits
	 * @return the transport options
	 */
	static StreamableHttpAcpAgentTransportOptions options(AcpRuntimeConfig.AgentHttp http) {
		return AcpSettings.limits(AcpAgentSettings.builder(), http).build().toOptions(false);
	}

	/**
	 * Returns the endpoint's JSON mapper: the SDK's default
	 * ({@link AcpJsonMapper#createDefault()}), never a mapper of the application's.
	 * @return the mapper
	 */
	public AcpJsonMapper jsonMapper() {
		return jsonMapper;
	}

	/**
	 * Returns the factory creating one agent per connection, all on the {@code @AcpAgent} bean.
	 * @return the agent factory
	 */
	public AcpAgentFactory agentFactory() {
		return agentFactory;
	}

	/**
	 * Returns the endpoint limits, from the configuration; an unset limit has the SDK's default.
	 * @return the transport options
	 */
	public StreamableHttpAcpAgentTransportOptions options() {
		return options;
	}

}
