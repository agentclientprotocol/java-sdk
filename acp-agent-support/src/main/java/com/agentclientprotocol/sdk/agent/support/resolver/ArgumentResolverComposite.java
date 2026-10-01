/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import org.jspecify.annotations.Nullable;

/**
 * Composite that chains multiple argument resolvers.
 * Caches resolver selection per parameter for performance.
 *
 * <p>Resolver lookup is cached using {@link AcpMethodParameter} as the
 * cache key. The first resolver that supports a parameter is cached and
 * reused for subsequent invocations.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class ArgumentResolverComposite implements ArgumentResolver {

	private final List<ArgumentResolver> resolvers = new ArrayList<>();

	// Cache: parameter -> resolver, empty when no resolver supports the parameter
	private final ConcurrentMap<AcpMethodParameter, Optional<ArgumentResolver>> resolverCache = new ConcurrentHashMap<>(
			256);

	/**
	 * Add a resolver to the chain.
	 * @param resolver the resolver to add
	 * @return this composite for chaining
	 */
	public ArgumentResolverComposite addResolver(ArgumentResolver resolver) {
		this.resolvers.add(resolver);
		return this;
	}

	/**
	 * Add multiple resolvers to the chain.
	 * @param resolvers the resolvers to add
	 * @return this composite for chaining
	 */
	public ArgumentResolverComposite addResolvers(List<ArgumentResolver> resolvers) {
		this.resolvers.addAll(resolvers);
		return this;
	}

	/**
	 * Get the list of registered resolvers.
	 * @return unmodifiable list of resolvers
	 */
	public List<ArgumentResolver> getResolvers() {
		return List.copyOf(resolvers);
	}

	/**
	 * Clear the resolver cache. Call this if resolvers are modified after
	 * the composite has been used.
	 */
	public void clearCache() {
		resolverCache.clear();
	}

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return getResolver(parameter) != null;
	}

	@Override
	public @Nullable Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		ArgumentResolver resolver = getResolver(parameter);
		if (resolver == null) {
			throw new ArgumentResolutionException(
					"No resolver for parameter: " + parameter.getName() + " of type "
							+ parameter.getParameterType().getName());
		}
		return resolver.resolveArgument(parameter, context);
	}

	private @Nullable ArgumentResolver getResolver(AcpMethodParameter parameter) {
		// The first resolver that supports the parameter; a miss is cached too
		return resolverCache
			.computeIfAbsent(parameter, p -> resolvers.stream().filter(r -> r.supportsParameter(p)).findFirst())
			.orElse(null);
	}

}
