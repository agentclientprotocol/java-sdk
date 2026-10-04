package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.StopReason;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;

import org.junit.jupiter.api.Test;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AcpAgentAutoConfigurationTests {

	private static final AutoConfigurations AGENT_AUTO_CONFIGURATIONS = AutoConfigurations
		.of(AcpAgentTransportAutoConfiguration.class, AcpAgentAutoConfiguration.class);

	// The default stdio agent reads the tests' empty System.in and ends at once: keep the
	// context open for the assertions. The shutdown itself is tested on its own
	// transport.
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AGENT_AUTO_CONFIGURATIONS)
		.withPropertyValues("spring.acp.agent.shutdown-on-transport-end=false");

	@Test
	void noAgentLifecycleWithoutAgentBean() {
		// Client-only app: the agent autoconfiguration must back off, not fail the
		// context.
		this.runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean("acpAgentLifecycle");
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
				.hasMessageContaining("Found 2 @AcpAgent beans");
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
	void disabledServesNothingOnTheApplicationsOwnTransport() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(AcpAgentTransportAutoConfiguration.class,
					AcpAgentAutoConfiguration.class, AcpAgentHttpAutoConfiguration.class))
			.withUserConfiguration(SingleAgentWithInMemoryTransportConfiguration.class)
			.withPropertyValues("spring.acp.agent.enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(AcpAgentFactory.class);
				assertThat(context).doesNotHaveBean("acpAgentLifecycle");
			});
	}

	@Test
	void disabledToleratesTwoAgentBeans() {
		this.runner.withUserConfiguration(MultipleAgentConfiguration.class)
			.withPropertyValues("spring.acp.agent.enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(AcpAgentFactory.class);
				assertThat(context).doesNotHaveBean("acpAgentLifecycle");
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

	@Test
	void closesContextWhenTransportEnds() throws IOException {
		PipedOutputStream clientSide = new PipedOutputStream();
		PipedInputStream agentInput = new PipedInputStream(clientSide);
		new ApplicationContextRunner().withConfiguration(AGENT_AUTO_CONFIGURATIONS)
			.withUserConfiguration(SingleAgentConfiguration.class)
			.withBean(AcpAgentTransport.class,
					() -> new StdioAcpAgentTransport(AcpJsonMapper.createDefault(), agentInput,
							new ByteArrayOutputStream()))
			.run(context -> {
				ConfigurableApplicationContext source = (ConfigurableApplicationContext) context
					.getSourceApplicationContext();
				assertThat(source.isActive()).isTrue();
				clientSide.close();
				await().atMost(Duration.ofSeconds(10)).until(() -> !source.isActive());
			});
	}

	@Test
	void keepsContextWhenShutdownOnTransportEndDisabled() throws IOException {
		PipedOutputStream clientSide = new PipedOutputStream();
		PipedInputStream agentInput = new PipedInputStream(clientSide);
		StdioAcpAgentTransport transport = new StdioAcpAgentTransport(AcpJsonMapper.createDefault(), agentInput,
				new ByteArrayOutputStream());
		this.runner.withUserConfiguration(SingleAgentConfiguration.class)
			.withBean(AcpAgentTransport.class, () -> transport)
			.run(context -> {
				clientSide.close();
				transport.awaitTermination().block(Duration.ofSeconds(10));
				ConfigurableApplicationContext source = (ConfigurableApplicationContext) context
					.getSourceApplicationContext();
				await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(source::isActive);
			});
	}

	@Test
	void servesACglibProxiedAgentBean() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AtomicInteger intercepted = new AtomicInteger();
		this.runner.withUserConfiguration(SingleAgentConfiguration.class)
			.withBean(AcpAgentTransport.class, pair::agentTransport)
			.withBean(ProxyingPostProcessor.class, () -> new ProxyingPostProcessor(intercepted))
			.run(context -> {
				assertThat(AopUtils.isCglibProxy(context.getBean("testAgent"))).isTrue();
				AcpSyncClient client = AcpClient.sync(pair.clientTransport()).build();
				try {
					client.initialize();
					NewSessionResponse session = client.newSession(new NewSessionRequest("/workspace", List.of()));
					PromptResponse response = client
						.prompt(new PromptRequest(session.sessionId(), List.of(new TextContent("hello"))));
					assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
					// The handlers were invoked through the proxy, so its advice ran
					assertThat(intercepted).hasValueGreaterThanOrEqualTo(3);
				}
				finally {
					client.closeGracefully();
				}
			});
	}

	/** Wraps the agent bean in a CGLIB proxy, as Spring AOP does for an advised class. */
	static class ProxyingPostProcessor implements BeanPostProcessor {

		private final AtomicInteger intercepted;

		ProxyingPostProcessor(AtomicInteger intercepted) {
			this.intercepted = intercepted;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (!(bean instanceof TestAgent)) {
				return bean;
			}
			ProxyFactory proxyFactory = new ProxyFactory(bean);
			proxyFactory.setProxyTargetClass(true);
			proxyFactory.addAdvice((MethodInterceptor) invocation -> {
				this.intercepted.incrementAndGet();
				return invocation.proceed();
			});
			return proxyFactory.getProxy();
		}

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
