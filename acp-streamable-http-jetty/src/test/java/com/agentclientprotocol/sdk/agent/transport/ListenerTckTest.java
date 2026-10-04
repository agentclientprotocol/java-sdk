/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.net.URI;
import java.time.Duration;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.test.http.AcpHttpTransportTck;

/**
 * The SDK's own listener, the embedded host: Jetty 12.1 running the servlet host with Jetty's
 * Jakarta WebSocket implementation, on a socket it binds itself (loopback by default).
 */
class ListenerTckTest extends AcpHttpTransportTck {

	@Override
	protected Host startHost(HostConfig config) {
		StreamableHttpAcpAgentTransport listener = new StreamableHttpAcpAgentTransport(0,
				StreamableHttpAcpAgentTransport.DEFAULT_ACP_PATH, AcpJsonMapper.createDefault(), config.agents(),
				config.options());
		listener.start().block(Duration.ofSeconds(10));
		URI endpoint = URI.create("http://127.0.0.1:" + listener.getPort() + "/acp");
		return new Host() {

			@Override
			public URI endpoint() {
				return endpoint;
			}

			@Override
			public void stop() {
				listener.closeGracefully().block(Duration.ofSeconds(10));
			}

		};
	}

	@Override
	protected boolean bindsItsOwnSocket() {
		return true;
	}

}
