/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.QuietLoggers;
import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RemoteAcpConnection}, the per-connection core that the Streamable HTTP
 * and WebSocket agent transports share, driven directly without a wire adapter.
 */
class RemoteAcpConnectionTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();

	private final List<JSONRPCMessage> outbound = new CopyOnWriteArrayList<>();

	private final CountDownLatch promptStarted = new CountDownLatch(1);

	private final CountDownLatch promptCancelled = new CountDownLatch(1);

	private final AcpAgentFactory agentFactory = AcpAgentFactory.async(transport -> AcpAgent.async(transport)
		.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok()))
		.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("session-1", null, null)))
		.promptHandler((request, context) -> Mono.<AcpSchema.PromptResponse>never()
			.doOnSubscribe(subscription -> promptStarted.countDown())
			.doOnCancel(promptCancelled::countDown))
		.build());

	@Test
	void constructorValidatesArguments() {
		assertThatThrownBy(() -> new RemoteAcpConnection("", jsonMapper, outbound::add))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("id");
		assertThatThrownBy(() -> new RemoteAcpConnection("c1", null, outbound::add))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("jsonMapper");
		assertThatThrownBy(() -> new RemoteAcpConnection("c1", jsonMapper, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("outboundConsumer");
		assertThat(new RemoteAcpConnection("c1", jsonMapper, outbound::add).id()).isEqualTo("c1");
	}

	@Test
	void inboundRequestIsAnsweredThroughTheOutboundConsumer() {
		RemoteAcpConnection connection = new RemoteAcpConnection("c1", jsonMapper, outbound::add);
		connection.start(agentFactory).block(TIMEOUT);

		connection.acceptInbound(new JSONRPCRequest(AcpSchema.METHOD_INITIALIZE, 1, Map.of("protocolVersion", 1)));

		JSONRPCResponse response = awaitResponse(1);
		assertThat(response.error()).isNull();
		assertThat(response.result()).isNotNull();
		connection.closeGracefully().block(TIMEOUT);
	}

	@Test
	void startIsRefusedTheSecondTime() {
		RemoteAcpConnection connection = new RemoteAcpConnection("c1", jsonMapper, outbound::add);
		connection.start(agentFactory).block(TIMEOUT);

		// The refusal is also reported to the transport's exception handler, which logs it
		try (QuietLoggers quiet = QuietLoggers.of(RemoteAcpConnection.class)) {
			assertThatThrownBy(() -> connection.start(agentFactory).block(TIMEOUT))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Already started");
		}
		connection.close();
	}

	@Test
	void anUnsubscribedStartDoesNotConsumeTheStart() {
		RemoteAcpConnection connection = new RemoteAcpConnection("c1", jsonMapper, outbound::add);
		connection.start(agentFactory); // never subscribed

		connection.start(agentFactory).block(TIMEOUT);

		connection.acceptInbound(new JSONRPCRequest(AcpSchema.METHOD_INITIALIZE, 1, Map.of("protocolVersion", 1)));
		assertThat(awaitResponse(1).error()).isNull();
		connection.close();
	}

	@Test
	void inboundMessagesAreRefusedOnceClosing() {
		RemoteAcpConnection connection = new RemoteAcpConnection("c1", jsonMapper, outbound::add);
		connection.start(agentFactory).block(TIMEOUT);
		connection.close();

		assertThatThrownBy(() -> connection
			.acceptInbound(new JSONRPCRequest(AcpSchema.METHOD_INITIALIZE, 1, Map.of("protocolVersion", 1))))
			.isInstanceOf(AcpConnectionException.class)
			.hasMessageContaining("closing");
	}

	@Test
	void closeCancelsAnInFlightHandler() throws InterruptedException {
		RemoteAcpConnection connection = new RemoteAcpConnection("c1", jsonMapper, outbound::add);
		connection.start(agentFactory).block(TIMEOUT);
		connection.acceptInbound(new JSONRPCRequest(AcpSchema.METHOD_INITIALIZE, 1, Map.of("protocolVersion", 1)));
		awaitResponse(1);
		connection.acceptInbound(new JSONRPCRequest(AcpSchema.METHOD_SESSION_NEW, 2,
				Map.of("cwd", "/workspace", "mcpServers", List.of())));
		awaitResponse(2);
		connection.acceptInbound(new JSONRPCRequest(AcpSchema.METHOD_SESSION_PROMPT, 3,
				Map.of("sessionId", "session-1", "prompt", List.of(Map.of("type", "text", "text", "hi")))));
		assertThat(promptStarted.await(5, TimeUnit.SECONDS)).isTrue();

		connection.closeGracefully().block(TIMEOUT);

		assertThat(promptCancelled.await(5, TimeUnit.SECONDS)).as("the running prompt is cancelled").isTrue();
	}

	@Test
	void closeIsIdempotentAndClosesTheAgentOnce() {
		AcpAsyncAgent agent = mock(AcpAsyncAgent.class);
		when(agent.start()).thenReturn(Mono.empty());
		RemoteAcpConnection connection = new RemoteAcpConnection("c1", jsonMapper, outbound::add);
		connection.start(transport -> agent).block(TIMEOUT);

		connection.close();
		connection.close();
		connection.closeGracefully().block(TIMEOUT);

		verify(agent).close();
		verify(agent, never()).closeGracefully();
	}

	@Test
	void closeGracefullyBeforeStartClosesTheTransport() {
		RemoteAcpConnection connection = new RemoteAcpConnection("c1", jsonMapper, outbound::add);

		connection.closeGracefully().block(TIMEOUT);

		assertThatThrownBy(() -> connection
			.acceptInbound(new JSONRPCRequest(AcpSchema.METHOD_INITIALIZE, 1, Map.of("protocolVersion", 1))))
			.isInstanceOf(AcpConnectionException.class);
	}

	@Test
	void aFailingAgentCloseIsReportedAndTheTransportStillCloses() {
		AtomicReference<AcpAgentTransport> transportRef = new AtomicReference<>();
		AtomicReference<Throwable> reported = new AtomicReference<>();
		AcpAsyncAgent agent = mock(AcpAsyncAgent.class);
		when(agent.start()).thenReturn(Mono.empty());
		when(agent.closeGracefully()).thenReturn(Mono.error(new IllegalStateException("close failed")));
		RemoteAcpConnection connection = new RemoteAcpConnection("c1", jsonMapper, outbound::add);
		connection.start(transport -> {
			transportRef.set(transport);
			transport.setExceptionHandler(reported::set);
			return agent;
		}).block(TIMEOUT);

		connection.closeGracefully().block(TIMEOUT);

		assertThat(reported.get()).hasMessage("close failed");
		transportRef.get().awaitTermination().block(TIMEOUT);
		assertThatThrownBy(() -> transportRef.get().sendMessage(new JSONRPCRequest("x", 9, null)).block(TIMEOUT))
			.isInstanceOf(AcpConnectionException.class);
	}

	/**
	 * The handler the host gives the connection receives its transport errors when the
	 * agent runtime installs none, as no runtime does: before, they were only logged.
	 */
	@Test
	void transportExceptionsReachTheHandlerTheHostGave() {
		AtomicReference<Throwable> reported = new AtomicReference<>();
		AcpAsyncAgent agent = mock(AcpAsyncAgent.class);
		when(agent.start()).thenReturn(Mono.empty());
		RemoteAcpConnection connection = new RemoteAcpConnection("c1", jsonMapper, outbound::add, reported::set);
		connection.start(transport -> agent).block(TIMEOUT);

		connection.signalException(new IllegalStateException("wire broke"));

		assertThat(reported.get()).hasMessage("wire broke");
	}

	@Test
	void agentMessagesReachTheOutboundConsumerAndTransportExceptionsTheHandler() {
		AtomicReference<AcpAgentTransport> transportRef = new AtomicReference<>();
		AtomicReference<Throwable> reported = new AtomicReference<>();
		AcpAsyncAgent agent = mock(AcpAsyncAgent.class);
		when(agent.start()).thenReturn(Mono.empty());
		RemoteAcpConnection connection = new RemoteAcpConnection("c1", jsonMapper, outbound::add);
		connection.start(transport -> {
			transportRef.set(transport);
			transport.setExceptionHandler(reported::set);
			return agent;
		}).block(TIMEOUT);
		AcpAgentTransport transport = transportRef.get();

		transport.sendMessage(new JSONRPCRequest("session/request_permission", 7, null)).block(TIMEOUT);
		connection.signalException(new IllegalStateException("wire broke"));

		assertThat(outbound).singleElement()
			.isInstanceOfSatisfying(JSONRPCRequest.class, request -> assertThat(request.id()).isEqualTo(7));
		assertThat(reported.get()).hasMessage("wire broke");
		assertThat(transport.unmarshalFrom(Map.of("protocolVersion", 1),
				new TypeRef<AcpSchema.InitializeRequest>() {
				}).protocolVersion()).isEqualTo(1);
		// The mock agent never started the transport: the first start succeeds, the second is refused
		transport.start(message -> message).block(TIMEOUT);
		assertThatThrownBy(() -> transport.start(message -> message).block(TIMEOUT))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Already started");
		connection.close();
	}

	private JSONRPCResponse awaitResponse(Object id) {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			for (JSONRPCMessage message : outbound) {
				if (message instanceof JSONRPCResponse response && id.equals(response.id())) {
					return response;
				}
			}
			Thread.onSpinWait();
		}
		throw new AssertionError("No response with id " + id + " in " + outbound);
	}

}
