/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Extension methods sent from inside the agent's handlers: a prompt handler reaches the client's
 * extension handlers through its context's {@code client()}, and an extension handler that takes
 * the agent as a second parameter can call the client back.
 */
class ExtensionMethodsFromHandlersTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final TypeRef<Ping> PING = new TypeRef<>() {
	};

	private static final TypeRef<Pong> PONG = new TypeRef<>() {
	};

	record Ping(String text) {
	}

	record Pong(String text, int length) {
	}

	private InMemoryTransportPair pair;

	private final CompletableFuture<Ping> typedNote = new CompletableFuture<>();

	private final CompletableFuture<Object> rawNote = new CompletableFuture<>();

	@BeforeEach
	void setUp() {
		pair = InMemoryTransportPair.create();
	}

	@AfterEach
	void tearDown() {
		pair.closeGracefully().block(TIMEOUT);
	}

	/** A client that answers {@code _test/ping} and {@code _test/raw} and records two notifications. */
	private AcpAsyncClient client() {
		AcpAsyncClient client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.extRequestHandler("_test/ping", PING,
					ping -> Mono.just(new Pong("client: " + ping.text(), ping.text().length())))
			.extRequestHandler("_test/raw", params -> Mono.just(Map.of("client", params)))
			.extNotificationHandler("_test/typed", PING, ping -> Mono.fromRunnable(() -> typedNote.complete(ping)))
			.extNotificationHandler("_test/rawnote", params -> Mono.fromRunnable(() -> rawNote.complete(params)))
			.build();
		client.initialize().block(TIMEOUT);
		return client;
	}

	private static AcpSchema.PromptRequest prompt() {
		return new AcpSchema.PromptRequest("s1", List.of(new AcpSchema.TextContent("go")));
	}

	private void assertNotesArrived() throws Exception {
		assertThat(typedNote.get(5, TimeUnit.SECONDS)).isEqualTo(new Ping("note"));
		assertThat(rawNote.get(5, TimeUnit.SECONDS)).isEqualTo(Map.of("k", 1));
	}

	// ---------------------------------------------------------------------------
	// From a prompt handler, through context.client()
	// ---------------------------------------------------------------------------

	@Test
	void asyncPromptHandlerSendsExtRequestsAndNotificationsThroughItsContext() throws Exception {
		AtomicReference<Pong> typed = new AtomicReference<>();
		AtomicReference<Object> raw = new AtomicReference<>();
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.promptHandler((request, context) -> context.client()
				.sendExtRequest("_test/ping", new Ping("hello"), PONG)
				.doOnNext(typed::set)
				.then(context.client().sendExtRequest("_test/raw", Map.of("q", 1)).doOnNext(raw::set))
				.then(context.client().sendExtNotification("_test/typed", new Ping("note")))
				.then(context.client().sendExtNotification("_test/rawnote", Map.of("k", 1)))
				.thenReturn(AcpSchema.PromptResponse.endTurn()))
			.build();
		agent.start().block(TIMEOUT);
		AcpAsyncClient client = client();
		try {
			client.prompt(prompt()).block(TIMEOUT);

			assertThat(typed.get()).isEqualTo(new Pong("client: hello", 5));
			assertThat(raw.get()).isEqualTo(Map.of("client", Map.of("q", 1)));
			assertNotesArrived();
		}
		finally {
			agent.close();
		}
	}

	@Test
	void syncPromptHandlerSendsExtRequestsAndNotificationsThroughItsContext() throws Exception {
		AtomicReference<Pong> typed = new AtomicReference<>();
		AtomicReference<Object> raw = new AtomicReference<>();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport()).promptHandler((request, context) -> {
			typed.set(context.client().sendExtRequest("_test/ping", new Ping("hi"), PONG));
			raw.set(context.client().sendExtRequest("_test/raw", List.of("x")));
			context.client().sendExtNotification("_test/typed", new Ping("note"));
			context.client().sendExtNotification("_test/rawnote", Map.of("k", 1));
			return AcpSchema.PromptResponse.endTurn();
		}).build();
		agent.start();
		AcpAsyncClient client = client();
		try {
			client.prompt(prompt()).block(TIMEOUT);

			assertThat(typed.get()).isEqualTo(new Pong("client: hi", 2));
			assertThat(raw.get()).isEqualTo(Map.of("client", List.of("x")));
			assertNotesArrived();
		}
		finally {
			agent.close();
		}
	}

	// ---------------------------------------------------------------------------
	// Agent-aware extension handlers
	// ---------------------------------------------------------------------------

	@Test
	void asyncAgentAwareExtHandlersReceiveTheBuiltAgentAndCallTheClientBack() throws Exception {
		List<AcpAsyncAgent> received = new CopyOnWriteArrayList<>();
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn()))
			.extRequestHandler("_agent/ping", PING, (ping, self) -> {
				received.add(self);
				return self.sendExtRequest("_test/ping", ping, PONG);
			})
			.extRequestHandler("_agent/raw", (params, self) -> {
				received.add(self);
				return self.sendExtRequest("_test/raw", params);
			})
			.extNotificationHandler("_agent/typed", PING, (ping, self) -> {
				received.add(self);
				return self.sendExtNotification("_test/typed", ping);
			})
			.extNotificationHandler("_agent/rawnote", (params, self) -> {
				received.add(self);
				return self.sendExtNotification("_test/rawnote", params);
			})
			.build();
		agent.start().block(TIMEOUT);
		AcpAsyncClient client = client();
		try {
			assertThat(client.sendExtRequest("_agent/ping", new Ping("abc"), PONG).block(TIMEOUT))
				.isEqualTo(new Pong("client: abc", 3));
			assertThat(client.sendExtRequest("_agent/raw", Map.of("n", 2)).block(TIMEOUT))
				.isEqualTo(Map.of("client", Map.of("n", 2)));
			client.sendExtNotification("_agent/typed", new Ping("note")).block(TIMEOUT);
			client.sendExtNotification("_agent/rawnote", Map.of("k", 1)).block(TIMEOUT);

			assertNotesArrived();
			assertThat(received).hasSize(4).allSatisfy(self -> assertThat(self).isSameAs(agent));
		}
		finally {
			agent.close();
		}
	}

	@Test
	void syncAgentAwareExtHandlersReceiveTheBuiltAgentAndCallTheClientBack() throws Exception {
		List<AcpSyncAgent> received = new CopyOnWriteArrayList<>();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.promptHandler((request, context) -> AcpSchema.PromptResponse.endTurn())
			.extRequestHandler("_agent/ping", PING, (ping, self) -> {
				received.add(self);
				return self.sendExtRequest("_test/ping", ping, PONG);
			})
			.extRequestHandler("_agent/raw", (params, self) -> {
				received.add(self);
				return self.sendExtRequest("_test/raw", params);
			})
			.extNotificationHandler("_agent/typed", PING, (ping, self) -> {
				received.add(self);
				self.sendExtNotification("_test/typed", ping);
			})
			.extNotificationHandler("_agent/rawnote", (params, self) -> {
				received.add(self);
				self.sendExtNotification("_test/rawnote", params);
			})
			.build();
		agent.start();
		AcpAsyncClient client = client();
		try {
			assertThat(client.sendExtRequest("_agent/ping", new Ping("sync"), PONG).block(TIMEOUT))
				.isEqualTo(new Pong("client: sync", 4));
			assertThat(client.sendExtRequest("_agent/raw", List.of(1)).block(TIMEOUT))
				.isEqualTo(Map.of("client", List.of(1)));
			client.sendExtNotification("_agent/typed", new Ping("note")).block(TIMEOUT);
			client.sendExtNotification("_agent/rawnote", Map.of("k", 1)).block(TIMEOUT);

			assertNotesArrived();
			assertThat(received).hasSize(4).allSatisfy(self -> assertThat(self).isSameAs(agent));
		}
		finally {
			agent.close();
		}
	}

	@Test
	void agentAwareExtSettersRejectNamesWithoutUnderscore() {
		AcpAgent.AsyncAgentBuilder agent = AcpAgent.async(pair.agentTransport());
		AcpAgent.SyncAgentBuilder syncAgent = AcpAgent.sync(pair.agentTransport());

		assertThatThrownBy(() -> agent.extRequestHandler("session/prompt", (params, self) -> Mono.just(Map.of())))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("session/prompt");
		assertThatThrownBy(() -> agent.extNotificationHandler("x", PING, (ping, self) -> Mono.empty()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> agent.extNotificationHandler("x", (params, self) -> Mono.empty()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> syncAgent.extRequestHandler("x", PING, (ping, self) -> ping))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> syncAgent.extNotificationHandler("x", (params, self) -> {
		})).isInstanceOf(IllegalArgumentException.class);
	}

}
