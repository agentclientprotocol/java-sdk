/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.PromptContext;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;

/**
 * Supplies the prompt context to a {@link com.agentclientprotocol.sdk.annotation.Prompt @Prompt}
 * method: a {@link SyncPromptContext} parameter receives the turn's context with blocking calls,
 * and a {@link PromptContext} parameter the same turn's context with calls that return a
 * {@code Mono} (the one {@code SyncPromptContext.async()} returns). Through it the method sends
 * session updates for the turn, calls the client (files, terminals, permission) and learns that the
 * turn was cancelled. It is one of the built-in {@link ArgumentResolver}s that
 * {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport AcpAgentSupport} registers by
 * default, so an application never creates or registers it: it declares the parameter.
 *
 * <p>Only a prompt turn has a prompt context. Building the agent rejects a prompt context parameter
 * on any other handler method, with an {@code IllegalStateException} that names the method and
 * suggests an {@code AcpSyncAgent} or {@code AcpAsyncAgent} parameter instead. An extension method
 * is not checked: {@link ExtensionParamsResolver}, asked first, takes such a parameter for the
 * params. With a context that has no prompt context, as a context built by hand can be,
 * {@link #resolveArgument} throws an {@link ArgumentResolutionException}.
 *
 * <p>The context resolvers fill a parameter from the call's {@link AcpInvocationContext}, not from
 * the request type: this one, {@link SessionIdResolver}, {@link AgentResolver},
 * {@link CapabilitiesResolver}, {@link ConfigOptionResolver} and {@link ExtensionParamsResolver}.
 * They are stateless and thread-safe.
 *
 * @author Mark Pollack
 */
public class PromptContextResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		Class<?> type = parameter.getParameterType();
		return PromptContext.class.isAssignableFrom(type)
				|| SyncPromptContext.class.isAssignableFrom(type);
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Class<?> type = parameter.getParameterType();

		if (SyncPromptContext.class.isAssignableFrom(type)) {
			return context.getSyncPromptContext()
					.orElseThrow(() -> new ArgumentResolutionException(
							"SyncPromptContext is only available for @Prompt handlers"));
		}

		if (PromptContext.class.isAssignableFrom(type)) {
			return context.getPromptContext()
					.orElseThrow(() -> new ArgumentResolutionException(
							"PromptContext is only available for @Prompt handlers"));
		}

		throw new ArgumentResolutionException("Unsupported context type: " + type.getName());
	}

}
