/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import org.jspecify.annotations.Nullable;

/**
 * One handler method that {@link AcpAgentSupport} found: the bean it is called on, the
 * {@link Method}, and the ACP method it answers, such as {@code session/prompt} or an extension
 * method name. The builder creates one for each handler method when a bean is given to it, and the
 * runtime calls {@link #invoke} for each request. Applications do not need this class, and no
 * extension point receives it: an interceptor or argument resolver learns what is called from
 * {@link com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext#getAcpMethod()
 * AcpInvocationContext.getAcpMethod()} and {@link AcpMethodParameter#getMethod()}.
 *
 * <p>The constructor makes the method accessible ({@link Method#setAccessible(boolean)}), so
 * handler methods of any visibility can be called; it fails if the method's module does not open
 * its package to the SDK. Instances do not change after construction.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public final class AcpHandlerMethod {

	private final Supplier<Object> beanSupplier;

	private final Method method;

	private final String acpMethod;

	private final AcpMethodParameter[] parameters;

	private final AcpMethodParameter returnType;

	/**
	 * Creates a handler method that calls {@code method} on {@code bean}.
	 * @param bean the object the method is called on
	 * @param method the handler method, made accessible here
	 * @param acpMethod the ACP method it answers, such as {@code "initialize"} or
	 * {@code "session/prompt"}
	 */
	public AcpHandlerMethod(Object bean, Method method, String acpMethod) {
		this(() -> bean, method, acpMethod);
	}

	/**
	 * Creates a handler method that calls {@code method} on the object {@code beanSupplier} gives.
	 * The supplier is asked on every call, so it may return a different object each time.
	 * @param beanSupplier gives the object the method is called on
	 * @param method the handler method, made accessible here
	 * @param acpMethod the ACP method it answers
	 */
	public AcpHandlerMethod(Supplier<Object> beanSupplier, Method method, String acpMethod) {
		this.beanSupplier = beanSupplier;
		this.method = method;
		this.acpMethod = acpMethod;
		this.method.setAccessible(true);

		// Pre-compute parameter metadata
		int paramCount = method.getParameterCount();
		this.parameters = new AcpMethodParameter[paramCount];
		for (int i = 0; i < paramCount; i++) {
			this.parameters[i] = new AcpMethodParameter(method, i);
		}

		this.returnType = AcpMethodParameter.forReturnType(method);
	}

	/**
	 * Returns the object the method is called on, asking the supplier if one was given.
	 * @return the bean
	 */
	public Object getBean() {
		return beanSupplier.get();
	}

	/**
	 * Returns the handler method.
	 * @return the method
	 */
	public Method getMethod() {
		return method;
	}

	/**
	 * Returns the ACP method this handler answers, such as {@code "initialize"},
	 * {@code "session/prompt"} or an extension method name starting with {@code _}.
	 * @return the ACP method name
	 */
	public String getAcpMethod() {
		return acpMethod;
	}

	/**
	 * Returns the descriptions of the method's parameters, in order, made once at construction. The
	 * array is shared: do not change it.
	 * @return the parameter descriptions
	 */
	public AcpMethodParameter[] getParameters() {
		return parameters;
	}

	/**
	 * Returns the description of the method's return type.
	 * @return the return type description, for which {@link AcpMethodParameter#isReturnType()} is
	 * true
	 */
	public AcpMethodParameter getReturnType() {
		return returnType;
	}

	/**
	 * Calls the handler method on the bean with {@code args}, which must match its parameters in
	 * number and type.
	 * @param args the arguments, one for each parameter
	 * @return what the method returned, or null for a {@code void} method
	 * @throws Exception if the handler method throws: its exception, as it threw it (not wrapped in
	 * an {@code InvocationTargetException}); an {@link Error} it throws is rethrown as is
	 * @throws IllegalArgumentException if {@code args} do not match the method's parameters
	 */
	public @Nullable Object invoke(@Nullable Object[] args) throws Exception {
		try {
			return method.invoke(getBean(), args);
		}
		catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception ex) {
				throw ex;
			}
			if (cause instanceof Error err) {
				throw err;
			}
			throw new RuntimeException(cause);
		}
	}

	@Override
	public String toString() {
		return method.getDeclaringClass().getSimpleName() + "." + method.getName()
				+ " -> " + acpMethod;
	}

}
