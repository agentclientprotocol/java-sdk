/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Collection;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * Finds a required field missing from inbound params or from a response's result. A schema
 * record's component without {@code @Nullable} is one the ACP schema lists as
 * {@code required}; Jackson reads an absent one as null, which a handler or caller would only
 * find when it fails on it. Only the schema's records
 * ({@link AcpSchema}) are checked: an application's own record, such as an extension method's
 * params, has no schema behind it, and its nullness is its own business.
 */
final class RequiredFields {

	private RequiredFields() {
	}

	/**
	 * The JSON path of the first required field that is null, looking into nested records
	 * and collections of records; null when none is.
	 */
	static @Nullable String firstMissing(@Nullable Object value) {
		return firstMissing(value, "");
	}

	private static @Nullable String firstMissing(@Nullable Object value, String path) {
		if (value instanceof Collection<?> collection) {
			return firstMissingInElements(collection, path);
		}
		if (value == null || !isSchemaRecord(value.getClass())) {
			return null;
		}
		for (RecordComponent component : value.getClass().getRecordComponents()) {
			String missing = firstMissingInComponent(component, value, path);
			if (missing != null) {
				return missing;
			}
		}
		return null;
	}

	private static boolean isSchemaRecord(Class<?> type) {
		return type.isRecord() && type.getName().startsWith(AcpSchema.class.getName() + "$");
	}

	private static @Nullable String firstMissingInElements(Collection<?> collection, String path) {
		int index = 0;
		for (Object element : collection) {
			String missing = firstMissing(element, path + "[" + index++ + "]");
			if (missing != null) {
				return missing;
			}
		}
		return null;
	}

	private static @Nullable String firstMissingInComponent(RecordComponent component, Object record, String path) {
		String name = path.isEmpty() ? jsonName(component) : path + "." + jsonName(component);
		Object field = read(component, record);
		if (field != null) {
			return firstMissing(field, name);
		}
		boolean optional = component.getAnnotatedType().isAnnotationPresent(Nullable.class)
				|| component.getType().isPrimitive();
		return optional ? null : name;
	}

	private static String jsonName(RecordComponent component) {
		JsonProperty property = component.getAnnotation(JsonProperty.class);
		return (property != null && !property.value().isEmpty()) ? property.value() : component.getName();
	}

	private static @Nullable Object read(RecordComponent component, Object record) {
		try {
			return component.getAccessor().invoke(record);
		}
		catch (IllegalAccessException | InvocationTargetException e) {
			// A record nested in a type this package cannot reach: no check rather than a failure.
			return null;
		}
	}

}
