/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.error.AcpException;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A request that times out reaches the callers of the sync facades ({@link AcpSyncClient},
 * {@link AcpSyncAgent}, {@code SyncPromptContext}) as an SDK exception whose cause is the
 * {@link TimeoutException}, never as Reactor's internal {@code Exceptions$ReactiveException}.
 */
class SyncFacadeTimeoutTest {

	private static final Duration SHORT = Duration.ofMillis(200);

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static void assertSdkTimeout(Throwable thrown) {
		assertThat(thrown).isNotNull();
		assertThat(thrown.getClass().getName()).doesNotStartWith("reactor.");
		assertThat(thrown).isInstanceOf(AcpException.class).hasCauseInstanceOf(TimeoutException.class);
	}

	@Test
	void syncClientRequestTimeoutIsAnSdkException() {
		MockAcpClientTransport silentAgent = new MockAcpClientTransport();
		AcpSyncClient client = AcpClient.sync(silentAgent).requestTimeout(SHORT).build();
		try {
			assertSdkTimeout(catchThrowable(
					() -> client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()))));
			assertSdkTimeout(catchThrowable(() -> client.sendExtRequest("_x/ping", Map.of())));
			assertSdkTimeout(catchThrowable(() -> client.initialize()));
		}
		finally {
			client.close();
		}
	}

	@Test
	void syncAgentAndPromptContextRequestTimeoutIsAnSdkException() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AtomicReference<Throwable> fromContext = new AtomicReference<>();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.requestTimeout(SHORT)
			.initializeHandler(req -> AcpSchema.InitializeResponse.ok())
			.newSessionHandler(req -> new AcpSchema.NewSessionResponse("s1", null, null))
			.promptHandler((request, context) -> {
				fromContext.set(catchThrowable(() -> context.readFile("/never")));
				return AcpSchema.PromptResponse.endTurn();
			})
			.build();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.clientCapabilities(new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(true, false), null))
			.readTextFileHandler(req -> Mono.never())
			.extRequestHandler("_x/ping", params -> Mono.never())
			.build();
		try {
			agent.start();
			client.initialize().block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
			client.prompt(new AcpSchema.PromptRequest("s1", List.of(new AcpSchema.TextContent("go"))))
				.block(TIMEOUT);

			assertSdkTimeout(fromContext.get());
			assertSdkTimeout(catchThrowable(() -> agent.sendExtRequest("_x/ping", Map.of())));
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully();
			pair.closeGracefully().block(TIMEOUT);
		}
	}

}
