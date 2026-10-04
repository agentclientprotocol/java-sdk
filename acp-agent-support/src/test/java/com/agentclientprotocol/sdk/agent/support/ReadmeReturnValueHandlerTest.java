/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandlingException;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.ExtRequest;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The custom return value handler example in this module's README: it serves the type it adds,
 * and registering it does not replace a built-in handler. The old example handled {@code CompletableFuture}, which the
 * built-in {@code AsyncValueHandler} already does; as a custom handler it was asked first, so
 * a {@code CompletableFuture<String>} from {@code @Prompt} failed every prompt with -32603.
 */
class ReadmeReturnValueHandlerTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final InMemoryTransportPair transportPair = InMemoryTransportPair.create();

	private AcpAgentSupport agentSupport;

	private AcpAsyncClient client;

	@AfterEach
	void tearDown() {
		if (client != null) {
			client.closeGracefully().block(TIMEOUT);
		}
		if (agentSupport != null) {
			agentSupport.close();
		}
	}

	// The README example, verbatim (README.md, "Custom Return Value Handlers").
	public class FutureHandler implements ReturnValueHandler {

		@Override
		public boolean supportsReturnType(AcpMethodParameter returnType) {
			// Future itself only: a CompletableFuture stays with the built-in handler
			return returnType.getParameterType() == Future.class;
		}

		@Override
		public Object handleReturnValue(Object returnValue, AcpMethodParameter returnType,
				AcpInvocationContext context) {
			try {
				return ((Future<?>) returnValue).get();  // the method's response, such as a PromptResponse
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new CancellationException("Interrupted while waiting for the handler's result");
			}
			catch (ExecutionException e) {
				throw new ReturnValueHandlingException("The handler's Future failed", e.getCause());
			}
		}
	}

	@AcpAgent
	static class FutureAgent {

		private final ExecutorService executor = Executors.newSingleThreadExecutor();

		@Prompt
		CompletableFuture<String> prompt(PromptRequest request) {
			return CompletableFuture.completedFuture("hello");
		}

		@ExtRequest("_test/plain-future")
		Future<Map<String, Object>> plainFuture() {
			return executor.submit(() -> Map.of("done", true));
		}

	}

	@Test
	void theReadmeExampleIsTheOneTestedHere() throws IOException {
		String readme = Files.readString(Path.of("README.md"));
		String section = readme.substring(readme.indexOf("## Custom Return Value Handlers"));
		assertThat(section).contains("public class FutureHandler implements ReturnValueHandler")
			.contains("return returnType.getParameterType() == Future.class;");
	}

	@Test
	void registeringTheReadmeExampleKeepsTheBuiltInHandlers() {
		List<AcpSchema.SessionNotification> updates = new CopyOnWriteArrayList<>();
		agentSupport = AcpAgentSupport.create(new FutureAgent())
			.returnValueHandler(new FutureHandler())
			.transport(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.build();
		agentSupport.start();
		client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateHandler(update -> {
				updates.add(update);
				return reactor.core.publisher.Mono.empty();
			})
			.build();
		client.initialize().block(TIMEOUT);
		String sessionId = client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT).sessionId();

		PromptResponse response = client.prompt(PromptRequest.text(sessionId, "hi")).block(TIMEOUT);

		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(updates).isNotEmpty();
		// and the type the example adds is served
		assertThat(client.sendExtRequest("_test/plain-future", Map.of()).block(TIMEOUT)).isEqualTo(Map.of("done", true));
	}

}
