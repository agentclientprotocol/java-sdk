/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.transport.InMemoryTransportPair;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The application's handler beans on the client the integrations build: every update handler
 * gets every update, the permission handler answers, and without one the client refuses.
 */
class AcpClientHandlersTest {

	private static final AcpSchema.PermissionOption ALLOW = new AcpSchema.PermissionOption("allow", "Allow",
			AcpSchema.PermissionOptionKind.ALLOW_ONCE, null);

	private static final AcpSchema.PermissionOption REJECT = new AcpSchema.PermissionOption("reject", "Reject",
			AcpSchema.PermissionOptionKind.REJECT_ONCE, null);

	@Test
	void updateHandlersGetEveryUpdateAndThePermissionHandlerAnswers() {
		List<String> first = new CopyOnWriteArrayList<>();
		List<String> second = new CopyOnWriteArrayList<>();
		List<String> asked = new CopyOnWriteArrayList<>();
		AcpClientHandlers handlers = AcpClientHandlers.of(
				List.of(notification -> first.add(text(notification)), notification -> second.add(text(notification))),
				List.of(request -> {
					asked.add(request.toolCall().toolCallId());
					return new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected("allow"));
				}));

		String answer = turn(handlers);

		assertThat(answer).isEqualTo("allow");
		assertThat(first).containsExactly("working");
		assertThat(second).containsExactly("working");
		assertThat(asked).containsExactly("tool-1");
	}

	@Test
	void withoutAPermissionHandlerTheClientRefuses() {
		assertThat(turn(AcpClientHandlers.NONE)).isEqualTo("reject");
	}

	@Test
	void theRefusalPicksARejectOptionElseCancels() {
		AcpSchema.PermissionOption rejectAlways = new AcpSchema.PermissionOption("never", "Never",
				AcpSchema.PermissionOptionKind.REJECT_ALWAYS, null);
		assertThat(AcpClientHandlers.refuse(request(List.of(ALLOW, rejectAlways, REJECT))).outcome())
			.isEqualTo(new AcpSchema.PermissionSelected("reject"));
		assertThat(AcpClientHandlers.refuse(request(List.of(ALLOW, rejectAlways))).outcome())
			.isEqualTo(new AcpSchema.PermissionSelected("never"));
		assertThat(AcpClientHandlers.refuse(request(List.of(ALLOW))).outcome())
			.isInstanceOf(AcpSchema.PermissionCancelled.class);
	}

	@Test
	void withoutPermissionHandlerBeansThereIsNone() {
		assertThat(AcpClientHandlers.of(List.of(), List.of()).permissionHandler()).isNull();
	}

	@Test
	void theHandlersRunOnVirtualThreadsOrElseDaemonThreads() throws Exception {
		java.util.concurrent.ExecutorService virtual = java.util.concurrent.Executors.newSingleThreadExecutor();
		assertThat(AcpClientHandlers.Threads.executor(virtual)).isSameAs(virtual);
		virtual.shutdown();
		java.util.concurrent.ExecutorService pool = AcpClientHandlers.Threads.executor(null);
		try {
			Thread thread = pool.submit(Thread::currentThread).get();
			assertThat(thread.isDaemon()).isTrue();
			assertThat(thread.getName()).isEqualTo("acp-client-handler");
		}
		finally {
			pool.shutdown();
		}
	}

	@Test
	void onePermissionHandlerAtMost() {
		AcpPermissionHandler handler = request -> AcpClientHandlers.refuse(request);
		assertThatThrownBy(() -> AcpClientHandlers.of(List.of(), List.of(handler, handler)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("one AcpPermissionHandler bean, found 2");
	}

	@Test
	void aPermissionBeanAndACustomizersHandlerConflict() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpClientHandlers handlers = AcpClientHandlers.of(List.of(), List.of(AcpClientHandlers::refuse));
		AcpClientCustomizer customizer = spec -> spec
			.requestPermissionHandler(request -> reactor.core.publisher.Mono.just(AcpClientHandlers.refuse(request)));

		assertThatThrownBy(() -> AcpClients.async(pair.clientTransport(), AcpClientSettings.builder().build(), handlers,
				List.of(customizer)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void theInProcessPairRefusesAClientOfAnotherAgent() {
		AcpClientSettings external = AcpClientSettings.builder().stdioCommand("some-agent").build();

		assertThat(AcpInProcess.pair(AcpClientSettings.builder().build(), "x.acp.client", "x.acp.agent")).isNotNull();
		assertThatThrownBy(() -> AcpInProcess.pair(external, "x.acp.client", "x.acp.agent"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("x.acp.agent.transport.type=in-process")
			.hasMessageContaining("remove x.acp.client.transport.*");
	}

	@Test
	void aClientCannotNameTheInProcessTransport() {
		AcpClientSettings settings = AcpClientSettings.builder().transport(AcpTransportType.IN_PROCESS).build();

		assertThatThrownBy(() -> AcpClientTransports.create(settings, "x.acp.client"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("x.acp.client.transport.type=in-process is not a client transport");
	}

	/** One prompt to an agent that streams an update and asks permission; returns the answer. */
	private static String turn(AcpClientHandlers handlers) {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		List<String> answers = new CopyOnWriteArrayList<>();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.initializeHandler(request -> AcpSchema.InitializeResponse.ok())
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse("session-1", null, null))
			.promptHandler((request, context) -> {
				context.sendMessage("working");
				AcpSchema.RequestPermissionResponse response = context.client()
					.requestPermission(request(List.of(ALLOW, REJECT)));
				answers.add(((AcpSchema.PermissionSelected) response.outcome()).optionId());
				return AcpSchema.PromptResponse.endTurn();
			})
			.build();
		agent.start();
		AcpAsyncClient async = AcpClients.async(pair.clientTransport(), AcpClientSettings.builder().build(), handlers,
				List.of());
		AcpSyncClient client = AcpClients.sync(async);
		try {
			AcpInProcess.initialize(client);
			String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/", List.of())).sessionId();
			client.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("go"))));
			return answers.get(0);
		}
		finally {
			new AcpClientHost(async).close(Duration.ofSeconds(5));
			agent.close();
		}
	}

	private static AcpSchema.RequestPermissionRequest request(List<AcpSchema.PermissionOption> options) {
		return new AcpSchema.RequestPermissionRequest("session-1", new AcpSchema.ToolCallUpdate("tool-1", null, null, null),
				options);
	}

	private static String text(AcpSchema.SessionNotification notification) {
		return (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
				&& chunk.content() instanceof AcpSchema.TextContent text) ? text.text() : "?";
	}

}
