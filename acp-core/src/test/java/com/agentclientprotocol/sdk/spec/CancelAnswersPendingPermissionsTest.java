/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the client sends {@code session/cancel}, every {@code session/request_permission} the agent
 * is still waiting on for that session is answered with the {@code cancelled} outcome by the client
 * SDK itself, whatever the application's permission handler does or does not do afterwards.
 *
 * <p>
 * ACP spec 2797d331, prompt-turn.mdx:350: "The Client MUST respond to all pending
 * session/request_permission requests with the cancelled outcome." (also tool-calls.mdx:193 and the
 * schema's RequestPermissionOutcome). Derived requirement
 * ACP-V1-PROMPT-CLIENT-RESPOND-PENDING-PERMISSIONS, found by audit run
 * acp-v1-2797d331-r1-5bb2cf6-001.
 * </p>
 */
class CancelAnswersPendingPermissionsTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final InMemoryTransportPair pair = InMemoryTransportPair.create();

	private AcpAsyncAgent agent;

	private AcpAsyncClient client;

	@AfterEach
	void stop() {
		if (client != null) {
			client.close();
		}
		if (agent != null) {
			agent.close();
		}
		pair.closeGracefully().block(TIMEOUT);
	}

	// ACP spec 2797d331: prompt-turn.mdx:350, "MUST respond to all pending session/request_permission
	// requests with the cancelled outcome"
	@Test
	void sessionCancelAnswersThePendingPermissionRequestWithCancelled() throws Exception {
		CountDownLatch asked = new CountDownLatch(1);
		AtomicReference<AcpSchema.RequestPermissionResponse> answer = new AtomicReference<>();
		agent = AcpAgent.async(pair.agentTransport())
			.promptHandler((request, context) -> context.client()
				.requestPermission(new AcpSchema.RequestPermissionRequest(request.sessionId(),
						new AcpSchema.ToolCallUpdate("call-1", "Delete everything", null, null),
						List.of(new AcpSchema.PermissionOption("yes", "Allow once",
								AcpSchema.PermissionOptionKind.ALLOW_ONCE))))
				.doOnSubscribe(s -> asked.countDown())
				.doOnNext(answer::set)
				.thenReturn(AcpSchema.PromptResponse.cancelled()))
			.build();
		agent.start().block(TIMEOUT);

		AtomicInteger handlerCalls = new AtomicInteger();
		client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			// the application never answers: the user is still looking at the dialog
			.requestPermissionHandler(request -> {
				handlerCalls.incrementAndGet();
				return Mono.never();
			})
			.build();
		client.initialize().block(TIMEOUT);
		String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/w", List.of())).block(TIMEOUT).sessionId();
		CompletableFuture<AcpSchema.PromptResponse> prompt = client
			.prompt(AcpSchema.PromptRequest.text(sessionId, "go"))
			.toFuture();
		assertThat(asked.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(answer.get()).isNull();

		client.cancel(new AcpSchema.CancelNotification(sessionId)).block(TIMEOUT);

		AcpSchema.PromptResponse response = prompt.get(5, TimeUnit.SECONDS);
		assertThat(answer.get()).as("the agent's pending permission request was answered").isNotNull();
		assertThat(answer.get().outcome()).isInstanceOf(AcpSchema.PermissionCancelled.class);
		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.CANCELLED);
		assertThat(handlerCalls).hasValue(1);
	}

	// prompt-turn.mdx:350 names the pending requests of the cancelled turn; another session's request
	// keeps waiting for its user
	@Test
	void sessionCancelLeavesAnotherSessionsPermissionRequestPending() throws Exception {
		CountDownLatch asked = new CountDownLatch(1);
		AtomicReference<AcpSchema.RequestPermissionResponse> answer = new AtomicReference<>();
		agent = AcpAgent.async(pair.agentTransport())
			.promptHandler((request, context) -> context.client()
				.requestPermission(new AcpSchema.RequestPermissionRequest(request.sessionId(),
						new AcpSchema.ToolCallUpdate("call-1", "Write", null, null),
						List.of(new AcpSchema.PermissionOption("yes", "Allow once",
								AcpSchema.PermissionOptionKind.ALLOW_ONCE))))
				.doOnSubscribe(s -> asked.countDown())
				.doOnNext(answer::set)
				.thenReturn(AcpSchema.PromptResponse.endTurn()))
			.build();
		agent.start().block(TIMEOUT);
		client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.requestPermissionHandler(request -> Mono.never())
			.build();
		client.initialize().block(TIMEOUT);
		String first = client.newSession(new AcpSchema.NewSessionRequest("/a", List.of())).block(TIMEOUT).sessionId();
		String second = client.newSession(new AcpSchema.NewSessionRequest("/b", List.of())).block(TIMEOUT).sessionId();
		assertThat(first).isNotEqualTo(second);
		CompletableFuture<AcpSchema.PromptResponse> prompt = client.prompt(AcpSchema.PromptRequest.text(first, "go"))
			.toFuture();
		assertThat(asked.await(5, TimeUnit.SECONDS)).isTrue();

		client.cancel(new AcpSchema.CancelNotification(second)).block(TIMEOUT);

		Thread.sleep(300);
		assertThat(answer.get()).as("a cancel for another session answers nothing").isNull();
		assertThat(prompt).isNotDone();
	}

}
