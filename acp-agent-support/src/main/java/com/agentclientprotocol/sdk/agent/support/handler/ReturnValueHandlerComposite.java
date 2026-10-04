/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import java.util.ArrayList;
import java.util.List;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import org.jspecify.annotations.Nullable;

/**
 * A {@link ReturnValueHandler} that asks a list of handlers in order and uses the first that
 * supports a return type. The annotation runtime builds one each time an agent is built, with the
 * custom handlers first and the built-in ones after, and turns every handler method's result into
 * its response through it. Applications register handlers on {@code AcpAgentSupport.Builder} and do
 * not need this class.
 *
 * <p>Unlike {@link com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolverComposite},
 * it remembers nothing: it asks the handlers again on every call. Handling is thread-safe once the
 * handlers are added; adding handlers is not, so add them all before the first use.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class ReturnValueHandlerComposite implements ReturnValueHandler {

	private final List<ReturnValueHandler> handlers = new ArrayList<>();

	/**
	 * Adds a handler, asked after the ones added before.
	 * @param handler the handler, not null
	 * @return this composite
	 */
	public ReturnValueHandlerComposite addHandler(ReturnValueHandler handler) {
		this.handlers.add(handler);
		return this;
	}

	/**
	 * Adds handlers, in list order, asked after the ones added before.
	 * @param handlers the handlers, none of them null
	 * @return this composite
	 */
	public ReturnValueHandlerComposite addHandlers(List<ReturnValueHandler> handlers) {
		this.handlers.addAll(handlers);
		return this;
	}

	/**
	 * Returns the handlers in the order they are asked.
	 * @return an unmodifiable copy of the handlers
	 */
	public List<ReturnValueHandler> getHandlers() {
		return List.copyOf(handlers);
	}

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		return findHandler(returnType) != null;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Delegates to the first handler that supports the return type.
	 * @param returnValue {@inheritDoc}
	 * @param returnType {@inheritDoc}
	 * @param context {@inheritDoc}
	 * @return {@inheritDoc}
	 * @throws ReturnValueHandlingException if no handler supports the return type, or the handler
	 * throws it
	 */
	@Override
	public @Nullable Object handleReturnValue(@Nullable Object returnValue, AcpMethodParameter returnType,
			AcpInvocationContext context) {
		ReturnValueHandler handler = findHandler(returnType);
		if (handler == null) {
			throw new ReturnValueHandlingException(
					"No handler for return type: " + returnType.getParameterType().getName());
		}
		return handler.handleReturnValue(returnValue, returnType, context);
	}

	private @Nullable ReturnValueHandler findHandler(AcpMethodParameter returnType) {
		for (ReturnValueHandler handler : handlers) {
			if (handler.supportsReturnType(returnType)) {
				return handler;
			}
		}
		return null;
	}

}
