/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import jakarta.inject.Singleton;

/**
 * What the Streamable HTTP servlet and the WebSocket route share: one agent factory over
 * the {@code @AcpAgent} bean, the JSON mapper, and the endpoint limits from
 * {@code quarkus.acp.agent.transport.http.*}.
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
		StreamableHttpAcpAgentTransportOptions.Builder options = StreamableHttpAcpAgentTransportOptions.builder();
		http.maxPostBodySize().ifPresent(size -> options.maxPostBodyBytes(size.asLongValue()));
		http.keepAliveInterval().ifPresent(options::keepAliveInterval);
		http.mailboxCapacity().ifPresent(options::mailboxCapacity);
		http.maxPendingSseEvents().ifPresent(options::maxPendingSseEvents);
		http.maxWebSocketPendingFrames().ifPresent(options::maxWebSocketPendingFrames);
		http.maxProvisionalSessions().ifPresent(options::maxProvisionalSessions);
		http.shutdownTimeout().ifPresent(options::shutdownTimeout);
		return options.build();
	}

	/**
	 * The JSON mapper of the endpoint (the SDK default, never the application's).
	 * @return the mapper
	 */
	public AcpJsonMapper jsonMapper() {
		return jsonMapper;
	}

	/**
	 * The factory creating one agent per connection, all on the {@code @AcpAgent} bean.
	 * @return the agent factory
	 */
	public AcpAgentFactory agentFactory() {
		return agentFactory;
	}

	/**
	 * The endpoint limits, from the configuration.
	 * @return the transport options
	 */
	public StreamableHttpAcpAgentTransportOptions options() {
		return options;
	}

}
