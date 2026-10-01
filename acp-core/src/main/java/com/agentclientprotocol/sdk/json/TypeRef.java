/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Captures generic type information at runtime for parameterized JSON (de)serialization.
 * Usage: TypeRef&lt;List&lt;Foo&gt;&gt; ref = new TypeRef&lt;&gt;(){};
 *
 * @param <T> the type to capture
 * @author Mark Pollack
 */
public abstract class TypeRef<T> {

	private final Type type;

	/**
	 * Constructs a new TypeRef instance, capturing the generic type information of the
	 * subclass. This constructor should be called from an anonymous subclass to capture
	 * the actual type arguments. For example: <pre>
	 * TypeRef&lt;List&lt;Foo&gt;&gt; ref = new TypeRef&lt;&gt;(){};
	 * </pre>
	 * @throws IllegalStateException if TypeRef is not subclassed with actual type
	 * information
	 */
	protected TypeRef() {
		Type superClass = getClass().getGenericSuperclass();
		if (superClass instanceof Class) {
			throw new IllegalStateException("TypeRef constructed without actual type information");
		}
		this.type = ((ParameterizedType) superClass).getActualTypeArguments()[0];
	}

	private TypeRef(Type type) {
		this.type = type;
	}

	/**
	 * A type reference for a type known only at runtime, such as the reflected generic
	 * type of a handler method's parameter.
	 * @param type the type
	 * @return a type reference to it
	 * @throws IllegalArgumentException if the type is null
	 */
	public static TypeRef<?> of(Type type) {
		if (type == null) {
			throw new IllegalArgumentException("Type must not be null");
		}
		return new RuntimeTypeRef(type);
	}

	/** The type reference {@link #of(Type)} returns. */
	private static final class RuntimeTypeRef extends TypeRef<Object> {

		RuntimeTypeRef(Type type) {
			super(type);
		}

	}

	/**
	 * Returns the captured type information.
	 * @return the Type representing the actual type argument captured by this TypeRef
	 * instance
	 */
	public Type getType() {
		return type;
	}

}
