/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SDK has one default request timeout, 60 seconds: a builder agent, an annotated agent and a
 * client that do not set {@code requestTimeout} all wait the same time for an answer. The default
 * is not part of the API, so the test reads the timeout the built session was given.
 */
class DefaultRequestTimeoutTest {

	private static final Duration SDK_DEFAULT = Duration.ofSeconds(60);

	@com.agentclientprotocol.sdk.annotation.AcpAgent
	static class EchoAgent {

		@Prompt
		PromptResponse prompt() {
			return PromptResponse.endTurn();
		}

	}

	@Test
	void builderAgentAnnotatedAgentAndClientShareOneDefault() {
		Object builderAgent = AcpAgent.sync(InMemoryTransportPair.create().agentTransport())
			.promptHandler((request, context) -> PromptResponse.endTurn())
			.build();
		Object annotatedAgent = AcpAgentSupport.create(new EchoAgent())
			.transport(InMemoryTransportPair.create().agentTransport())
			.build();
		Object asyncClient = AcpClient.async(InMemoryTransportPair.create().clientTransport()).build();
		Object syncClient = AcpClient.sync(InMemoryTransportPair.create().clientTransport()).build();

		assertThat(requestTimeoutOf(builderAgent)).isEqualTo(SDK_DEFAULT);
		assertThat(requestTimeoutOf(annotatedAgent)).isEqualTo(SDK_DEFAULT);
		assertThat(requestTimeoutOf(asyncClient)).isEqualTo(SDK_DEFAULT);
		assertThat(requestTimeoutOf(syncClient)).isEqualTo(SDK_DEFAULT);
	}

	@Test
	void anAnnotatedAgentStillTakesItsOwnTimeout() {
		Object annotatedAgent = AcpAgentSupport.create(new EchoAgent())
			.requestTimeout(Duration.ofSeconds(7))
			.transport(InMemoryTransportPair.create().agentTransport())
			.build();

		assertThat(requestTimeoutOf(annotatedAgent)).isEqualTo(Duration.ofSeconds(7));
	}

	/**
	 * The first {@code Duration requestTimeout} field reachable from {@code root} through the
	 * SDK's own objects, breadth first.
	 */
	private static Duration requestTimeoutOf(Object root) {
		Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<Object> queue = new ArrayDeque<>();
		queue.add(root);
		while (!queue.isEmpty()) {
			Object current = queue.poll();
			if (!seen.add(current)) {
				continue;
			}
			for (Class<?> type = current.getClass(); type != null
					&& type.getName().startsWith("com.agentclientprotocol."); type = type.getSuperclass()) {
				for (Field field : type.getDeclaredFields()) {
					if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
						continue;
					}
					Object value = read(field, current);
					if (field.getName().equals("requestTimeout") && value instanceof Duration duration) {
						return duration;
					}
					if (value != null && value.getClass().getName().startsWith("com.agentclientprotocol.")) {
						queue.add(value);
					}
				}
			}
		}
		throw new AssertionError("No requestTimeout reachable from " + root.getClass().getName());
	}

	private static Object read(Field field, Object target) {
		try {
			field.setAccessible(true);
			return field.get(target);
		}
		catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

}
