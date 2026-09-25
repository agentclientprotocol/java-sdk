package com.agentclientprotocol.autoconfigure.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AcpClientAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(AcpClientTransportAutoConfiguration.class, AcpClientAutoConfiguration.class));

	@Test
	void noClientBeansWithoutTransport() {
		this.runner.run(context -> {
			assertThat(context).doesNotHaveBean(AcpSyncClient.class);
			assertThat(context).doesNotHaveBean(AcpAsyncClient.class);
		});
	}

	@Test
	void createsClientBeansWhenTransportPresent() {
		this.runner.withUserConfiguration(InMemoryTransportConfiguration.class).run(context -> {
			assertThat(context).hasSingleBean(AcpSyncClient.class);
			assertThat(context).hasSingleBean(AcpAsyncClient.class);
		});
	}

	@Test
	void contextStartConnectsTheTransportExactlyOnce() {
		this.runner.withUserConfiguration(CountingTransportConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(AcpAsyncClient.class);
			assertThat(context).hasSingleBean(AcpSyncClient.class);
			assertThat(context.getBean(CountingClientTransport.class).connects.get()).isEqualTo(1);
		});
	}

	@Test
	void createsLifecycleBeanWhenTransportPresent() {
		this.runner.withUserConfiguration(InMemoryTransportConfiguration.class)
			.run(context -> assertThat(context).hasBean("acpClientLifecycle"));
	}

	@Test
	void respectsCustomRequestTimeout() {
		this.runner.withUserConfiguration(InMemoryTransportConfiguration.class)
			.withPropertyValues("spring.acp.client.request-timeout=120s")
			.run(context -> {
				assertThat(context).hasSingleBean(AcpSyncClient.class);
				assertThat(context).hasSingleBean(AcpAsyncClient.class);
			});
	}

	@Test
	void defaultCapabilities() {
		this.runner.withUserConfiguration(InMemoryTransportConfiguration.class).run(context -> {
			assertThat(context).hasSingleBean(AcpSyncClient.class);
			// Verify defaults are used (readTextFile=true, writeTextFile=true,
			// terminal=false)
			AcpClientProperties props = context.getBean(AcpClientProperties.class);
			assertThat(props.getCapabilities().isReadTextFile()).isTrue();
			assertThat(props.getCapabilities().isWriteTextFile()).isTrue();
			assertThat(props.getCapabilities().isTerminal()).isFalse();
		});
	}

	@Test
	void customCapabilities() {
		this.runner.withUserConfiguration(InMemoryTransportConfiguration.class)
			.withPropertyValues("spring.acp.client.capabilities.read-text-file=false",
					"spring.acp.client.capabilities.write-text-file=false",
					"spring.acp.client.capabilities.terminal=true")
			.run(context -> {
				AcpClientProperties props = context.getBean(AcpClientProperties.class);
				assertThat(props.getCapabilities().isReadTextFile()).isFalse();
				assertThat(props.getCapabilities().isWriteTextFile()).isFalse();
				assertThat(props.getCapabilities().isTerminal()).isTrue();
			});
	}

	@Test
	void userProvidedSyncClientTakesPrecedence() {
		this.runner.withUserConfiguration(InMemoryTransportConfiguration.class, CustomSyncClientConfiguration.class)
			.run(context -> {
				assertThat(context).hasSingleBean(AcpSyncClient.class);
				assertThat(context.getBean("customSyncClient")).isNotNull();
			});
	}

	@Test
	void userProvidedAsyncClientTakesPrecedence() {
		this.runner.withUserConfiguration(InMemoryTransportConfiguration.class, CustomAsyncClientConfiguration.class)
			.run(context -> {
				assertThat(context).hasSingleBean(AcpAsyncClient.class);
				assertThat(context.getBean("customAsyncClient")).isNotNull();
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class InMemoryTransportConfiguration {

		@Bean
		AcpClientTransport acpClientTransport() {
			return InMemoryTransportPair.create().clientTransport();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomSyncClientConfiguration {

		@Bean
		AcpSyncClient customSyncClient(AcpAsyncClient asyncClient) {
			return new AcpSyncClient(asyncClient);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomAsyncClientConfiguration {

		@Bean
		AcpAsyncClient customAsyncClient(AcpClientTransport transport) {
			return com.agentclientprotocol.sdk.client.AcpClient.async(transport).build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CountingTransportConfiguration {

		@Bean
		CountingClientTransport acpClientTransport() {
			return new CountingClientTransport(InMemoryTransportPair.create().clientTransport());
		}

	}

	/**
	 * Counts {@code connect()} calls: each client session connects its transport once, so
	 * the count is the number of sessions built on it.
	 */
	static class CountingClientTransport implements AcpClientTransport {

		final AtomicInteger connects = new AtomicInteger();

		private final AcpClientTransport delegate;

		CountingClientTransport(AcpClientTransport delegate) {
			this.delegate = delegate;
		}

		@Override
		public Mono<Void> connect(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
			this.connects.incrementAndGet();
			return this.delegate.connect(handler);
		}

		@Override
		public void setExceptionHandler(Consumer<Throwable> handler) {
			this.delegate.setExceptionHandler(handler);
		}

		@Override
		public Mono<Void> awaitTermination() {
			return this.delegate.awaitTermination();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return this.delegate.closeGracefully();
		}

		@Override
		public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
			return this.delegate.sendMessage(message);
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return this.delegate.unmarshalFrom(data, typeRef);
		}

		@Override
		public List<Integer> protocolVersions() {
			return this.delegate.protocolVersions();
		}

	}

}
