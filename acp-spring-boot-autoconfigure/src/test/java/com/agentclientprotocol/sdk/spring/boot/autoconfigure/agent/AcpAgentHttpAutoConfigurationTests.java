package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.spring.boot.autoconfigure.client.AcpClientAutoConfiguration;
import com.agentclientprotocol.sdk.spring.boot.autoconfigure.client.AcpClientTransportAutoConfiguration;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.http.server.AcpHttpEndpoint;
import com.agentclientprotocol.sdk.http.webflux.AcpWebFluxHost;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.StopReason;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AcpAgentHttpAutoConfigurationTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final AutoConfigurations AGENT_AUTO_CONFIGURATIONS = AutoConfigurations.of(
			AcpAgentTransportAutoConfiguration.class, AcpAgentAutoConfiguration.class,
			AcpAgentHttpAutoConfiguration.class);

	// stdioRemainsTheDefault starts the stdio agent on the tests' empty System.in: keep
	// the
	// context open for its assertions.
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AGENT_AUTO_CONFIGURATIONS)
		.withPropertyValues("spring.acp.agent.shutdown-on-transport-end=false");

	@Test
	void listenerServesAgentOverStreamableHttpAndWebSocket() {
		this.runner.withUserConfiguration(EchoAgentConfiguration.class)
			.withPropertyValues("spring.acp.agent.transport.type=http", "spring.acp.agent.transport.http.listener.port=0")
			.run(context -> {
				assertThat(context).hasSingleBean(StreamableHttpAcpAgentTransport.class);
				int port = context.getBean(StreamableHttpAcpAgentTransport.class).getPort();
				assertThat(context).doesNotHaveBean(AcpAgentTransport.class);
				assertThat(context).doesNotHaveBean("acpAgentLifecycle");

				URI endpoint = URI.create("http://localhost:" + port + "/acp");
				assertRoundTrip(new StreamableHttpAcpClientTransport(endpoint, AcpJsonMapper.createDefault()));
				URI webSocket = URI.create("ws://localhost:" + port + "/acp");
				assertRoundTrip(new WebSocketAcpClientTransport(webSocket, AcpJsonMapper.createDefault()));
			});
	}

	@Test
	void websocketIsTheSameAsHttp() {
		this.runner.withUserConfiguration(EchoAgentConfiguration.class)
			.withPropertyValues("spring.acp.agent.transport.type=websocket",
					"spring.acp.agent.transport.http.listener.port=0")
			.run(context -> {
				int port = context.getBean(StreamableHttpAcpAgentTransport.class).getPort();
				assertThat(context).doesNotHaveBean(AcpAgentTransport.class);
				assertRoundTrip(new WebSocketAcpClientTransport(URI.create("ws://localhost:" + port + "/acp"),
						AcpJsonMapper.createDefault()));
			});
	}

	@Test
	void anExplicitStdioTypeServesNoListener() {
		this.runner.withUserConfiguration(EchoAgentConfiguration.class)
			.withPropertyValues("spring.acp.agent.transport.type=STDIO")
			.run(context -> {
				assertThat(context).doesNotHaveBean(StreamableHttpAcpAgentTransport.class);
				assertThat(context).hasBean("acpAgentLifecycle");
			});
	}

	@Test
	void anUnknownTypeFailsTheBinding() {
		this.runner.withUserConfiguration(EchoAgentConfiguration.class)
			.withPropertyValues("spring.acp.agent.transport.type=pigeon")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context).getFailure().hasStackTraceContaining("pigeon");
			});
	}

	@Test
	void autoConfiguredHttpClientTalksToTheAgent() {
		this.runner.withUserConfiguration(EchoAgentConfiguration.class)
			.withPropertyValues("spring.acp.agent.transport.type=http", "spring.acp.agent.transport.http.listener.port=0")
			.run(agentContext -> {
				int port = agentContext.getBean(StreamableHttpAcpAgentTransport.class).getPort();
				new ApplicationContextRunner()
					.withConfiguration(AutoConfigurations.of(AcpClientTransportAutoConfiguration.class,
							AcpClientAutoConfiguration.class))
					.withPropertyValues("spring.acp.client.transport.http.uri=http://localhost:" + port + "/acp")
					.run(clientContext -> {
						AcpSyncClient client = clientContext.getBean(AcpSyncClient.class);
						client.initialize();
						NewSessionResponse session = client.newSession(new NewSessionRequest("/workspace", List.of()));
						PromptResponse response = client
							.prompt(new PromptRequest(session.sessionId(), List.of(new TextContent("hello"))));
						assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
					});
			});
	}

	@Test
	void listenerUsesConfiguredPath() {
		this.runner.withUserConfiguration(EchoAgentConfiguration.class)
			.withPropertyValues("spring.acp.agent.transport.type=http", "spring.acp.agent.transport.http.listener.port=0",
					"spring.acp.agent.transport.http.path=/agents/echo")
			.run(context -> {
				int port = context.getBean(StreamableHttpAcpAgentTransport.class).getPort();
				assertRoundTrip(new StreamableHttpAcpClientTransport(
						URI.create("http://localhost:" + port + "/agents/echo"), AcpJsonMapper.createDefault()));
			});
	}

	@Test
	void userDefinedListenerOverridesAutoConfigured() {
		this.runner.withUserConfiguration(EchoAgentConfiguration.class)
			.withBean("customListener", StreamableHttpAcpAgentTransport.class,
					() -> new StreamableHttpAcpAgentTransport(0, AcpJsonMapper.createDefault(),
							AcpAgentFactory.sync(transport -> {
								throw new IllegalStateException("not used");
							})))
			.withPropertyValues("spring.acp.agent.transport.type=http")
			.run(context -> {
				assertThat(context).hasSingleBean(StreamableHttpAcpAgentTransport.class);
				assertThat(context).hasBean("customListener");
				// Started by the autoconfigured lifecycle: the ephemeral port is bound
				assertThat(context.getBean(StreamableHttpAcpAgentTransport.class).getPort()).isPositive();
			});
	}

	@Test
	void userDefinedServletRegistrationOverridesAutoConfigured() {
		new WebApplicationContextRunner().withConfiguration(AGENT_AUTO_CONFIGURATIONS)
			.withUserConfiguration(EchoAgentConfiguration.class)
			.withBean("acpServletRegistration", ServletRegistrationBean.class, () -> new ServletRegistrationBean<>(
					new StreamableHttpAcpServlet(AcpJsonMapper.createDefault(), context -> {
						throw new IllegalStateException("not used");
					}), "/custom"))
			.withPropertyValues("spring.acp.agent.transport.type=http")
			.run(context -> assertThat(
					context.getBean("acpServletRegistration", ServletRegistrationBean.class).getUrlMappings())
				.containsExactly("/custom"));
	}

	@Test
	void stdioRemainsTheDefault() {
		this.runner.withUserConfiguration(EchoAgentConfiguration.class).run(context -> {
			assertThat(context).doesNotHaveBean(StreamableHttpAcpAgentTransport.class);
			assertThat(context).hasSingleBean(AcpAgentTransport.class);
			assertThat(context).hasBean("acpAgentLifecycle");
		});
	}

	@Test
	void backsOffWithoutAgentBean() {
		this.runner.withPropertyValues("spring.acp.agent.transport.type=http").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(AcpAgentFactory.class);
			assertThat(context).doesNotHaveBean(StreamableHttpAcpAgentTransport.class);
		});
	}

	@Test
	void backsOffWhenAgentDisabled() {
		this.runner.withUserConfiguration(EchoAgentConfiguration.class)
			.withPropertyValues("spring.acp.agent.enabled=false", "spring.acp.agent.transport.type=http")
			.run(context -> assertThat(context).doesNotHaveBean(StreamableHttpAcpAgentTransport.class));
	}

	@Test
	void reactiveWebApplicationRoutesThePathToTheEndpoint() {
		new ReactiveWebApplicationContextRunner().withConfiguration(AGENT_AUTO_CONFIGURATIONS)
			.withUserConfiguration(EchoAgentConfiguration.class)
			.withPropertyValues("spring.acp.agent.transport.type=http", "spring.acp.agent.transport.http.path=/agent")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(AcpHttpEndpoint.class);
				assertThat(context).hasBean("acpRouterFunction");
				assertThat(context).hasSingleBean(AcpAgentHttpAutoConfiguration.AcpEndpointLifecycle.class);
				assertThat(context).doesNotHaveBean(StreamableHttpAcpAgentTransport.class);
				assertThat(context).doesNotHaveBean(ServletRegistrationBean.class);
			});
	}

	@Test
	void reactiveWebApplicationNeedsOnlyTheWebFluxHost() {
		new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(AcpAgentTransportAutoConfiguration.class,
					AcpAgentAutoConfiguration.class, AcpAgentHttpAutoConfiguration.class))
			.withClassLoader(new FilteredClassLoader(StreamableHttpAcpServlet.class, StreamableHttpAcpAgentTransport.class))
			.withUserConfiguration(EchoAgentConfiguration.class)
			.withPropertyValues("spring.acp.agent.transport.type=websocket")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasBean("acpRouterFunction");
			});
	}

	@Test
	void reactiveWebApplicationWithoutTheWebFluxHostFailsTheStartup() {
		new ReactiveWebApplicationContextRunner().withConfiguration(AGENT_AUTO_CONFIGURATIONS)
			.withClassLoader(new FilteredClassLoader(AcpWebFluxHost.class))
			.withUserConfiguration(EchoAgentConfiguration.class)
			.withPropertyValues("spring.acp.agent.transport.type=http")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("The ACP HTTP transport in a reactive (WebFlux) web application needs "
							+ "acp-http-webflux on the classpath");
			});
	}

	@Test
	void userDefinedRouterFunctionOverridesAutoConfigured() {
		new ReactiveWebApplicationContextRunner().withConfiguration(AGENT_AUTO_CONFIGURATIONS)
			.withUserConfiguration(EchoAgentConfiguration.class)
			.withBean("acpRouterFunction", RouterFunction.class,
					() -> RouterFunctions.route(RequestPredicates.path("/mine"), request -> ServerResponse.ok().build()))
			.withPropertyValues("spring.acp.agent.transport.type=http")
			.run(context -> assertThat(context.getBean("acpRouterFunction").toString()).contains("/mine"));
	}

	@Test
	void servletWebApplicationMountsServletInsteadOfListener() {
		new WebApplicationContextRunner().withConfiguration(AGENT_AUTO_CONFIGURATIONS)
			.withUserConfiguration(EchoAgentConfiguration.class)
			.withPropertyValues("spring.acp.agent.transport.type=http",
					"spring.acp.agent.transport.http.path=/agents/echo")
			.run(context -> {
				assertThat(context).doesNotHaveBean(StreamableHttpAcpAgentTransport.class);
				ServletRegistrationBean<?> registration = context.getBean("acpServletRegistration",
						ServletRegistrationBean.class);
				assertThat(registration.getServlet()).isInstanceOf(StreamableHttpAcpServlet.class);
				assertThat(registration.getUrlMappings()).containsExactly("/agents/echo");
				assertThat(registration.isAsyncSupported()).isTrue();
			});
	}

	@Test
	void mapsLimitsToTransportOptions() {
		AcpAgentProperties properties = new AcpAgentProperties();
		AcpAgentProperties.AgentHttpProperties http = properties.getTransport().getHttp();
		assertThat(properties.toSettings().toOptions(true)).isEqualTo(StreamableHttpAcpAgentTransportOptions.defaults());

		http.setMaxPostBodySize(DataSize.ofMegabytes(1));
		http.setKeepAliveInterval(Duration.ZERO);
		http.setMailboxCapacity(10);
		http.setMaxPendingSseEvents(11);
		http.setMaxWebSocketPendingFrames(12);
		http.setMaxProvisionalSessions(13);
		http.getListener().setMaxConcurrentStreamsPerConnection(14);
		http.setShutdownTimeout(Duration.ofSeconds(2));
		http.setWebSocketIdleTimeout(Duration.ofMinutes(7));
		http.setInitializeTimeout(Duration.ofSeconds(8));
		assertThat(properties.toSettings().toOptions(true))
			.isEqualTo(StreamableHttpAcpAgentTransportOptions.builder()
				.maxPostBodyBytes(1024 * 1024)
				.mailboxCapacity(10)
				.maxPendingSseEvents(11)
				.maxWebSocketPendingFrames(12)
				.maxProvisionalSessions(13)
				.keepAliveInterval(Duration.ZERO)
				.maxConcurrentStreamsPerConnection(14)
				.shutdownTimeout(Duration.ofSeconds(2))
				.webSocketIdleTimeout(Duration.ofMinutes(7))
				.initializeTimeout(Duration.ofSeconds(8))
				.build());
	}

	static void assertRoundTrip(AcpClientTransport transport) {
		List<String> messages = new CopyOnWriteArrayList<>();
		AcpSyncClient client = AcpClient.sync(transport).requestTimeout(TIMEOUT).sessionUpdateHandler(notification -> {
			if (notification.update() instanceof AgentMessageChunk chunk
					&& chunk.content() instanceof TextContent text) {
				messages.add(text.text());
			}
		}).build();
		try {
			client.initialize();
			NewSessionResponse session = client.newSession(new NewSessionRequest("/workspace", List.of()));
			PromptResponse response = client
				.prompt(new PromptRequest(session.sessionId(), List.of(new TextContent("hello"))));
			assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
			await().atMost(TIMEOUT).untilAsserted(() -> assertThat(messages).containsExactly("echo: hello"));
		}
		finally {
			client.closeGracefully();
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class EchoAgentConfiguration {

		@Bean
		EchoAgent echoAgent() {
			return new EchoAgent();
		}

	}

	@AcpAgent(name = "echo-agent", version = "1.0")
	static class EchoAgent {

		@NewSession
		public NewSessionResponse newSession(NewSessionRequest request) {
			return new NewSessionResponse(UUID.randomUUID().toString(), null, null);
		}

		@Prompt
		public PromptResponse prompt(PromptRequest request, SyncPromptContext context) {
			context.sendMessage("echo: " + ((TextContent) request.prompt().get(0)).text());
			return PromptResponse.endTurn();
		}

	}

}
