/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The in-process transport: delivery in both directions, the single connection, the pair's
 * mapper, and the end of the connection from either side.
 */
class InMemoryTransportPairTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	/** Far below every request timeout the tests set, so an answer at a timeout fails them. */
	private static final long PROMPTLY_MILLIS = 2_000;

	@Test
	void clientMessagesReachTheAgent() throws InterruptedException {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		List<AcpSchema.JSONRPCMessage> received = new CopyOnWriteArrayList<>();
		CountDownLatch latch = new CountDownLatch(1);
		pair.agentTransport().start(msg -> msg.doOnNext(m -> {
			received.add(m);
			latch.countDown();
		}).then(Mono.empty())).block(TIMEOUT);

		pair.clientTransport().sendMessage(request("1")).block(TIMEOUT);

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(received).singleElement().isInstanceOf(AcpSchema.JSONRPCRequest.class);
	}

	@Test
	void agentMessagesSentBeforeTheClientConnectsWaitForIt() throws InterruptedException {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		pair.agentTransport().start(msg -> msg).block(TIMEOUT);
		pair.agentTransport()
			.sendMessage(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, "1", null, null))
			.block(TIMEOUT);

		List<AcpSchema.JSONRPCMessage> received = new CopyOnWriteArrayList<>();
		CountDownLatch latch = new CountDownLatch(1);
		pair.clientTransport().connect(msg -> msg.doOnNext(m -> {
			received.add(m);
			latch.countDown();
		}).then(Mono.empty())).block(TIMEOUT);

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(received).singleElement().isInstanceOf(AcpSchema.JSONRPCResponse.class);
	}

	@Test
	void theAgentsAnswersAreSentBack() throws InterruptedException {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		pair.agentTransport()
			.start(msg -> msg.map(m -> new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION,
					((AcpSchema.JSONRPCRequest) m).id(), "answer", null)))
			.block(TIMEOUT);
		CountDownLatch answered = new CountDownLatch(1);
		pair.clientTransport().connect(msg -> msg.doOnNext(m -> answered.countDown()).then(Mono.empty())).block(TIMEOUT);

		pair.clientTransport().sendMessage(request("1")).block(TIMEOUT);

		assertThat(answered.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void aFailingMessageDoesNotStopTheOnesAfterIt() throws InterruptedException {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		List<Throwable> errors = new CopyOnWriteArrayList<>();
		pair.clientTransport().setExceptionHandler(errors::add);
		CountDownLatch second = new CountDownLatch(1);
		pair.clientTransport().connect(msg -> msg.flatMap(m -> {
			if ("1".equals(((AcpSchema.JSONRPCRequest) m).id())) {
				return Mono.error(new IllegalStateException("handler failed"));
			}
			second.countDown();
			return Mono.empty();
		})).block(TIMEOUT);
		pair.agentTransport().start(msg -> msg).block(TIMEOUT);

		pair.agentTransport().sendMessage(request("1")).block(TIMEOUT);
		pair.agentTransport().sendMessage(request("2")).block(TIMEOUT);

		assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(errors).singleElement().extracting(Throwable::getMessage).isEqualTo("handler failed");
	}

	@Test
	void eachSideConnectsOnce() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		pair.clientTransport().connect(msg -> msg).block(TIMEOUT);
		pair.agentTransport().start(msg -> msg).block(TIMEOUT);

		assertThatThrownBy(() -> pair.clientTransport().connect(msg -> msg).block(TIMEOUT))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> pair.agentTransport().start(msg -> msg).block(TIMEOUT))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void conversionsUseThePairsMapper() {
		CountingMapper mapper = new CountingMapper(AcpJsonMapper.createDefault());
		InMemoryTransportPair pair = InMemoryTransportPair.create(mapper);
		AcpSchema.InitializeRequest params = new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities());

		AcpSchema.InitializeRequest viaAgent = pair.agentTransport()
			.unmarshalFrom(params, new TypeRef<AcpSchema.InitializeRequest>() {
			});
		AcpSchema.InitializeRequest viaClient = pair.clientTransport()
			.unmarshalFrom(params, new TypeRef<AcpSchema.InitializeRequest>() {
			});

		assertThat(viaAgent).isEqualTo(params);
		assertThat(viaClient).isEqualTo(params);
		assertThat(mapper.conversions).hasValue(2);
	}

	@Test
	void aMapperIsRequired() {
		assertThatThrownBy(() -> InMemoryTransportPair.create(nullMapper())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void closingTheClientEndsTheAgentTransport() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSyncAgent agent = agent(pair, (request, context) -> AcpSchema.PromptResponse.endTurn());
		agent.start();
		AcpSyncClient client = AcpClient.sync(pair.clientTransport()).build();
		client.initialize();

		client.close();

		pair.agentTransport().awaitTermination().block(TIMEOUT);
		pair.clientTransport().awaitTermination().block(TIMEOUT);
		agent.close();
	}

	@Test
	void runReturnsOnceTheClientCloses() throws Exception {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSyncAgent agent = agent(pair, (request, context) -> AcpSchema.PromptResponse.endTurn());
		CompletableFuture<Void> run = CompletableFuture.runAsync(agent::run);
		AcpSyncClient client = AcpClient.sync(pair.clientTransport()).build();
		client.initialize();

		client.close();

		run.get(5, TimeUnit.SECONDS);
		agent.close();
	}

	@Test
	void theAgentSidesEndFailsTheClientsWaitingRequestAtOnce() throws Exception {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		CountDownLatch inPrompt = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AcpSyncAgent agent = agent(pair, (request, context) -> {
			inPrompt.countDown();
			awaitQuietly(release, 30);
			return AcpSchema.PromptResponse.endTurn();
		});
		agent.start();
		AcpSyncClient client = AcpClient.sync(pair.clientTransport()).requestTimeout(Duration.ofSeconds(30)).build();
		try {
			client.initialize();
			String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).sessionId();
			CompletableFuture<Long> prompt = CompletableFuture.supplyAsync(() -> millisUntilFailure(() -> client
				.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("Hi"))))));
			assertThat(inPrompt.await(5, TimeUnit.SECONDS)).isTrue();

			// The transport, not the agent: closing the agent answers its prompts itself.
			pair.agentTransport().closeGracefully().block(TIMEOUT);

			assertThat(prompt.get(10, TimeUnit.SECONDS)).isLessThan(PROMPTLY_MILLIS);
		}
		finally {
			release.countDown();
			client.close();
			agent.close();
		}
	}

	@Test
	void theClientSidesEndFailsTheAgentsWaitingRequestAtOnce() throws Exception {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		CountDownLatch asked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Long> permission = new CompletableFuture<>();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.requestTimeout(Duration.ofSeconds(30))
			.initializeHandler(request -> AcpSchema.InitializeResponse.ok())
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse("session-1", null, null))
			.promptHandler((request, context) -> {
				permission.complete(millisUntilFailure(() -> context.askPermission("write a file")));
				return AcpSchema.PromptResponse.endTurn();
			})
			.build();
		agent.start();
		AcpSyncClient client = AcpClient.sync(pair.clientTransport()).requestPermissionHandler(request -> {
			asked.countDown();
			awaitQuietly(release, 30);
			return new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionCancelled());
		}).build();
		try {
			client.initialize();
			String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).sessionId();
			CompletableFuture.runAsync(() -> millisUntilFailure(() -> client
				.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("Hi"))))));
			assertThat(asked.await(5, TimeUnit.SECONDS)).isTrue();

			// The transport, not the client: closing the client answers the agent's requests itself.
			pair.clientTransport().closeGracefully().block(TIMEOUT);

			assertThat(permission.get(10, TimeUnit.SECONDS)).isLessThan(PROMPTLY_MILLIS);
		}
		finally {
			release.countDown();
			client.close();
			agent.close();
		}
	}

	@Test
	void aRequestTheAgentSendsAfterTheEndFails() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		pair.agentTransport().start(msg -> msg).block(TIMEOUT);
		pair.closeGracefully().block(TIMEOUT);

		assertThatThrownBy(() -> pair.agentTransport().sendMessage(request("1")).block(TIMEOUT))
			.isInstanceOf(AcpConnectionException.class);
	}

	@Test
	void sendsAfterTheEndFailOnBothSides() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		pair.clientTransport().closeGracefully().block(TIMEOUT);

		assertThatThrownBy(() -> pair.clientTransport().sendMessage(request("1")).block(TIMEOUT))
			.isInstanceOf(AcpConnectionException.class);
		assertThatThrownBy(() -> pair.agentTransport()
			.sendMessage(new AcpSchema.JSONRPCNotification(AcpSchema.METHOD_SESSION_UPDATE, null))
			.block(TIMEOUT)).isInstanceOf(AcpConnectionException.class);
	}

	@Test
	void messagesSentBeforeTheEndAreStillDelivered() throws InterruptedException {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		pair.clientTransport().sendMessage(request("1")).block(TIMEOUT);
		pair.clientTransport().sendMessage(request("2")).block(TIMEOUT);
		pair.closeGracefully().block(TIMEOUT);

		List<Object> received = new CopyOnWriteArrayList<>();
		CountDownLatch both = new CountDownLatch(2);
		pair.agentTransport().start(msg -> msg.doOnNext(m -> {
			received.add(((AcpSchema.JSONRPCRequest) m).id());
			both.countDown();
		}).then(Mono.empty())).block(TIMEOUT);

		assertThat(both.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(received).containsExactly("1", "2");
	}

	@Test
	void closingTwiceFromEitherSideIsHarmless() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		pair.agentTransport().closeGracefully().block(TIMEOUT);
		pair.clientTransport().closeGracefully().block(TIMEOUT);
		pair.closeGracefully().block(TIMEOUT);
		pair.agentTransport().close();

		pair.agentTransport().awaitTermination().block(TIMEOUT);
		pair.clientTransport().awaitTermination().block(TIMEOUT);
	}

	@Test
	void sendIsDeliveredWhileAnotherThreadIsStillDeliveringItsMessage() throws Exception {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		List<Object> received = new CopyOnWriteArrayList<>();
		CountDownLatch firstInHandler = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch bothReceived = new CountDownLatch(2);

		// The first message is delivered on its sender's thread and held there, so that
		// thread is still emitting when the second send arrives.
		pair.agentTransport().start(msg -> msg.doOnNext(m -> {
			Object id = ((AcpSchema.JSONRPCRequest) m).id();
			if ("1".equals(id)) {
				firstInHandler.countDown();
				awaitQuietly(releaseFirst, 5);
			}
			received.add(id);
			bothReceived.countDown();
		}).then(Mono.empty())).block(TIMEOUT);

		ExecutorService senders = Executors.newFixedThreadPool(2);
		try {
			senders.submit(() -> pair.clientTransport().sendMessage(request("1")).block(TIMEOUT));
			assertThat(firstInHandler.await(5, TimeUnit.SECONDS)).isTrue();

			// The second sender does not wait for the first delivery to finish.
			Future<?> second = senders.submit(() -> pair.clientTransport().sendMessage(request("2")).block(TIMEOUT));
			second.get(1, TimeUnit.SECONDS);
			releaseFirst.countDown();

			assertThat(bothReceived.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(received).containsExactly("1", "2");
		}
		catch (TimeoutException e) {
			throw new AssertionError("a send waited for another thread's delivery", e);
		}
		finally {
			releaseFirst.countDown();
			senders.shutdownNow();
			pair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void sendFromInsideADeliveryIsDeliveredInOrder() throws Exception {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		List<Object> received = new CopyOnWriteArrayList<>();
		CountDownLatch bothReceived = new CountDownLatch(2);

		pair.agentTransport().start(msg -> msg.doOnNext(m -> {
			Object id = ((AcpSchema.JSONRPCRequest) m).id();
			received.add(id);
			if ("1".equals(id)) {
				// Re-entrant: the sender's thread is still delivering message 1.
				pair.clientTransport().sendMessage(request("2")).block(TIMEOUT);
			}
			bothReceived.countDown();
		}).then(Mono.empty())).block(TIMEOUT);

		pair.clientTransport().sendMessage(request("1")).block(TIMEOUT);

		assertThat(bothReceived.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(received).containsExactly("1", "2");
		pair.closeGracefully().block(TIMEOUT);
	}

	private static AcpSyncAgent agent(InMemoryTransportPair pair, AcpAgent.SyncPromptHandler promptHandler) {
		return AcpAgent.sync(pair.agentTransport())
			.initializeHandler(request -> AcpSchema.InitializeResponse.ok())
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse("session-1", null, null))
			.promptHandler(promptHandler)
			.build();
	}

	private static AcpSchema.JSONRPCRequest request(String id) {
		return new AcpSchema.JSONRPCRequest(AcpSchema.METHOD_INITIALIZE, id,
				new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()));
	}

	/** How long the call took to fail; a call that succeeds fails the test. */
	private static long millisUntilFailure(Runnable call) {
		long start = System.nanoTime();
		try {
			call.run();
		}
		catch (RuntimeException expected) {
			return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		}
		throw new AssertionError("the call succeeded after the end of the connection");
	}

	private static void awaitQuietly(CountDownLatch latch, long seconds) {
		try {
			latch.await(seconds, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@SuppressWarnings("NullAway")
	private static AcpJsonMapper nullMapper() {
		return null;
	}

	/** Counts the conversions the transports make through it. */
	private static final class CountingMapper implements AcpJsonMapper {

		private final AcpJsonMapper delegate;

		private final AtomicInteger conversions = new AtomicInteger();

		CountingMapper(AcpJsonMapper delegate) {
			this.delegate = delegate;
		}

		@Override
		public <T> @Nullable T readValue(String content, Class<T> type) throws IOException {
			return this.delegate.readValue(content, type);
		}

		@Override
		public <T> @Nullable T readValue(byte[] content, Class<T> type) throws IOException {
			return this.delegate.readValue(content, type);
		}

		@Override
		public <T> @Nullable T readValue(String content, TypeRef<T> type) throws IOException {
			return this.delegate.readValue(content, type);
		}

		@Override
		public <T> @Nullable T readValue(byte[] content, TypeRef<T> type) throws IOException {
			return this.delegate.readValue(content, type);
		}

		@Override
		public <T> T convertValue(Object fromValue, Class<T> type) {
			this.conversions.incrementAndGet();
			return this.delegate.convertValue(fromValue, type);
		}

		@Override
		public <T> T convertValue(Object fromValue, TypeRef<T> type) {
			this.conversions.incrementAndGet();
			return this.delegate.convertValue(fromValue, type);
		}

		@Override
		public String writeValueAsString(Object value) throws IOException {
			return this.delegate.writeValueAsString(value);
		}

		@Override
		public byte[] writeValueAsBytes(Object value) throws IOException {
			return this.delegate.writeValueAsBytes(value);
		}

	}

}
