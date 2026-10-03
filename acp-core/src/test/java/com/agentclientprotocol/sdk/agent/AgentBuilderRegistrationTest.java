/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The agent builders fail fast on a handler that cannot work: a null handler at the setter
 * (it used to be accepted and every request for that method was answered {@code -32603}), and a
 * second handler for the same method (it used to replace the first silently).
 */
class AgentBuilderRegistrationTest {

	private final InMemoryTransportPair pair = InMemoryTransportPair.create();

	/** Every typed {@code xxxHandler(handler)} setter of a builder: one functional parameter. */
	private static List<Method> typedSetters(Class<?> builder) {
		List<Method> setters = new ArrayList<>();
		for (Method method : builder.getMethods()) {
			if (method.getName().endsWith("Handler") && method.getParameterCount() == 1
					&& method.getReturnType() == builder) {
				setters.add(method);
			}
		}
		return setters;
	}

	private static Throwable invokeWithNull(Object builder, Method setter) {
		try {
			setter.invoke(builder, new Object[] { null });
			return null;
		}
		catch (InvocationTargetException e) {
			return e.getCause();
		}
		catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void everyTypedSetterOfTheAsyncBuilderRejectsNull() {
		List<Method> setters = typedSetters(AcpAgent.AsyncAgentBuilder.class);
		assertThat(setters).hasSizeGreaterThan(15);
		for (Method setter : setters) {
			assertThat(invokeWithNull(AcpAgent.async(this.pair.agentTransport()), setter)).as(setter.getName())
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void everyTypedSetterOfTheSyncBuilderRejectsNull() {
		List<Method> setters = typedSetters(AcpAgent.SyncAgentBuilder.class);
		assertThat(setters).hasSizeGreaterThan(15);
		for (Method setter : setters) {
			assertThat(invokeWithNull(AcpAgent.sync(this.pair.agentTransport()), setter)).as(setter.getName())
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void aHandlerSetTwiceFailsNamingTheSetter() {
		AcpAgent.AsyncAgentBuilder async = AcpAgent.async(this.pair.agentTransport())
			.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn()));
		assertThatThrownBy(
				() -> async.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn())))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("promptHandler")
			.hasMessageContaining(AcpSchema.METHOD_SESSION_PROMPT);

		AcpAgent.SyncAgentBuilder sync = AcpAgent.sync(this.pair.agentTransport())
			.cancelHandler(notification -> {
			});
		assertThatThrownBy(() -> sync.cancelHandler(notification -> {
		})).isInstanceOf(IllegalStateException.class).hasMessageContaining("cancelHandler");
	}

	@Test
	void anExtensionHandlerSetTwiceFailsNamingTheMethod() {
		AcpAgent.SyncAgentBuilder sync = AcpAgent.sync(this.pair.agentTransport())
			.extRequestHandler("_x/ping", params -> Map.of());
		assertThatThrownBy(() -> sync.extRequestHandler("_x/ping", params -> Map.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("extRequestHandler")
			.hasMessageContaining("_x/ping");
		// A different extension method is fine.
		sync.extRequestHandler("_x/pong", params -> Map.of());
	}

}
