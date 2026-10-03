package com.agentclientprotocol.autoconfigure.agent;

import com.agentclientprotocol.autoconfigure.TransportType;
import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
			return new StdioAcpAgentTransport();
		}

	}

	// type=http without the HTTP module would otherwise leave the application with no
	// agent
	// and no explanation.
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnBean(annotation = com.agentclientprotocol.sdk.annotation.AcpAgent.class)
	@ConditionalOnProperty(prefix = "spring.acp.agent.transport", name = "type", havingValue = "http")
	@ConditionalOnMissingClass("com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet")
	static class MissingHttpTransportConfiguration {

		@Bean
		Object acpAgentHttpTransportMissing() {
			throw new IllegalStateException("spring.acp.agent.transport.type=http needs "
					+ "com.agentclientprotocol:acp-streamable-http-jetty on the classpath");
		}

	}

}
