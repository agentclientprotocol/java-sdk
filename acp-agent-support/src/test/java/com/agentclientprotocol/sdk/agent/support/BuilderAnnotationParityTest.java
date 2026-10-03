/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything an agent builder offers is reachable from an annotated agent: each handler setter
 * through a handler annotation ({@link HandlerAnnotationCoverageTest} checks which), and each
 * other setter through a method of the same name on {@link AcpAgentSupport.Builder}, unless it
 * is listed below as builder-only, with the reason. A setter added to the builders without an
 * annotation equivalent fails here.
 */
class BuilderAnnotationParityTest {

	/** Builder methods an annotated agent reaches another way, or does not need, and why. */
	private static final Map<String, String> BUILDER_ONLY = Map.of("extRequestHandler",
			"served by @ExtRequest methods, one per extension method name", "extNotificationHandler",
			"served by @ExtNotification methods, one per extension method name", "agentInfo",
			"declared by @AcpAgent(name, version, title)");

	@Test
	void everySyncBuilderMethodHasAnAnnotationEquivalent() {
		assertThat(unmatched(AcpAgent.SyncAgentBuilder.class)).isEmpty();
	}

	@Test
	void everyAsyncBuilderMethodHasAnAnnotationEquivalent() {
		assertThat(unmatched(AcpAgent.AsyncAgentBuilder.class)).isEmpty();
	}

	/** The builder-only list names only methods the builders have, so it cannot go stale. */
	@Test
	void theBuilderOnlyListIsCurrent() {
		assertThat(publicMethods(AcpAgent.SyncAgentBuilder.class)).containsAll(BUILDER_ONLY.keySet());
	}

	private static Set<String> unmatched(Class<?> builder) {
		Set<String> annotationSetters = AcpAgentSupport.handlerAnnotations()
			.keySet()
			.stream()
			.map(Class::getSimpleName)
			.map(name -> Character.toLowerCase(name.charAt(0)) + name.substring(1) + "Handler")
			.collect(Collectors.toSet());
		Set<String> supportBuilder = publicMethods(AcpAgentSupport.Builder.class);
		Set<String> unmatched = new TreeSet<>(publicMethods(builder));
		unmatched.removeIf(name -> annotationSetters.contains(name) || supportBuilder.contains(name)
				|| BUILDER_ONLY.containsKey(name));
		return unmatched;
	}

	private static Set<String> publicMethods(Class<?> type) {
		return Arrays.stream(type.getDeclaredMethods())
			.filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
			.map(Method::getName)
			.collect(Collectors.toCollection(TreeSet::new));
	}

}
