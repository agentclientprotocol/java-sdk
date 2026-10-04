/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.annotation.ConfigId;
import com.agentclientprotocol.sdk.annotation.ConfigValue;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionRequest;
import org.jspecify.annotations.Nullable;

/**
 * Supplies the parts of a {@code session/set_config_option} request to the parameters of a
 * {@link com.agentclientprotocol.sdk.annotation.SetSessionConfigOption @SetSessionConfigOption}
 * method: a {@link ConfigId @ConfigId} {@code String} receives the option id, and a
 * {@link ConfigValue @ConfigValue} parameter the new value, typed. The method can take them instead
 * of reading {@link SetSessionConfigOptionRequest#configId()} and {@code value()} from the request.
 * It is one of the built-in {@link ArgumentResolver}s that
 * {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport AcpAgentSupport} registers by
 * default, so an application never creates or registers it: it declares the parameter.
 *
 * <p>A {@code @ConfigValue} parameter may be a {@code String} for a select option, a
 * {@code boolean} or {@code Boolean} for a boolean option, or an {@code Object} for either, which
 * then receives a {@code String} or a {@code Boolean}. When the client sends a value of the other
 * kind, the call is answered with invalid params ({@code -32602}) naming the option, and the method
 * is not called. A {@code @ConfigId} parameter must be a {@code String}. For a parameter of another
 * type than these, this resolver does not apply, and building the agent fails.
 *
 * <p>It works as {@link PromptContextResolver} does otherwise: building the agent rejects these
 * parameters on any method but the {@code @SetSessionConfigOption} one, registering it rejects them
 * on an extension method, and {@link #resolveArgument} throws an
 * {@link ArgumentResolutionException} for a context whose request is not a
 * {@code SetSessionConfigOptionRequest}.
 *
 * @author Mark Pollack
 * @since 0.80.0
 */
public class ConfigOptionResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		Class<?> type = parameter.getParameterType();
		if (parameter.hasAnnotation(ConfigId.class)) {
			return type == String.class;
		}
		if (parameter.hasAnnotation(ConfigValue.class)) {
			return type == String.class || type == boolean.class || type == Boolean.class || type == Object.class;
		}
		return false;
	}

	@Override
	public @Nullable Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		if (!(context.getRequest() instanceof SetSessionConfigOptionRequest request)) {
			throw new ArgumentResolutionException("@ConfigId and @ConfigValue parameters are only available in a"
					+ " @SetSessionConfigOption method, not for " + context.getAcpMethod());
		}
		if (parameter.hasAnnotation(ConfigId.class)) {
			return request.configId();
		}
		return value(parameter.getParameterType(), request);
	}

	/** The request's value as {@code type}, or an invalid params error when it is of the other kind. */
	private static Object value(Class<?> type, SetSessionConfigOptionRequest request) {
		Object value = request.value();
		if (type == Object.class) {
			return value;
		}
		boolean select = type == String.class;
		if (select ? value instanceof String : value instanceof Boolean) {
			return value;
		}
		throw invalid(request, select ? "a select value (a string)" : "a boolean value");
	}

	private static AcpProtocolException invalid(SetSessionConfigOptionRequest request, String expected) {
		return new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "Config option '" + request.configId()
				+ "' takes " + expected + ", not " + request.value());
	}

}
