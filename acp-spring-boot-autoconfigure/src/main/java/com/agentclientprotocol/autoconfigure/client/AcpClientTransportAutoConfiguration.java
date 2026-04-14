package com.agentclientprotocol.autoconfigure.client;

import com.agentclientprotocol.autoconfigure.TransportType;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration
@ConditionalOnClass(AcpClient.class)
@EnableConfigurationProperties(AcpClientProperties.class)
public class AcpClientTransportAutoConfiguration {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(AcpClientTransport.class)
	@ConditionalOnProperty(prefix = "spring.acp.client.transport", name = "type", havingValue = "websocket",
			matchIfMissing = false)
	static class ExplicitWebSocketTransportConfiguration {

		@Bean
		AcpClientTransport acpClientTransport(AcpClientProperties properties) {
			return createWebSocketTransport(properties);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(AcpClientTransport.class)
	@ConditionalOnProperty(prefix = "spring.acp.client.transport", name = "type", havingValue = "stdio",
			matchIfMissing = false)
	static class ExplicitStdioTransportConfiguration {

		@Bean
		AcpClientTransport acpClientTransport(AcpClientProperties properties) {
			return createStdioTransport(properties);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(AcpClientTransport.class)
	@ConditionalOnProperty(prefix = "spring.acp.client.transport.websocket", name = "uri")
	static class AutoDetectWebSocketTransportConfiguration {

		@Bean
		AcpClientTransport acpClientTransport(AcpClientProperties properties) {
			return createWebSocketTransport(properties);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(AcpClientTransport.class)
	@ConditionalOnProperty(prefix = "spring.acp.client.transport.stdio", name = "command")
	static class AutoDetectStdioTransportConfiguration {

		@Bean
		AcpClientTransport acpClientTransport(AcpClientProperties properties) {
			return createStdioTransport(properties);
		}

	}

	private static AcpClientTransport createWebSocketTransport(AcpClientProperties properties) {
		var ws = properties.getTransport().getWebsocket();
		return new WebSocketAcpClientTransport(ws.getUri(), null).connectTimeout(ws.getConnectTimeout());
	}

	private static AcpClientTransport createStdioTransport(AcpClientProperties properties) {
		var stdio = properties.getTransport().getStdio();
		var builder = AgentParameters.builder(stdio.getCommand()).args(stdio.getArgs());
		if (!stdio.getEnv().isEmpty()) {
			builder.env(stdio.getEnv());
		}
		return new StdioAcpClientTransport(builder.build());
	}

}
