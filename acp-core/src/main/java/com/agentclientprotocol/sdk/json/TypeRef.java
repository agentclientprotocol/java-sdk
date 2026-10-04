/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * A Java type, generics included, for the SDK to read JSON as. Create one as an anonymous subclass,
 * {@code new TypeRef<List<String>>() {}}, and pass it where the SDK reads a value whose type only
 * you know: an extension method's result ({@code sendExtRequest(method, params, resultType)}), an
 * extension handler's params ({@code extRequestHandler(method, paramsType, handler)}), or
 * {@link AcpJsonMapper#readValue(String, TypeRef)} and
 * {@link AcpJsonMapper#convertValue(Object, TypeRef)}. A plain {@code Class} cannot carry a type
 * argument such as {@code String} in {@code List<String>}; the anonymous subclass keeps it.
 *
 * <p>For a type known only at runtime, such as a reflected method parameter's generic type, use
 * {@link #of(Type)}. Instances are immutable and safe to share between threads.
 *
 * <p>Example, reading an extension method's result as a record:
 * <pre>{@code
 * record Pong(String text) {}
 *
 * TypeRef<Pong> pongType = new TypeRef<>() {};
 * Pong pong = client.sendExtRequest("_example.com/ping", Map.of("text", "hi"), pongType);
 * }</pre>
 *
 * @param <T> the type to read
 * @author Mark Pollack
 */
public abstract class TypeRef<T> {

	private final Type type;

	/**
	 * Captures the type argument of the anonymous subclass being created, such as
	 * {@code List<String>} in {@code new TypeRef<List<String>>() {}}.
	 * @throws IllegalStateException if the subclass gives no type argument, as in a raw
	 * {@code new TypeRef() {}}
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
	 * Returns a type reference for a type known only at runtime, such as the reflected generic type
	 * of a handler method's parameter.
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
	 * Returns the captured type: a {@code Class} for a plain type, or a {@code ParameterizedType}
	 * for a generic one.
	 * @return the type
	 */
	public Type getType() {
		return type;
	}

}
