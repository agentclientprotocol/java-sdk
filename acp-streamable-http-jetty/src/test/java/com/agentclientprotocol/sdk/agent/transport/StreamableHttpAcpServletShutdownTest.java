/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Shutting the servlet down in an application's own container: {@code destroy()} and
 * {@code closeGracefully()} finish within the shutdown timeout, whatever the clients and
 * agents still connected are doing.
 */
class StreamableHttpAcpServletShutdownTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final AcpJsonMapper JSON = AcpJsonMapper.createDefault();

	@Test
	void destroyAfterTheConnectorsStoppedReturnsPromptlyWithAClientConnected() throws Exception {
		StreamableHttpAcpServlet servlet = new StreamableHttpAcpServlet(JSON, factory(agent -> agent));
		try (Container container = Container.start(servlet)) {
			AcpAsyncClient client = connectClient(container.endpoint());
			try {
				assertThat(servlet.activeConnectionCount()).isEqualTo(1);

				// The order of Spring Boot's Tomcat: the connectors go first, the servlets later.
				container.connector.stop();
				long start = System.nanoTime();
				container.context.stop();
				Duration destroy = Duration.ofNanos(System.nanoTime() - start);

				assertThat(destroy).isLessThan(Duration.ofSeconds(2));
				assertThat(servlet.activeConnectionCount()).isZero();
			}
			finally {
				client.close();
			}
		}
	}

	@Test
	void destroyIsBoundedByTheShutdownTimeoutWhenAnAgentNeverFinishesClosing() throws Exception {
		CompletableFuture<AcpAsyncAgent> stuck = new CompletableFuture<>();
		StreamableHttpAcpServlet servlet = new StreamableHttpAcpServlet(JSON, factory(agent -> {
			AcpAsyncAgent neverCloses = mock(AcpAsyncAgent.class, delegatesTo(agent));
			doReturn(Mono.never()).when(neverCloses).closeGracefully();
			stuck.complete(neverCloses);
			return neverCloses;
		}), StreamableHttpAcpAgentTransportOptions.builder().shutdownTimeout(Duration.ofMillis(500)).build());
		try (Container container = Container.start(servlet)) {
			AcpAsyncClient client = connectClient(container.endpoint());
			try {
				long start = System.nanoTime();
				servlet.destroy();
				Duration destroy = Duration.ofNanos(System.nanoTime() - start);

				assertThat(destroy).isGreaterThanOrEqualTo(Duration.ofMillis(500)).isLessThan(Duration.ofSeconds(3));
				// What did not close gracefully in time is closed at once.
				verify(stuck.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).close();
				assertThat(servlet.activeConnectionCount()).isZero();
			}
			finally {
				client.close();
			}
		}
	}

	@Test
	void closingWhileAnInitializeIsInFlightRefusesItAndKeepsNoConnection() throws Exception {
		CountDownLatch initializing = new CountDownLatch(1);
		Sinks.One<AcpSchema.InitializeResponse> release = Sinks.one();
		StreamableHttpAcpServlet servlet = new StreamableHttpAcpServlet(JSON,
				AcpAgentFactory.async(transport -> AcpAgent.async(transport).initializeHandler(request -> {
					initializing.countDown();
					return release.asMono();
				}).promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn())).build()));
		try (Container container = Container.start(servlet)) {
			HttpClient http = HttpClient.newHttpClient();
			CompletableFuture<HttpResponse<String>> initialize = http.sendAsync(HttpRequest
				.newBuilder(container.endpoint())
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("""
						{"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}
						"""))
				.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			assertThat(initializing.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

			servlet.closeGracefully().block(TIMEOUT);

			// The initialize is answered at once, not when its handler or its timeout ends.
			HttpResponse<String> refused = initialize.get(2, TimeUnit.SECONDS);
			assertThat(refused.statusCode()).isEqualTo(503);
			assertThat(refused.headers().firstValue("Acp-Connection-Id")).isEmpty();

			// An answer the agent gives afterwards opens no connection on a closed servlet.
			release.tryEmitValue(new AcpSchema.InitializeResponse(AcpSchema.LATEST_PROTOCOL_VERSION,
					new AcpSchema.AgentCapabilities(), List.of()));
			long deadline = System.nanoTime() + Duration.ofMillis(500).toNanos();
			while (System.nanoTime() < deadline) {
				assertThat(servlet.activeConnectionCount()).isZero();
				Thread.sleep(25);
			}
		}
	}

	private static AcpAgentFactory factory(java.util.function.UnaryOperator<AcpAsyncAgent> decorate) {
		return AcpAgentFactory.async(transport -> decorate.apply(AcpAgent.async(transport)
			.initializeHandler(r -> Mono.just(new AcpSchema.InitializeResponse(AcpSchema.LATEST_PROTOCOL_VERSION,
					new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(r -> Mono.just(new AcpSchema.NewSessionResponse("shutdown-1", null, null)))
			.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn()))
			.build()));
	}

	/** A client holding its connection stream and one session stream open. */
	private static AcpAsyncClient connectClient(URI endpoint) {
		AcpAsyncClient client = AcpClient.async(new StreamableHttpAcpClientTransport(endpoint, JSON))
			.requestTimeout(TIMEOUT)
			.build();
		client.initialize().block(TIMEOUT);
		client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
		return client;
	}

	/** An application's own Jetty servlet container with the servlet mounted at /acp. */
	private record Container(Server server, ServerConnector connector, ServletContextHandler context)
			implements AutoCloseable {

		static Container start(StreamableHttpAcpServlet servlet) throws Exception {
			Server server = new Server();
			ServerConnector connector = new ServerConnector(server);
			connector.setPort(0);
			server.addConnector(connector);
			ServletContextHandler context = new ServletContextHandler("/");
			ServletHolder holder = new ServletHolder(servlet);
			holder.setAsyncSupported(true);
			context.addServlet(holder, "/acp");
			server.setHandler(context);
			server.start();
			return new Container(server, connector, context);
		}

		URI endpoint() {
			return URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/acp");
		}

		@Override
		public void close() throws Exception {
			server.stop();
		}

	}

}
