/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.invocation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Metadata about a method parameter. Implements equals/hashCode
 * for use as cache key in argument resolver lookup.
 *
 * <p>Computes the parameter metadata once, at construction.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public final class AcpMethodParameter {

	private final Method method;

	private final int index;

	/** The reflected parameter; null for a return type. */
	private final @Nullable Parameter parameter;

	private final Annotation[] annotations;

	private final Class<?> parameterType;

	private final Type genericType;

	/**
	 * Create a new AcpMethodParameter for a method parameter.
	 * @param method the method
	 * @param index the parameter index (0-based)
	 */
	public AcpMethodParameter(Method method, int index) {
		this.method = method;
		this.index = index;
		Parameter reflected = (index >= 0) ? method.getParameters()[index] : null;
		this.parameter = reflected;
		this.parameterType = (reflected != null) ? reflected.getType() : method.getReturnType();
		this.genericType = (reflected != null) ? reflected.getParameterizedType() : method.getGenericReturnType();
		this.annotations = (reflected != null) ? reflected.getAnnotations() : new Annotation[0];
	}

	/**
	 * Create an AcpMethodParameter representing the return type.
	 * @param method the method
	 * @return a parameter representing the return type
	 */
	public static AcpMethodParameter forReturnType(Method method) {
		return new AcpMethodParameter(method, -1);
	}

	/**
	 * Get the method this parameter belongs to.
	 * @return the method
	 */
	public Method getMethod() {
		return method;
	}

	/**
	 * Get the parameter index (0-based), or -1 for return type.
	 * @return the parameter index
	 */
	public int getIndex() {
		return index;
	}

	/**
	 * Get the parameter name (requires -parameters compiler flag).
	 * @return the parameter name, or null for return type
	 */
	public @Nullable String getName() {
		return parameter != null ? parameter.getName() : null;
	}

	/**
	 * Get the parameter type, or return type if index is -1.
	 * @return the type
	 */
	public Class<?> getParameterType() {
		return parameterType;
	}

	/**
	 * Get the generic type, preserving type parameters.
	 * @return the generic type
	 */
	public Type getGenericType() {
		return genericType;
	}

	/**
	 * Get all annotations on this parameter.
	 * @return the annotations, or empty array for return type
	 */
	public Annotation[] getAnnotations() {
		return annotations;
	}

	/**
	 * Get a specific annotation if present.
	 * @param annotationType the annotation type
	 * @param <A> the annotation type
	 * @return the annotation, or null if not present
	 */
	public <A extends Annotation> @Nullable A getAnnotation(Class<A> annotationType) {
		for (Annotation ann : getAnnotations()) {
			if (annotationType.isInstance(ann)) {
				return annotationType.cast(ann);
			}
		}
		return null;
	}

	/**
	 * Check if this parameter has the specified annotation.
	 * @param annotationType the annotation type
	 * @return true if the annotation is present
	 */
	public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
		return getAnnotation(annotationType) != null;
	}

	/**
	 * Check if this represents a return type (index == -1).
	 * @return true if this is a return type
	 */
	public boolean isReturnType() {
		return index == -1;
	}

	// Critical: equals/hashCode for caching
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof AcpMethodParameter that)) {
			return false;
		}
		return index == that.index && method.equals(that.method);
	}

	@Override
	public int hashCode() {
		return Objects.hash(method, index);
	}

	@Override
	public String toString() {
		if (isReturnType()) {
			return "return type of " + method.getName();
		}
		return "parameter " + index + " (" + getName() + ") of " + method.getName();
	}

}
