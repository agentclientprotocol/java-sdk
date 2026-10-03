/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.LoadSession;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Handler discovery walks the class hierarchy: a bean that a framework proxies (Spring CGLIB,
 * Quarkus ArC or Micronaut AOP generate a subclass that carries no annotations) is still found,
 * and invoked through the proxy, so its interceptors run; handlers declared on a superclass are
 * found; an override is one handler, not two; and the bridge method a generic override compiles
 * to is not a handler of its own.
 */
class AgentDiscoveryTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@AcpAgent(name = "proxied", version = "1")
	static class ProxiedAgent {

		@NewSession
		public NewSessionResponse newSession() {
			return new NewSessionResponse("s1", null, null);
		}

		@LoadSession
		public AcpSchema.LoadSessionResponse load() {
			return new AcpSchema.LoadSessionResponse(null);
		}

		@Prompt
		public PromptResponse prompt() {
			return new PromptResponse(AcpSchema.StopReason.END_TURN);
		}

	}

	/** What a CGLIB/ArC/Micronaut proxy looks like: an unannotated subclass overriding public methods. */
	static class ProxiedAgent$$SpringCGLIB$$0 extends ProxiedAgent {

		final AtomicInteger intercepted = new AtomicInteger();

		@Override
		public PromptResponse prompt() {
			intercepted.incrementAndGet();
			return super.prompt();
		}

		@Override
		public NewSessionResponse newSession() {
			intercepted.incrementAndGet();
			return super.newSession();
		}

	}

	@Test
	void aProxiedBeanIsDiscoveredOnItsUserClassAndInvokedThroughTheProxy() {
		ProxiedAgent$$SpringCGLIB$$0 proxy = new ProxiedAgent$$SpringCGLIB$$0();

		InitializeResponse init = session(AcpAgentSupport.create(proxy), client -> {
			assertThat(prompt(client)).isEqualTo(AcpSchema.StopReason.END_TURN);
			return client.initialize().block(TIMEOUT);
		});

		assertThat(proxy.intercepted).as("newSession and prompt ran through the proxy").hasValue(2);
		assertThat(init.agentCapabilities().loadSession()).isTrue();
		assertThat(init.agentInfo().name()).isEqualTo("proxied");
	}

	@Test
	void anAnonymousSubclassIsDiscoveredToo() {
		AtomicInteger intercepted = new AtomicInteger();
		ProxiedAgent anonymous = new ProxiedAgent() {
			@Override
			public PromptResponse prompt() {
				intercepted.incrementAndGet();
				return super.prompt();
			}
		};

		session(AcpAgentSupport.create(anonymous), AgentDiscoveryTest::prompt);

		assertThat(intercepted).hasValue(1);
	}

	abstract static class AbstractAgent {

		@NewSession
		NewSessionResponse newSession() {
			return new NewSessionResponse("s1", null, null);
		}

		@Prompt
		PromptResponse prompt(PromptRequest request) {
			return new PromptResponse(AcpSchema.StopReason.MAX_TOKENS);
		}

	}

	@AcpAgent
	static class ConcreteAgent extends AbstractAgent {

	}

	@Test
	void handlersOnAnAbstractSuperclassAreDiscovered() {
		assertThat(session(AcpAgentSupport.create(new ConcreteAgent()), AgentDiscoveryTest::prompt))
			.isEqualTo(AcpSchema.StopReason.MAX_TOKENS);
	}

	@AcpAgent
	static class OverridingAgent extends AbstractAgent {

		@Override
		@Prompt
		PromptResponse prompt(PromptRequest request) {
			return new PromptResponse(AcpSchema.StopReason.REFUSAL);
		}

	}

	@Test
	void anAnnotatedOverrideIsOneHandlerAndTheOverrideRuns() {
		assertThat(session(AcpAgentSupport.create(new OverridingAgent()), AgentDiscoveryTest::prompt))
			.isEqualTo(AcpSchema.StopReason.REFUSAL);
	}

	abstract static class GenericAgent<R> {

		@Prompt
		abstract PromptResponse prompt(R request);

	}

	/** The override compiles to a bridge prompt(Object) that carries the annotation too. */
	@AcpAgent
	static class TypedAgent extends GenericAgent<PromptRequest> {

		@NewSession
		NewSessionResponse newSession() {
			return new NewSessionResponse("s1", null, null);
		}

		@Override
		@Prompt
		PromptResponse prompt(PromptRequest request) {
			return new PromptResponse(request.sessionId().equals("s1") ? AcpSchema.StopReason.END_TURN
					: AcpSchema.StopReason.REFUSAL);
		}

	}

	@Test
	void aBridgeMethodIsNotAHandler() {
		assertThat(session(AcpAgentSupport.create(new TypedAgent()), AgentDiscoveryTest::prompt))
			.isEqualTo(AcpSchema.StopReason.END_TURN);
	}

	static class NotAnAgent {

	}

	@Test
	void aClassWithNoAcpAgentInItsHierarchyIsRejectedNamingIt() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new NotAnAgent()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(NotAnAgent.class.getName())
			.hasMessageContaining("@AcpAgent");
	}

	private static AcpSchema.StopReason prompt(AcpAsyncClient client) {
		client.newSession(new AcpSchema.NewSessionRequest("/w", List.of())).block(TIMEOUT);
		return client.prompt(new PromptRequest("s1", List.of(new AcpSchema.TextContent("hi"))))
			.block(TIMEOUT)
			.stopReason();
	}

	private static <T> T session(AcpAgentSupport.Builder builder, Function<AcpAsyncClient, T> scenario) {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAgentSupport agent = builder.transport(pair.agentTransport()).requestTimeout(TIMEOUT).build();
		agent.start();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		try {
			client.initialize().block(TIMEOUT);
			return scenario.apply(client);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.close();
		}
	}

}
