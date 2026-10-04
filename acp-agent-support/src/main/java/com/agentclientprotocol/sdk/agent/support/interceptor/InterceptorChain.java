/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.interceptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a list of {@link AcpInterceptor}s around one handler method call, in their order, and
 * records which ones started so that each gets its {@code afterCompletion} exactly once. The
 * annotation runtime creates one for every call; applications do not need it. Its methods carry out
 * the order that {@link AcpInterceptor} describes, which is where an interceptor's author reads the
 * contract.
 *
 * <p>A chain records how far {@code preInvoke} got, so it serves one call only: create a new one
 * for each call. It is not thread-safe.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class InterceptorChain {

	private static final Logger log = LoggerFactory.getLogger(InterceptorChain.class);

	private final List<AcpInterceptor> interceptors;

	private int interceptorIndex = -1;

	/**
	 * Creates a chain over a copy of {@code interceptors}, sorted by
	 * {@link AcpInterceptor#getOrder()}, lowest first; interceptors with the same order keep their
	 * order in the list.
	 * @param interceptors the interceptors, in the order they were added
	 */
	public InterceptorChain(List<AcpInterceptor> interceptors) {
		this.interceptors = new ArrayList<>(interceptors);
		this.interceptors.sort(Comparator.comparingInt(AcpInterceptor::getOrder));
	}

	/**
	 * Calls {@code preInvoke} on each interceptor in order, stopping at the first that returns
	 * {@code false}. An exception from {@code preInvoke} reaches the caller. However the call then
	 * ends, the caller must call {@link #triggerAfterCompletion} once, which covers the
	 * interceptors whose {@code preInvoke} returned {@code true}.
	 * @param context the call's context
	 * @return {@code true} if every interceptor returned {@code true}, {@code false} if one stopped
	 * the call
	 */
	public boolean applyPreInvoke(AcpInvocationContext context) {
		for (int i = 0; i < interceptors.size(); i++) {
			if (!interceptors.get(i).preInvoke(context)) {
				return false;
			}
			this.interceptorIndex = i;
		}
		return true;
	}

	/**
	 * Calls {@code postInvoke} on each interceptor in reverse order, passing each the value the
	 * previous one returned. An exception from an interceptor is logged, and the value goes on
	 * unchanged.
	 * @param context the call's context
	 * @param result what the handler method returned
	 * @return the value the last interceptor returned
	 */
	public @Nullable Object applyPostInvoke(AcpInvocationContext context, @Nullable Object result) {
		for (int i = interceptors.size() - 1; i >= 0; i--) {
			try {
				result = interceptors.get(i).postInvoke(context, result);
			}
			catch (Exception e) {
				log.warn("Interceptor postInvoke threw exception", e);
				// Continue with other interceptors
			}
		}
		return result;
	}

	/**
	 * Calls {@code onError}, in reverse order, on each interceptor whose {@code preInvoke}
	 * returned {@code true}, the same interceptors {@link #triggerAfterCompletion} reaches, until
	 * one returns a replacement. What an interceptor throws ends the walk and reaches the caller,
	 * which answers with it (see {@link AcpInterceptor#onError}).
	 * @param context the call's context
	 * @param ex what the call threw
	 * @return the first replacement, or null if no interceptor gave one
	 */
	public @Nullable Object applyOnError(AcpInvocationContext context, Throwable ex) {
		for (int i = this.interceptorIndex; i >= 0; i--) {
			Object replacement = interceptors.get(i).onError(context, ex);
			if (replacement != null) {
				return replacement;
			}
		}
		return null;
	}

	/**
	 * Calls {@code afterCompletion}, in reverse order, on each interceptor whose {@code preInvoke}
	 * returned {@code true}, and resets the chain, so a second call does nothing. Never throws:
	 * what an interceptor throws is logged.
	 * @param context the call's context
	 * @param ex the exception the call failed with, or null; it is passed to each interceptor
	 */
	public void triggerAfterCompletion(AcpInvocationContext context, @Nullable Throwable ex) {
		int started = this.interceptorIndex;
		this.interceptorIndex = -1;
		for (int i = started; i >= 0; i--) {
			try {
				interceptors.get(i).afterCompletion(context, ex);
			}
			catch (Throwable t) {
				log.warn("Interceptor afterCompletion threw exception", t);
				// Don't propagate - continue cleanup
			}
		}
	}

}
