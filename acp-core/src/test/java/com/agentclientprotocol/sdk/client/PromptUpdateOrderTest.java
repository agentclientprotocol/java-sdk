/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code prompt()} returns only once the session-update consumer has handled every update the
 * agent sent before its prompt response, so the updates a caller collects are complete when
 * the stop reason arrives. The consumers here are slow on purpose: before, {@code prompt()}
 * returned while they were still running.
 */
class PromptUpdateOrderTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final String SESSION = "order-session";

	private static final int UPDATES = 3;

	private final InMemoryTransportPair transportPair = InMemoryTransportPair.create();

	private AcpSyncAgent agent;

	@AfterEach
	void tearDown() {
		if (this.agent != null) {
			this.agent.close();
		}
		this.transportPair.closeGracefully().block(TIMEOUT);
	}

	/** An agent whose turn sends {@value #UPDATES} message chunks, then ends. */
	private void startAgent() {
		this.agent = AcpAgent.sync(this.transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> InitializeResponse.ok())
			.newSessionHandler(request -> new NewSessionResponse(SESSION, null, null))
			.setSessionModeHandler(request -> new AcpSchema.SetSessionModeResponse())
			.promptHandler((request, context) -> {
				for (int i = 1; i <= UPDATES; i++) {
					context.sendMessage("chunk " + i);
				}
				return PromptResponse.endTurn();
			})
			.build();
		this.agent.start();
	}

	private static PromptRequest prompt() {
		return new PromptRequest(SESSION, List.of(new TextContent("go")));
	}

	private static String text(AcpSchema.SessionNotification notification) {
		return ((TextContent) ((AgentMessageChunk) notification.update()).content()).text();
	}

	@Test
	void syncPromptReturnsAfterTheConsumerHandledEveryUpdateOfTheTurn() {
		startAgent();
		List<String> handled = new CopyOnWriteArrayList<>();
		AcpSyncClient client = AcpClient.sync(this.transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(notification -> {
				sleep(100);
				handled.add(text(notification));
			})
			.build();
		try {
			client.initialize();
			client.newSession(new NewSessionRequest("/workspace", List.of()));

			PromptResponse response = client.prompt(prompt());

			assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(handled).containsExactly("chunk 1", "chunk 2", "chunk 3");
		}
		finally {
			client.close();
		}
	}

	@Test
	void asyncPromptCompletesAfterTheConsumerHandledEveryUpdateOfTheTurn() {
		startAgent();
		List<String> handled = new CopyOnWriteArrayList<>();
		AcpAsyncClient client = AcpClient.async(this.transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(notification -> Mono.delay(Duration.ofMillis(100))
				.then(Mono.fromRunnable(() -> handled.add(text(notification)))))
			.build();
		try {
			client.initialize().block(TIMEOUT);
			client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

			PromptResponse response = client.prompt(prompt()).block(TIMEOUT);

			assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(handled).containsExactly("chunk 1", "chunk 2", "chunk 3");
		}
		finally {
			client.close();
		}
	}

	/**
	 * A consumer that calls the agent and waits for the answer, while the agent's later
	 * updates arrive first: that answer is not held behind the consumer that waits for it.
	 */
	@Test
	void aConsumerThatWaitsForARequestOfItsOwnDoesNotDeadlockThePrompt() {
		startAgent();
		List<String> handled = new CopyOnWriteArrayList<>();
		AtomicReference<AcpSyncClient> self = new AtomicReference<>();
		AcpSyncClient client = AcpClient.sync(this.transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(notification -> {
				if (handled.isEmpty()) {
					self.get().setSessionMode(new AcpSchema.SetSessionModeRequest(SESSION, "code"));
				}
				handled.add(text(notification));
			})
			.build();
		self.set(client);
		try {
			client.initialize();
			client.newSession(new NewSessionRequest("/workspace", List.of()));

			long start = System.nanoTime();
			client.prompt(prompt());

			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(TIMEOUT.dividedBy(2));
			assertThat(handled).containsExactly("chunk 1", "chunk 2", "chunk 3");
		}
		finally {
			client.close();
		}
	}

	/**
	 * The agent announces a tool call with a {@code tool_call} update, then asks permission for
	 * it: the permission handler runs only after the session update consumer handled the
	 * announcement, so a client can look the tool call up when it asks the user.
	 */
	@Test
	void anAgentRequestReachesItsHandlerAfterTheUpdatesSentBeforeIt() {
		this.agent = AcpAgent.sync(this.transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> InitializeResponse.ok())
			.newSessionHandler(request -> new NewSessionResponse(SESSION, null, null))
			.promptHandler((request, context) -> {
				context.sendUpdate(SESSION, new AcpSchema.ToolCall(null, "call-1", "Edit file", null,
						AcpSchema.ToolKind.EDIT, AcpSchema.ToolCallStatus.PENDING, null, null, null, null, null));
				context.requestPermission(new AcpSchema.RequestPermissionRequest(SESSION,
						new AcpSchema.ToolCallUpdate("call-1", "Edit file", AcpSchema.ToolKind.EDIT,
								AcpSchema.ToolCallStatus.PENDING),
						List.of(new AcpSchema.PermissionOption("allow", "Allow", AcpSchema.PermissionOptionKind.ALLOW_ONCE))));
				return PromptResponse.endTurn();
			})
			.build();
		this.agent.start();

		List<String> announced = new CopyOnWriteArrayList<>();
		List<Boolean> knownWhenAsked = new CopyOnWriteArrayList<>();
		AcpSyncClient client = AcpClient.sync(this.transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(notification -> {
				sleep(300);
				if (notification.update() instanceof AcpSchema.ToolCall toolCall) {
					announced.add(toolCall.toolCallId());
				}
			})
			.requestPermissionHandler(request -> {
				knownWhenAsked.add(announced.contains(request.toolCall().toolCallId()));
				return new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected("allow"));
			})
			.build();
		try {
			client.initialize();
			client.newSession(new NewSessionRequest("/workspace", List.of()));

			client.prompt(prompt());

			assertThat(knownWhenAsked).containsExactly(true);
		}
		finally {
			client.close();
		}
	}

	/**
	 * A consumer that waits for a prompt of its own, during which the agent asks the client
	 * something: the agent's request is not held behind the consumer that waits for it.
	 */
	@Test
	void aConsumerWaitingForAPromptDoesNotHoldTheAgentsRequestsOfThatPrompt() {
		this.agent = AcpAgent.sync(this.transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> InitializeResponse.ok())
			.newSessionHandler(request -> new NewSessionResponse(SESSION, null, null))
			.promptHandler((request, context) -> {
				String text = ((TextContent) request.prompt().get(0)).text();
				if ("first".equals(text)) {
					context.sendMessage("trigger");
				}
				else {
					context.requestPermission(new AcpSchema.RequestPermissionRequest(SESSION,
							new AcpSchema.ToolCallUpdate("call-2", "Edit", AcpSchema.ToolKind.EDIT,
									AcpSchema.ToolCallStatus.PENDING),
							List.of(new AcpSchema.PermissionOption("allow", "Allow",
									AcpSchema.PermissionOptionKind.ALLOW_ONCE))));
				}
				return PromptResponse.endTurn();
			})
			.build();
		this.agent.start();

		AtomicReference<AcpSyncClient> self = new AtomicReference<>();
		List<String> nested = new CopyOnWriteArrayList<>();
		AcpSyncClient client = AcpClient.sync(this.transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(notification -> {
				if (nested.isEmpty()) {
					nested.add("asking");
					// The agent answers only after the client answered its permission request.
					nested.add(self.get()
						.prompt(new PromptRequest(SESSION + "-2", List.of(new TextContent("second"))))
						.stopReason()
						.toString());
				}
			})
			.requestPermissionHandler(
					request -> new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected("allow")))
			.build();
		self.set(client);
		try {
			client.initialize();
			client.newSession(new NewSessionRequest("/workspace", List.of()));

			long start = System.nanoTime();
			client.prompt(new PromptRequest(SESSION, List.of(new TextContent("first"))));

			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(TIMEOUT.dividedBy(2));
			assertThat(nested).containsExactly("asking", "end_turn");
		}
		finally {
			client.close();
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

}
