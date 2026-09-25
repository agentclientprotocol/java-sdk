/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.io.IOException;
import java.util.ServiceLoader;

/**
 * Abstraction for JSON serialization/deserialization to decouple the SDK from any
 * specific JSON library.
 *
 * <p>
 * {@code acp-core} contains no implementation. One comes from a JSON module on the
 * classpath: {@code acp-json-jackson2} (Jackson 2, which the transport and agent-support
 * modules bring in) or {@code acp-json-jackson3} (Jackson 3). Each registers an
 * {@link AcpJsonMapperSupplier} discovered via {@link ServiceLoader}.
 * </p>
 *
 * <p>
 * Alternative implementations (e.g., for Micronaut serialization) can be provided by
 * implementing {@link AcpJsonMapperSupplier} and registering it in
 * {@code META-INF/services/com.agentclientprotocol.sdk.json.AcpJsonMapperSupplier}.
 * </p>
 *
 * @author Mark Pollack
 */
public interface AcpJsonMapper {

	/**
	 * Creates a default AcpJsonMapper from the {@link AcpJsonMapperSupplier}s found via
	 * {@link ServiceLoader}. The choice is deterministic:
	 * <ol>
	 * <li>if the system property {@code acp.json.mapper.supplier} is set, the supplier
	 * with exactly that fully qualified class name is used (and it is an error if there is
	 * none);</li>
	 * <li>otherwise the supplier with the highest {@link AcpJsonMapperSupplier#priority()}
	 * is used, ties broken by class name. With both SDK modules present,
	 * {@code acp-json-jackson3} (priority -100) wins over {@code acp-json-jackson2}
	 * (-200); an application supplier that keeps the default priority (0) wins over
	 * both.</li>
	 * </ol>
	 * @return a new AcpJsonMapper instance
	 * @throws java.util.ServiceConfigurationError if no supplier is found, or none
	 * matches the system property
	 */
	static AcpJsonMapper createDefault() {
		return AcpJsonMapperSelector.select().get();
	}

	/**
	 * Deserialize JSON string into a target type.
	 * @param content JSON as String
	 * @param type target class
	 * @return deserialized instance
	 * @param <T> generic type
	 * @throws IOException on parse errors
	 */
	<T> T readValue(String content, Class<T> type) throws IOException;

	/**
	 * Deserialize JSON bytes into a target type.
	 * @param content JSON as bytes
	 * @param type target class
	 * @return deserialized instance
	 * @param <T> generic type
	 * @throws IOException on parse errors
	 */
	<T> T readValue(byte[] content, Class<T> type) throws IOException;

	/**
	 * Deserialize JSON string into a parameterized target type.
	 * @param content JSON as String
	 * @param type parameterized type reference
	 * @return deserialized instance
	 * @param <T> generic type
	 * @throws IOException on parse errors
	 */
	<T> T readValue(String content, TypeRef<T> type) throws IOException;

	/**
	 * Deserialize JSON bytes into a parameterized target type.
	 * @param content JSON as bytes
	 * @param type parameterized type reference
	 * @return deserialized instance
	 * @param <T> generic type
	 * @throws IOException on parse errors
	 */
	<T> T readValue(byte[] content, TypeRef<T> type) throws IOException;

	/**
	 * Convert a value to a given type, useful for mapping nested JSON structures.
	 * @param fromValue source value
	 * @param type target class
	 * @return converted value
	 * @param <T> generic type
	 */
	<T> T convertValue(Object fromValue, Class<T> type);

	/**
	 * Convert a value to a given parameterized type.
	 * @param fromValue source value
	 * @param type target type reference
	 * @return converted value
	 * @param <T> generic type
	 */
	<T> T convertValue(Object fromValue, TypeRef<T> type);

	/**
	 * Serialize an object to JSON string.
	 * @param value object to serialize
	 * @return JSON as String
	 * @throws IOException on serialization errors
	 */
	String writeValueAsString(Object value) throws IOException;

	/**
	 * Serialize an object to JSON bytes.
	 * @param value object to serialize
	 * @return JSON as bytes
	 * @throws IOException on serialization errors
	 */
	byte[] writeValueAsBytes(Object value) throws IOException;

}
