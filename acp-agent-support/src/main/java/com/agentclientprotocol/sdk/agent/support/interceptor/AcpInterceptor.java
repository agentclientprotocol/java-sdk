/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.interceptor;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import org.jspecify.annotations.Nullable;

/**
 * Runs code around every call of an annotated agent's handler methods, for concerns that cut across
 * them: logging, metrics, tracing, an access check, or a fallback answer when a method fails.
 * Register one with
 * {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport.Builder#interceptor
 * AcpAgentSupport.Builder.interceptor(..)}; the SDK's Spring Boot, Quarkus and Micronaut modules
 * also register the application's interceptor beans. Every method has a default that does nothing,
 * so implement only the ones you need.
 *
 * <p>For each call, interceptors run in {@link #getOrder()} order, lowest first, and those with the
 * same order in the order they were added:
 * <ol>
 *   <li>{@link #preInvoke} on each, in order. One that returns {@code false} stops the call: the
 *   handler method is not called, a request is answered with an internal error ({@code -32603}),
 *   and a notification is dropped.</li>
 *   <li>The argument resolvers, then the handler method.</li>
 *   <li>{@link #postInvoke} on each, in reverse order, with the value the method returned, before a
 *   return value handler turns it into the response.</li>
 *   <li>If {@code preInvoke}, an argument resolver, the method or the return value handler threw:
 *   {@link #onError}, in reverse order, on each interceptor whose {@code preInvoke} returned
 *   {@code true}, until one returns a replacement result or throws.</li>
 *   <li>{@link #afterCompletion}, in reverse order, on each interceptor whose {@code preInvoke}
 *   returned {@code true}, once, however the call ended.</li>
 * </ol>
 *
 * <p>Interceptors see every handler method call, extension methods and the {@code session/cancel}
 * notification included, and the {@code initialize} answer derived from the annotations. They do
 * not see the default {@code session/new} answer given when the agent has no {@code @NewSession}
 * method. The {@link AcpInvocationContext} names the ACP method and carries the request, the
 * session id, and attributes that pass state from one step of a call to a later one.
 *
 * <p>Example: log how long each call takes.
 * <pre>{@code
 * class TimingInterceptor implements AcpInterceptor {
 *
 *     private static final System.Logger log = System.getLogger("acp.timing");
 *
 *     @Override
 *     public boolean preInvoke(AcpInvocationContext context) {
 *         context.setAttribute("start", System.nanoTime());
 *         return true;
 *     }
 *
 *     @Override
 *     public void afterCompletion(AcpInvocationContext context) {
 *         long start = context.getAttribute("start", Long.class).orElseThrow();
 *         long millis = (System.nanoTime() - start) / 1_000_000;
 *         log.log(System.Logger.Level.INFO, "{0} took {1} ms", context.getAcpMethod(), millis);
 *     }
 * }
 * }</pre>
 *
 * <p>Implementations must be thread-safe: one instance serves every call, from every session and
 * connection, concurrently. The steps of one call run on that call's handler thread, so they may
 * block; keep per-call state in the context's attributes, not in fields. An exception thrown from
 * {@code postInvoke} or {@code afterCompletion} is logged and ignored; one thrown from
 * {@code preInvoke} fails the call like an exception from the handler method, and one thrown from
 * {@code onError} replaces the failure (see {@link #onError}).
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see InterceptorChain
 */
public interface AcpInterceptor {

	/**
	 * The order {@link #getOrder()} returns by default, {@value}.
	 */
	int DEFAULT_ORDER = 0;

	/**
	 * Returns this interceptor's place among the others: lower values run {@code preInvoke} earlier
	 * and {@code postInvoke}, {@code onError} and {@code afterCompletion} later. Interceptors with
	 * the same order run in the order they were added. It is read for every call, so return a
	 * constant.
	 * @implSpec Returns {@link #DEFAULT_ORDER}.
	 * @return the order, lowest first
	 */
	default int getOrder() {
		return DEFAULT_ORDER;
	}

	/**
	 * Called before the handler method's arguments are resolved. Return {@code false} to stop the
	 * call: the handler method is not called, a request is answered with an internal error
	 * ({@code -32603}) that says the call was vetoed, and a notification is dropped. To reject a
	 * request with a specific error instead, throw an {@code AcpProtocolException} with that code:
	 * the call fails with it unless an {@link #onError} returns a replacement.
	 * @implSpec Returns {@code true}.
	 * @param context the call's context
	 * @return {@code true} to go on, {@code false} to stop the call
	 */
	default boolean preInvoke(AcpInvocationContext context) {
		return true;
	}

	/**
	 * Called after the handler method returned, with the value it returned, before a return value
	 * handler turns it into the response: the response itself, or what the method declared, such as
	 * a {@code Mono}, a {@code String} from a {@code @Prompt} method, or null for a {@code void}
	 * method. Return that value or a replacement for it; the next interceptor, then the return
	 * value handler, receives what you return. Not called when the method threw or a
	 * {@code preInvoke} stopped the call. An exception thrown here is logged and ignored, and the
	 * next interceptor gets the value unchanged.
	 * @implSpec Returns {@code result} unchanged.
	 * @param context the call's context
	 * @param result what the handler method returned, or what the previous interceptor returned
	 * @return the value to use from now on
	 */
	default @Nullable Object postInvoke(AcpInvocationContext context, @Nullable Object result) {
		return result;
	}

	/**
	 * Called when {@code preInvoke}, an argument resolver, the handler method or the return value
	 * handler threw. Return null to leave the failure as it is: if every interceptor does, a
	 * request's client receives the error the exception maps to ({@code AcpProtocolException} keeps
	 * its code, an {@code AcpError} from a call to the client passes that error on, anything else
	 * is an internal error, {@code -32603}). Return a replacement to answer
	 * with it instead; the remaining interceptors' {@code onError} are then not called. A
	 * replacement is used as the result as it is, without a return value handler, so for a request
	 * it must be the method's response, such as a {@code PromptResponse}; another type is answered
	 * with an internal error. For a notification, a replacement only ends the failure.
	 *
	 * <p>To answer with a specific JSON-RPC error instead, throw an {@code AcpProtocolException}
	 * with that code: it becomes the failure the client receives, with its code, message and data,
	 * and the remaining interceptors' {@code onError} are not called. Any other exception thrown
	 * here is taken as a fault in the interceptor and answered as an internal error
	 * ({@code -32603}). Either way the original failure is attached to the thrown exception as
	 * suppressed, for the log.
	 *
	 * <p>It is called only on the interceptors whose {@code preInvoke} returned {@code true}, the
	 * same ones that get {@link #afterCompletion}: not on one whose {@code preInvoke} threw or
	 * returned {@code false}, nor on those after it, which never saw the call.
	 * @implSpec Returns null.
	 * @param context the call's context
	 * @param ex what was thrown, as the step threw it
	 * @return a replacement result, or null to keep the failure
	 */
	default @Nullable Object onError(AcpInvocationContext context, Throwable ex) {
		return null;
	}

	/**
	 * Called once at the end of every call in which this interceptor's {@code preInvoke} returned
	 * {@code true}, however the call ended: answered, failed, or stopped by a later interceptor.
	 * Use it to release what {@code preInvoke} took, such as a timer or a logging context. It
	 * receives neither the result nor the exception; save them as context attributes in
	 * {@link #postInvoke} or {@link #onError} if you need them. An exception thrown here is logged
	 * and ignored.
	 * @implSpec Does nothing.
	 * @param context the call's context
	 */
	default void afterCompletion(AcpInvocationContext context) {
		// Default: no-op
	}

}
