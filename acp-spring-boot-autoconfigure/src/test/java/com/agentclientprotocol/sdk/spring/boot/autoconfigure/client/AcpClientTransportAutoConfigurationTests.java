package com.agentclientprotocol.sdk.spring.boot.autoconfigure.client;

import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AcpClientTransportAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AcpClientTransportAutoConfiguration.class));

	@Test
	void httpTransportAutoDetectedFromUri() {
		this.runner.withPropertyValues("spring.acp.client.transport.http.uri=http://localhost:8080/acp")
			.run(context -> assertThat(context.getBean(AcpClientTransport.class))
				.isInstanceOf(StreamableHttpAcpClientTransport.class));
	}

	@Test
	void httpTransportWithExplicitType() {
		this.runner
			.withPropertyValues("spring.acp.client.transport.type=http",
					"spring.acp.client.transport.http.uri=http://localhost:8080/acp")
			.run(context -> assertThat(context.getBean(AcpClientTransport.class))
				.isInstanceOf(StreamableHttpAcpClientTransport.class));
	}

	@Test
	void httpTypeWithoutUriFailsWithAClearMessage() {
		this.runner.withPropertyValues("spring.acp.client.transport.type=http").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("requires spring.acp.client.transport.http.uri");
		});
	}

	@Test
	void transportBeanHasNoDestroyMethod() {
		// The client lifecycle closes the transport; an inferred close() would close it
		// again.
		this.runner.withPropertyValues("spring.acp.client.transport.http.uri=http://localhost:8080/acp")
			.run(context -> assertThat(
					context.getBeanFactory().getBeanDefinition("acpClientTransport").getDestroyMethodName())
				.isEmpty());
	}

	@Test
	void noTransportWithoutProperties() {
		this.runner.run(context -> assertThat(context).doesNotHaveBean(AcpClientTransport.class));
	}

	@Test
	void stdioTransportWithExplicitType() {
		this.runner
			.withPropertyValues("spring.acp.client.transport.type=stdio",
					"spring.acp.client.transport.stdio.command=echo")
			.run(context -> {
				assertThat(context).hasSingleBean(AcpClientTransport.class);
				assertThat(context.getBean(AcpClientTransport.class)).isInstanceOf(StdioAcpClientTransport.class);
			});
	}

	@Test
	void websocketTransportWithExplicitType() {
		this.runner
			.withPropertyValues("spring.acp.client.transport.type=websocket",
					"spring.acp.client.transport.websocket.uri=ws://localhost:8080/acp")
			.run(context -> {
				assertThat(context).hasSingleBean(AcpClientTransport.class);
				assertThat(context.getBean(AcpClientTransport.class)).isInstanceOf(WebSocketAcpClientTransport.class);
			});
	}

	@Test
	void stdioTransportAutoDetectedFromCommand() {
		this.runner.withPropertyValues("spring.acp.client.transport.stdio.command=python").run(context -> {
			assertThat(context).hasSingleBean(AcpClientTransport.class);
			assertThat(context.getBean(AcpClientTransport.class)).isInstanceOf(StdioAcpClientTransport.class);
		});
	}

	@Test
	void websocketTransportAutoDetectedFromUri() {
		this.runner.withPropertyValues("spring.acp.client.transport.websocket.uri=ws://localhost:9090/acp")
			.run(context -> {
				assertThat(context).hasSingleBean(AcpClientTransport.class);
				assertThat(context.getBean(AcpClientTransport.class)).isInstanceOf(WebSocketAcpClientTransport.class);
			});
	}

	@Test
	void stdioTransportWithArgs() {
		this.runner
			.withPropertyValues("spring.acp.client.transport.stdio.command=java",
					"spring.acp.client.transport.stdio.args=-jar,agent.jar")
			.run(context -> assertThat(context).hasSingleBean(AcpClientTransport.class));
	}

	@Test
	void stdioTransportWithEnv() {
		this.runner
			.withPropertyValues("spring.acp.client.transport.stdio.command=node",
					"spring.acp.client.transport.stdio.env.NODE_ENV=production")
			.run(context -> assertThat(context).hasSingleBean(AcpClientTransport.class));
	}

	@Test
	void explicitTypeTakesPrecedenceOverAutoDetect() {
		this.runner
			.withPropertyValues("spring.acp.client.transport.type=stdio",
					"spring.acp.client.transport.stdio.command=echo",
					"spring.acp.client.transport.websocket.uri=ws://localhost:8080/acp")
			.run(context -> {
				assertThat(context).hasSingleBean(AcpClientTransport.class);
				assertThat(context.getBean(AcpClientTransport.class)).isInstanceOf(StdioAcpClientTransport.class);
			});
	}

	@Test
	void userProvidedTransportBeanTakesPrecedence() {
		this.runner.withUserConfiguration(CustomTransportConfiguration.class)
			.withPropertyValues("spring.acp.client.transport.stdio.command=echo")
			.run(context -> {
				assertThat(context).hasSingleBean(AcpClientTransport.class);
				assertThat(context.getBean(AcpClientTransport.class)).isInstanceOf(StubTransport.class);
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomTransportConfiguration {

		@Bean
		AcpClientTransport customTransport() {
			return new StubTransport();
		}

	}

	static class StubTransport implements AcpClientTransport {

		@Override
		public reactor.core.publisher.Mono<Void> connect(
				java.util.function.Function<reactor.core.publisher.Mono<com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage>, reactor.core.publisher.Mono<com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage>> handler) {
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
