/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code session/cancel} reaches a streaming {@code @Prompt}: the {@code Multi}'s
 * subscription is cancelled and the turn ends {@code cancelled}.
 */
class PromptCancellationTest {

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(TickingAgent.class, InMemoryAgentTransport.class))
		.overrideConfigKey("quarkus.acp.agent.shutdown-on-transport-end", "false");

	@Inject
	InMemoryAgentTransport transport;

	@Test
	void cancelStopsTheStreamAndEndsTheTurnCancelled() {
		AcpSyncClient client = transport.client();
		client.initialize();
		String sessionId = client.newSession(InMemoryAgentTransport.newSession()).sessionId();

		CompletableFuture<AcpSchema.PromptResponse> response = CompletableFuture
			.supplyAsync(() -> client.prompt(InMemoryAgentTransport.prompt(sessionId, "tick")));
		waitFor(() -> transport.messages.contains("tick"));
		client.cancel(new AcpSchema.CancelNotification(sessionId));

		assertThat(response.join().stopReason()).isEqualTo(AcpSchema.StopReason.CANCELLED);
		int ticks = transport.messages.size();
		sleep(300);
		assertThat(transport.messages).as("no tick after the cancel").hasSize(ticks);
	}

	private static void waitFor(java.util.function.BooleanSupplier condition) {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime()).as("waited 10 s").isLessThan(deadline);
			sleep(20);
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@AcpAgent
	public static class TickingAgent {

		@Prompt
		Multi<String> prompt() {
			return Multi.createFrom().ticks().every(Duration.ofMillis(50)).map(tick -> "tick");
		}

	}

}
