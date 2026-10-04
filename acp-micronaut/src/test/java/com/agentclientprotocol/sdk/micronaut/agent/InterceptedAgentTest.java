/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.LoadSession;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import io.micronaut.aop.Around;
import io.micronaut.aop.Intercepted;
import io.micronaut.aop.InterceptorBean;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import static com.agentclientprotocol.sdk.micronaut.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An {@code @AcpAgent} bean with Micronaut AOP advice: Micronaut generates an unannotated
 * {@code $Intercepted} subclass for it. The agent is still found, discovered on its own class
 * (agentInfo and derived capabilities from its annotations), and invoked through the proxy, so
 * the advice runs on every handler call.
 */
@MicronautTest
@Property(name = InterceptedAgentTest.ENABLED, value = "true")
@Property(name = TestAgents.IN_MEMORY, value = "true")
class InterceptedAgentTest {

	static final String ENABLED = "test.agent.intercepted";

	@Inject
	InMemoryTransportPair pair;

	@Inject
	InterceptedAgent agent;

	@Inject
	CountingInterceptor advice;

	@Test
	void aProxiedAgentBeanIsServedThroughItsProxy() {
		assertThat(agent).as("the bean is a Micronaut AOP proxy").isInstanceOf(Intercepted.class);
		assertThat(agent.getClass()).isNotEqualTo(InterceptedAgent.class);

		List<String> chunks = new CopyOnWriteArrayList<>();
		AcpSyncClient client = AcpClient.sync(pair.clientTransport())
			.requestTimeout(Duration.ofSeconds(10))
			.sessionUpdateHandler(notification -> {
				if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
						&& chunk.content() instanceof AcpSchema.TextContent text) {
					chunks.add(text.text());
				}
			})
			.build();

		AcpSchema.InitializeResponse init = client.initialize();
		assertThat(init.agentInfo()).isNotNull();
		assertThat(init.agentInfo().name()).as("the user class's name, not the proxy's")
			.isEqualTo("intercepted-agent");
		assertThat(init.agentCapabilities().loadSession()).as("derived from @LoadSession on the user class")
			.isTrue();

		String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/tmp", List.of())).sessionId();
		AcpSchema.PromptResponse response = client
			.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("advised"))));
		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		eventually(Duration.ofSeconds(5), () -> assertThat(String.join("", chunks)).isEqualTo("advised"));

		assertThat(advice.calls).as("newSession and prompt ran through the proxy's advice")
			.contains("newSession", "prompt");
	}

	/** Marks a bean whose public methods {@link CountingInterceptor} advises. */
	@Documented
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ ElementType.TYPE, ElementType.METHOD })
	@Around
	@interface Counted {

	}

	/** Records the name of every advised method call. */
	@Singleton
	@InterceptorBean(Counted.class)
	@Requires(property = ENABLED, value = "true")
	static class CountingInterceptor implements MethodInterceptor<Object, Object> {

		final List<String> calls = new CopyOnWriteArrayList<>();

		@Override
		public Object intercept(MethodInvocationContext<Object, Object> context) {
			calls.add(context.getMethodName());
			return context.proceed();
		}

	}

	/** The advised agent. */
	@Singleton
	@Counted
	@AcpAgent(name = "intercepted-agent", version = "2.0")
	@Requires(property = ENABLED, value = "true")
	public static class InterceptedAgent {

		@NewSession
		public AcpSchema.NewSessionResponse newSession(AcpSchema.NewSessionRequest request) {
			return new AcpSchema.NewSessionResponse(UUID.randomUUID().toString(), null, null);
		}

		@LoadSession
		public AcpSchema.LoadSessionResponse loadSession(AcpSchema.LoadSessionRequest request) {
			return new AcpSchema.LoadSessionResponse(null);
		}

		@Prompt
		public AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
			context.sendMessage(request.text());
			return AcpSchema.PromptResponse.endTurn();
		}

	}

}
