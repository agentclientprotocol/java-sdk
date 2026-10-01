/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeRequest;

/**
 * Resolves {@link SetSessionModeRequest} parameters in set session mode handlers.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class SetSessionModeRequestResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return SetSessionModeRequest.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Object request = context.getRequest();
		if (request instanceof SetSessionModeRequest) {
			return request;
		}
		throw new ArgumentResolutionException("Expected SetSessionModeRequest but got: "
				+ request.getClass().getName());
	}

}
