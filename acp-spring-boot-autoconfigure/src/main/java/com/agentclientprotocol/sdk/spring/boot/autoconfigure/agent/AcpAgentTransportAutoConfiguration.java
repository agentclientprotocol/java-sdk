/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.integration.AcpAgentTransports;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the stdio transport that {@link AcpAgentAutoConfiguration} serves the application's
 * {@code @AcpAgent} bean on. It applies when the application has an {@code @AcpAgent} bean and no
 * {@code AcpAgentTransport} bean of its own, and {@code spring.acp.agent.transport.type} is
 * {@code stdio} or unset. An application that defines its own transport bean, such as the agent
 * side of an {@code InMemoryTransportPair} in a test, is served on that one instead.
 *
 * <p>The stdio transport reads the client's messages from standard input and writes its own to
 * standard output, so nothing else may write to standard output: Spring Boot's banner and console
 * logging do by default, so turn the banner off and send logging to standard error. The transport
 * bean has no destroy method; the agent's lifecycle closes it when it stops the agent.
 *
 * <p>With {@code type} {@code http} or {@code websocket} but neither {@code acp-http-servlet} nor
 * {@code acp-streamable-http-jetty} on the classpath, the startup fails with an error naming them. Nothing here applies when
 * {@code spring.acp.agent.enabled=false}.
 */
@AutoConfiguration
@ConditionalOnClass(AcpAgent.class)
@ConditionalOnProperty(prefix = "spring.acp.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AcpAgentProperties.class)
public class AcpAgentTransportAutoConfiguration {

	// Only for an application that defines an @AcpAgent bean: a client-only application
	// gets no agent transport.
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(AcpAgentTransport.class)
	@ConditionalOnBean(annotation = com.agentclientprotocol.sdk.annotation.AcpAgent.class)
	@ConditionalOnProperty(prefix = "spring.acp.agent.transport", name = "type", havingValue = "stdio",
			matchIfMissing = true)
	static class StdioAgentTransportConfiguration {

		// The agent lifecycle closes the transport when it stops the agent.
		@Bean(destroyMethod = "")
		AcpAgentTransport acpAgentTransport() {
			return AcpAgentTransports.stdio();
		}

	}

	// type=http without the HTTP module would otherwise leave the application with no agent
	// and no explanation.
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnBean(annotation = com.agentclientprotocol.sdk.annotation.AcpAgent.class)
	@Conditional(OnHttpAgentTransportCondition.class)
	@ConditionalOnMissingClass("com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet")
	static class MissingHttpTransportConfiguration {

		@Bean
		Object acpAgentHttpTransportMissing(AcpAgentProperties properties) {
			throw new IllegalStateException("spring.acp.agent.transport.type="
					+ properties.toSettings().transport().value() + " needs "
					+ "com.agentclientprotocol:acp-http-servlet (served on the application's server) or "
					+ "com.agentclientprotocol:acp-streamable-http-jetty (the SDK's own listener) on the classpath");
		}

	}

}
