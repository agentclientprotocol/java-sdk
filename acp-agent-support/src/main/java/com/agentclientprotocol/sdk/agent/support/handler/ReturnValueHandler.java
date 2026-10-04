/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import org.jspecify.annotations.Nullable;

/**
 * Turns what a handler method returned into the response of its ACP method. Implement one for a
 * return type the built-in handlers do not accept, such as another library's async type, and
 * register it with
 * {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport.Builder#returnValueHandler
 * AcpAgentSupport.Builder.returnValueHandler(..)}. The built-in handlers accept the method's
 * response itself, {@code void} and {@code String} from a {@code @Prompt} method, a {@code Mono},
 * {@code CompletionStage} or single-value {@code Publisher} of those, and an extension method's
 * result.
 *
 * <p>For each call, the runtime uses the first handler whose {@link #supportsReturnType} returns
 * {@code true}: custom handlers in the order they were added, then the built-in ones, so a custom
 * handler can also replace a built-in one. A return type that a custom handler supports is not
 * checked when the agent is built. A handler for an async type can wait for the value and pass it
 * to a built-in handler, such as {@link StringToPromptResponseHandler} for a {@code String} from a
 * {@code @Prompt} method.
 *
 * <p>Implementations must be thread-safe: one instance serves every call, from every session and
 * connection, concurrently. {@link #handleReturnValue} runs on the call's handler thread and may
 * block there, as the built-in handler does to wait for a {@code Mono}.
 *
 * @author Mark Pollack
 * @see ReturnValueHandlerComposite
 */
public interface ReturnValueHandler {

	/**
	 * Returns whether this handler accepts the return type. Decide from the type only: it is asked
	 * when the agent is built and on every call.
	 * @param returnType the handler method's return type
	 * @return true if {@link #handleReturnValue} can turn values of it into the response
	 */
	boolean supportsReturnType(AcpMethodParameter returnType);

	/**
	 * Turns {@code returnValue} into the response sent to the client. For a request, the result
	 * must be the ACP method's response type, such as a {@code PromptResponse}; another type, or
	 * null, is answered with an internal error ({@code -32603}). For a notification, the result is
	 * ignored.
	 * @param returnValue what the method returned, after the interceptors' {@code postInvoke}; null
	 * for a {@code void} method
	 * @param returnType the handler method's return type
	 * @param context the call's context
	 * @return the response
	 * @throws ReturnValueHandlingException if the value cannot be turned into a response; the call
	 * fails with an internal error ({@code -32603}), and the exception is logged at the agent
	 */
	@Nullable Object handleReturnValue(@Nullable Object returnValue, AcpMethodParameter returnType,
			AcpInvocationContext context);

}
