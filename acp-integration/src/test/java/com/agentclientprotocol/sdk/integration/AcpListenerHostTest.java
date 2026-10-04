/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.integration.AcpAgentDiscovery.AgentCandidate;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class AcpListenerHostTest {

	private static final AcpAgentSettings SETTINGS = AcpAgentSettings.builder()
		.transport(AcpTransportType.HTTP)
		.listenerPort(0)
		.path("/agents/echo")
		.maxConcurrentStreamsPerConnection(64)
		.shutdownTimeout(Duration.ofSeconds(2))
		.build();

	@Test
	void servesHttpAndWebSocketOnOnePathUntilStopped() throws Exception {
		assertThat(AcpListeners.isListenerAvailable()).isTrue();
		AcpListenerHost host = new AcpListenerHost(AcpListeners.listener(SETTINGS, factory()));
		assertThat(host.port()).isEmpty();
		host.start();
		host.start(); // once
		int port = host.port().orElseThrow();
		assertThat(port).isPositive();

		URI http = URI.create("http://localhost:" + port + "/agents/echo");
		assertThat(TestAgents.roundTrip(new StreamableHttpAcpClientTransport(http, AcpJsonMapper.createDefault())))
			.containsExactly("echo: hello!");
		URI ws = URI.create("ws://localhost:" + port + "/agents/echo");
		assertThat(TestAgents.roundTrip(new WebSocketAcpClientTransport(ws, AcpJsonMapper.createDefault())))
			.containsExactly("echo: hello!");

		assertThat(host.termination().toCompletableFuture().isDone()).isFalse();
		host.stop(Duration.ofSeconds(10));
		assertThat(host.stopGracefully()).isSameAs(host.stopGracefully());
		host.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
		host.start(); // not after the stop
	}

	@Test
	void aStopThatTimesOutReturns() {
		AcpListenerHost host = new AcpListenerHost(AcpListeners.listener(SETTINGS, factory()));
		host.start();
		host.stop(Duration.ofNanos(1));
		host.stop(Duration.ofSeconds(10));
	}

	@Test
	void theServletTakesTheLimitsAndClosesBeforeShutdown() {
		StreamableHttpAcpServlet servlet = AcpListeners.servlet(SETTINGS, factory());
		AcpServletHost.closeBeforeShutdown(servlet, Duration.ofSeconds(5));
	}

	@Test
	void aServletThatDoesNotCloseInTimeIsLeft() {
		StreamableHttpAcpServlet servlet = new StreamableHttpAcpServlet(AcpJsonMapper.createDefault(), factory()) {

			private static final long serialVersionUID = 1L;

			@Override
			public Mono<Void> closeGracefully() {
				return Mono.never();
			}

		};
		AcpServletHost.closeBeforeShutdown(servlet, Duration.ofMillis(10));
	}

	@Test
	void anAbsentClassIsReportedAbsent() {
		assertThat(AcpListeners.isPresent("com.example.NoSuchListener")).isFalse();
	}

	private static AcpAgentFactory factory() {
		return AcpAgents
			.builder(new AgentCandidate<>("echo", TestAgents.EchoAgent.class, TestAgents.EchoAgent::new), SETTINGS,
					List.of(), List.of(new TestAgents.SuffixResolver()), List.of(new TestAgents.ReplyHandler()))
			.buildFactory();
	}

}
