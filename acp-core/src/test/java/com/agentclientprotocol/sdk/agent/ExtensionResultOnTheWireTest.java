/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import reactor.core.publisher.Mono;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An extension request on the stdio wire: the agent writes the method and params as given,
 * and a peer's {@code "result": null}, which the spec allows for an extension method,
 * completes a typed or raw async send empty and makes a sync send return null.
 */
class ExtensionResultOnTheWireTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	record Pong(String text) {
	}

	private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();

	private PipedOutputStream clientOut;

	private BufferedReader clientIn;

	private AcpAsyncAgent agent;

	@BeforeEach
	void setUp() throws Exception {
		clientOut = new PipedOutputStream();
		PipedInputStream agentIn = new PipedInputStream(clientOut, 65536);
		PipedOutputStream agentOut = new PipedOutputStream();
		clientIn = new BufferedReader(
				new InputStreamReader(new PipedInputStream(agentOut, 65536), StandardCharsets.UTF_8));
		agent = AcpAgent.async(new StdioAcpAgentTransport(mapper, agentIn, agentOut)).requestTimeout(TIMEOUT).promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn())).build();
		agent.start().block(TIMEOUT);
	}

	@AfterEach
	void tearDown() throws Exception {
		agent.closeGracefully().block(TIMEOUT);
		clientOut.close();
		clientIn.close();
	}

	/** Reads the agent's next request, checks its method and params, and answers it with the given result JSON. */
	private void answerNext(String expectedMethod, String resultJson) throws Exception {
		Map<?, ?> request = mapper.readValue(clientIn.readLine(), Map.class);
		assertThat(request.get("method")).isEqualTo(expectedMethod);
		assertThat(request.get("params")).isEqualTo(Map.of("q", 1));
		String response = "{\"jsonrpc\":\"2.0\",\"id\":" + mapper.writeValueAsString(request.get("id")) + ",\"result\":"
				+ resultJson + "}\n";
		clientOut.write(response.getBytes(StandardCharsets.UTF_8));
		clientOut.flush();
	}

	@Test
	void nullResultCompletesARawSendEmpty() throws Exception {
		CompletableFuture<Boolean> completedEmpty = agent.sendExtRequest("_test/nothing", Map.of("q", 1))
			.hasElement()
			.map(hasElement -> !hasElement)
			.toFuture();
		answerNext("_test/nothing", "null");
		assertThat(completedEmpty.get(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void nullResultCompletesATypedSendEmpty() throws Exception {
		CompletableFuture<Boolean> completedEmpty = agent
			.sendExtRequest("_test/nothing", Map.of("q", 1), new TypeRef<Pong>() {
			})
			.hasElement()
			.map(hasElement -> !hasElement)
			.toFuture();
		answerNext("_test/nothing", "null");
		assertThat(completedEmpty.get(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void nullResultIsNullForASyncSend() throws Exception {
		AcpSyncAgent syncAgent = new AcpSyncAgent(agent, TIMEOUT);
		CompletableFuture<Object> result = CompletableFuture
			.supplyAsync(() -> syncAgent.sendExtRequest("_test/nothing", Map.of("q", 1), new TypeRef<Pong>() {
			}));
		answerNext("_test/nothing", "null");
		assertThat(result.get(5, TimeUnit.SECONDS)).isNull();

		CompletableFuture<Object> raw = CompletableFuture
			.supplyAsync(() -> syncAgent.sendExtRequest("_test/nothing", Map.of("q", 1)));
		answerNext("_test/nothing", "null");
		assertThat(raw.get(5, TimeUnit.SECONDS)).isNull();
	}

	@Test
	void scalarResultIsDeliveredRawAndTyped() throws Exception {
		CompletableFuture<Object> raw = agent.sendExtRequest("_test/count", Map.of("q", 1)).toFuture();
		answerNext("_test/count", "42");
		assertThat(raw.get(5, TimeUnit.SECONDS)).isEqualTo(42);

		CompletableFuture<Pong> typed = agent.sendExtRequest("_test/pong", Map.of("q", 1), new TypeRef<Pong>() {
		}).toFuture();
		answerNext("_test/pong", "{\"text\":\"hi\"}");
		assertThat(typed.get(5, TimeUnit.SECONDS)).isEqualTo(new Pong("hi"));
	}

	@Test
	void notificationIsWrittenWithoutAnId() throws Exception {
		agent.sendExtNotification("_test/note", Map.of("q", 1)).block(TIMEOUT);
		Map<?, ?> notification = mapper.readValue(clientIn.readLine(), Map.class);
		assertThat(notification.containsKey("id")).isFalse();
		assertThat(notification.get("method")).isEqualTo("_test/note");
		assertThat(notification.get("params")).isEqualTo(Map.of("q", 1));
	}

}
