/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A framework sets a default session update handler, for example one that logs at DEBUG; the
 * application's own handler replaces it rather than running beside it, whichever is registered
 * first.
 */
class DefaultSessionUpdateHandlerTest {

	private static final AcpSchema.JSONRPCNotification UPDATE = new AcpSchema.JSONRPCNotification(
			AcpSchema.JSONRPC_VERSION, AcpSchema.METHOD_SESSION_UPDATE,
			Map.of("sessionId", "s-1", "update", Map.of("sessionUpdate", "agent_message_chunk", "content",
					Map.of("type", "text", "text", "hi"))));

	@Test
	void theDefaultReceivesUpdatesWhenTheApplicationAddsNone() {
		List<String> received = new CopyOnWriteArrayList<>();
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport)
			.defaultSessionUpdateHandler(record(received, "default"))
			.build();

		transport.simulateIncomingMessage(UPDATE);

		eventually(() -> assertThat(received).containsExactly("default"));
		client.close();
	}

	@Test
	void anApplicationConsumerReplacesTheDefault() {
		List<String> received = new CopyOnWriteArrayList<>();
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport)
			.defaultSessionUpdateHandler(record(received, "default"))
			.sessionUpdateHandler(record(received, "app"))
			.build();

		transport.simulateIncomingMessage(UPDATE);
		transport.simulateIncomingMessage(UPDATE);

		eventually(() -> assertThat(received).containsExactly("app", "app"));
		client.close();
	}

	@Test
	void theOrderOfRegistrationDoesNotMatter() {
		List<String> received = new CopyOnWriteArrayList<>();
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport)
			.sessionUpdateHandler(record(received, "app"))
			.defaultSessionUpdateHandler(record(received, "default"))
			.build();

		transport.simulateIncomingMessage(UPDATE);

		eventually(() -> assertThat(received).containsExactly("app"));
		client.close();
	}

	@Test
	void theSyncBuilderHasTheSameDefault() {
		List<String> received = new CopyOnWriteArrayList<>();
		MockAcpClientTransport transport = new MockAcpClientTransport();
		Consumer<AcpSchema.SessionNotification> defaultConsumer = notification -> received.add("default");
		AcpSyncClient defaultOnly = AcpClient.sync(transport).defaultSessionUpdateHandler(defaultConsumer).build();
		transport.simulateIncomingMessage(UPDATE);
		eventually(() -> assertThat(received).containsExactly("default"));
		defaultOnly.close();

		received.clear();
		MockAcpClientTransport other = new MockAcpClientTransport();
		AcpSyncClient replaced = AcpClient.sync(other)
			.defaultSessionUpdateHandler(defaultConsumer)
			.sessionUpdateHandler(notification -> received.add("app"))
			.build();
		other.simulateIncomingMessage(UPDATE);
		eventually(() -> assertThat(received).containsExactly("app"));
		replaced.close();
	}

	@Test
	void aSecondDefaultIsRefused() {
		AcpClient.AsyncSpec spec = AcpClient.async(new MockAcpClientTransport())
			.defaultSessionUpdateHandler(notification -> Mono.empty());

		assertThatThrownBy(() -> spec.defaultSessionUpdateHandler(notification -> Mono.empty()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("defaultSessionUpdateHandler");
		assertThatThrownBy(() -> spec.defaultSessionUpdateHandler(null)).isInstanceOf(IllegalArgumentException.class);
	}

	/** Retries the assertion for up to five seconds: updates are delivered asynchronously. */
	private static void eventually(Runnable assertion) {
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (true) {
			try {
				assertion.run();
				// Give a wrongly delivered extra update the chance to show up.
				Thread.sleep(100);
				assertion.run();
				return;
			}
			catch (AssertionError e) {
				if (System.nanoTime() > deadline) {
					throw e;
				}
				Thread.onSpinWait();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		}
	}

	private static java.util.function.Function<AcpSchema.SessionNotification, Mono<Void>> record(List<String> received,
			String name) {
		return notification -> Mono.fromRunnable(() -> received.add(name));
	}

}
