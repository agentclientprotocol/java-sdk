/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransportOptions;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.util.PlatformThreads;
import org.jspecify.annotations.Nullable;

/**
 * Creates the client transport the {@link AcpClientSettings} describe, by one rule that every
 * framework applies, so the same configuration selects the same transport everywhere:
 * <ul>
 * <li>an explicit {@code transport.type} wins, and fails when its command or URI is missing;</li>
 * <li>otherwise exactly one of {@code transport.stdio.command}, {@code transport.websocket.uri}
 * and {@code transport.http.uri} selects the transport, and setting more than one fails, naming
 * the transports found and the {@code transport.type} key that chooses one;</li>
 * <li>with none, there is no transport, and the framework creates no client: an agent-only
 * application, or one that configures no client.</li>
 * </ul>
 *
 * <p>Pass the framework's property prefix, such as {@code quarkus.acp.client}, so the error
 * messages name the keys the user wrote. The stdio transport starts the agent process with the
 * settings' arguments and added environment; the WebSocket transport uses the settings' connect
 * timeout; each transport uses the default JSON mapper. A transport is not connected here:
 * building the client on it does that, and one transport serves one client.
 */
public final class AcpClientTransports {

	/** The prefix error messages name when the caller gives none. */
	static final String DEFAULT_PREFIX = "acp.client";

	private AcpClientTransports() {
	}

	/**
	 * Returns the transport the settings describe, as {@link #create(AcpClientSettings, String)}
	 * does, with errors that name {@code acp.client.*} keys.
	 * @param settings the client settings
	 * @return the transport, or empty when no transport is configured
	 * @throws IllegalStateException if several transports are configured with no type, or the
	 * type lacks its command or URI
	 */
	public static Optional<AcpClientTransport> create(AcpClientSettings settings) {
		return create(settings, DEFAULT_PREFIX);
	}

	/**
	 * Returns the transport the settings describe, by the rule above, on the SDK's own threads
	 * ({@link AcpTransportThreads#sdkDefault()}). Empty exactly when
	 * {@link AcpClientSettings#hasTransport()} is false.
	 * @param settings the client settings
	 * @param prefix the framework's prefix of the client keys, such as {@code spring.acp.client},
	 * which error messages name
	 * @return the transport, not connected, or empty when no transport is configured
	 * @throws IllegalStateException if several transports are configured with no type (the
	 * message names {@code <prefix>.transport.type}), or the type lacks its command or URI (the
	 * message names the missing key)
	 */
	public static Optional<AcpClientTransport> create(AcpClientSettings settings, String prefix) {
		return create(settings, prefix, AcpTransportThreads.sdkDefault());
	}

	/**
	 * Returns the transport the settings describe, by the rule above, with the WebSocket and
	 * Streamable HTTP transports on the given threads: the framework's executor, the SDK's own
	 * threads (virtual on JDK 21 and later), or platform threads on every JDK. A transport never
	 * shuts down an executor it is given. The stdio transport keeps its own reader and writer
	 * threads either way.
	 * @param settings the client settings
	 * @param prefix the framework's prefix of the client keys, which error messages name
	 * @param threads the threads the network transports run on
	 * @return the transport, not connected, or empty when no transport is configured
	 * @throws IllegalStateException as {@link #create(AcpClientSettings, String)} does
	 */
	public static Optional<AcpClientTransport> create(AcpClientSettings settings, String prefix,
			AcpTransportThreads threads) {
		AcpTransportType type = type(settings, prefix);
		if (type == null) {
			return Optional.empty();
		}
		return Optional.of(switch (type) {
			case STDIO -> stdio(settings.stdio(), prefix);
			case WEBSOCKET -> webSocket(required(settings.websocket().uri(), type, prefix, "websocket.uri"), threads)
				.connectTimeout(settings.websocket().connectTimeout());
			case HTTP -> http(required(settings.http().uri(), type, prefix, "http.uri"), threads);
		});
	}

	private static WebSocketAcpClientTransport webSocket(URI uri, AcpTransportThreads threads) {
		Executor executor = threads.executor();
		if (executor == null && !threads.virtualThreads()) {
			// The WebSocket transport's own pool before JDK 21; its idle threads end after a
			// minute, so the transport not shutting it down leaves nothing running.
			executor = PlatformThreads.newCachedPool("acp-ws-client");
		}
		return (executor != null) ? new WebSocketAcpClientTransport(uri, AcpJsonMapper.createDefault(), executor)
				: new WebSocketAcpClientTransport(uri);
	}

	private static StreamableHttpAcpClientTransport http(URI uri, AcpTransportThreads threads) {
		StreamableHttpAcpClientTransportOptions.Builder options = StreamableHttpAcpClientTransportOptions.builder()
			.virtualThreads(threads.virtualThreads());
		Executor executor = threads.executor();
		if (executor != null) {
			options.executor(executor);
		}
		return new StreamableHttpAcpClientTransport(uri, AcpJsonMapper.createDefault(), options.build());
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
