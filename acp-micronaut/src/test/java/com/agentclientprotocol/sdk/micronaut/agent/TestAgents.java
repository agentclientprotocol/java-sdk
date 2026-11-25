/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.CloseSession;
import com.agentclientprotocol.sdk.annotation.ExtRequest;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.annotation.SetSessionMode;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.agent.transport.InMemoryTransportPair;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Beans for the agent tests, each switched on by its own property so that a test context
 * holds exactly the agents it asks for.
 */
final class TestAgents {

	static final String ECHO = "test.agent.echo";

	static final String OTHER = "test.agent.other";

	static final String IN_MEMORY = "test.in-memory";

	private TestAgents() {
	}

	/** An ordinary bean the agent depends on. */
	@Singleton
	static class Prefix {

		String value() {
			return "echo: ";
		}

	}

	/**
	 * The echo agent: a Micronaut singleton with an injected dependency, returning plain,
	 * Publisher (Flux) and Mono values, and taking a parameter only a custom resolver bean
	 * can supply.
	 */
	@Singleton
	@AcpAgent(name = "micronaut-test-agent", version = "1.2.3")
	@Requires(property = ECHO, value = "true")
	static class EchoAgent {

		/** The client capabilities each prompt's connection negotiated. */
		final List<NegotiatedCapabilities> clientCapabilities = new CopyOnWriteArrayList<>();

		/** The threads the prompt handler ran on. */
		final List<Thread> promptThreads = new CopyOnWriteArrayList<>();

		private final Prefix prefix;

		EchoAgent(Prefix prefix) {
			this.prefix = prefix;
		}

		@NewSession
		Publisher<AcpSchema.NewSessionResponse> newSession(AcpSchema.NewSessionRequest request) {
			return Flux.just(new AcpSchema.NewSessionResponse(UUID.randomUUID().toString(), null, null));
		}

		@Prompt
		AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context, Locale locale,
				NegotiatedCapabilities client) {
			clientCapabilities.add(client);
			promptThreads.add(Thread.currentThread());
			context.sendMessage(prefix.value());
			context.sendMessage(request.text() + " [" + locale.toLanguageTag() + "]");
			return AcpSchema.PromptResponse.endTurn();
		}

		@SetSessionMode
		Mono<AcpSchema.SetSessionModeResponse> setMode(AcpSchema.SetSessionModeRequest request) {
			return Mono.just(new AcpSchema.SetSessionModeResponse());
		}

		/** Declared so that initialize advertises sessionCapabilities.close (devex derivation). */
		@CloseSession
		AcpSchema.CloseSessionResponse close(AcpSchema.CloseSessionRequest request) {
			return new AcpSchema.CloseSessionResponse();
		}

		@ExtRequest("_test/empty")
		Publisher<Object> empty(Object params) {
			return Flux.empty();
		}

	}

	static final String CLOSING = "test.agent.closing";

	static final String CLOSING_FAILS = "test.agent.closing.fails";

	/** An agent that is {@code AutoCloseable}: counts its closes, and fails them when told to. */
	@Singleton
	@AcpAgent
	@Requires(property = CLOSING, value = "true")
	static class ClosingAgent implements AutoCloseable {

		static final java.util.concurrent.atomic.AtomicInteger CLOSES = new java.util.concurrent.atomic.AtomicInteger();

		private final boolean fails;

		ClosingAgent(@io.micronaut.context.annotation.Value("${" + CLOSING_FAILS + ":false}") boolean fails) {
			this.fails = fails;
		}

		@Prompt
		AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request) {
			return AcpSchema.PromptResponse.endTurn();
		}

		@Override
		public void close() {
			CLOSES.incrementAndGet();
			if (fails) {
				throw new IllegalStateException("cannot close");
			}
		}

	}

	/** A second agent, for the "exactly one" rule. */
	@Singleton
	@AcpAgent
	@Requires(property = OTHER, value = "true")
	static class OtherAgent {

		@Prompt
		AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request) {
			return AcpSchema.PromptResponse.endTurn();
		}

	}

	/** Records the ACP methods it sees: interceptor beans are applied to the agent. */
	@Singleton
	@Requires(property = ECHO, value = "true")
	static class RecordingInterceptor implements AcpInterceptor {

		final List<String> methods = new CopyOnWriteArrayList<>();

		@Override
		public boolean preInvoke(AcpInvocationContext context) {
			methods.add(context.getAcpMethod());
			return true;
		}

	}

	/** Supplies {@link Locale} parameters: argument resolver beans are applied to the agent. */
	@Singleton
	@Requires(property = ECHO, value = "true")
	static class LocaleResolver implements ArgumentResolver {

		@Override
		public boolean supportsParameter(AcpMethodParameter parameter) {
			return parameter.getParameterType() == Locale.class;
		}

		@Override
		public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
			return Locale.CANADA_FRENCH;
		}

	}

	/** An in-memory transport replacing stdio, with its client end for the test. */
	@Factory
	@Requires(property = IN_MEMORY, value = "true")
	static class InMemoryTransport {

		@Singleton
		InMemoryTransportPair pair() {
			return InMemoryTransportPair.create();
		}

		@Singleton
		AcpAgentTransport agentTransport(InMemoryTransportPair pair) {
			return pair.agentTransport();
		}

	}

	static Map<String, Object> echoInMemory() {
		return Map.of(ECHO, "true", IN_MEMORY, "true");
	}

	static final String STDIO_PIPE = "test.stdio-pipe";

	/** A stdio transport over a pipe, whose writing end the test closes as a client would. */
	@Factory
	@Requires(property = STDIO_PIPE, value = "true")
	static class StdioPipe {

		@Singleton
		java.io.PipedOutputStream clientSide() {
			return new java.io.PipedOutputStream();
		}

		@Singleton
		AcpAgentTransport agentTransport(java.io.PipedOutputStream clientSide) throws java.io.IOException {
			return new com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport(
					com.agentclientprotocol.sdk.json.AcpJsonMapper.createDefault(), new java.io.PipedInputStream(clientSide),
					new java.io.ByteArrayOutputStream());
		}

	}

	static Map<String, Object> echoOverAPipe() {
		return Map.of(ECHO, "true", STDIO_PIPE, "true");
	}

}
