package com.agentclientprotocol.autoconfigure.client;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = AcpClientTransportAutoConfiguration.class)
@ConditionalOnClass(AcpClient.class)
@ConditionalOnBean(AcpClientTransport.class)
@EnableConfigurationProperties(AcpClientProperties.class)
public class AcpClientAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	AcpAsyncClient acpAsyncClient(AcpClientTransport transport, AcpClientProperties properties) {
		var caps = properties.getCapabilities();
		var clientCapabilities = new AcpSchema.ClientCapabilities(
				new AcpSchema.FileSystemCapability(caps.isReadTextFile(), caps.isWriteTextFile()), caps.isTerminal());
		return AcpClient.async(transport)
			.requestTimeout(properties.getRequestTimeout())
			.clientCapabilities(clientCapabilities)
			.build();
	}

	@Bean
	@ConditionalOnMissingBean
	AcpSyncClient acpSyncClient(AcpClientTransport transport, AcpClientProperties properties) {
		var caps = properties.getCapabilities();
		var clientCapabilities = new AcpSchema.ClientCapabilities(
				new AcpSchema.FileSystemCapability(caps.isReadTextFile(), caps.isWriteTextFile()), caps.isTerminal());
		return AcpClient.sync(transport)
			.requestTimeout(properties.getRequestTimeout())
			.clientCapabilities(clientCapabilities)
			.build();
	}

	@Bean
	AcpClientLifecycle acpClientLifecycle(AcpAsyncClient asyncClient) {
		return new AcpClientLifecycle(asyncClient);
	}

	static class AcpClientLifecycle implements DisposableBean {

		private final AcpAsyncClient asyncClient;

		AcpClientLifecycle(AcpAsyncClient asyncClient) {
			this.asyncClient = asyncClient;
		}

		@Override
		public void destroy() {
			asyncClient.closeGracefully().block();
		}
	}
}
