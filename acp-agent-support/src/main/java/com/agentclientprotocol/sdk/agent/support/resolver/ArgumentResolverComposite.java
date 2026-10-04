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
 * An {@link ArgumentResolver} that asks a list of resolvers in order and uses the first that
 * supports a parameter. The annotation runtime builds one each time an agent is built, with the
 * custom resolvers first and the built-in ones after, and resolves every handler parameter through
 * it. Applications register resolvers on {@code AcpAgentSupport.Builder} and do not need this
 * class.
 *
 * <p>It remembers, for each {@link AcpMethodParameter}, which resolver supports it, or that none
 * does, so it asks the resolvers once per parameter. Resolvers added after a parameter was looked
 * up do not change the answer for it; call {@link #clearCache()} then. Looking up and resolving are
 * thread-safe; adding resolvers is not, so add them all before the first use.
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
	 * Adds a resolver, asked after the ones added before.
	 * @param resolver the resolver, not null
	 * @return this composite
	 */
	public ArgumentResolverComposite addResolver(ArgumentResolver resolver) {
		this.resolvers.add(resolver);
		return this;
	}

	/**
	 * Adds resolvers, in list order, asked after the ones added before.
	 * @param resolvers the resolvers, none of them null
	 * @return this composite
	 */
	public ArgumentResolverComposite addResolvers(List<ArgumentResolver> resolvers) {
		this.resolvers.addAll(resolvers);
		return this;
	}

	/**
	 * Returns the resolvers in the order they are asked.
	 * @return an unmodifiable copy of the resolvers
	 */
	public List<ArgumentResolver> getResolvers() {
		return List.copyOf(resolvers);
	}

	/**
	 * Forgets which resolver supports each parameter, so the next lookup asks the resolvers again.
	 * Needed only after adding resolvers to a composite already in use.
	 */
	public void clearCache() {
		resolverCache.clear();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>True if one of its resolvers supports the parameter. The answer is kept.
	 * @param parameter {@inheritDoc}
	 * @return {@inheritDoc}
	 */
	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return getResolver(parameter) != null;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Delegates to the first resolver that supports the parameter.
	 * @param parameter {@inheritDoc}
	 * @param context {@inheritDoc}
	 * @return {@inheritDoc}
	 * @throws ArgumentResolutionException if no resolver supports the parameter, or the resolver
	 * throws it
	 */
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
