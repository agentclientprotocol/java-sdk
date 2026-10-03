package com.agentclientprotocol.sdk.spring.boot.autoconfigure.client;

import java.time.Duration;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
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

	private static final Logger logger = LoggerFactory.getLogger(AcpClientAutoConfiguration.class);

	// destroyMethod = "": AcpClientLifecycle closes the client (and with it the
	// transport)
	// once; Spring's inferred close() on these beans and the transport bean closed it
	// three times.
	@Bean(destroyMethod = "")
	@ConditionalOnMissingBean
	AcpAsyncClient acpAsyncClient(AcpClientTransport transport, AcpClientProperties properties,
			ObjectProvider<AcpClientCustomizer> customizers) {
		var caps = properties.getCapabilities();
		var clientCapabilities = new AcpSchema.ClientCapabilities(
				new AcpSchema.FileSystemCapability(caps.isReadTextFile(), caps.isWriteTextFile()), caps.isTerminal());
		var spec = AcpClient.async(transport)
			.requestTimeout(properties.getRequestTimeout())
			.clientCapabilities(clientCapabilities)
			// Session updates always have a consumer, so the SDK does not warn about an
			// unhandled session/update; an application adds its own through a customizer.
			.sessionUpdateConsumer(AcpClientAutoConfiguration::logSessionUpdate);
		Duration promptTimeout = properties.getPromptTimeout();
		if (promptTimeout != null) {
			spec.promptTimeout(promptTimeout);
		}
		customizers.orderedStream().forEach(customizer -> customizer.customize(spec));
		return spec.build();
	}

	private static Mono<Void> logSessionUpdate(AcpSchema.SessionNotification notification) {
		logger.debug("Session update for {}: {}", notification.sessionId(), notification.update());
		return Mono.empty();
	}

	/**
	 * The sync client is a facade over the async client: one session, one transport
	 * connection. Building it from the transport instead would call {@code connect()} a
	 * second time on the same transport instance, which the SDK refuses.
	 */
	@Bean(destroyMethod = "")
	@ConditionalOnMissingBean
	AcpSyncClient acpSyncClient(AcpAsyncClient asyncClient) {
		return new AcpSyncClient(asyncClient);
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
