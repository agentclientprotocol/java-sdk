/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * The rule every framework applies to the application's {@code @AcpAgent} classes: none means a
 * client-only application, one is served, more than one is an error naming them. The container
 * finds the candidates its own way (bean metadata, a build-time index); this class decides.
 */
public final class AcpAgentDiscovery {

	private AcpAgentDiscovery() {
	}

	/**
	 * An {@code @AcpAgent} bean the container found.
	 * @param name the container's name for it, used in messages
	 * @param userClass the class the application wrote, from the container's metadata: never a
	 * proxy or generated subclass (CGLIB, ArC, Micronaut AOP), whose methods carry no handler
	 * annotations
	 * @param instance supplies the bean, possibly a proxy, that handles every request
	 * @param <T> the agent type
	 */
	public record AgentCandidate<T>(String name, Class<T> userClass, Supplier<? extends T> instance) {

		/**
		 * A candidate, each part checked.
		 * @throws NullPointerException if a part is null
		 */
		public AgentCandidate {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(userClass, "userClass");
			Objects.requireNonNull(instance, "instance");
		}

	}

	/**
	 * The one candidate.
	 * @param candidates the {@code @AcpAgent} beans the container found
	 * @return the candidate, or empty when there is none
	 * @throws IllegalStateException listing them when there is more than one
	 */
	public static Optional<AgentCandidate<?>> requireSingle(Collection<? extends AgentCandidate<?>> candidates) {
		return requireSingle(candidates, null);
	}

	/**
	 * The one candidate.
	 * @param candidates the {@code @AcpAgent} beans the container found
	 * @param enabledProperty the framework's property that turns the agent off, named in the error
	 * as the way to serve none; null to name none
	 * @return the candidate, or empty when there is none
	 * @throws IllegalStateException listing them when there is more than one
	 */
	public static Optional<AgentCandidate<?>> requireSingle(Collection<? extends AgentCandidate<?>> candidates,
			@Nullable String enabledProperty) {
		if (candidates.size() > 1) {
			throw tooMany("beans", candidates.stream().map(candidate -> candidate.userClass().getName()).toList(),
					enabledProperty);
		}
		return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.iterator().next());
	}

	/**
	 * The same rule over class names, for build-time discovery (a Jandex index), with no class
	 * loaded.
	 * @param classNames the names of the {@code @AcpAgent} classes found
	 * @param enabledProperty the framework's property that turns the agent off, named in the error;
	 * null to name none
	 * @return the class name, or empty when there is none
	 * @throws IllegalStateException listing them when there is more than one
	 */
	public static Optional<String> requireSingle(List<String> classNames, @Nullable String enabledProperty) {
		if (classNames.size() > 1) {
			throw tooMany("classes", classNames, enabledProperty);
		}
		return classNames.stream().findFirst();
	}

	private static IllegalStateException tooMany(String kind, List<String> names, @Nullable String enabledProperty) {
		String serveNone = (enabledProperty != null) ? ", or set " + enabledProperty + "=false to serve none" : "";
		return new IllegalStateException("Found " + names.size() + " @AcpAgent " + kind + " "
				+ names.stream().sorted().toList() + ", but an application serves one: remove @AcpAgent from all but one"
				+ serveNone);
	}

}
