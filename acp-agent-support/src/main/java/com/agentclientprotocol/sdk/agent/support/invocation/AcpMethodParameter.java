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
 * Describes one parameter of a handler method, or its return type: its type, generic type,
 * annotations and position. The annotation runtime creates one for each parameter and for the
 * return type when it finds a handler method. Argument resolvers and return value handlers receive
 * it, decide from it whether they handle that parameter or return type ({@code supportsParameter},
 * {@code supportsReturnType}), and then supply the value or the response.
 *
 * <p>Two descriptions are equal when they describe the same position (a parameter index, or the
 * return type) of the same method; the composite argument resolver keeps its choice of resolver by
 * this key. The values are read once, at construction.
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
	 * Describes parameter {@code index} of {@code method}.
	 * @param method the handler method
	 * @param index the parameter's position, from 0, which must be one of the method's parameters;
	 * -1 describes the return type, as {@link #forReturnType} does
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
	 * Describes the return type of {@code method}: its {@link #getParameterType()} is the return
	 * type ({@code void.class} for {@code void}), its annotations are empty and its name is null.
	 * @param method the handler method
	 * @return the description of the return type
	 */
	public static AcpMethodParameter forReturnType(Method method) {
		return new AcpMethodParameter(method, -1);
	}

	/**
	 * Returns the handler method this parameter belongs to.
	 * @return the method
	 */
	public Method getMethod() {
		return method;
	}

	/**
	 * Returns the parameter's position.
	 * @return the position, from 0, or -1 for the return type
	 */
	public int getIndex() {
		return index;
	}

	/**
	 * Returns the parameter's name as compiled: the source name if the class was compiled with
	 * {@code javac -parameters}, otherwise a generated name such as {@code arg0}. Decide on the
	 * type or an annotation, not on the name.
	 * @return the name, or null for the return type
	 */
	public @Nullable String getName() {
		return parameter != null ? parameter.getName() : null;
	}

	/**
	 * Returns the parameter's class, or the method's return class for the return type. For a
	 * generic type this is the raw class, such as {@code Mono} for {@code Mono<PromptResponse>}.
	 * @return the class
	 */
	public Class<?> getParameterType() {
		return parameterType;
	}

	/**
	 * Returns the declared type with its type arguments, such as {@code Mono<PromptResponse>}; for
	 * a type without arguments, the same class as {@link #getParameterType()}.
	 * @return the declared type
	 */
	public Type getGenericType() {
		return genericType;
	}

	/**
	 * Returns the parameter's annotations, such as {@code @SessionId}. The array is shared: do not
	 * change it.
	 * @return the annotations; empty for the return type (the method's own annotations are on
	 * {@link #getMethod()})
	 */
	public Annotation[] getAnnotations() {
		return annotations;
	}

	/**
	 * Returns the parameter's annotation of the given type.
	 * @param annotationType the annotation type
	 * @param <A> the annotation type
	 * @return the annotation, or null if the parameter has none of that type (always null for the
	 * return type)
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
	 * Returns whether the parameter carries an annotation of the given type, as a resolver checks
	 * for {@code @SessionId}.
	 * @param annotationType the annotation type
	 * @return true if the parameter has it
	 */
	public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
		return getAnnotation(annotationType) != null;
	}

	/**
	 * Returns whether this describes the method's return type rather than a parameter.
	 * @return true for the return type
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
