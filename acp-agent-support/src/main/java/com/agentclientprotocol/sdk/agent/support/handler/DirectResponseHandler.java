/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import java.util.List;

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

	/** The response types of the ACP methods an annotated handler can serve. */
	private static final List<Class<?>> RESPONSE_TYPES = List.of(InitializeResponse.class, LogoutResponse.class,
			NewSessionResponse.class, LoadSessionResponse.class, PromptResponse.class, SetSessionModeResponse.class,
			ListSessionsResponse.class, CloseSessionResponse.class, DeleteSessionResponse.class,
			ResumeSessionResponse.class, ForkSessionResponse.class, SetSessionConfigOptionResponse.class,
			ListProvidersResponse.class, SetProviderResponse.class, DisableProviderResponse.class);

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		Class<?> type = returnType.getParameterType();
		return RESPONSE_TYPES.stream().anyMatch(responseType -> responseType.isAssignableFrom(type));
	}

	@Override
	public @Nullable Object handleReturnValue(@Nullable Object returnValue, AcpMethodParameter returnType,
			AcpInvocationContext context) {
		// Direct passthrough - no conversion needed
		return returnValue;
	}

}
