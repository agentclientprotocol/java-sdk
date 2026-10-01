/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.CloseSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.DeleteSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.DisableProviderResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ForkSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ListProvidersResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ListSessionsResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.LoadSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.LogoutResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ResumeSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetProviderResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeResponse;
import org.jspecify.annotations.Nullable;

/**
 * Handles direct protocol response types that need no conversion.
 *
 * <p>Supports {@link InitializeResponse}, {@link NewSessionResponse},
 * {@link LoadSessionResponse}, {@link PromptResponse}, {@link SetSessionModeResponse}, and the other
 * session and provider responses.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class DirectResponseHandler implements ReturnValueHandler {

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		Class<?> type = returnType.getParameterType();
		return InitializeResponse.class.isAssignableFrom(type)
				|| LogoutResponse.class.isAssignableFrom(type)
				|| NewSessionResponse.class.isAssignableFrom(type)
				|| LoadSessionResponse.class.isAssignableFrom(type)
				|| PromptResponse.class.isAssignableFrom(type)
				|| SetSessionModeResponse.class.isAssignableFrom(type)
				|| ListSessionsResponse.class.isAssignableFrom(type)
				|| CloseSessionResponse.class.isAssignableFrom(type)
				|| DeleteSessionResponse.class.isAssignableFrom(type)
				|| ResumeSessionResponse.class.isAssignableFrom(type)
				|| ForkSessionResponse.class.isAssignableFrom(type)
				|| SetSessionConfigOptionResponse.class.isAssignableFrom(type)
				|| ListProvidersResponse.class.isAssignableFrom(type)
				|| SetProviderResponse.class.isAssignableFrom(type)
				|| DisableProviderResponse.class.isAssignableFrom(type);
	}

	@Override
	public @Nullable Object handleReturnValue(@Nullable Object returnValue, AcpMethodParameter returnType,
			AcpInvocationContext context) {
		// Direct passthrough - no conversion needed
		return returnValue;
	}

}
