/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt context holds the convenience layer; the raw ACP requests to the client are one step
 * down, on {@code context.client()}, so {@code readFile} no longer sits beside {@code readTextFile}
 * in the completion menu.
 */
class PromptContextLayersTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final Set<String> PROTOCOL_CALLS = Set.of("readTextFile", "writeTextFile", "requestPermission",
			"createTerminal", "getTerminalOutput", "releaseTerminal", "waitForTerminalExit", "killTerminal",
			"createElicitation", "completeElicitation", "sendExtRequest", "sendExtNotification");

	private static Set<String> methods(Class<?> type) {
		return Arrays.stream(type.getMethods()).map(Method::getName).collect(Collectors.toSet());
	}

	@Test
	void theProtocolCallsLiveOnTheClientNotOnTheContext() {
		for (Class<?> context : List.of(PromptContext.class, SyncPromptContext.class)) {
			assertThat(methods(context)).as(context.getSimpleName())
				.doesNotContainAnyElementsOf(PROTOCOL_CALLS)
				.contains("client", "readFile", "writeFile", "askPermission", "askChoice", "execute", "sendMessage",
						"sendThought", "sendSessionUpdate", "getSessionId", "isCancelled", "getClientCapabilities");
		}
		assertThat(methods(SessionClient.class)).isEqualTo(PROTOCOL_CALLS);
		assertThat(methods(SyncSessionClient.class)).isEqualTo(PROTOCOL_CALLS);
	}

	@Test
	void syncAndAsyncContextsReachTheClientThroughClient() {
		InMemoryTransportPair asyncPair = InMemoryTransportPair.create();
		InMemoryTransportPair syncPair = InMemoryTransportPair.create();
		AtomicReference<String> asyncRead = new AtomicReference<>();
		AtomicReference<String> syncRead = new AtomicReference<>();
		AcpAsyncAgent asyncAgent = AcpAgent.async(asyncPair.agentTransport())
			.promptHandler((request, context) -> context.client()
				.readTextFile(new AcpSchema.ReadTextFileRequest(context.getSessionId(), "/a.txt", 2, 1))
				.doOnNext(response -> asyncRead.set(response.content()))
				.thenReturn(AcpSchema.PromptResponse.endTurn()))
			.build();
		AcpSyncAgent syncAgent = AcpAgent.sync(syncPair.agentTransport())
			.promptHandler((request, context) -> {
				syncRead.set(context.client()
					.readTextFile(new AcpSchema.ReadTextFileRequest(context.getSessionId(), "/b.txt", null, null))
					.content());
				return AcpSchema.PromptResponse.endTurn();
			})
			.build();
		asyncAgent.start().block(TIMEOUT);
		syncAgent.start();
		try (AcpSyncClient asyncClient = client(asyncPair); AcpSyncClient syncClient = client(syncPair)) {
			prompt(asyncClient);
			prompt(syncClient);

			assertThat(asyncRead.get()).isEqualTo("/a.txt@2+1");
			assertThat(syncRead.get()).isEqualTo("/b.txt@null+null");
		}
		finally {
			asyncAgent.close();
			syncAgent.close();
			asyncPair.closeGracefully().block(TIMEOUT);
			syncPair.closeGracefully().block(TIMEOUT);
		}
	}

	private static AcpSyncClient client(InMemoryTransportPair pair) {
		AcpSyncClient client = AcpClient.sync(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.clientCapabilities(AcpSchema.ClientCapabilities.builder().readTextFile().build())
			.readTextFileHandler(request -> new AcpSchema.ReadTextFileResponse(
					request.path() + "@" + request.line() + "+" + request.limit()))
			.build();
		client.initialize();
		return client;
	}

	private static void prompt(AcpSyncClient client) {
		String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/w", List.of())).sessionId();
		client.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("go"))));
	}


}
