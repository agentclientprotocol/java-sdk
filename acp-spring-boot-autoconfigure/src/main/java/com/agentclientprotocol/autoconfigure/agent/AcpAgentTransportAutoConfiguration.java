package com.agentclientprotocol.autoconfigure.agent;

import com.agentclientprotocol.autoconfigure.TransportType;
import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration
@ConditionalOnClass(AcpAgent.class)
@ConditionalOnProperty(prefix = "spring.acp.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AcpAgentProperties.class)
public class AcpAgentTransportAutoConfiguration {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(AcpAgentTransport.class)
	@ConditionalOnProperty(prefix = "spring.acp.agent.transport", name = "type", havingValue = "stdio",
			matchIfMissing = true)
	static class StdioAgentTransportConfiguration {

		@Bean
		AcpAgentTransport acpAgentTransport() {
			return new StdioAcpAgentTransport();
		}

	}

}
