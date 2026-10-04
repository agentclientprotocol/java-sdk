/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WebSocketAcpClientTransport} against the WebSocket upgrade of
 * {@link StreamableHttpAcpAgentTransport}: the client-side paths that
 * {@link StreamableHttpAcpAgentTransportWebSocketIntegrationTest} does not cover — agent-to-client
 * file requests (and that the client never echoes them back), a client-to-agent cancel
 * notification, messages larger than Jetty's 64 KB default, and sequential prompts on one session.
 */
class StreamableHttpWebSocketClientTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();

	private StreamableHttpAcpAgentTransport server;

	private AcpAsyncClient client;

	@AfterEach
	void tearDown() {
		if (client != null) {
			client.closeGracefully().block(TIMEOUT);
		}
		if (server != null) {
			server.closeGracefully().block(TIMEOUT);
		}
	}

	/**
	 * The agent sends {@code fs/read_text_file} while handling a prompt. The client answers it
	 * with a response and must not send the request itself back to the agent (an
	 * {@code AcpClientSession} that forwarded handled inbound messages to the transport did).
	 */
	@Test
	void agentToClientFileReadIsAnsweredAndNotEchoedBackToTheAgent() throws Exception {
		CountDownLatch echoedRequestReceived = new CountDownLatch(1);
		AtomicReference<String> contentSeenByAgent = new AtomicReference<>();
		start(transport -> {
			AcpAgentTransport observed = new InboundObservingTransport(transport, message -> {
				if (message instanceof AcpSchema.JSONRPCRequest request
						&& AcpSchema.METHOD_FS_READ_TEXT_FILE.equals(request.method())) {
					echoedRequestReceived.countDown();
				}
			});
			AtomicReference<AcpAsyncAgent> agent = new AtomicReference<>();
			agent.set(baseAgent(observed)
				.promptHandler((request, context) -> agent.get()
					.readTextFile(new AcpSchema.ReadTextFileRequest(request.sessionId(), "/src/Main.java", null, null))
					.doOnNext(response -> contentSeenByAgent.set(response.content()))
					.thenReturn(AcpSchema.PromptResponse.endTurn()))
				.build());
			return agent.get();
		});

		client = AcpClient.async(clientTransport())
			.clientCapabilities(new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(true, false), false))
			.requestTimeout(TIMEOUT)
			.readTextFileHandler(request -> Mono.just(new AcpSchema.ReadTextFileResponse("public class Main { }")))
			.build();
		client.initialize()
			.block(TIMEOUT);
		AcpSchema.NewSessionResponse session = newSession();

		AcpSchema.PromptResponse response = client
			.prompt(new AcpSchema.PromptRequest(session.sessionId(), List.of(new AcpSchema.TextContent("read file"))))
			.block(TIMEOUT);

		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(contentSeenByAgent.get()).isEqualTo("public class Main { }");
		assertThat(echoedRequestReceived.await(1, TimeUnit.SECONDS))
			.as("the client must not send an inbound agent request back to the agent")
			.isFalse();
	}

	@Test
	void agentToClientFileWriteReachesTheClient() throws Exception {
		AtomicReference<String> writtenPath = new AtomicReference<>();
		AtomicReference<String> writtenContent = new AtomicReference<>();
		start(transport -> {
			AtomicReference<AcpAsyncAgent> agent = new AtomicReference<>();
			agent.set(baseAgent(transport)
				.promptHandler((request, context) -> agent.get()
					.writeTextFile(new AcpSchema.WriteTextFileRequest(request.sessionId(), "/out.txt", "hello world"))
					.thenReturn(AcpSchema.PromptResponse.endTurn()))
				.build());
			return agent.get();
		});

		client = AcpClient.async(clientTransport())
			.clientCapabilities(new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(false, true), false))
			.requestTimeout(TIMEOUT)
			.writeTextFileHandler(request -> {
				writtenPath.set(request.path());
				writtenContent.set(request.content());
				return Mono.just(new AcpSchema.WriteTextFileResponse());
			})
			.build();
		client.initialize()
			.block(TIMEOUT);
		AcpSchema.NewSessionResponse session = newSession();

		AcpSchema.PromptResponse response = client
			.prompt(new AcpSchema.PromptRequest(session.sessionId(), List.of(new AcpSchema.TextContent("write a file"))))
			.block(TIMEOUT);

		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(writtenPath.get()).isEqualTo("/out.txt");
		assertThat(writtenContent.get()).isEqualTo("hello world");
	}

	@Test
	void cancelDuringAPromptReachesTheAgent() throws Exception {
		CountDownLatch promptStarted = new CountDownLatch(1);
		AtomicReference<String> cancelledSession = new AtomicReference<>();
		CountDownLatch cancelReceived = new CountDownLatch(1);
		start(transport -> baseAgent(transport)
			.promptHandler((request, context) -> {
				promptStarted.countDown();
				return Mono.delay(Duration.ofSeconds(5)).thenReturn(AcpSchema.PromptResponse.endTurn());
			})
			.cancelHandler(notification -> {
				cancelledSession.set(notification.sessionId());
				cancelReceived.countDown();
				return Mono.empty();
			})
			.build());

		client = AcpClient.async(clientTransport()).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);
		AcpSchema.NewSessionResponse session = newSession();

		client.prompt(new AcpSchema.PromptRequest(session.sessionId(), List.of(new AcpSchema.TextContent("slow work"))))
			.subscribe(response -> {
			}, error -> {
			});
		assertThat(promptStarted.await(5, TimeUnit.SECONDS)).isTrue();
		client.cancel(new AcpSchema.CancelNotification(session.sessionId())).block(TIMEOUT);

		assertThat(cancelReceived.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(cancelledSession.get()).isEqualTo(session.sessionId());
	}

	/**
	 * A prompt of 500,000 characters, past Jetty's 64 KB default text-message limit: agent
	 * messages and prompts can carry whole files.
	 */
	@Test
	void largeMessagesCrossTheWebSocketIntact() throws Exception {
		String largeText = "x".repeat(500_000);
		start(transport -> baseAgent(transport)
			.promptHandler((request, context) -> context.sendMessage("received " + request.text().length())
				.then(context.sendMessage(request.text()))
				.thenReturn(AcpSchema.PromptResponse.endTurn()))
			.build());

		List<String> agentMessages = new CopyOnWriteArrayList<>();
		client = AcpClient.async(clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateHandler(notification -> {
				if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
						&& chunk.content() instanceof AcpSchema.TextContent text) {
					agentMessages.add(text.text());
				}
				return Mono.empty();
			})
			.build();
		client.initialize().block(TIMEOUT);
		AcpSchema.NewSessionResponse session = newSession();

		AcpSchema.PromptResponse response = client
			.prompt(new AcpSchema.PromptRequest(session.sessionId(), List.of(new AcpSchema.TextContent(largeText))))
			.block(TIMEOUT);

		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(agentMessages).containsExactly("received 500000", largeText);
	}

	@Test
	void sequentialPromptsOnOneSessionArriveInOrder() throws Exception {
		List<String> receivedPrompts = new CopyOnWriteArrayList<>();
		start(transport -> baseAgent(transport)
			.promptHandler((request, context) -> {
				receivedPrompts.add(request.text());
				return context.sendMessage("echo: " + request.text()).thenReturn(AcpSchema.PromptResponse.endTurn());
			})
			.build());

		client = AcpClient.async(clientTransport()).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);
		AcpSchema.NewSessionResponse session = newSession();

		for (int i = 1; i <= 5; i++) {
			AcpSchema.PromptResponse response = client
				.prompt(new AcpSchema.PromptRequest(session.sessionId(),
						List.of(new AcpSchema.TextContent("prompt-" + i))))
				.block(TIMEOUT);
			assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		}

		assertThat(receivedPrompts).containsExactly("prompt-1", "prompt-2", "prompt-3", "prompt-4", "prompt-5");
	}

	private void start(Function<AcpAgentTransport, AcpAsyncAgent> agentFactory) {
		server = new StreamableHttpAcpAgentTransport(freePort(), jsonMapper, AcpAgentFactory.async(agentFactory));
		server.start().block(TIMEOUT);
	}

	private WebSocketAcpClientTransport clientTransport() {
		return new WebSocketAcpClientTransport(URI.create("ws://127.0.0.1:" + server.getPort() + "/acp"), jsonMapper);
	}

	private AcpSchema.NewSessionResponse newSession() {
		AcpSchema.NewSessionResponse session = client
			.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()))
			.block(TIMEOUT);
		assertThat(session).isNotNull();
		return session;
	}

	private static AcpAgent.AsyncAgentBuilder baseAgent(AcpAgentTransport transport) {
		return AcpAgent.async(transport)
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> Mono.just(
					new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("ws-session", null)));
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException e) {
			throw new IllegalStateException("Unable to allocate a free port", e);
		}
	}

	/**
	 * Delegates to the per-connection transport and reports every inbound message before the
	 * agent sees it.
	 */
	private static final class InboundObservingTransport implements AcpAgentTransport {

		private final AcpAgentTransport delegate;

		private final Consumer<AcpSchema.JSONRPCMessage> observer;

		InboundObservingTransport(AcpAgentTransport delegate, Consumer<AcpSchema.JSONRPCMessage> observer) {
			this.delegate = delegate;
			this.observer = observer;
		}

		@Override
		public Mono<Void> start(
				Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
			return delegate.start(inbound -> handler.apply(inbound.doOnNext(observer)));
		}

		@Override
		public void setExceptionHandler(Consumer<Throwable> handler) {
			delegate.setExceptionHandler(handler);
		}

		@Override
		public Mono<Void> awaitTermination() {
			return delegate.awaitTermination();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return delegate.closeGracefully();
		}

		@Override
		public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
			return delegate.sendMessage(message);
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return delegate.unmarshalFrom(data, typeRef);
		}

		@Override
		public List<Integer> protocolVersions() {
			return delegate.protocolVersions();
		}

	}

}
