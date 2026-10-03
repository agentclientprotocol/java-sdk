/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpTransport;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Every shipped transport, client side and agent side, can be closed more than once, with
 * {@code close()} and {@code closeGracefully()} in either order: each call completes without
 * an error, and nothing is dropped to Reactor's error hook. Applications close more than once
 * as a matter of course: try-with-resources after an explicit close, or a container's destroy
 * callbacks.
 */
class TransportCloseIdempotenceTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	private final AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();

	private final List<Throwable> dropped = new CopyOnWriteArrayList<>();

	private final List<Runnable> cleanups = new ArrayList<>();

	@BeforeEach
	void captureDroppedErrors() {
		Hooks.onErrorDropped(this.dropped::add);
	}

	@AfterEach
	void cleanUp() {
		Hooks.resetOnErrorDropped();
		for (int i = this.cleanups.size() - 1; i >= 0; i--) {
			try {
				this.cleanups.get(i).run();
			}
			catch (RuntimeException e) {
				// Best effort: the transport under test may have taken its peer down.
			}
		}
	}

	enum Kind {

		WEBSOCKET_CLIENT, STREAMABLE_HTTP_CLIENT, STREAMABLE_HTTP_AGENT, STREAMABLE_HTTP_WEBSOCKET_AGENT,
		STDIO_CLIENT, STDIO_AGENT, IN_MEMORY_CLIENT, IN_MEMORY_AGENT

	}

	enum Step {

		CLOSE, CLOSE_GRACEFULLY

	}

	static Stream<Arguments> transportsAndOrders() {
		List<List<Step>> orders = List.of(List.of(Step.CLOSE_GRACEFULLY, Step.CLOSE_GRACEFULLY),
				List.of(Step.CLOSE_GRACEFULLY, Step.CLOSE), List.of(Step.CLOSE, Step.CLOSE_GRACEFULLY),
				List.of(Step.CLOSE, Step.CLOSE));
		return Stream.of(Kind.values())
			.flatMap(kind -> orders.stream().map(order -> Arguments.of(kind, order)));
	}

	@ParameterizedTest(name = "{0}: {1}")
	@MethodSource("transportsAndOrders")
	void closingAConnectedTransportAgainIsHarmless(Kind kind, List<Step> order) {
		AcpTransport transport = connected(kind);

		for (Step step : order) {
			if (step == Step.CLOSE) {
				assertThatCode(transport::close).as("close()").doesNotThrowAnyException();
			}
			else {
				assertThatCode(() -> transport.closeGracefully().block(TIMEOUT)).as("closeGracefully()")
					.doesNotThrowAnyException();
			}
		}
		// A last graceful close completes too, once whatever the steps started has finished.
		assertThatCode(() -> transport.closeGracefully().block(TIMEOUT)).as("a last closeGracefully()")
			.doesNotThrowAnyException();

		assertThat(this.dropped).as("errors dropped to Reactor's onErrorDropped hook").isEmpty();
	}

	/** The reported symptom: the second graceful close of a WebSocket sync client returned false. */
	@Test
	void aWebSocketSyncClientClosesGracefullyTwice() {
		StreamableHttpAcpAgentTransport server = startServer(new AtomicReference<>());
		AcpSyncClient client = AcpClient.sync(webSocketClient(server)).requestTimeout(TIMEOUT).build();
		client.initialize();

		assertThat(client.closeGracefully()).isTrue();
		assertThat(client.closeGracefully()).isTrue();
		client.close();
	}

	private AcpTransport connected(Kind kind) {
		AtomicReference<AcpAgentTransport> agentSide = new AtomicReference<>();
		switch (kind) {
			case WEBSOCKET_CLIENT -> {
				WebSocketAcpClientTransport transport = webSocketClient(startServer(agentSide));
				initialize(transport);
				return transport;
			}
			case STREAMABLE_HTTP_CLIENT -> {
				StreamableHttpAcpClientTransport transport = httpClient(startServer(agentSide));
				initialize(transport);
				return transport;
			}
			case STREAMABLE_HTTP_AGENT -> {
				initialize(httpClient(startServer(agentSide)));
				return agentSide.get();
			}
			case STREAMABLE_HTTP_WEBSOCKET_AGENT -> {
				initialize(webSocketClient(startServer(agentSide)));
				return agentSide.get();
			}
			case STDIO_CLIENT -> {
				StdioAcpClientTransport transport = new StdioAcpClientTransport(javaAgent(StdioCloseAgent.class));
				transport.setStdErrorHandler(line -> {
				});
				initialize(transport);
				return transport;
			}
			case STDIO_AGENT -> {
				return stdioAgent();
			}
			case IN_MEMORY_CLIENT, IN_MEMORY_AGENT -> {
				InMemoryTransportPair pair = InMemoryTransportPair.create();
				AcpAsyncAgent agent = agent(pair.agentTransport());
				agent.start().block(TIMEOUT);
				this.cleanups.add(agent::close);
				initialize(pair.clientTransport());
				return kind == Kind.IN_MEMORY_CLIENT ? pair.clientTransport() : pair.agentTransport();
			}
			default -> throw new IllegalArgumentException(kind.name());
		}
	}

	private StreamableHttpAcpAgentTransport startServer(AtomicReference<AcpAgentTransport> agentSide) {
		StreamableHttpAcpAgentTransport server = new StreamableHttpAcpAgentTransport(0, this.jsonMapper,
				AcpAgentFactory.async(transport -> {
					agentSide.set(transport);
					return agent(transport);
				}));
		server.start().block(TIMEOUT);
		this.cleanups.add(() -> server.closeGracefully().block(TIMEOUT));
		return server;
	}

	private static AcpAsyncAgent agent(AcpAgentTransport transport) {
		return AcpAgent.async(transport)
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok()))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("s-1", null)))
			.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn()))
			.build();
	}

	private WebSocketAcpClientTransport webSocketClient(StreamableHttpAcpAgentTransport server) {
		return new WebSocketAcpClientTransport(URI.create("ws://127.0.0.1:" + server.getPort() + "/acp"),
				this.jsonMapper);
	}

	private StreamableHttpAcpClientTransport httpClient(StreamableHttpAcpAgentTransport server) {
		return new StreamableHttpAcpClientTransport(URI.create("http://127.0.0.1:" + server.getPort() + "/acp"),
				this.jsonMapper);
	}

	/** Connects a client over the transport and initializes it, so the transport is in use. */
	private void initialize(AcpClientTransport transport) {
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);
		this.cleanups.add(client::close);
	}

	/** A started stdio agent transport on pipes; the client side writes nothing. */
	private AcpAgentTransport stdioAgent() {
		try {
			PipedOutputStream clientOutput = new PipedOutputStream();
			PipedInputStream agentInput = new PipedInputStream(clientOutput);
			StdioAcpAgentTransport transport = new StdioAcpAgentTransport(this.jsonMapper, agentInput,
					new ByteArrayOutputStream());
			AcpAsyncAgent agent = agent(transport);
			agent.start().block(TIMEOUT);
			// Ends the reader thread, which closing the transport does not interrupt.
			this.cleanups.add(() -> {
				try {
					clientOutput.close();
				}
				catch (IOException e) {
					// already closed
				}
			});
			return transport;
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static AgentParameters javaAgent(Class<?> main) {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		return AgentParameters.builder(java)
			.arg("-Dlogback.configurationFile=logback-stdio-agent.xml")
			.arg("-cp")
			.arg(System.getProperty("java.class.path"))
			.arg(main.getName())
			.build();
	}

	/** A stdio agent run as the child process of the stdio client case. */
	public static final class StdioCloseAgent {

		private StdioCloseAgent() {
		}

		public static void main(String[] args) {
			AcpAsyncAgent agent = agent(new StdioAcpAgentTransport());
			agent.start().then(agent.awaitTermination()).block();
			System.exit(0);
		}

	}

}
