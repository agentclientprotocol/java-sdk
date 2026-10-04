package com.agentclientprotocol.sdk.spring.boot.autoconfigure.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
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
	void respectsCustomPromptTimeout() {
		this.runner.withUserConfiguration(InMemoryTransportConfiguration.class)
			.withPropertyValues("spring.acp.client.prompt-timeout=10m")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(AcpAsyncClient.class);
			});
	}

	@Test
	void defaultCapabilities() {
		this.runner.withUserConfiguration(InMemoryTransportConfiguration.class).run(context -> {
			assertThat(context).hasSingleBean(AcpSyncClient.class);
			// No capability is advertised by default: the autoconfiguration registers no
			// file system or terminal handler
			AcpClientProperties props = context.getBean(AcpClientProperties.class);
			assertThat(props.getCapabilities().isReadTextFile()).isFalse();
			assertThat(props.getCapabilities().isWriteTextFile()).isFalse();
			assertThat(props.getCapabilities().isTerminal()).isFalse();
		});
	}

	@Test
	void capabilitiesFromPropertiesWithTheirHandlersBuild() {
		this.runner.withUserConfiguration(InMemoryTransportConfiguration.class)
			.withPropertyValues("spring.acp.client.capabilities.read-text-file=true",
					"spring.acp.client.capabilities.write-text-file=true",
					"spring.acp.client.capabilities.terminal=true")
			.withBean(AcpClientCustomizer.class, () -> spec -> spec.readTextFileHandler(request -> Mono.empty())
				.writeTextFileHandler(request -> Mono.empty())
				.createTerminalHandler(request -> Mono.empty())
				.terminalOutputHandler(request -> Mono.empty())
				.releaseTerminalHandler(request -> Mono.empty())
				.waitForTerminalExitHandler(request -> Mono.empty())
				.killTerminalHandler(request -> Mono.empty()))
			.run(context -> {
				assertThat(context).hasNotFailed();
				AcpClientProperties props = context.getBean(AcpClientProperties.class);
				assertThat(props.getCapabilities().isReadTextFile()).isTrue();
				assertThat(props.getCapabilities().isWriteTextFile()).isTrue();
				assertThat(props.getCapabilities().isTerminal()).isTrue();
			});
	}

	@Test
	void aCapabilityPropertyWithoutItsHandlerFailsNamingTheProperty() {
		this.runner.withUserConfiguration(InMemoryTransportConfiguration.class)
			.withPropertyValues("spring.acp.client.capabilities.read-text-file=true",
					"spring.acp.client.capabilities.terminal=true")
			.run(context -> assertThat(context).getFailure()
				.hasStackTraceContaining("readTextFileHandler")
				.hasStackTraceContaining("The capabilities come from spring.acp.client.capabilities.read-text-file=true, "
						+ "spring.acp.client.capabilities.terminal=true:")
				.satisfies(failure -> assertThat(failure).hasStackTraceContaining("IllegalStateException")));
	}

	@Test
	void advertisesNoFileSystemCapabilityByDefault() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		List<AcpSchema.InitializeRequest> initializeRequests = new CopyOnWriteArrayList<>();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport()).initializeHandler(request -> {
			initializeRequests.add(request);
			return AcpSchema.InitializeResponse.ok();
		}).promptHandler((request, context) -> AcpSchema.PromptResponse.endTurn()).build();
		agent.start();
		try {
			this.runner.withBean(AcpClientTransport.class, pair::clientTransport).run(context -> {
				context.getBean(AcpSyncClient.class).initialize();
				AcpSchema.ClientCapabilities capabilities = initializeRequests.get(0).clientCapabilities();
				assertThat(capabilities.fs().readTextFile()).isFalse();
				assertThat(capabilities.fs().writeTextFile()).isFalse();
				assertThat(capabilities.terminal()).isFalse();
			});
		}
		finally {
			agent.closeGracefully();
		}
	}

	@Test
	void contextCloseClosesTheClientOnce() {
		CountingClientTransport transport = new CountingClientTransport(
				InMemoryTransportPair.create().clientTransport());
		// The transport bean's own destroy method is off: count only the closes that come
		// through the clients.
		this.runner
			.withBean("acpClientTransport", AcpClientTransport.class, () -> transport,
					definition -> ((AbstractBeanDefinition) definition).setDestroyMethodName(""))
			.run(context -> assertThat(context).hasSingleBean(AcpSyncClient.class));
		assertThat(transport.closes).hasValue(1);
	}

	@Test
	void appliesCustomizersInOrder() {
		List<String> applied = new CopyOnWriteArrayList<>();
		this.runner.withUserConfiguration(InMemoryTransportConfiguration.class)
			.withBean("second", AcpClientCustomizer.class, () -> new OrderedCustomizer(2, "second", applied))
			.withBean("first", AcpClientCustomizer.class, () -> new OrderedCustomizer(1, "first", applied))
			.run(context -> {
				assertThat(context).hasSingleBean(AcpAsyncClient.class);
				assertThat(applied).containsExactly("first", "second");
			});
	}

	@Test
	void customizerReceivesSessionUpdates() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.initializeHandler(request -> AcpSchema.InitializeResponse.ok())
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse("session-1", null, null))
			.promptHandler((request, prompt) -> {
				prompt.sendMessage("hello");
				return AcpSchema.PromptResponse.endTurn();
			})
			.build();
		agent.start();
		List<AcpSchema.SessionNotification> received = new CopyOnWriteArrayList<>();
		try {
			this.runner.withBean(AcpClientTransport.class, pair::clientTransport)
				.withBean(AcpClientCustomizer.class, () -> spec -> spec.sessionUpdateConsumer(notification -> {
					received.add(notification);
					return Mono.empty();
				}))
				.run(context -> {
					AcpSyncClient client = context.getBean(AcpSyncClient.class);
					client.initialize();
					client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()));
					client.prompt(new AcpSchema.PromptRequest("session-1", List.of(new AcpSchema.TextContent("hi"))));
					assertThat(received).singleElement()
						.satisfies(notification -> assertThat(notification.sessionId()).isEqualTo("session-1"));
				});
		}
		finally {
			agent.closeGracefully();
		}
	}

	@Test
	void anApplicationConsumerReplacesTheDebugLoggingDefault() {
		ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
			.getLogger(AcpClientAutoConfiguration.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logged = new ch.qos.logback.core.read.ListAppender<>();
		logged.start();
		ch.qos.logback.classic.Level level = logger.getLevel();
		logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
		logger.addAppender(logged);
		try {
			// Without an application consumer, the default logs each update at DEBUG.
			assertThat(promptAndCollect(null)).isEmpty();
			assertThat(logged.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
				.startsWith("Session update for session-1"));

			// With one, the default is replaced, not run beside it.
			logged.list.clear();
			assertThat(promptAndCollect(spec -> {
			})).singleElement();
			assertThat(logged.list).noneSatisfy(event -> assertThat(event.getFormattedMessage())
				.startsWith("Session update for"));
		}
		finally {
			logger.detachAppender(logged);
			logger.setLevel(level);
		}
	}

	/**
	 * Prompts an agent that sends one update; with a customizer, it also adds a consumer that
	 * collects the updates, which the method returns.
	 */
	private List<AcpSchema.SessionNotification> promptAndCollect(AcpClientCustomizer customizer) {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.initializeHandler(request -> AcpSchema.InitializeResponse.ok())
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse("session-1", null, null))
			.promptHandler((request, prompt) -> {
				prompt.sendMessage("hello");
				return AcpSchema.PromptResponse.endTurn();
			})
			.build();
		agent.start();
		List<AcpSchema.SessionNotification> received = new CopyOnWriteArrayList<>();
		ApplicationContextRunner contextRunner = this.runner.withBean(AcpClientTransport.class, pair::clientTransport);
		if (customizer != null) {
			contextRunner = contextRunner.withBean(AcpClientCustomizer.class,
					() -> spec -> spec.sessionUpdateConsumer(notification -> {
						received.add(notification);
						return Mono.empty();
					}));
		}
		try {
			contextRunner.run(context -> {
				AcpSyncClient client = context.getBean(AcpSyncClient.class);
				client.initialize();
				client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()));
				client.prompt(new AcpSchema.PromptRequest("session-1", List.of(new AcpSchema.TextContent("hi"))));
			});
		}
		finally {
			agent.closeGracefully();
		}
		return received;
	}

	@Test
	void theCapabilityErrorNamesOnlyTheSettingsThatAdvertisedAMissingHandler() {
		IllegalStateException sdk = new IllegalStateException("The client advertises capabilities it has no handler for: fs.readTextFile needs readTextFileHandler; fs.writeTextFile needs writeTextFileHandler; terminal needs killTerminalHandler; elicitation needs createElicitationHandler");

		assertThat(AcpClientAutoConfiguration.namingTheSettings(sdk, "spring.acp.client", true, true, true))
			.hasMessageContaining("spring.acp.client.capabilities.read-text-file=true, "
					+ "spring.acp.client.capabilities.write-text-file=true, spring.acp.client.capabilities.terminal=true")
			.hasCause(sdk);
		assertThat(AcpClientAutoConfiguration.namingTheSettings(sdk, "spring.acp.client", false, false, false))
			.isSameAs(sdk);
		IllegalStateException other = new IllegalStateException("Already connected");
		assertThat(AcpClientAutoConfiguration.namingTheSettings(other, "spring.acp.client", true, true, true))
			.isSameAs(other);
	}

	record OrderedCustomizer(int order, String name, List<String> applied) implements AcpClientCustomizer, Ordered {

		@Override
		public void customize(AcpClient.AsyncSpec spec) {
			this.applied.add(this.name);
		}

		@Override
		public int getOrder() {
			return this.order;
		}

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

		final AtomicInteger closes = new AtomicInteger();

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
			this.closes.incrementAndGet();
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
