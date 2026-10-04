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
 * The client transport {@code spring.acp.client.transport.*} describes, by the SDK's rule
 * ({@link AcpClientTransports}): an explicit {@code type} wins; otherwise the one of
 * {@code stdio.command}, {@code websocket.uri} and {@code http.uri} that is set; several
 * without a type fail at startup.
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
