/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.annotation.SessionId;

/**
 * Supplies the session id to a {@code String} parameter annotated {@link SessionId @SessionId}: the
 * id of the session that the request or notification being handled names, the same value as its
 * {@code sessionId()}. A handler method uses it when it needs only the id, or does not take the
 * request. It is one of the built-in {@link ArgumentResolver}s that
 * {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport AcpAgentSupport} registers by
 * default, so an application never creates or registers it: it declares the parameter.
 *
 * <p>It works as {@link PromptContextResolver} does, for the session id: building the agent rejects
 * a {@code @SessionId} parameter on a method whose ACP method names no session ({@code @SessionId}
 * lists them), and {@link #resolveArgument} throws an {@link ArgumentResolutionException} for a
 * context without a session id. It supplies only a {@code String} parameter: for a
 * {@code @SessionId} parameter of another type no resolver applies, and building the agent fails.
 *
 * @author Mark Pollack
 */
public class SessionIdResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return parameter.hasAnnotation(SessionId.class)
				&& String.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		return context.getSessionId()
				.orElseThrow(() -> new ArgumentResolutionException(
						"Session ID not available in current context"));
	}

}
