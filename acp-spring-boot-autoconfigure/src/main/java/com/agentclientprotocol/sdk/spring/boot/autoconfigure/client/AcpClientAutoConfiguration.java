package com.agentclientprotocol.sdk.spring.boot.autoconfigure.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
			// unhandled session/update. This one only logs at DEBUG; a consumer an
			// application adds through a customizer replaces it.
			.defaultSessionUpdateConsumer(AcpClientAutoConfiguration::logSessionUpdate);
		Duration promptTimeout = properties.getPromptTimeout();
		if (promptTimeout != null) {
			spec.promptTimeout(promptTimeout);
		}
		customizers.orderedStream().forEach(customizer -> customizer.customize(spec));
		try {
			return spec.build();
		}
		catch (IllegalStateException ex) {
			throw namingTheSettings(ex, "spring.acp.client", caps.isReadTextFile(), caps.isWriteTextFile(),
					caps.isTerminal());
		}
	}

	/**
	 * Adds to the SDK's error about an advertised capability without its handler which of the
	 * capability properties it came from: register the handler in an {@link AcpClientCustomizer}, or
	 * stop advertising it.
	 */
	static IllegalStateException namingTheSettings(IllegalStateException error, String prefix, boolean readTextFile,
			boolean writeTextFile, boolean terminal) {
		String message = String.valueOf(error.getMessage());
		List<String> settings = new ArrayList<>();
		if (readTextFile && message.contains("fs.readTextFile needs")) {
			settings.add(prefix + ".capabilities.read-text-file=true");
		}
		if (writeTextFile && message.contains("fs.writeTextFile needs")) {
			settings.add(prefix + ".capabilities.write-text-file=true");
		}
		if (terminal && message.contains("terminal needs")) {
			settings.add(prefix + ".capabilities.terminal=true");
		}
		if (settings.isEmpty()) {
			return error;
		}
		return new IllegalStateException(message + ". The capabilities come from " + String.join(", ", settings)
				+ ": register the handlers in an AcpClientCustomizer, or set the properties to false", error);
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
