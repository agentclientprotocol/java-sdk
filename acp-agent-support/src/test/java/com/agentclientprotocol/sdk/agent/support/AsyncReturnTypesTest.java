/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A handler may return its value later: as a {@code Mono}, a {@code CompletionStage} or a
 * single-value Reactive Streams {@code Publisher}, with the same meaning as returning the value
 * itself. A prompt method's {@code Mono<String>} sends the text as an agent message chunk and ends
 * the turn, as a {@code String} does.
 */
class AsyncReturnTypesTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@AcpAgent
	static class MonoStringAgent {

		@Prompt
		Mono<String> prompt() {
			return Mono.just("hello later");
		}

	}

	@AcpAgent
	static class CompletionStageAgent {

		@NewSession
		CompletableFuture<NewSessionResponse> newSession() {
			return CompletableFuture.supplyAsync(() -> new NewSessionResponse("s1", null, null));
		}

		@Prompt
		CompletionStage<PromptResponse> prompt() {
			return CompletableFuture.supplyAsync(PromptResponse::refusal);
		}

	}

	@AcpAgent
	static class CompletionStageStringAgent {

		@Prompt
		CompletionStage<String> prompt() {
			return CompletableFuture.completedFuture("staged text");
		}

	}

	@AcpAgent
	static class PublisherAgent {

		@Prompt
		Publisher<PromptResponse> prompt() {
			return Flux.just(new PromptResponse(AcpSchema.StopReason.MAX_TOKENS));
		}

	}

	@Test
	void aMonoOfStringSendsTheTextAndEndsTheTurn() {
		List<String> chunks = new CopyOnWriteArrayList<>();
		assertThat(prompt(new MonoStringAgent(), chunks)).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(chunks).containsExactly("hello later");
	}

	@Test
	void aCompletionStageIsAwaited() {
		assertThat(prompt(new CompletionStageAgent(), new CopyOnWriteArrayList<>()))
			.isEqualTo(AcpSchema.StopReason.REFUSAL);
	}

	@Test
	void aCompletionStageOfStringSendsTheText() {
		List<String> chunks = new CopyOnWriteArrayList<>();
		assertThat(prompt(new CompletionStageStringAgent(), chunks)).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(chunks).containsExactly("staged text");
	}

	@Test
	void aSingleValuePublisherIsAwaited() {
		assertThat(prompt(new PublisherAgent(), new CopyOnWriteArrayList<>()))
			.isEqualTo(AcpSchema.StopReason.MAX_TOKENS);
	}

	@AcpAgent
	static class WrongStageType {

		@NewSession
		CompletionStage<PromptResponse> newSession() {
			return CompletableFuture.completedFuture(PromptResponse.endTurn());
		}

		@Prompt
		PromptResponse prompt() {
			return PromptResponse.endTurn();
		}

	}

	@Test
	void aStageOfAnotherMethodsResponseIsRejectedWhenBuilt() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new WrongStageType()).buildFactory())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("WrongStageType.newSession")
			.hasMessageContaining("NewSessionResponse");
	}

	private static AcpSchema.StopReason prompt(Object bean, List<String> chunks) {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAgentSupport agent = AcpAgentSupport.create(bean)
			.transport(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.build();
		agent.start();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateHandler(notification -> Mono.fromRunnable(() -> {
				if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
						&& chunk.content() instanceof AcpSchema.TextContent text) {
					chunks.add(text.text());
				}
			}))
			.build();
		try {
			client.initialize().block(TIMEOUT);
			String session = client.newSession(new AcpSchema.NewSessionRequest("/w", List.of()))
				.block(TIMEOUT)
				.sessionId();
			return client.prompt(new PromptRequest(session, List.of(new AcpSchema.TextContent("hi"))))
				.block(TIMEOUT)
				.stopReason();
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.close();
		}
	}

}
