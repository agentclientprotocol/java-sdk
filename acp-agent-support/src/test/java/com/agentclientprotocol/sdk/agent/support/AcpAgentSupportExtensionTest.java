/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.ExtNotification;
import com.agentclientprotocol.sdk.annotation.ExtRequest;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Annotated extension handlers: {@link ExtRequest} and {@link ExtNotification} methods
 * serve custom {@code _}-prefixed requests and notifications, their params read as the
 * method's parameter type.
 */
class AcpAgentSupportExtensionTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	record Ping(String text) {
	}

	record Pong(String text) {
	}

	private InMemoryTransportPair transportPair;

	private AcpAgentSupport agentSupport;

	private AcpAsyncClient client;

	@BeforeEach
	void setUp() {
		transportPair = InMemoryTransportPair.create();
	}

	@AfterEach
	void tearDown() {
		if (client != null) {
			client.closeGracefully().block(TIMEOUT);
		}
		if (agentSupport != null) {
			agentSupport.close();
		}
	}

	@AcpAgent
	static class ExtAgent {

		final CompletableFuture<Ping> typedNote = new CompletableFuture<>();

		final CompletableFuture<Map<String, Object>> rawNote = new CompletableFuture<>();

		@ExtRequest("_test/ping")
		Pong ping(Ping ping) {
			return new Pong("pong: " + ping.text());
		}

		@ExtRequest("_test/generic")
		Map<String, Object> generic(Map<String, List<Integer>> params) {
			return Map.of("sum", params.get("values").stream().mapToInt(Integer::intValue).sum());
		}

		@ExtRequest("_test/mono")
		Mono<Pong> mono(Ping ping) {
			return Mono.just(new Pong("mono: " + ping.text()));
		}

		@ExtRequest("_test/noparams")
		List<String> noParams() {
			return List.of("a", "b");
		}

		@ExtRequest("_test/null")
		Object nothing() {
			return null;
		}

		@ExtNotification("_test/typed")
		void typed(Ping ping) {
			typedNote.complete(ping);
		}

		@ExtNotification("_test/raw")
		void raw(Map<String, Object> params) {
			rawNote.complete(params);
		}

		@Prompt
		PromptResponse prompt() {
			return PromptResponse.endTurn();
		}

	}

	private ExtAgent start() {
		ExtAgent agent = new ExtAgent();
		agentSupport = AcpAgentSupport.create(agent)
			.transport(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.build();
		agentSupport.start();
		client = AcpClient.async(transportPair.clientTransport()).requestTimeout(TIMEOUT).build();
		return agent;
	}

	@Test
	void extRequestMethodsAnswerWithTypedParams() {
		start();

		assertThat(client.sendExtRequest("_test/ping", new Ping("hi"), new TypeRef<Pong>() {
		}).block(TIMEOUT)).isEqualTo(new Pong("pong: hi"));
		assertThat(client.sendExtRequest("_test/generic", Map.of("values", List.of(1, 2, 3))).block(TIMEOUT))
			.isEqualTo(Map.of("sum", 6));
		assertThat(client.sendExtRequest("_test/mono", new Ping("m"), new TypeRef<Pong>() {
		}).block(TIMEOUT)).isEqualTo(new Pong("mono: m"));
		assertThat(client.sendExtRequest("_test/noparams", Map.of()).block(TIMEOUT)).isEqualTo(List.of("a", "b"));
	}

	@Test
	void extRequestMethodReturningNullAnswersWithError() {
		start();

		assertThatThrownBy(() -> client.sendExtRequest("_test/null", Map.of()).block(TIMEOUT))
			.isInstanceOf(AcpError.class)
			.hasMessageContaining("produced no response");
	}

	@Test
	void extNotificationMethodsReceiveParams() throws Exception {
		ExtAgent agent = start();

		client.sendExtNotification("_test/typed", new Ping("n")).block(TIMEOUT);
		client.sendExtNotification("_test/raw", Map.of("k", "v")).block(TIMEOUT);

		assertThat(agent.typedNote.get(5, TimeUnit.SECONDS)).isEqualTo(new Ping("n"));
		assertThat(agent.rawNote.get(5, TimeUnit.SECONDS)).isEqualTo(Map.of("k", "v"));
	}

	@Test
	void unannotatedExtMethodIsMethodNotFound() {
		start();

		assertThatThrownBy(() -> client.sendExtRequest("_test/absent", Map.of()).block(TIMEOUT))
			.isInstanceOfSatisfying(AcpError.class,
					error -> assertThat(error.getCode()).isEqualTo(AcpErrorCodes.METHOD_NOT_FOUND));
	}

	@AcpAgent
	static class BadNameAgent {

		@ExtRequest("session/prompt")
		Object hijack(Map<String, Object> params) {
			return params;
		}

		@Prompt
		PromptResponse prompt() {
			return PromptResponse.endTurn();
		}

	}

	@AcpAgent
	static class BadNotificationNameAgent {

		@ExtNotification("x/y")
		void note(Map<String, Object> params) {
		}

		@Prompt
		PromptResponse prompt() {
			return PromptResponse.endTurn();
		}

	}

	@AcpAgent
	static class TwoParamsAgent {

		@ExtRequest("_test/two")
		Object two(Ping ping, String other) {
			return ping;
		}

		@Prompt
		PromptResponse prompt() {
			return PromptResponse.endTurn();
		}

	}

	@Test
	void discoveryRejectsNamesWithoutUnderscore() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new BadNameAgent()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("session/prompt");
		assertThatThrownBy(() -> AcpAgentSupport.create(new BadNotificationNameAgent()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("x/y");
	}

	@Test
	void discoveryRejectsMoreThanOneParameter() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new TwoParamsAgent()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("two");
	}

}
