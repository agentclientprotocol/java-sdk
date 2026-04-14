package com.agentclientprotocol.autoconfigure.client;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;

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
		AcpSyncClient customSyncClient(AcpClientTransport transport) {
			return com.agentclientprotocol.sdk.client.AcpClient.sync(transport).build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomAsyncClientConfiguration {

		@Bean
		AcpAsyncClient customAsyncClient(AcpClientTransport transport) {
			return com.agentclientprotocol.sdk.client.AcpClient.async(transport).build();
		}

	}

}
