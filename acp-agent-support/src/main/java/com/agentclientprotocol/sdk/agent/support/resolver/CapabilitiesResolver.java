/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;

/**
 * Supplies the capabilities negotiated on the connection that the request or notification arrived
 * on to a {@link NegotiatedCapabilities} parameter: what the client offered in its
 * {@code initialize} request. Any handler method can take one, extension methods included, to check
 * that the client supports a feature before offering it or calling it. It is one of the built-in
 * {@link ArgumentResolver}s that {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport
 * AcpAgentSupport} registers by default, so an application never creates or registers it: it
 * declares the parameter.
 *
 * <p>It works as {@link PromptContextResolver} does, for the capabilities, with no restriction on
 * the method. The agent records them from the client's {@code initialize} request before the
 * {@code @Initialize} method runs, so that method can take them too. A call that arrives before the
 * client has sent {@code initialize} has none: {@link #resolveArgument} then throws an
 * {@link ArgumentResolutionException}, and the client receives an internal error ({@code -32603}).
 *
 * @author Mark Pollack
 */
public class CapabilitiesResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return NegotiatedCapabilities.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		return context.getCapabilities()
				.orElseThrow(() -> new ArgumentResolutionException(
						"NegotiatedCapabilities are not available: the client has not sent initialize"));
	}

}
