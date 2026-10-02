/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;

/**
 * Resolves {@link NegotiatedCapabilities} parameters to the capabilities negotiated on the
 * connection a request arrived on, in any handler. The agent records them from the client's
 * {@code initialize} request before the {@code @Initialize} handler runs, so that handler can
 * take them too.
 *
 * @author Mark Pollack
 * @since 1.0.0
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
