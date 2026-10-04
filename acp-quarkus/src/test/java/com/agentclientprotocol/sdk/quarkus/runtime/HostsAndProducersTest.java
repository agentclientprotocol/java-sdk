/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import com.agentclientprotocol.sdk.quarkus.ClientTransportType;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The hosts and producers on their own, with the container's part played by the test. */
class HostsAndProducersTest {

	@Test
	void stdioHostStartsTheAgentOnStartupAndClosesItOnceOnShutdown() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAgentAssembly assembly = mock(AcpAgentAssembly.class);
		when(assembly.builder()).thenReturn(AcpAgentSupport.create(new PingAgent()));
		AcpRuntimeConfig config = mock(AcpRuntimeConfig.class, Answers.RETURNS_DEEP_STUBS);
		when(config.agent().shutdownOnTransportEnd()).thenReturn(false);

		AcpStdioAgentHost host = new AcpStdioAgentHost(assembly, pair.agentTransport(), config);
		assertThat(host.agent()).isNull();
		host.start(null);
		assertThat(host.agent()).isNotNull();

		AcpSyncClient client = AcpClient.sync(pair.clientTransport()).requestTimeout(Duration.ofSeconds(5)).build();
		client.initialize();
		String session = client.newSession(new AcpSchema.NewSessionRequest("/", List.of())).sessionId();
		assertThat(client.prompt(new AcpSchema.PromptRequest(session, List.of(new AcpSchema.TextContent("x"))))
			.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);

		host.stop(null);
		host.stop(null);
		client.close();
	}

	@Test
	void stdioTransportProducerMakesTheStdioTransport() {
		assertThat(new AcpStdioTransportProducer().stdioAgentTransport()).isInstanceOf(StdioAcpAgentTransport.class);
	}

	@Test
	void httpHostClosesServletAndWebSocketsOnShutdown() {
		AcpHttpServlet servlet = mock(AcpHttpServlet.class);
		AcpWebSocketRoute webSockets = mock(AcpWebSocketRoute.class);
		AcpHttpEndpoint endpoint = mock(AcpHttpEndpoint.class);
		when(endpoint.options()).thenReturn(StreamableHttpAcpAgentTransportOptions.defaults());
		when(servlet.closeGracefully()).thenReturn(Mono.empty());
		when(webSockets.closeGracefully()).thenReturn(Mono.never());
		when(servlet.activeConnectionCount()).thenReturn(2);
		when(webSockets.activeConnectionCount()).thenReturn(3);

		AcpHttpAgentHost host = new AcpHttpAgentHost(servlet, webSockets, endpoint);
		assertThat(host.activeConnectionCount()).isEqualTo(5);
		// A close that never finishes is given up after the shutdown timeout.
		when(endpoint.options()).thenReturn(StreamableHttpAcpAgentTransportOptions.builder()
			.shutdownTimeout(Duration.ofMillis(10))
			.build());
		host.stop(null);
		verify(servlet).closeGracefully();
		verify(webSockets).closeGracefully();
	}

	@Test
	void clientProducersBuildCustomizeAndCloseTheClient() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpRuntimeConfig config = mock(AcpRuntimeConfig.class, Answers.RETURNS_DEEP_STUBS);
		when(config.client().requestTimeout()).thenReturn(Optional.of(Duration.ofSeconds(1)));
		when(config.client().capabilities().readTextFile()).thenReturn(true);
		when(config.client().transport().type()).thenReturn(Optional.of(ClientTransportType.HTTP));
		when(config.client().transport().http().uri()).thenReturn(Optional.of(java.net.URI.create("http://localhost:9/acp")));
		AcpClientProducers producers = new AcpClientProducers(config);
		assertThat(producers.acpClientTransport()).isNotNull();

		List<String> customized = new java.util.ArrayList<>();
		AcpAsyncClient client = producers.acpAsyncClient(pair.clientTransport(),
				List.of(spec -> customized.add("first"), spec -> customized.add("second")));
		assertThat(customized).containsExactly("first", "second");
		assertThat(producers.acpSyncClient(client)).isNotNull();
		producers.close(client);
	}

	@Test
	void anApplicationConsumerReplacesTheDebugLoggingDefault() {
		org.jboss.logmanager.Logger logger = org.jboss.logmanager.LogContext.getLogContext()
			.getLogger(AcpClientProducers.class.getName());
		List<String> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
		java.util.logging.Handler handler = new java.util.logging.Handler() {
			@Override
			public void publish(java.util.logging.LogRecord record) {
				logged.add(String.valueOf(record.getMessage()));
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		handler.setLevel(java.util.logging.Level.ALL);
		java.util.logging.Level level = logger.getLevel();
		logger.setLevel(java.util.logging.Level.FINE);
		logger.addHandler(handler);
		try {
			// Without an application consumer, the default logs each update at DEBUG.
			promptWith(List.of());
			assertThat(logged).anySatisfy(message -> assertThat(message).startsWith("Session update for"));

			// With one, the default is replaced, not run beside it.
			logged.clear();
			List<AcpSchema.SessionNotification> received = new java.util.concurrent.CopyOnWriteArrayList<>();
			promptWith(List.of(spec -> spec.sessionUpdateConsumer(notification -> {
				received.add(notification);
				return Mono.empty();
			})));
			assertThat(received).singleElement();
			assertThat(logged).noneSatisfy(message -> assertThat(message).startsWith("Session update for"));
		}
		finally {
			logger.removeHandler(handler);
			logger.setLevel(level);
		}
	}

	/** The client bean, customized, prompts an agent that sends one session update. */
	private static void promptWith(
			List<com.agentclientprotocol.sdk.quarkus.AcpClientCustomizer> customizers) {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		com.agentclientprotocol.sdk.agent.AcpSyncAgent agent = com.agentclientprotocol.sdk.agent.AcpAgent
			.sync(pair.agentTransport())
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse("session-1", null, null))
			.promptHandler((request, context) -> {
				context.sendMessage("hello");
				return AcpSchema.PromptResponse.endTurn();
			})
			.build();
		agent.start();
		AcpRuntimeConfig config = mock(AcpRuntimeConfig.class, Answers.RETURNS_DEEP_STUBS);
		AcpClientProducers producers = new AcpClientProducers(config);
		AcpSyncClient client = producers.acpSyncClient(producers.acpAsyncClient(pair.clientTransport(), customizers));
		try {
			client.initialize();
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()));
			client.prompt(new AcpSchema.PromptRequest("session-1", List.of(new AcpSchema.TextContent("hi"))));
		}
		finally {
			client.close();
			agent.closeGracefully();
		}
	}

	@Test
	void capabilitiesFollowTheConfiguration() {
		AcpRuntimeConfig.Capabilities none = mock(AcpRuntimeConfig.Capabilities.class);
		AcpSchema.ClientCapabilities nothing = AcpClientProducers.capabilities(none);
		assertThat(nothing.fs().readTextFile()).isFalse();
		assertThat(nothing.terminal()).isFalse();
		assertThat(nothing.elicitation()).isNull();
		assertThat(nothing.session()).isNull();

		AcpRuntimeConfig.Capabilities all = mock(AcpRuntimeConfig.Capabilities.class);
		when(all.readTextFile()).thenReturn(true);
		when(all.writeTextFile()).thenReturn(true);
		when(all.terminal()).thenReturn(true);
		when(all.elicitationForm()).thenReturn(true);
		when(all.booleanConfigOptions()).thenReturn(true);
		AcpSchema.ClientCapabilities everything = AcpClientProducers.capabilities(all);
		assertThat(everything.fs().writeTextFile()).isTrue();
		assertThat(everything.terminal()).isTrue();
		assertThat(everything.elicitation().form()).isNotNull();
		assertThat(everything.elicitation().url()).isNull();
		assertThat(everything.session()).isEqualTo(AcpSchema.ClientSessionCapabilities.withBooleanConfigOptions());

		when(all.elicitationForm()).thenReturn(false);
		when(all.elicitationUrl()).thenReturn(true);
		assertThat(AcpClientProducers.capabilities(all).elicitation().url()).isNotNull();
	}

	@AcpAgent
	public static class PingAgent {

		@Prompt
		AcpSchema.PromptResponse prompt() {
			return AcpSchema.PromptResponse.endTurn();
		}

	}

}
