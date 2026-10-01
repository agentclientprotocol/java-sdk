/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Extension methods ({@code _}-prefixed, ACP v1 Extensibility) in both directions: each
 * side registers handlers for custom requests and notifications and sends them to its
 * peer, with a typed or a raw result.
 */
class ExtensionMethodsTest {

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

	@BeforeEach
	void setUp() {
		pair = InMemoryTransportPair.create();
	}

	@AfterEach
	void tearDown() {
		pair.closeGracefully().block(TIMEOUT);
	}

	private static Mono<Pong> pong(Ping ping) {
		return Mono.just(new Pong("pong: " + ping.text(), ping.text().length()));
	}

	// ---------------------------------------------------------------------------
	// Client -> agent
	// ---------------------------------------------------------------------------

	@Test
	void clientSendsTypedExtRequestToAgent() {
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.extRequestHandler("_test/ping", PING, ExtensionMethodsTest::pong)
			.build();
		agent.start().block(TIMEOUT);
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();

		Pong pong = client.sendExtRequest("_test/ping", new Ping("hi"), PONG).block(TIMEOUT);

		assertThat(pong).isEqualTo(new Pong("pong: hi", 2));
	}

	@Test
	void clientSendsRawExtRequestToAgent() {
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.extRequestHandler("_test/raw", params -> Mono.just(Map.of("echo", params, "list", List.of(1, 2))))
			.build();
		agent.start().block(TIMEOUT);
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();

		Object result = client.sendExtRequest("_test/raw", Map.of("a", "b")).block(TIMEOUT);

		assertThat(result).isEqualTo(Map.of("echo", Map.of("a", "b"), "list", List.of(1, 2)));
	}

	@Test
	void clientSendsExtNotificationToAgent() throws Exception {
		CompletableFuture<Ping> typed = new CompletableFuture<>();
		CompletableFuture<Object> raw = new CompletableFuture<>();
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.extNotificationHandler("_test/typed", PING, ping -> Mono.fromRunnable(() -> typed.complete(ping)))
			.extNotificationHandler("_test/raw", params -> Mono.fromRunnable(() -> raw.complete(params)))
			.build();
		agent.start().block(TIMEOUT);
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();

		client.sendExtNotification("_test/typed", new Ping("t")).block(TIMEOUT);
		client.sendExtNotification("_test/raw", Map.of("k", 1)).block(TIMEOUT);

		assertThat(typed.get(5, TimeUnit.SECONDS)).isEqualTo(new Ping("t"));
		assertThat(raw.get(5, TimeUnit.SECONDS)).isEqualTo(Map.of("k", 1));
	}

	@Test
	void syncClientAndSyncAgentExchangeExtMessages() throws Exception {
		CompletableFuture<Ping> notified = new CompletableFuture<>();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.extRequestHandler("_test/ping", PING, ping -> new Pong("sync: " + ping.text(), 0))
			.extRequestHandler("_test/raw", params -> Map.of("got", params))
			.extNotificationHandler("_test/note", PING, notified::complete)
			.build();
		agent.start();
		AcpSyncClient client = AcpClient.sync(pair.clientTransport()).requestTimeout(TIMEOUT).build();

		assertThat(client.sendExtRequest("_test/ping", new Ping("x"), PONG)).isEqualTo(new Pong("sync: x", 0));
		assertThat(client.sendExtRequest("_test/raw", Map.of("q", true))).isEqualTo(Map.of("got", Map.of("q", true)));
		client.sendExtNotification("_test/note", new Ping("n"));

		assertThat(notified.get(5, TimeUnit.SECONDS)).isEqualTo(new Ping("n"));
	}

	@Test
	void syncAgentRawNotificationHandlerReceivesParams() throws Exception {
		CompletableFuture<Object> notified = new CompletableFuture<>();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.extNotificationHandler("_test/note", notified::complete)
			.build();
		agent.start();
		AcpSyncClient client = AcpClient.sync(pair.clientTransport()).requestTimeout(TIMEOUT).build();

		client.sendExtNotification("_test/note", Map.of("v", "w"));

		assertThat(notified.get(5, TimeUnit.SECONDS)).isEqualTo(Map.of("v", "w"));
	}

	// ---------------------------------------------------------------------------
	// Agent -> client
	// ---------------------------------------------------------------------------

	@Test
	void agentSendsTypedAndRawExtRequestsToClient() {
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport()).build();
		agent.start().block(TIMEOUT);
		AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.extRequestHandler("_test/ping", PING, ExtensionMethodsTest::pong)
			.extRequestHandler("_test/raw", params -> Mono.just(List.of("a", params)))
			.build();

		assertThat(agent.sendExtRequest("_test/ping", new Ping("abc"), PONG).block(TIMEOUT))
			.isEqualTo(new Pong("pong: abc", 3));
		assertThat(agent.sendExtRequest("_test/raw", Map.of("n", 1)).block(TIMEOUT))
			.isEqualTo(List.of("a", Map.of("n", 1)));
	}

	@Test
	void agentSendsExtNotificationsToClient() throws Exception {
		CompletableFuture<Ping> typed = new CompletableFuture<>();
		CompletableFuture<Object> raw = new CompletableFuture<>();
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport()).build();
		agent.start().block(TIMEOUT);
		AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.extNotificationHandler("_test/typed", PING, ping -> Mono.fromRunnable(() -> typed.complete(ping)))
			.extNotificationHandler("_test/raw", params -> Mono.fromRunnable(() -> raw.complete(params)))
			.build();

		agent.sendExtNotification("_test/typed", new Ping("t")).block(TIMEOUT);
		agent.sendExtNotification("_test/raw", Map.of("k", "v")).block(TIMEOUT);

		assertThat(typed.get(5, TimeUnit.SECONDS)).isEqualTo(new Ping("t"));
		assertThat(raw.get(5, TimeUnit.SECONDS)).isEqualTo(Map.of("k", "v"));
	}

	@Test
	void syncAgentSendsToSyncClientHandlers() throws Exception {
		CompletableFuture<Ping> typed = new CompletableFuture<>();
		CompletableFuture<Object> raw = new CompletableFuture<>();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport()).build();
		agent.start();
		AcpClient.sync(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.extRequestHandler("_test/ping", PING, ping -> new Pong(ping.text(), 1))
			.extRequestHandler("_test/raw", params -> Map.of("raw", params))
			.extNotificationHandler("_test/typed", PING, typed::complete)
			.extNotificationHandler("_test/raw", raw::complete)
			.build();

		assertThat(agent.sendExtRequest("_test/ping", new Ping("s"), PONG)).isEqualTo(new Pong("s", 1));
		assertThat(agent.sendExtRequest("_test/raw", Map.of("x", 2))).isEqualTo(Map.of("raw", Map.of("x", 2)));
		agent.sendExtNotification("_test/typed", new Ping("y"));
		agent.sendExtNotification("_test/raw", Map.of("z", 3));

		assertThat(typed.get(5, TimeUnit.SECONDS)).isEqualTo(new Ping("y"));
		assertThat(raw.get(5, TimeUnit.SECONDS)).isEqualTo(Map.of("z", 3));
	}

	// ---------------------------------------------------------------------------
	// Unhandled extension methods (ACP v1 Extensibility)
	// ---------------------------------------------------------------------------

	@Test
	void unhandledExtRequestIsMethodNotFoundAndUnhandledNotificationIsIgnored() {
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.extRequestHandler("_test/ping", PING, ExtensionMethodsTest::pong)
			.build();
		agent.start().block(TIMEOUT);
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();

		assertThatThrownBy(() -> client.sendExtRequest("_test/unknown", Map.of()).block(TIMEOUT))
			.isInstanceOfSatisfying(AcpError.class,
					error -> assertThat(error.getCode()).isEqualTo(AcpErrorCodes.METHOD_NOT_FOUND));
		client.sendExtNotification("_test/unknown", Map.of()).block(TIMEOUT);

		// The connection survives both.
		assertThat(client.sendExtRequest("_test/ping", new Ping("ok"), PONG).block(TIMEOUT))
			.isEqualTo(new Pong("pong: ok", 2));
	}

	@Test
	void clientAnswersUnhandledExtRequestWithMethodNotFound() {
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport()).build();
		agent.start().block(TIMEOUT);
		AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();

		assertThatThrownBy(() -> agent.sendExtRequest("_test/unknown", Map.of()).block(TIMEOUT))
			.isInstanceOfSatisfying(AcpError.class,
					error -> assertThat(error.getCode()).isEqualTo(AcpErrorCodes.METHOD_NOT_FOUND));
		agent.sendExtNotification("_test/unknown", Map.of()).block(TIMEOUT);
	}

	@Test
	void extHandlerThatProducesNoResultIsAnsweredWithAnError() {
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.extRequestHandler("_test/empty", params -> Mono.empty())
			.build();
		agent.start().block(TIMEOUT);
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();

		assertThatThrownBy(() -> client.sendExtRequest("_test/empty", Map.of()).block(TIMEOUT))
			.isInstanceOfSatisfying(AcpError.class,
					error -> assertThat(error.getCode()).isEqualTo(AcpErrorCodes.INTERNAL_ERROR));
	}

	// ---------------------------------------------------------------------------
	// Names must start with an underscore
	// ---------------------------------------------------------------------------

	@Test
	void buildersRejectNamesWithoutUnderscore() {
		AcpAgent.AsyncAgentBuilder agent = AcpAgent.async(pair.agentTransport());
		AcpAgent.SyncAgentBuilder syncAgent = AcpAgent.sync(pair.agentTransport());
		AcpClient.AsyncSpec client = AcpClient.async(pair.clientTransport());
		AcpClient.SyncSpec syncClient = AcpClient.sync(pair.clientTransport());

		assertThatThrownBy(() -> agent.extRequestHandler("session/prompt", params -> Mono.just(Map.of())))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("session/prompt");
		assertThatThrownBy(() -> agent.extRequestHandler("x", PING, ExtensionMethodsTest::pong))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> agent.extNotificationHandler("session/cancel", params -> Mono.empty()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> agent.extNotificationHandler("x", PING, ping -> Mono.empty()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> syncAgent.extRequestHandler("x", params -> Map.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> syncAgent.extRequestHandler("x", PING, ping -> ping))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> syncAgent.extNotificationHandler("x", params -> {
		})).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> syncAgent.extNotificationHandler("x", PING, ping -> {
		})).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> client.extRequestHandler("fs/read_text_file", params -> Mono.just(Map.of())))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> client.extRequestHandler("x", PING, ExtensionMethodsTest::pong))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> client.extNotificationHandler("session/update", params -> Mono.empty()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> client.extNotificationHandler("x", PING, ping -> Mono.empty()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> syncClient.extRequestHandler("x", params -> Map.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> syncClient.extRequestHandler("x", PING, ping -> ping))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> syncClient.extNotificationHandler("x", params -> {
		})).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> syncClient.extNotificationHandler("x", PING, ping -> {
		})).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> agent.extRequestHandler("", params -> Mono.just(Map.of())))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void sendersRejectNamesWithoutUnderscore() {
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport()).build();
		agent.start().block(TIMEOUT);
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		AcpSyncAgent syncAgent = new AcpSyncAgent(agent);
		AcpSyncClient syncClient = new AcpSyncClient(client);

		assertThatThrownBy(() -> client.sendExtRequest("session/prompt", Map.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("session/prompt");
		assertThatThrownBy(() -> client.sendExtRequest("initialize", Map.of(), PONG))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> client.sendExtNotification("session/cancel", Map.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> agent.sendExtRequest("fs/read_text_file", Map.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> agent.sendExtRequest("x", Map.of(), PONG))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> agent.sendExtNotification("session/update", Map.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> syncAgent.sendExtRequest("x", Map.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> syncAgent.sendExtNotification("x", Map.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> syncClient.sendExtRequest("x", Map.of(), PONG))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> syncClient.sendExtNotification("x", Map.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void agentNotStartedFailsExtSends() {
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport()).build();

		assertThatThrownBy(() -> agent.sendExtRequest("_test/x", Map.of()).block(TIMEOUT))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> agent.sendExtNotification("_test/x", Map.of()).block(TIMEOUT))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void extHandlersDoNotNeedInitialization() {
		// Extension methods are outside the protocol's lifecycle: the SDK does not gate
		// them on initialize, the peer decides.
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok()))
			.extRequestHandler("_test/ping", PING, ExtensionMethodsTest::pong)
			.build();
		agent.start().block(TIMEOUT);
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();

		assertThat(client.sendExtRequest("_test/ping", new Ping("pre"), PONG).block(TIMEOUT))
			.isEqualTo(new Pong("pong: pre", 3));
	}

}
