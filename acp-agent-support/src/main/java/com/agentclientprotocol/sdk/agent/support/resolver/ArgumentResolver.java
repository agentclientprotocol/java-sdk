/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import org.jspecify.annotations.Nullable;

/**
 * Supplies the value of one kind of handler-method parameter, so that a handler method can declare
 * the parameter and receive it on each call. Implement one for a type the built-in resolvers do not
 * supply, such as a service of the application, and register it with
 * {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport.Builder#argumentResolver
 * AcpAgentSupport.Builder.argumentResolver(..)}. The built-in resolvers supply the parameters that
 * {@code @AcpAgent} and each handler annotation list: the request, the {@code @SessionId}, the
 * prompt contexts, {@code @ConfigId} and {@code @ConfigValue}, the connection's capabilities and
 * agent, and an extension method's params.
 *
 * <p>For each parameter, the runtime asks the resolvers in turn and uses the first whose
 * {@link #supportsParameter} returns {@code true}: custom resolvers in the order they were added,
 * then the built-in ones, so a custom resolver can also replace a built-in one. The choice is made
 * once per parameter and kept ({@link ArgumentResolverComposite}); {@link #resolveArgument} is then
 * called on every call of the method. A parameter that a custom resolver supports is not checked
 * when the agent is built, so making it work is the resolver's job.
 *
 * <p>Example: give handler methods the application's {@code Clock}.
 * <pre>{@code
 * class ClockResolver implements ArgumentResolver {
 *
 *     private final Clock clock;
 *
 *     ClockResolver(Clock clock) {
 *         this.clock = clock;
 *     }
 *
 *     @Override
 *     public boolean supportsParameter(AcpMethodParameter parameter) {
 *         return parameter.getParameterType() == Clock.class;
 *     }
 *
 *     @Override
 *     public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
 *         return clock;
 *     }
 * }
 * }</pre>
 *
 * <p>Implementations must be thread-safe: one instance serves every call, from every session and
 * connection, concurrently. To fail a call, throw an {@link ArgumentResolutionException}, which the
 * client receives as an internal error ({@code -32603}), or an {@code AcpProtocolException} with
 * the error the client should see, such as invalid params ({@code -32602}).
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see ArgumentResolverComposite
 */
public interface ArgumentResolver {

	/**
	 * Returns whether this resolver supplies {@code parameter}. Decide from the parameter's type
	 * and annotations only: it may be asked more than once for a parameter, when the agent is built
	 * and at the first call, and must give the same answer each time.
	 * @param parameter the handler method parameter
	 * @return true if {@link #resolveArgument} can supply it
	 */
	boolean supportsParameter(AcpMethodParameter parameter);

	/**
	 * Returns the value of {@code parameter} for this call. Called on every call of the handler
	 * method, on that call's handler thread, for a parameter {@link #supportsParameter} accepted.
	 * @param parameter the handler method parameter
	 * @param context the call's context: the request, session id, prompt context and connection
	 * @return the value, or null to pass null (which fails the call if the parameter is a
	 * primitive)
	 * @throws ArgumentResolutionException if the value cannot be supplied; the call fails with an
	 * internal error ({@code -32603}), and the exception is logged at the agent
	 */
	@Nullable Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context);

}
