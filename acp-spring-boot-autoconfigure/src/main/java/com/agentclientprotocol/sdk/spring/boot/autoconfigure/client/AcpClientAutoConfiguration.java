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
 * Creates the application's ACP client on the client transport bean: an {@link AcpAsyncClient}, and
 * an {@link AcpSyncClient} over that same client, so both share one connection. Inject either. It
 * applies when there is an {@code AcpClientTransport} bean, which
 * {@link AcpClientTransportAutoConfiguration} creates from {@code spring.acp.client.transport.*},
 * or the application defines itself.
 *
 * <p>The client advertises the capabilities and uses the timeouts of {@link AcpClientProperties}.
 * Every {@link AcpClientCustomizer} bean is applied to its builder, in bean order: register the
 * session-update handler, the permission handler, and the file system, terminal and elicitation
 * handlers there. Without a session-update handler of the application's own, session updates are
 * logged at DEBUG. Creating the client connects the transport (for stdio, starts the agent process)
 * when the context starts; the application then calls {@code initialize()} and opens sessions.
 *
 * <p>When the context closes, the client is closed once: pending notifications are delivered,
 * waiting at most the request timeout plus 10 seconds, then the transport is closed. An
 * {@code AcpAsyncClient} or {@code AcpSyncClient} bean of the application's own replaces the one
 * created here.
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
		return AcpClients.async(transport, properties.toSettings(), customizers.orderedStream().toList(),
				AcpClientTransportAutoConfiguration.PREFIX);
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
