/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.client;

import java.time.Duration;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.integration.AcpClientCustomizer;
import com.agentclientprotocol.sdk.integration.AcpClientHost;
import com.agentclientprotocol.sdk.integration.AcpClients;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * An {@link AcpAsyncClient} on the client transport, built by {@link AcpClients} with every
 * {@link AcpClientCustomizer} bean in order, and an {@link AcpSyncClient} facade over it.
 */
@AutoConfiguration(after = AcpClientTransportAutoConfiguration.class)
@ConditionalOnClass(AcpClient.class)
@ConditionalOnBean(AcpClientTransport.class)
@EnableConfigurationProperties(AcpClientProperties.class)
public class AcpClientAutoConfiguration {

	// destroyMethod = "": AcpClientLifecycle closes the client (and with it the transport)
	// once; Spring's inferred close() on these beans and the transport bean closed it three
	// times.
	@Bean(destroyMethod = "")
	@ConditionalOnMissingBean
	AcpAsyncClient acpAsyncClient(AcpClientTransport transport, AcpClientProperties properties,
			ObjectProvider<AcpClientCustomizer> customizers) {
		return AcpClients.async(transport, properties.toSettings(), customizers.orderedStream().toList());
	}

	/**
	 * The sync client is a facade over the async client: one session, one transport
	 * connection.
	 */
	@Bean(destroyMethod = "")
	@ConditionalOnMissingBean
	AcpSyncClient acpSyncClient(AcpAsyncClient asyncClient) {
		return AcpClients.sync(asyncClient);
	}

	@Bean
	AcpClientLifecycle acpClientLifecycle(AcpAsyncClient asyncClient, AcpClientProperties properties) {
		return new AcpClientLifecycle(new AcpClientHost(asyncClient), properties.toSettings().closeTimeout());
	}

	static class AcpClientLifecycle implements DisposableBean {

		private final AcpClientHost host;

		private final Duration timeout;

		AcpClientLifecycle(AcpClientHost host, Duration timeout) {
			this.host = host;
			this.timeout = timeout;
		}

		@Override
		public void destroy() {
			host.close(timeout);
		}

	}

}
