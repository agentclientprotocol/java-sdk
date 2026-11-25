/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.transport.InMemoryTransportPair;
import com.agentclientprotocol.sdk.integration.AcpAgentTransports;
import com.agentclientprotocol.sdk.integration.AcpInProcess;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;

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
import org.springframework.core.env.Environment;

/**
 * Creates the single transport that {@link AcpAgentAutoConfiguration} serves the application's
 * {@code @AcpAgent} bean on, when the application has an {@code @AcpAgent} bean and no
 * {@code AcpAgentTransport} bean of its own:
 * <ul>
 * <li>{@code spring.acp.agent.transport.type} {@code stdio} or unset: the stdio transport;</li>
 * <li>{@code in-process}: an {@link InMemoryTransportPair} bean, its agent side as the agent
 * transport and its client side as the {@code AcpClientTransport} bean, so the client
 * autoconfiguration gives the application its {@code AcpSyncClient} and {@code AcpAsyncClient},
 * connected to its own agent and initialized once the agent has started. Setting a
 * {@code spring.acp.client.transport.*} property as well fails the startup.</li>
 * </ul>
 * An application that defines its own transport bean is served on that one instead.
 *
 * <p>The stdio transport reads the client's messages from standard input and writes its own to
 * standard output, so nothing else may write to standard output: Spring Boot's banner and console
 * logging do by default, so turn the banner off and send logging to standard error. The transport
 * bean has no destroy method; the agent's lifecycle closes it when it stops the agent.
 *
 * <p>With {@code type} {@code http} or {@code websocket} but none of {@code acp-http-servlet},
 * {@code acp-http-webflux} and {@code acp-streamable-http-jetty} on the classpath, the startup
 * fails with an error naming them. Nothing here applies when
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

	// The agent and the application's own client, in one JVM: one pair for the application.
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(AcpAgentTransport.class)
	@ConditionalOnBean(annotation = com.agentclientprotocol.sdk.annotation.AcpAgent.class)
	@Conditional(OnInProcessAgentTransportCondition.class)
	static class InProcessAgentTransportConfiguration {

		// The agent lifecycle and the client lifecycle close the two sides. The client
		// settings are read here only to refuse a client transport of another agent.
		@Bean(destroyMethod = "")
		InMemoryTransportPair acpInProcessTransportPair(Environment environment) {
			boolean clientTransport = java.util.stream.Stream.of("type", "stdio.command", "websocket.uri", "http.uri")
				.anyMatch(key -> environment.containsProperty("spring.acp.client.transport." + key));
			return AcpInProcess.pair(clientTransport, "spring.acp.client", "spring.acp.agent");
		}

		@Bean(destroyMethod = "")
		AcpAgentTransport acpAgentTransport(InMemoryTransportPair pair) {
			return pair.agentTransport();
		}

		@Bean(destroyMethod = "")
		AcpClientTransport acpClientTransport(InMemoryTransportPair pair) {
			return pair.clientTransport();
		}

	}

	// type=http without the HTTP module would otherwise leave the application with no agent
	// and no explanation.
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnBean(annotation = com.agentclientprotocol.sdk.annotation.AcpAgent.class)
	@Conditional(OnHttpAgentTransportCondition.class)
	@ConditionalOnMissingClass({ "com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet",
			"com.agentclientprotocol.sdk.http.webflux.AcpWebFluxHost" })
	static class MissingHttpTransportConfiguration {

		@Bean
		Object acpAgentHttpTransportMissing(AcpAgentProperties properties) {
			throw new IllegalStateException("spring.acp.agent.transport.type="
					+ properties.toSettings().transport().value() + " needs "
					+ "com.agentclientprotocol:acp-http-servlet (a servlet web application), "
					+ "com.agentclientprotocol:acp-http-webflux (a reactive web application) or "
					+ "com.agentclientprotocol:acp-streamable-http-jetty (the SDK's own listener) on the classpath");
		}

	}

}
