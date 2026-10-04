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
 * Decides which {@code @AcpAgent} an application serves, by the rule every framework applies:
 * none means a client-only application (serve no agent), one is served, and more than one is an
 * error that names them all. The framework finds the candidates its own way, from bean metadata
 * at run time ({@link AgentCandidate}) or from a build-time index by class name, and passes them
 * to {@code requireSingle}; this class only decides. The chosen candidate goes to
 * {@link AcpAgents#builder}.
 *
 * <p>Pass the framework's property that turns the agent off, such as
 * {@code spring.acp.agent.enabled}, so the error tells the user how to serve none. A framework
 * that requires an agent (because it found one by annotation already) turns the empty result
 * into its own error.
 */
public final class AcpAgentDiscovery {

	private AcpAgentDiscovery() {
	}

	/**
	 * One {@code @AcpAgent} bean the container found: its name, the class the application wrote,
	 * and a way to get the bean. The framework builds one per bean from its own metadata and
	 * passes them to {@link AcpAgentDiscovery#requireSingle(Collection, String)}.
	 *
	 * <p>The user class must be the class the application declared, taken from the container's
	 * metadata (Spring's {@code ClassUtils.getUserClass}, Micronaut's bean definition type), never
	 * the class of a proxy or generated subclass (CGLIB, ArC, Micronaut AOP): the handler
	 * annotations are on the user class, and a proxy's overriding methods carry none. The instance
	 * may be such a proxy, which keeps its advice (transactions, security) on every call.
	 * {@link AcpAgents#builder} calls the supplier once, while it assembles the agent.
	 * @param name the container's name for the bean, used in messages
	 * @param userClass the class the application wrote, whose methods carry the handler
	 * annotations
	 * @param instance supplies the bean that handles every request, possibly a proxy
	 * @param <T> the agent type
	 */
	public record AgentCandidate<T>(String name, Class<T> userClass, Supplier<? extends T> instance) {

		/**
		 * Creates a candidate, checking each part.
		 * @throws NullPointerException if a part is null
		 */
		public AgentCandidate {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(userClass, "userClass");
			Objects.requireNonNull(instance, "instance");
		}

	}

	/**
	 * Returns the one candidate, as {@link #requireSingle(Collection, String)} does, with an
	 * error that names no property.
	 * @param candidates the {@code @AcpAgent} beans the container found
	 * @return the candidate, or empty when there is none
	 * @throws IllegalStateException if there is more than one; the message lists their user
	 * classes
	 */
	public static Optional<AgentCandidate<?>> requireSingle(Collection<? extends AgentCandidate<?>> candidates) {
		return requireSingle(candidates, null);
	}

	/**
	 * Returns the one candidate the application serves, or empty for a client-only application.
	 * @param candidates the {@code @AcpAgent} beans the container found
	 * @param enabledProperty the framework's property that turns the agent off, such as
	 * {@code spring.acp.agent.enabled}, which the error names as the way to serve none; null to
	 * name none
	 * @return the candidate, or empty when there is none
	 * @throws IllegalStateException if there is more than one; the message lists their user
	 * classes, sorted, and says to remove {@code @AcpAgent} from all but one
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
	 * Applies the same rule to class names, for discovery at build time (Quarkus reads a Jandex
	 * index), so no class is loaded.
	 * @param classNames the names of the {@code @AcpAgent} classes found
	 * @param enabledProperty the framework's property that turns the agent off, which the error
	 * names; null to name none
	 * @return the class name, or empty when there is none
	 * @throws IllegalStateException if there is more than one; the message lists them, sorted
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
