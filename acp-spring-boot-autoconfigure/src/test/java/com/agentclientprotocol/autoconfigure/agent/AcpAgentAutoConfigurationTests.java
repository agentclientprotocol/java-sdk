package com.agentclientprotocol.autoconfigure.agent;

import java.util.UUID;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AcpAgentAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(AcpAgentTransportAutoConfiguration.class, AcpAgentAutoConfiguration.class));

	@Test
	void noAgentLifecycleWithoutAgentBean() {
		this.runner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(BeanCreationException.class)
				.hasMessageContaining("No @AcpAgent-annotated bean found");
		});
	}

	@Test
	void createsAgentLifecycleWithAgentBean() {
		this.runner.withUserConfiguration(SingleAgentConfiguration.class)
			.run(context -> assertThat(context).hasBean("acpAgentLifecycle"));
	}

	@Test
	void failsWithMultipleAgentBeans() {
		this.runner.withUserConfiguration(MultipleAgentConfiguration.class).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(BeanCreationException.class)
				.hasMessageContaining("Found 2 @AcpAgent-annotated beans");
		});
	}

	@Test
	void respectsCustomRequestTimeout() {
		this.runner.withUserConfiguration(SingleAgentConfiguration.class)
			.withPropertyValues("spring.acp.agent.request-timeout=120s")
			.run(context -> {
				assertThat(context).hasBean("acpAgentLifecycle");
				AcpAgentProperties props = context.getBean(AcpAgentProperties.class);
				assertThat(props.getRequestTimeout()).hasSeconds(120);
			});
	}

	@Test
	void noAgentWhenDisabled() {
		this.runner.withUserConfiguration(SingleAgentConfiguration.class)
			.withPropertyValues("spring.acp.agent.enabled=false")
			.run(context -> {
				assertThat(context).doesNotHaveBean("acpAgentLifecycle");
				assertThat(context).doesNotHaveBean(AcpAgentTransport.class);
			});
	}

	@Test
	void noAgentWithoutTransportBean() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AcpAgentAutoConfiguration.class))
			.withUserConfiguration(SingleAgentConfiguration.class)
			.run(context -> assertThat(context).doesNotHaveBean("acpAgentLifecycle"));
	}

	@Test
	void wiresInterceptors() {
		this.runner.withUserConfiguration(SingleAgentConfiguration.class, InterceptorConfiguration.class)
			.run(context -> {
				assertThat(context).hasBean("acpAgentLifecycle");
				assertThat(context).hasSingleBean(AcpInterceptor.class);
			});
	}

	@Test
	void agentLifecycleImplementsSmartLifecycle() {
		this.runner.withUserConfiguration(SingleAgentConfiguration.class).run(context -> {
			Object lifecycle = context.getBean("acpAgentLifecycle");
			assertThat(lifecycle).isInstanceOf(org.springframework.context.SmartLifecycle.class);
		});
	}

	@Test
	void usesInMemoryTransportWhenProvided() {
		this.runner.withUserConfiguration(SingleAgentWithInMemoryTransportConfiguration.class).run(context -> {
			assertThat(context).hasBean("acpAgentLifecycle");
			assertThat(context).hasSingleBean(AcpAgentTransport.class);
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class SingleAgentConfiguration {

		@Bean
		TestAgent testAgent() {
			return new TestAgent();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class MultipleAgentConfiguration {

		@Bean
		TestAgent testAgent1() {
			return new TestAgent();
		}

		@Bean
		SecondTestAgent testAgent2() {
			return new SecondTestAgent();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class InterceptorConfiguration {

		@Bean
		AcpInterceptor testInterceptor() {
			return new AcpInterceptor() {
			};
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class SingleAgentWithInMemoryTransportConfiguration {

		@Bean
		TestAgent testAgent() {
			return new TestAgent();
		}

		@Bean
		AcpAgentTransport acpAgentTransport() {
			return InMemoryTransportPair.create().agentTransport();
		}

	}

	@AcpAgent(name = "test-agent", version = "1.0")
	static class TestAgent {

		@Initialize
		public InitializeResponse initialize(InitializeRequest request) {
			return InitializeResponse.ok();
		}

		@NewSession
		public NewSessionResponse newSession(NewSessionRequest request) {
			return new NewSessionResponse(UUID.randomUUID().toString(), null, null);
		}

		@Prompt
		public PromptResponse prompt(PromptRequest request, SyncPromptContext context) {
			return PromptResponse.endTurn();
		}

	}

	@AcpAgent(name = "second-agent", version = "1.0")
	static class SecondTestAgent {

		@Initialize
		public InitializeResponse initialize(InitializeRequest request) {
			return InitializeResponse.ok();
		}

		@NewSession
		public NewSessionResponse newSession(NewSessionRequest request) {
			return new NewSessionResponse(UUID.randomUUID().toString(), null, null);
		}

		@Prompt
		public PromptResponse prompt(PromptRequest request, SyncPromptContext context) {
			return PromptResponse.endTurn();
		}

	}

}
