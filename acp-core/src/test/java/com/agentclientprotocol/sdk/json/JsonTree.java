/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * A read-only view of parsed JSON for assertions, built with whichever
 * {@link AcpJsonMapper} is under test, so a test that inspects the wire shape does not
 * need a particular JSON library. The method names follow Jackson's {@code JsonNode} so
 * assertions read the same.
 */
public final class JsonTree {

	private static final TypeRef<Object> ANY = new TypeRef<>() {
	};

	private final Object value;

	private JsonTree(Object value) {
		this.value = value;
	}

	/**
	 * Parses {@code json} with {@code mapper}.
	 * @param mapper the mapper under test
	 * @param json the JSON text
	 * @return the root of the tree
	 * @throws IOException if the text is not JSON
	 */
	public static JsonTree parse(AcpJsonMapper mapper, String json) throws IOException {
		return new JsonTree(mapper.readValue(json, ANY));
	}

	/**
	 * The member called {@code name}, or {@code null} if this is not an object or has no
	 * such member (a member whose value is JSON {@code null} is present:
	 * {@link #isNull()}).
	 * @param name the member name
	 * @return the member, or {@code null}
	 */
	public JsonTree get(String name) {
		if (value instanceof Map<?, ?> map && map.containsKey(name)) {
			return new JsonTree(map.get(name));
		}
		return null;
	}

	/**
	 * The element at {@code index}, or {@code null} if this is not an array or is too
	 * short.
	 * @param index the element index
	 * @return the element, or {@code null}
	 */
	public JsonTree get(int index) {
		if (value instanceof List<?> list && index >= 0 && index < list.size()) {
			return new JsonTree(list.get(index));
		}
		return null;
	}

	/**
	 * Whether this object has a member called {@code name}.
	 * @param name the member name
	 * @return true if present, even with a JSON {@code null} value
	 */
	public boolean has(String name) {
		return value instanceof Map<?, ?> map && map.containsKey(name);
	}

	/** @return the value as text: a string as is, any other scalar via toString */
	public String asText() {
		return value == null ? "null" : String.valueOf(value);
	}

	/** @return the value as an int (numbers only) */
	public int asInt() {
		return ((Number) value).intValue();
	}

	/** @return true if the value is JSON {@code null} */
	public boolean isNull() {
		return value == null;
	}

	/** @return true if the value is a JSON array */
	public boolean isArray() {
		return value instanceof List<?>;
	}

	/** @return the number of members or elements; 0 for a scalar */
	public int size() {
		if (value instanceof List<?> list) {
			return list.size();
		}
		if (value instanceof Map<?, ?> map) {
			return map.size();
		}
		return 0;
	}

}
