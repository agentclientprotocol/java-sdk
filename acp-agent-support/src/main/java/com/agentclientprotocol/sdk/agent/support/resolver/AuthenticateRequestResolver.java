/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.AuthenticateRequest;

/**
 * Resolves {@link AuthenticateRequest} parameters in authenticate handlers.
 *
 * @author Mark Pollack
 * @since 0.80.0
 */
public class AuthenticateRequestResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return AuthenticateRequest.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Object request = context.getRequest();
		if (request instanceof AuthenticateRequest) {
			return request;
		}
		throw new ArgumentResolutionException(
				"Expected AuthenticateRequest but got: " + request.getClass().getName());
	}

}
