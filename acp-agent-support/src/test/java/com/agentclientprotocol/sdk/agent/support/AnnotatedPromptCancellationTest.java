/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.agent.PromptContext;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Cancel;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An annotated {@code @Prompt} method learns of its prompt's cancellation from its context,
 * with no {@code @Cancel} handler or shared state: {@code SyncPromptContext.isCancelled()} or
 * {@code PromptContext.whenCancelled()}. The grace period is long, so a prompt answered
 * promptly with the handler's {@code _meta} was answered by the handler.
 */
class AnnotatedPromptCancellationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String SESSION = "annotated-cancel";

	private static final Map<String, Object> HANDLERS_OWN = Map.of("answeredBy", "handler");

	@AcpAgent
	static class PollingAgent {

		final CountDownLatch running = new CountDownLatch(1);

		final CountDownLatch sawCancel = new CountDownLatch(1);

		final CountDownLatch cancelHandlerRan = new CountDownLatch(1);

		@NewSession
		AcpSchema.NewSessionResponse newSession() {
			return new AcpSchema.NewSessionResponse(SESSION, null, null);
		}

		@Prompt
		PromptResponse prompt(PromptRequest request, SyncPromptContext context) {
			running.countDown();
			while (!context.isCancelled()) {
				Thread.interrupted(); // a cancelled request interrupts the thread: keep polling
				pause();
			}
			sawCancel.countDown();
			return new PromptResponse(AcpSchema.StopReason.CANCELLED, HANDLERS_OWN);
		}

		@Cancel
		void cancel() {
			cancelHandlerRan.countDown();
		}

	}

	@AcpAgent
	static class ReactiveAgent {

		@NewSession
		AcpSchema.NewSessionResponse newSession() {
			return new AcpSchema.NewSessionResponse(SESSION, null, null);
		}

		@Prompt
		Mono<PromptResponse> prompt(PromptContext context) {
			return Mono.<PromptResponse>never()
				.takeUntilOther(context.whenCancelled())
				.switchIfEmpty(Mono.fromSupplier(
						() -> new PromptResponse(AcpSchema.StopReason.CANCELLED, HANDLERS_OWN)));
		}

	}

	/** A void prompt: the SDK answers end_turn for it, unless session/cancel arrived. */
	@AcpAgent
	static class VoidAgent {

		final CountDownLatch running = new CountDownLatch(1);

		@NewSession
		AcpSchema.NewSessionResponse newSession() {
			return new AcpSchema.NewSessionResponse(SESSION, null, null);
		}

		@Prompt
		void prompt(SyncPromptContext context) {
			running.countDown();
			while (!context.isCancelled()) {
				Thread.interrupted();
				pause();
			}
		}

	}

	/** A prompt whose work fails once cancelled, with an exception that is no cancellation. */
	@AcpAgent
	static class FailingAgent {

		final CountDownLatch running = new CountDownLatch(1);

		@NewSession
		AcpSchema.NewSessionResponse newSession() {
			return new AcpSchema.NewSessionResponse(SESSION, null, null);
		}

		@Prompt
		String prompt(SyncPromptContext context) {
			running.countDown();
			while (!context.isCancelled()) {
				Thread.interrupted();
				pause();
			}
			throw new IllegalStateException("model stream closed");
		}

	}

	// ACP spec 7628b153: prompt-turn.mdx:354, "the Agent MUST respond to the original
	// session/prompt request with the cancelled stop reason"
	@Test
	void aVoidPromptReturningAfterSessionCancelIsAnsweredCancelled() throws Exception {
		VoidAgent bean = new VoidAgent();
		run(bean, client -> {
			Mono<PromptResponse> response = client.prompt(prompt()).cache();
			response.subscribe(r -> {
			}, e -> {
			});
			await(bean.running);
			client.cancel(new AcpSchema.CancelNotification(SESSION)).block(TIMEOUT);

			assertThat(response.block(TIMEOUT).stopReason()).isEqualTo(AcpSchema.StopReason.CANCELLED);
		});
	}

	// ACP spec 7628b153: prompt-turn.mdx:361, "Agents MUST catch these errors and return the
	// semantically meaningful cancelled stop reason"
	@Test
	void aPromptFailingAfterSessionCancelIsAnsweredCancelled() throws Exception {
		FailingAgent bean = new FailingAgent();
		run(bean, client -> {
			Mono<PromptResponse> response = client.prompt(prompt()).cache();
			response.subscribe(r -> {
			}, e -> {
			});
			await(bean.running);
			client.cancel(new AcpSchema.CancelNotification(SESSION)).block(TIMEOUT);

			assertThat(response.block(TIMEOUT).stopReason()).isEqualTo(AcpSchema.StopReason.CANCELLED);
		});
	}

	@Test
	void aPollingPromptAnswersCancelledPromptlyOnSessionCancel() throws Exception {
		PollingAgent bean = new PollingAgent();
		run(bean, client -> {
			Mono<PromptResponse> response = client.prompt(prompt()).cache();
			response.subscribe(r -> {
			}, e -> {
			});
			await(bean.running);
			long start = System.nanoTime();
			client.cancel(new AcpSchema.CancelNotification(SESSION)).block(TIMEOUT);

			PromptResponse answer = response.block(TIMEOUT);
			assertThat(answer.stopReason()).isEqualTo(AcpSchema.StopReason.CANCELLED);
			assertThat(answer.meta()).as("the handler's own answer").isEqualTo(HANDLERS_OWN);
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
			await(bean.cancelHandlerRan);
		});
	}

	@Test
	void aPollingPromptSeesACancelRequestForItsRequest() throws Exception {
		PollingAgent bean = new PollingAgent();
		run(bean, client -> {
			Disposable prompt = client.prompt(prompt()).subscribe(r -> {
			}, e -> {
			});
			await(bean.running);

			prompt.dispose(); // $/cancel_request

			await(bean.sawCancel);
		});
	}

	@Test
	void aReactivePromptCompletesOnWhenCancelled() throws Exception {
		run(new ReactiveAgent(), client -> {
			Mono<PromptResponse> response = client.prompt(prompt()).cache();
			response.subscribe(r -> {
			}, e -> {
			});
			pause();
			client.cancel(new AcpSchema.CancelNotification(SESSION)).block(TIMEOUT);

			assertThat(response.block(TIMEOUT).meta()).isEqualTo(HANDLERS_OWN);
		});
	}

	interface ClientScenario {

		void run(AcpAsyncClient client) throws Exception;

	}

	private static void run(Object bean, ClientScenario scenario) throws Exception {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAgentSupport agent = AcpAgentSupport.create(bean)
			.transport(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.cancelGracePeriod(Duration.ofSeconds(30))
			.build();
		agent.start();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		try {
			client.initialize().block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/w", List.of())).block(TIMEOUT);
			scenario.run(client);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.close();
		}
	}

	private static PromptRequest prompt() {
		return new PromptRequest(SESSION, List.of(new AcpSchema.TextContent("work")));
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
	}

	private static void pause() {
		try {
			Thread.sleep(20);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
