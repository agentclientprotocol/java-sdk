/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import java.util.List;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.AuthenticateResponse;
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
 * Handles a return value that is already an ACP response, such as an {@link InitializeResponse},
 * {@link NewSessionResponse}, {@link LoadSessionResponse}, {@link PromptResponse} or
 * {@link SetSessionModeResponse}: it passes the value through unchanged, as the response sent to
 * the client. It applies when the declared return type is the response type of any ACP method that
 * a handler annotation serves, the other session, auth and provider responses included. It is one
 * of the built-in {@link ReturnValueHandler}s that
 * {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport AcpAgentSupport} registers by
 * default, so an application never creates or registers it: the method declares the return type.
 *
 * <p>It works as {@link AsyncValueHandler} does, for a value that needs no waiting, and is asked
 * first. Building the agent rejects a method declared to return the response of another method,
 * such as a {@code NewSessionResponse} from a {@code @Prompt} method, and a {@code null} value
 * produces no response, which is answered with an internal error ({@code -32603}). An
 * {@code @ExtRequest} method that returns one of these types has it sent as its result.
 *
 * @author Mark Pollack
 */
public class DirectResponseHandler implements ReturnValueHandler {

	/** The response types of the ACP methods an annotated handler can serve. */
	private static final List<Class<?>> RESPONSE_TYPES = List.of(InitializeResponse.class, AuthenticateResponse.class,
			LogoutResponse.class, NewSessionResponse.class, LoadSessionResponse.class, PromptResponse.class,
			SetSessionModeResponse.class, ListSessionsResponse.class, CloseSessionResponse.class,
			DeleteSessionResponse.class, ResumeSessionResponse.class, ForkSessionResponse.class,
			SetSessionConfigOptionResponse.class, ListProvidersResponse.class, SetProviderResponse.class,
			DisableProviderResponse.class);

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
