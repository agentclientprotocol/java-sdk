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
 * Resolves the params parameter of an {@link ExtRequest} or {@link ExtNotification} handler to
 * the extension method's params, already read as that parameter's type. The handler's other
 * parameters, if any, are of a {@linkplain #isConnectionType connection type}, resolved by
 * their own resolvers.
 *
 * @author Mark Pollack
 */
public class ExtensionParamsResolver implements ArgumentResolver {

	/** The types an extension handler's parameters may have besides its params. */
	private static final List<Class<?>> CONNECTION_TYPES = List.of(NegotiatedCapabilities.class,
			AcpSyncAgent.class, AcpAsyncAgent.class);

	/**
	 * Whether a handler parameter of this type receives its connection's capabilities or agent,
	 * not an extension method's params.
	 * @param type the parameter type
	 * @return true for {@code NegotiatedCapabilities}, {@code AcpSyncAgent} and
	 * {@code AcpAsyncAgent}
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
