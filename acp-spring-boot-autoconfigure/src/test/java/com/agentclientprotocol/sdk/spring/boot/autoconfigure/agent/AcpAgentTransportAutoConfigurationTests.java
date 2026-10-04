package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AcpAgentTransportAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AcpAgentTransportAutoConfiguration.class))
		.withBean(TestAgent.class);

	@Test
	void httpTypeWithoutHttpModuleFailsWithAClearMessage() {
		this.runner.withClassLoader(new FilteredClassLoader(StreamableHttpAcpServlet.class))
			.withPropertyValues("spring.acp.agent.transport.type=http")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("acp-http-servlet").hasMessageContaining("acp-streamable-http-jetty");
			});
	}

	@Test
	void stdioTransportBeanHasNoDestroyMethod() {
		// The agent lifecycle closes the transport; an inferred close() would close it
		// again.
		this.runner.run(context -> assertThat(
				context.getBeanFactory().getBeanDefinition("acpAgentTransport").getDestroyMethodName())
			.isEmpty());
	}

	@Test
	void noTransportInClientOnlyApplication() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(AcpAgentTransportAutoConfiguration.class))
			.run(context -> assertThat(context).doesNotHaveBean(AcpAgentTransport.class));
	}

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

	@com.agentclientprotocol.sdk.annotation.AcpAgent(name = "test-agent", version = "1.0")
	static class TestAgent {

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
		public <T> T unmarshalFrom(Object data, com.agentclientprotocol.sdk.json.TypeRef<T> typeRef) {
			return null;
		}

	}

}
