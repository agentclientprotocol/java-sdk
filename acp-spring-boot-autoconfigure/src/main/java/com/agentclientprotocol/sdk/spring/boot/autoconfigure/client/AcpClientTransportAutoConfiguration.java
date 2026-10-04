/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.client;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.integration.AcpClientTransports;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConfigurationCondition;

/**
 * Creates the client transport that {@code spring.acp.client.transport.*} describes, for
 * {@link AcpClientAutoConfiguration} to build the client on. It applies when any of {@code type},
 * {@code stdio.command}, {@code websocket.uri} or {@code http.uri} is set and the application has
 * no {@code AcpClientTransport} bean of its own; with none of them set, the application gets no
 * transport and no client.
 *
 * <p>The transport is chosen by the SDK's rule ({@link AcpClientTransports}), the same in every
 * framework: a {@code type} that is set wins, and fails the startup when its command or URI is
 * missing; otherwise the one transport whose command or URI is set is used, and more than one fails
 * the startup, naming them. The errors name the {@code spring.acp.client} properties. The transport
 * bean has no destroy method: the client closes it when it is closed.
 */
@AutoConfiguration
@ConditionalOnClass(AcpClient.class)
@EnableConfigurationProperties(AcpClientProperties.class)
public class AcpClientTransportAutoConfiguration {

	static final String PREFIX = "spring.acp.client";

	// The client lifecycle closes the transport with the client.
	@Bean(destroyMethod = "")
	@ConditionalOnMissingBean(AcpClientTransport.class)
	@Conditional(OnClientTransportCondition.class)
	AcpClientTransport acpClientTransport(AcpClientProperties properties) {
		return AcpClientTransports.create(properties.toSettings(), PREFIX)
			.orElseThrow(() -> new IllegalStateException("No ACP client transport is configured"));
	}

	/** Any client transport property: a type, a command or a URI. */
	static class OnClientTransportCondition extends AnyNestedCondition {

		OnClientTransportCondition() {
			super(ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN);
		}

		@ConditionalOnProperty(prefix = PREFIX + ".transport", name = "type")
		static class Type {

		}

		@ConditionalOnProperty(prefix = PREFIX + ".transport.stdio", name = "command")
		static class StdioCommand {

		}

		@ConditionalOnProperty(prefix = PREFIX + ".transport.websocket", name = "uri")
		static class WebSocketUri {

		}

		@ConditionalOnProperty(prefix = PREFIX + ".transport.http", name = "uri")
		static class HttpUri {

		}

	}

}
