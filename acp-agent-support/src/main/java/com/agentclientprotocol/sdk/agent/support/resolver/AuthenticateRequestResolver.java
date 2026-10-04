/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.AuthenticateRequest;

/**
 * Supplies the {@link AuthenticateRequest} that the client sent in {@code authenticate} to an
 * {@link com.agentclientprotocol.sdk.annotation.Authenticate @Authenticate} method that declares a
 * parameter of that type: the id of the auth method the client chose from those the agent
 * advertised. It is one of the built-in {@link ArgumentResolver}s that
 * {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport AcpAgentSupport} registers by
 * default, so an application never creates or registers it: it declares the parameter, or leaves it
 * out when the method does not need the request.
 *
 * <p>It works as {@link PromptRequestResolver} does, for {@code AuthenticateRequest}: building the
 * agent rejects the parameter on a method of another ACP method, a custom resolver for the type
 * replaces this one, and {@link #resolveArgument} throws an {@link ArgumentResolutionException} for
 * a context that holds another request.
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
