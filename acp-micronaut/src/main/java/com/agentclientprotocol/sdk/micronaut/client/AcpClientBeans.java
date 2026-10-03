/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.micronaut.TransportType;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * The configured client, when {@code acp.client.transport.*} is set: its transport, an
 * {@link AcpAsyncClient} and an {@link AcpSyncClient} facade over that same client (one
 * session on one transport connection). The client connects when the application calls
 * {@code initialize()}, and closes gracefully, once, with the application context.
 *
 * <p>
 * The builder gets the configured request timeout and capabilities and a session-update
 * consumer that logs at DEBUG, then every {@link AcpClientCustomizer} bean in order. Replace
 * the transport with an application bean annotated
 * {@code @Replaces(bean = AcpClientTransport.class, factory = AcpClientBeans.class)}.
 */
@Factory
@Requires(property = AcpClientConfiguration.PREFIX + ".transport")
public class AcpClientBeans {

	private static final Logger logger = LoggerFactory.getLogger(AcpClientBeans.class);

	/**
	 * The transport {@code acp.client.transport} describes.
	 * @param config the client settings
	 * @return the transport
	 * @throws IllegalStateException if the settings name no transport, several without a
	 * type, or a type without its command or URI
	 */
	@Singleton
	public AcpClientTransport acpClientTransport(AcpClientConfiguration config) {
		AcpClientConfiguration.Transport transport = config.getTransport();
		return switch (transportType(transport)) {
			case STDIO -> stdio(transport.getStdio());
			case WEBSOCKET -> new WebSocketAcpClientTransport(
					required(transport.getWebsocket().getUri(), TransportType.WEBSOCKET, "websocket.uri"),
					AcpJsonMapper.createDefault())
				.connectTimeout(transport.getWebsocket().getConnectTimeout());
			case HTTP -> new StreamableHttpAcpClientTransport(
					required(transport.getHttp().getUri(), TransportType.HTTP, "http.uri"),
					AcpJsonMapper.createDefault());
		};
	}

	/**
	 * The client, built once over the transport.
	 * @param transport the client transport
	 * @param config the client settings
	 * @param customizers every customizer bean, in bean order
	 * @return the async client
	 */
	@Singleton
	public AcpAsyncClient acpAsyncClient(AcpClientTransport transport, AcpClientConfiguration config,
			List<AcpClientCustomizer> customizers) {
		AcpClientConfiguration.Capabilities caps = config.getCapabilities();
		AcpClient.AsyncSpec spec = AcpClient.async(transport)
			.requestTimeout(config.getRequestTimeout())
			.clientCapabilities(new AcpSchema.ClientCapabilities(
					new AcpSchema.FileSystemCapability(caps.isReadTextFile(), caps.isWriteTextFile()),
					caps.isTerminal()))
			// Session updates always have a consumer, so the SDK does not warn about an
			// unhandled session/update; an application adds its own through a customizer.
			.sessionUpdateConsumer(AcpClientBeans::logSessionUpdate);
		if (config.getPromptTimeout() != null) {
			spec.promptTimeout(config.getPromptTimeout());
		}
		customizers.forEach(customizer -> customizer.customize(spec));
		return spec.build();
	}

	/**
	 * The sync facade over the one async client. Building it from the transport instead
	 * would connect that transport a second time, which the SDK refuses.
	 * @param client the async client
	 * @return the sync client
	 */
	@Singleton
	public AcpSyncClient acpSyncClient(AcpAsyncClient client) {
		return new AcpSyncClient(client);
	}

	/**
	 * The transport type: the configured one, else the only one whose command or URI is set.
	 */
	static TransportType transportType(AcpClientConfiguration.Transport transport) {
		TransportType type = transport.getType();
		if (type != null) {
			return type;
		}
		List<TransportType> configured = new ArrayList<>();
		if (transport.getStdio().getCommand() != null) {
			configured.add(TransportType.STDIO);
		}
		if (transport.getWebsocket().getUri() != null) {
			configured.add(TransportType.WEBSOCKET);
		}
		if (transport.getHttp().getUri() != null) {
			configured.add(TransportType.HTTP);
		}
		if (configured.size() == 1) {
			return configured.get(0);
		}
		if (configured.isEmpty()) {
			throw new IllegalStateException("An ACP client needs a transport: set "
					+ AcpClientConfiguration.PREFIX + ".transport.stdio.command, .websocket.uri or .http.uri");
		}
		throw new IllegalStateException("Several ACP client transports are configured " + configured
				+ "; choose one with " + AcpClientConfiguration.PREFIX + ".transport.type");
	}

	private static AcpClientTransport stdio(AcpClientConfiguration.Transport.Stdio stdio) {
		AgentParameters.Builder agent = AgentParameters
			.builder(required(stdio.getCommand(), TransportType.STDIO, "stdio.command"))
			.args(stdio.getArgs());
		if (!stdio.getEnv().isEmpty()) {
			agent.env(stdio.getEnv());
		}
		return new StdioAcpClientTransport(agent.build());
	}

	private static <T> T required(@Nullable T value, TransportType type, String property) {
		if (value == null) {
			throw new IllegalStateException(AcpClientConfiguration.PREFIX + ".transport.type="
					+ type.name().toLowerCase(Locale.ROOT) + " needs " + AcpClientConfiguration.PREFIX + ".transport."
					+ property);
		}
		return value;
	}

	private static Mono<Void> logSessionUpdate(AcpSchema.SessionNotification notification) {
		logger.debug("Session update for {}: {}", notification.sessionId(), notification.update());
		return Mono.empty();
	}

}
