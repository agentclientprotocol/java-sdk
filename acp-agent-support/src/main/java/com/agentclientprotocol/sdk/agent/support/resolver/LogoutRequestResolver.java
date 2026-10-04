/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.LogoutRequest;

/**
 * Supplies the {@link LogoutRequest} that the client sent in {@code logout} to a
 * {@link com.agentclientprotocol.sdk.annotation.Logout @Logout} method that declares a parameter of
 * that type: a request that carries only {@code _meta}. It is one of the built-in
 * {@link ArgumentResolver}s that {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport
 * AcpAgentSupport} registers by default, so an application never creates or registers it: it
 * declares the parameter, or leaves it out when the method does not need the request.
 *
 * <p>It works as {@link PromptRequestResolver} does, for {@code LogoutRequest}: building the agent
 * rejects the parameter on a method of another ACP method, a custom resolver for the type replaces
 * this one, and {@link #resolveArgument} throws an {@link ArgumentResolutionException} for a
 * context that holds another request.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class LogoutRequestResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return LogoutRequest.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Object request = context.getRequest();
		if (request instanceof LogoutRequest) {
			return request;
		}
		throw new ArgumentResolutionException(
				"Expected LogoutRequest but got: " + request.getClass().getName());
	}

}
