package com.agentclientprotocol.autoconfigure.agent;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AcpAgentTransportAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AcpAgentTransportAutoConfiguration.class));

	@Test
	void createsStdioTransportByDefault() {
		this.runner.run(context -> {
			assertThat(context).hasSingleBean(AcpAgentTransport.class);
			assertThat(context.getBean(AcpAgentTransport.class)).isInstanceOf(StdioAcpAgentTransport.class);
		});
	}

	@Test
	void createsStdioTransportWithExplicitType() {
		this.runner.withPropertyValues("spring.acp.agent.transport.type=stdio").run(context -> {
			assertThat(context).hasSingleBean(AcpAgentTransport.class);
			assertThat(context.getBean(AcpAgentTransport.class)).isInstanceOf(StdioAcpAgentTransport.class);
		});
	}

	@Test
	void noTransportWhenDisabled() {
		this.runner.withPropertyValues("spring.acp.agent.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(AcpAgentTransport.class));
	}

	@Test
	void userProvidedTransportTakesPrecedence() {
		this.runner.withUserConfiguration(CustomTransportConfiguration.class).run(context -> {
			assertThat(context).hasSingleBean(AcpAgentTransport.class);
			assertThat(context.getBean(AcpAgentTransport.class)).isInstanceOf(StubAgentTransport.class);
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomTransportConfiguration {

		@Bean
		AcpAgentTransport customTransport() {
			return new StubAgentTransport();
		}

	}

	static class StubAgentTransport implements AcpAgentTransport {

		@Override
		public reactor.core.publisher.Mono<Void> start(
				java.util.function.Function<reactor.core.publisher.Mono<com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage>, reactor.core.publisher.Mono<com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage>> handler) {
			return reactor.core.publisher.Mono.empty();
		}

		@Override
		public reactor.core.publisher.Mono<Void> awaitTermination() {
			return reactor.core.publisher.Mono.empty();
		}

		@Override
		public reactor.core.publisher.Mono<Void> sendMessage(
				com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage message) {
			return reactor.core.publisher.Mono.empty();
		}

		@Override
		public reactor.core.publisher.Mono<Void> closeGracefully() {
			return reactor.core.publisher.Mono.empty();
		}

		@Override
		public <T> T unmarshalFrom(Object data, io.modelcontextprotocol.json.TypeRef<T> typeRef) {
			return null;
		}

	}

}
