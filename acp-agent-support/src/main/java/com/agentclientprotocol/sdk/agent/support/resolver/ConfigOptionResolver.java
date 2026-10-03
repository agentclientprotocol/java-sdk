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
 * Resolves the {@link ConfigId} and {@link ConfigValue} parameters of a
 * {@code @SetSessionConfigOption} method from its {@link SetSessionConfigOptionRequest}: the
 * option id as a {@code String}, and the value as a {@code String} (a select option), a
 * {@code boolean} or {@code Boolean} (a boolean option), or an {@code Object} (either). A value of
 * the other kind than the parameter's is answered with {@code -32602} (invalid params), so the
 * method is not called with a value it cannot take.
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
