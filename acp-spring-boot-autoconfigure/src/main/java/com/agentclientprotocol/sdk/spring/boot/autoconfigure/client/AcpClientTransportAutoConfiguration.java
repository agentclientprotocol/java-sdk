package com.agentclientprotocol.sdk.spring.boot.autoconfigure.client;

import java.net.URI;

import com.agentclientprotocol.sdk.spring.boot.autoconfigure.TransportType;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import org.jspecify.annotations.Nullable;

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

		@Bean(destroyMethod = "")
		AcpClientTransport acpClientTransport(AcpClientProperties properties) {
			return createWebSocketTransport(properties);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(AcpClientTransport.class)
	@ConditionalOnProperty(prefix = "spring.acp.client.transport", name = "type", havingValue = "stdio",
			matchIfMissing = false)
	static class ExplicitStdioTransportConfiguration {

		@Bean(destroyMethod = "")
		AcpClientTransport acpClientTransport(AcpClientProperties properties) {
			return createStdioTransport(properties);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(AcpClientTransport.class)
	@ConditionalOnProperty(prefix = "spring.acp.client.transport", name = "type", havingValue = "http",
			matchIfMissing = false)
	static class ExplicitHttpTransportConfiguration {

		@Bean(destroyMethod = "")
		AcpClientTransport acpClientTransport(AcpClientProperties properties) {
			return createHttpTransport(properties);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(AcpClientTransport.class)
	@ConditionalOnProperty(prefix = "spring.acp.client.transport.websocket", name = "uri")
	static class AutoDetectWebSocketTransportConfiguration {

		@Bean(destroyMethod = "")
		AcpClientTransport acpClientTransport(AcpClientProperties properties) {
			return createWebSocketTransport(properties);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(AcpClientTransport.class)
	@ConditionalOnProperty(prefix = "spring.acp.client.transport.http", name = "uri")
	static class AutoDetectHttpTransportConfiguration {

		@Bean(destroyMethod = "")
		AcpClientTransport acpClientTransport(AcpClientProperties properties) {
			return createHttpTransport(properties);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(AcpClientTransport.class)
	@ConditionalOnProperty(prefix = "spring.acp.client.transport.stdio", name = "command")
	static class AutoDetectStdioTransportConfiguration {

		@Bean(destroyMethod = "")
		AcpClientTransport acpClientTransport(AcpClientProperties properties) {
			return createStdioTransport(properties);
		}

	}

	private static AcpClientTransport createWebSocketTransport(AcpClientProperties properties) {
		var ws = properties.getTransport().getWebsocket();
		return new WebSocketAcpClientTransport(required(ws.getUri(), "websocket", "websocket.uri"),
				AcpJsonMapper.createDefault())
			.connectTimeout(ws.getConnectTimeout());
	}

	private static AcpClientTransport createHttpTransport(AcpClientProperties properties) {
		URI uri = required(properties.getTransport().getHttp().getUri(), "http", "http.uri");
		return new StreamableHttpAcpClientTransport(uri, AcpJsonMapper.createDefault());
	}

	private static AcpClientTransport createStdioTransport(AcpClientProperties properties) {
		var stdio = properties.getTransport().getStdio();
		var builder = AgentParameters.builder(required(stdio.getCommand(), "stdio", "stdio.command"))
			.args(stdio.getArgs());
		if (!stdio.getEnv().isEmpty()) {
			builder.env(stdio.getEnv());
		}
		return new StdioAcpClientTransport(builder.build());
	}

	/** An explicit transport type without the property it needs fails with a message naming both. */
	private static <T> T required(@Nullable T value, String type, String property) {
		if (value == null) {
			throw new IllegalStateException("spring.acp.client.transport.type=" + type
					+ " requires spring.acp.client.transport." + property);
		}
		return value;
	}

}
