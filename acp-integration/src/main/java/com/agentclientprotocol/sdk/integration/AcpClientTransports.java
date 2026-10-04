/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import org.jspecify.annotations.Nullable;

/**
 * The client transport {@link AcpClientSettings} describe. The rule, the same in every framework:
 * <ul>
 * <li>an explicit {@code transport.type} wins, and fails without its command or URI;</li>
 * <li>otherwise exactly one of {@code transport.stdio.command}, {@code transport.websocket.uri}
 * and {@code transport.http.uri} selects the transport; more than one fails, naming them;</li>
 * <li>with none, there is no transport (a client-only application that configures no client, or
 * an agent-only one).</li>
 * </ul>
 */
public final class AcpClientTransports {

	/** The prefix error messages name when the caller gives none. */
	static final String DEFAULT_PREFIX = "acp.client";

	private AcpClientTransports() {
	}

	/**
	 * The transport the settings describe, naming {@code acp.client.*} keys in errors.
	 * @param settings the client settings
	 * @return the transport, or empty when no transport is configured
	 * @throws IllegalStateException when several transports are configured with no type, or the
	 * type lacks its command or URI
	 */
	public static Optional<AcpClientTransport> create(AcpClientSettings settings) {
		return create(settings, DEFAULT_PREFIX);
	}

	/**
	 * The transport the settings describe.
	 * @param settings the client settings
	 * @param prefix the framework's prefix of the client keys, such as {@code spring.acp.client},
	 * which error messages name
	 * @return the transport, or empty when no transport is configured
	 * @throws IllegalStateException when several transports are configured with no type, or the
	 * type lacks its command or URI
	 */
	public static Optional<AcpClientTransport> create(AcpClientSettings settings, String prefix) {
		AcpTransportType type = type(settings, prefix);
		if (type == null) {
			return Optional.empty();
		}
		return Optional.of(switch (type) {
			case STDIO -> stdio(settings.stdio(), prefix);
			case WEBSOCKET -> new WebSocketAcpClientTransport(
					required(settings.websocket().uri(), type, prefix, "websocket.uri"), AcpJsonMapper.createDefault())
				.connectTimeout(settings.websocket().connectTimeout());
			case HTTP -> new StreamableHttpAcpClientTransport(
					required(settings.http().uri(), type, prefix, "http.uri"), AcpJsonMapper.createDefault());
		});
	}

	/**
	 * The transport type: the explicit one, else the only one configured.
	 * @return the type, or null when none is configured
	 */
	static @Nullable AcpTransportType type(AcpClientSettings settings, String prefix) {
		AcpTransportType type = settings.transport();
		if (type != null) {
			return type;
		}
		List<AcpTransportType> configured = new ArrayList<>();
		if (settings.stdio().command() != null) {
			configured.add(AcpTransportType.STDIO);
		}
		if (settings.websocket().uri() != null) {
			configured.add(AcpTransportType.WEBSOCKET);
		}
		if (settings.http().uri() != null) {
			configured.add(AcpTransportType.HTTP);
		}
		if (configured.size() > 1) {
			throw new IllegalStateException("Several ACP client transports are configured " + configured
					+ "; choose one with " + prefix + ".transport.type");
		}
		return configured.isEmpty() ? null : configured.get(0);
	}

	private static AcpClientTransport stdio(AcpClientSettings.Stdio stdio, String prefix) {
		AgentParameters.Builder agent = AgentParameters
			.builder(required(stdio.command(), AcpTransportType.STDIO, prefix, "stdio.command"))
			.args(stdio.args());
		if (!stdio.env().isEmpty()) {
			agent.env(stdio.env());
		}
		return new StdioAcpClientTransport(agent.build());
	}

	private static <T> T required(@Nullable T value, AcpTransportType type, String prefix, String property) {
		if (value == null) {
			throw new IllegalStateException(prefix + ".transport.type=" + type.value() + " requires " + prefix
					+ ".transport." + property);
		}
		return value;
	}

}
