/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import java.util.List;

import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.annotation.ExtNotification;
import com.agentclientprotocol.sdk.annotation.ExtRequest;

/**
 * Supplies the params of an extension method to its handler: the parameter of an
 * {@link ExtRequest @ExtRequest} or {@link ExtNotification @ExtNotification} method that is not of
 * a {@linkplain #isConnectionType connection type} receives the request's or notification's params,
 * already read by the JSON mapper as that parameter's declared type, such as a record or a
 * {@code Map<String, Object>}. It is one of the built-in {@link ArgumentResolver}s that
 * {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport AcpAgentSupport} registers by
 * default, the first of them to be asked, so an application never creates or registers it: the
 * method declares the parameter.
 *
 * <p>It takes every parameter of an extension method that is not of a connection type, whatever its
 * type or annotations, so a parameter declared as a request type such as {@code PromptRequest}, or
 * as a prompt context, is read from the params too. Registering the agent rejects an extension
 * method with more than one such parameter, or with a {@code @SessionId}, {@code @ConfigId} or
 * {@code @ConfigValue} parameter, with an {@code IllegalArgumentException}. Params that cannot be
 * read as the declared type are answered with invalid params ({@code -32602}) before the method is
 * called. The connection-type parameters go to {@link CapabilitiesResolver} and
 * {@link AgentResolver}.
 *
 * <p>It works as {@link PromptContextResolver} does otherwise, except that {@link #resolveArgument}
 * never throws: it passes the call's request, which for an extension method is its params.
 *
 * @author Mark Pollack
 */
public class ExtensionParamsResolver implements ArgumentResolver {

	/** The types an extension handler's parameters may have besides its params. */
	private static final List<Class<?>> CONNECTION_TYPES = List.of(NegotiatedCapabilities.class,
			AcpSyncAgent.class, AcpAsyncAgent.class);

	/**
	 * Returns whether a parameter of this type receives the call's connection rather than an
	 * extension method's params: true for {@link NegotiatedCapabilities}, {@link AcpSyncAgent} and
	 * {@link AcpAsyncAgent}, the types {@link CapabilitiesResolver} and {@link AgentResolver}
	 * supply. The runtime uses it to find an extension method's params parameter; applications do
	 * not need it.
	 * @param type the parameter type
	 * @return true for exactly those three types, false for any other type, a subtype included
	 */
	public static boolean isConnectionType(Class<?> type) {
		return CONNECTION_TYPES.contains(type);
	}

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return (parameter.getMethod().isAnnotationPresent(ExtRequest.class)
				|| parameter.getMethod().isAnnotationPresent(ExtNotification.class))
				&& !isConnectionType(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		return context.getRequest();
	}

}
