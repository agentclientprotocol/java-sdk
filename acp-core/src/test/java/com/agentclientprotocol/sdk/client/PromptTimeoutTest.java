/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A prompt turn is bounded by the client's {@code promptTimeout}, not by its request timeout: a turn
 * that runs longer than the request timeout (a 40-second turn under the default 30-second request
 * timeout, scaled down here) still gets its answer and is never cancelled.
 */
class PromptTimeoutTest {

	private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(200);

	private static final Duration TURN = Duration.ofMillis(800);

	/** Answers initialize at once and every session/prompt after {@link #TURN}. */
	private static MockAcpClientTransport slowPromptAgent() {
		return new MockAcpClientTransport((t, msg) -> {
			if (msg instanceof AcpSchema.JSONRPCRequest request
					&& AcpSchema.METHOD_INITIALIZE.equals(request.method())) {
				t.simulateIncomingMessage(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(),
						AcpSchema.InitializeResponse.ok(), null));
			}
			if (msg instanceof AcpSchema.JSONRPCRequest request
					&& AcpSchema.METHOD_SESSION_PROMPT.equals(request.method())) {
				Mono.delay(TURN, Schedulers.parallel())
					.subscribe(tick -> {
						try {
							t.simulateIncomingMessage(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION,
									request.id(), new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN), null));
						}
						catch (RuntimeException closed) {
							// the client was closed before the turn ended
						}
					});
			}
		});
	}

	private static AcpSchema.PromptRequest prompt() {
		return new AcpSchema.PromptRequest("s1", List.of(new AcpSchema.TextContent("work for a while")));
	}

	private static boolean sentCancelRequest(MockAcpClientTransport transport) {
		return transport.getSentMessages()
			.stream()
			.anyMatch(m -> m instanceof AcpSchema.JSONRPCNotification n
					&& AcpSchema.METHOD_CANCEL_REQUEST.equals(n.method()));
	}

	@Test
	void asyncPromptLongerThanRequestTimeoutIsNotCutOff() {
		MockAcpClientTransport transport = slowPromptAgent();
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(REQUEST_TIMEOUT).build();
		try {
			client.initialize().block(Duration.ofSeconds(10));
			AcpSchema.PromptResponse response = client.prompt(prompt()).block(Duration.ofSeconds(10));
			assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(sentCancelRequest(transport)).as("no $/cancel_request for a prompt").isFalse();
		}
		finally {
			client.close();
		}
	}

	@Test
	void syncPromptLongerThanRequestTimeoutIsNotCutOff() {
		MockAcpClientTransport transport = slowPromptAgent();
		AcpSyncClient client = AcpClient.sync(transport).requestTimeout(REQUEST_TIMEOUT).build();
		try {
			client.initialize();
			assertThat(client.prompt(prompt()).stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(sentCancelRequest(transport)).isFalse();
		}
		finally {
			client.close();
		}
	}

	@Test
	void promptTimeoutBoundsTheTurnAndCancelsIt() {
		MockAcpClientTransport transport = slowPromptAgent();
		AcpAsyncClient client = AcpClient.async(transport)
			.requestTimeout(Duration.ofSeconds(10))
			.promptTimeout(REQUEST_TIMEOUT)
			.build();
		try {
			client.initialize().block(Duration.ofSeconds(10));
			assertThatThrownBy(() -> client.prompt(prompt()).block(Duration.ofSeconds(10)))
				.hasCauseInstanceOf(TimeoutException.class);
			long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
			while (!sentCancelRequest(transport) && System.nanoTime() < deadline) {
				Thread.onSpinWait();
			}
			assertThat(sentCancelRequest(transport)).isTrue();
		}
		finally {
			client.close();
		}
	}

	@Test
	void syncPromptTimeoutBoundsTheTurn() {
		MockAcpClientTransport transport = slowPromptAgent();
		AcpSyncClient client = AcpClient.sync(transport).promptTimeout(REQUEST_TIMEOUT).build();
		try {
			client.initialize();
			assertThatThrownBy(() -> client.prompt(prompt())).hasRootCauseInstanceOf(TimeoutException.class);
		}
		finally {
			client.close();
		}
	}

	@Test
	void promptTimeoutRejectsNullAndNegative() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		assertThatThrownBy(() -> AcpClient.async(transport).promptTimeout(null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AcpClient.async(transport).promptTimeout(Duration.ofSeconds(-1)))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
