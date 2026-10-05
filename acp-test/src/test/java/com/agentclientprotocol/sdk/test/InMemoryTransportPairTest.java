/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Unit tests for {@link InMemoryTransportPair}.
 */
class InMemoryTransportPairTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void createReturnsPair() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		assertThat(pair).isNotNull();
		assertThat(pair.clientTransport()).isNotNull();
		assertThat(pair.agentTransport()).isNotNull();
	}

	@Test
	void clientCanSendToAgent() throws InterruptedException {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AtomicReference<AcpSchema.JSONRPCMessage> received = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		// Agent listens
		pair.agentTransport().start(msg -> {
			return msg.doOnNext(m -> {
				received.set(m);
				latch.countDown();
			}).then(Mono.empty());
		}).subscribe();

		// Client connects and sends
		pair.clientTransport().connect(msg -> msg).subscribe();
		var initRequest = new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities());
		var jsonRpcRequest = new AcpSchema.JSONRPCRequest(AcpSchema.METHOD_INITIALIZE, "1", initRequest);
		pair.clientTransport().sendMessage(jsonRpcRequest).block(TIMEOUT);

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(received.get()).isNotNull();
		assertThat(received.get()).isInstanceOf(AcpSchema.JSONRPCRequest.class);
	}

	@Test
	void agentCanSendToClient() throws InterruptedException {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AtomicReference<AcpSchema.JSONRPCMessage> received = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		// Client listens
		pair.clientTransport().connect(msg -> {
			return msg.doOnNext(m -> {
				received.set(m);
				latch.countDown();
			}).then(Mono.empty());
		}).subscribe();

		// Agent starts and sends
		pair.agentTransport().start(msg -> msg).subscribe();
		var initResponse = new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of());
		var jsonRpcResponse = new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, "1", initResponse, null);
		pair.agentTransport().sendMessage(jsonRpcResponse).block(TIMEOUT);

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(received.get()).isNotNull();
		assertThat(received.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);
	}

	@Test
	void closeGracefullyCompletesBothTransports() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		CountDownLatch agentLatch = new CountDownLatch(1);
		CountDownLatch clientLatch = new CountDownLatch(1);

		pair.agentTransport().start(msg -> msg).doFinally(signal -> agentLatch.countDown()).subscribe();
		pair.clientTransport().connect(msg -> msg).doFinally(signal -> clientLatch.countDown()).subscribe();

		pair.closeGracefully().block(TIMEOUT);

		try {
			assertThat(agentLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(clientLatch.await(5, TimeUnit.SECONDS)).isTrue();
		}
		catch (InterruptedException e) {
			fail("Test interrupted", e);
		}
	}

	@Test
	void sendIsDeliveredWhileAnotherThreadIsStillDeliveringItsMessage() throws Exception {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		List<Object> received = new CopyOnWriteArrayList<>();
		CountDownLatch firstInHandler = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch bothReceived = new CountDownLatch(2);

		// The first message is delivered on its sender's thread and held there, so that
		// thread is still emitting on the sink when the second send arrives.
		pair.agentTransport().start(msg -> msg.doOnNext(m -> {
			Object id = ((AcpSchema.JSONRPCRequest) m).id();
			if ("1".equals(id)) {
				firstInHandler.countDown();
				awaitQuietly(releaseFirst);
			}
			received.add(id);
			bothReceived.countDown();
		}).then(Mono.empty())).subscribe();

		ExecutorService senders = Executors.newFixedThreadPool(2);
		try {
			senders.submit(() -> pair.clientTransport().sendMessage(request("1")).block(TIMEOUT));
			assertThat(firstInHandler.await(5, TimeUnit.SECONDS)).isTrue();

			// Held well past the 100 ms a busy loop would wait before giving up.
			Future<?> second = senders.submit(() -> pair.clientTransport().sendMessage(request("2")).block(TIMEOUT));
			try {
				second.get(1, TimeUnit.SECONDS);
			}
			catch (TimeoutException stillWaiting) {
				// Waiting for the first delivery to finish is allowed; dropping is not.
			}
			releaseFirst.countDown();
			second.get(5, TimeUnit.SECONDS);

			assertThat(bothReceived.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(received).containsExactly("1", "2");
		}
		finally {
			releaseFirst.countDown();
			senders.shutdownNow();
			pair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void sendFromInsideDeliveryOnTheSameSinkIsDeliveredInOrder() throws Exception {
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
		}).then(Mono.empty())).subscribe();

		pair.clientTransport().sendMessage(request("1")).block(TIMEOUT);

		assertThat(bothReceived.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(received).containsExactly("1", "2");
		pair.closeGracefully().block(TIMEOUT);
	}

	private static AcpSchema.JSONRPCRequest request(String id) {
		return new AcpSchema.JSONRPCRequest(AcpSchema.METHOD_INITIALIZE, id,
				new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()));
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
