/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;

/**
 * Supplies the {@link PromptRequest} that the client sent in {@code session/prompt} to a
 * {@link com.agentclientprotocol.sdk.annotation.Prompt @Prompt} method that declares a parameter of
 * that type: the session id and the content blocks of the user's prompt. It is one of the built-in
 * {@link ArgumentResolver}s that {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport
 * AcpAgentSupport} registers by default, so an application never creates or registers it: it
 * declares the parameter, or leaves it out when the method does not need the request.
 *
 * <p>It supplies a parameter whose type is {@code PromptRequest}, whatever its annotations, and
 * passes the request the call received, unchanged. A built agent never asks it for another request:
 * building the agent rejects a {@code PromptRequest} parameter on a method that handles another ACP
 * method (an {@code IllegalStateException} naming the method), and the parameters of an extension
 * method go to {@link ExtensionParamsResolver}, which is asked first. A custom resolver that
 * supports {@code PromptRequest} is asked before this one and replaces it. With a context whose
 * request is not a {@code PromptRequest}, as a context built by hand can be,
 * {@link #resolveArgument} throws an {@link ArgumentResolutionException}.
 *
 * <p>There is one request resolver for each request or notification a handler annotation receives;
 * they all work like this one and differ only in the type they supply. They are stateless and
 * thread-safe.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class PromptRequestResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return PromptRequest.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Object request = context.getRequest();
		if (request instanceof PromptRequest) {
			return request;
		}
		throw new ArgumentResolutionException(
				"Expected PromptRequest but got: " + request.getClass().getName());
	}

}
