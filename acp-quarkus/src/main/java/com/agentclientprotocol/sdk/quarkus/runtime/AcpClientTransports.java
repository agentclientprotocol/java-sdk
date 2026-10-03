/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.net.URI;

import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import com.agentclientprotocol.sdk.quarkus.ClientTransportType;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;

/**
 * The client transport from {@code quarkus.acp.client.transport.*}: the configured type,
 * or, when none is set, WebSocket if a WebSocket URI is set, else Streamable HTTP if an
 * HTTP URI is set, else stdio if a command is set.
 *
 * @author Mark Pollack
 */
final class AcpClientTransports {

	private static final String PREFIX = "quarkus.acp.client.transport.";

	private AcpClientTransports() {
	}

	/**
	 * The transport type the configuration selects.
	 * @param transport the transport settings
	 * @return the type
	 * @throws IllegalStateException when nothing selects a transport
	 */
	static ClientTransportType type(AcpRuntimeConfig.ClientTransport transport) {
		if (transport.type().isPresent()) {
			return transport.type().get();
		}
		if (transport.websocket().uri().isPresent()) {
			return ClientTransportType.WEBSOCKET;
		}
		if (transport.http().uri().isPresent()) {
			return ClientTransportType.HTTP;
		}
		if (transport.stdio().command().isPresent()) {
			return ClientTransportType.STDIO;
		}
		throw new IllegalStateException("No ACP client transport is configured: set " + PREFIX + "stdio.command, "
				+ PREFIX + "websocket.uri or " + PREFIX + "http.uri");
	}

	static AcpClientTransport create(AcpRuntimeConfig.ClientTransport transport) {
		return switch (type(transport)) {
			case WEBSOCKET -> webSocket(transport.websocket());
			case HTTP -> http(transport.http());
			case STDIO -> stdio(transport.stdio());
		};
	}

	private static AcpClientTransport webSocket(AcpRuntimeConfig.WebSocket webSocket) {
		URI uri = webSocket.uri().orElseThrow(() -> missing("websocket", "websocket.uri"));
		return new WebSocketAcpClientTransport(uri, AcpJsonMapper.createDefault())
			.connectTimeout(webSocket.connectTimeout());
	}

	private static AcpClientTransport http(AcpRuntimeConfig.Http http) {
		URI uri = http.uri().orElseThrow(() -> missing("http", "http.uri"));
		return new StreamableHttpAcpClientTransport(uri, AcpJsonMapper.createDefault());
	}

	private static AcpClientTransport stdio(AcpRuntimeConfig.Stdio stdio) {
		String command = stdio.command().orElseThrow(() -> missing("stdio", "stdio.command"));
		AgentParameters.Builder parameters = AgentParameters.builder(command);
		stdio.args().ifPresent(parameters::args);
		if (!stdio.env().isEmpty()) {
			parameters.env(stdio.env());
		}
		return new StdioAcpClientTransport(parameters.build());
	}

	private static IllegalStateException missing(String type, String property) {
		return new IllegalStateException(PREFIX + "type=" + type + " requires " + PREFIX + property);
	}

}
