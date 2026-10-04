/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/** An annotated agent, its extension beans, and a client round trip against it. */
final class TestAgents {

	private TestAgents() {
	}

	/** Echoes the prompt, with a suffix only {@link SuffixResolver} supplies. */
	@AcpAgent(name = "integration-test-agent", version = "1.0")
	public static class EchoAgent {

		@Prompt
		public Reply prompt(AcpSchema.PromptRequest request, SyncPromptContext context, Suffix suffix) {
			context.sendMessage("echo: " + ((AcpSchema.TextContent) request.prompt().get(0)).text() + suffix.value());
			return new Reply();
		}

	}

	/** A subclass standing in for a container proxy: it carries no annotations of its own. */
	public static class ProxiedEchoAgent extends EchoAgent {

		final AtomicInteger calls = new AtomicInteger();

		@Override
		public Reply prompt(AcpSchema.PromptRequest request, SyncPromptContext context, Suffix suffix) {
			calls.incrementAndGet();
			return super.prompt(request, context, suffix);
		}

	}

	record Suffix(String value) {
	}

	record Reply() {
	}

	static final class SuffixResolver implements ArgumentResolver {

		@Override
		public boolean supportsParameter(AcpMethodParameter parameter) {
			return parameter.getParameterType() == Suffix.class;
		}

		@Override
		public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
			return new Suffix("!");
		}

	}

	static final class ReplyHandler implements ReturnValueHandler {

		@Override
		public boolean supportsReturnType(AcpMethodParameter returnType) {
			return returnType.getParameterType() == Reply.class;
		}

		@Override
		public Object handleReturnValue(Object returnValue, AcpMethodParameter returnType,
				AcpInvocationContext context) {
			return AcpSchema.PromptResponse.endTurn();
		}

	}

	static final class RecordingInterceptor implements AcpInterceptor {

		final List<String> methods = new CopyOnWriteArrayList<>();

		@Override
		public boolean preInvoke(AcpInvocationContext context) {
			methods.add(context.getAcpMethod());
			return true;
		}

	}

	/** Initializes, opens a session and prompts once; returns the messages the agent sent. */
	static List<String> roundTrip(AcpClientTransport transport) {
		List<String> messages = new CopyOnWriteArrayList<>();
		AcpSyncClient client = AcpClient.sync(transport)
			.requestTimeout(Duration.ofSeconds(10))
			.sessionUpdateConsumer(notification -> {
				if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
						&& chunk.content() instanceof AcpSchema.TextContent text) {
					messages.add(text.text());
				}
			})
			.build();
		try {
			client.initialize();
			String session = client.newSession(new AcpSchema.NewSessionRequest("/", List.of())).sessionId();
			AcpSchema.PromptResponse response = client
				.prompt(new AcpSchema.PromptRequest(session, List.of(new AcpSchema.TextContent("hello"))));
			assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			return messages;
		}
		finally {
			client.closeGracefully();
		}
	}

	/** An agent transport whose graceful close never finishes; counts its immediate closes. */
	static class StuckAgentTransport implements AcpAgentTransport {

		private final AcpAgentTransport delegate;

		final AtomicInteger closes = new AtomicInteger();

		StuckAgentTransport(AcpAgentTransport delegate) {
			this.delegate = delegate;
		}

		@Override
		public Mono<Void> start(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
			return delegate.start(handler);
		}

		@Override
		public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
			return delegate.sendMessage(message);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.never();
		}

		@Override
		public void close() {
			closes.incrementAndGet();
		}

		@Override
		public Mono<Void> awaitTermination() {
			return delegate.awaitTermination();
		}

		@Override
		public void setExceptionHandler(Consumer<Throwable> handler) {
			delegate.setExceptionHandler(handler);
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return delegate.unmarshalFrom(data, typeRef);
		}

	}

}
