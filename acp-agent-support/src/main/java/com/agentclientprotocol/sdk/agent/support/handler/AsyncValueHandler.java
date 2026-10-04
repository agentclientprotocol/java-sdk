/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.CompletionStage;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Handles a value that a handler method returns later: a {@link Mono}, a {@link CompletionStage}
 * (such as a {@code CompletableFuture}) or another Reactive Streams {@link Publisher} of at most
 * one value. The runtime waits for the value on the handler's thread, and the value then means what
 * returning it directly means: the method's response, a prompt method's {@code String} (sent to the
 * client as an agent message chunk before the turn ends, as {@link StringToPromptResponseHandler}
 * does), or an extension request's result. It is one of the built-in {@link ReturnValueHandler}s
 * that {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport AcpAgentSupport} registers
 * by default, so an application never creates or registers it: the method declares the return type.
 *
 * <p>An empty {@code Mono} or {@code Publisher}, or a stage that completes with {@code null},
 * produces no response, which for a request is answered with an internal error ({@code -32603}). A
 * {@code Publisher} that emits more than one value fails the call with an internal error too, and a
 * value that fails fails the call as an exception from the method would. Building the agent checks
 * the value type where it can read it: a {@code Mono<NewSessionResponse>} or a {@code Mono<Void>}
 * from a {@code @Prompt} method is rejected. The wait has no time limit of its own.
 *
 * <p>There is one built-in return value handler for each kind of return value:
 * {@link DirectResponseHandler}, {@link StringToPromptResponseHandler}, {@link VoidHandler}, this
 * one and {@link ExtensionResultHandler}, asked in that order after any custom handler. They are
 * stateless and thread-safe.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class AsyncValueHandler implements ReturnValueHandler {

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		Class<?> type = returnType.getParameterType();
		return Publisher.class.isAssignableFrom(type) || CompletionStage.class.isAssignableFrom(type);
	}

	@Override
	public @Nullable Object handleReturnValue(@Nullable Object returnValue, AcpMethodParameter returnType,
			AcpInvocationContext context) {
		if (returnValue == null) {
			return null;
		}
		Object value = await(returnValue);
		if (value instanceof String text && AcpSchema.METHOD_SESSION_PROMPT.equals(context.getAcpMethod())) {
			return StringToPromptResponseHandler.endTurnWith(text, context);
		}
		return value;
	}

	private static @Nullable Object await(Object async) {
		if (async instanceof CompletionStage<?> stage) {
			return Mono.fromCompletionStage(stage).block();
		}
		if (async instanceof Mono<?> mono) {
			return mono.block();
		}
		return Flux.from((Publisher<?>) async).singleOrEmpty().block();
	}

	/**
	 * Returns the value type of an async return type, such as {@code PromptResponse} for
	 * {@code Mono<PromptResponse>}.
	 * @param returnType a handler method's return type
	 * @return the first type argument when it is a class; {@code Object} otherwise, also for a raw
	 * type, a wildcard, a type variable or a parameterized argument such as {@code List<String>}
	 */
	public static Class<?> getValueType(AcpMethodParameter returnType) {
		Type genericType = returnType.getGenericType();
		if (genericType instanceof ParameterizedType pt) {
			Type[] typeArgs = pt.getActualTypeArguments();
			if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> c) {
				return c;
			}
		}
		return Object.class;
	}

}
