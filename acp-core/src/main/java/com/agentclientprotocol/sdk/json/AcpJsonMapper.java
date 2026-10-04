/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.io.IOException;
import java.util.ServiceLoader;

import org.jspecify.annotations.Nullable;

/**
 * Reads and writes the JSON of ACP messages for the SDK, so that acp-core itself depends on no JSON
 * library. The transports use it to write each message they send, to read each message that
 * arrives, and to convert a message's params or result into the
 * {@link com.agentclientprotocol.sdk.spec.AcpSchema} record the method expects. Most applications
 * never call it: a transport created without a mapper gets one from {@link #createDefault()}.
 *
 * <p>The implementation comes from a JSON module on the classpath: {@code acp-json-jackson2}
 * ({@code JacksonAcpJsonMapper}, Jackson 2) or {@code acp-json-jackson3}
 * ({@code Jackson3AcpJsonMapper}, Jackson 3). {@code acp-agent-support},
 * {@code acp-streamable-http-jetty} and {@code acp-test} bring in {@code acp-json-jackson2}; with
 * acp-core alone, add one of the two. Each module registers an {@link AcpJsonMapperSupplier}, and
 * {@link #createDefault()} picks one. To configure Jackson yourself, create the module's mapper
 * around your own Jackson mapper and pass it to the transport's constructor.
 *
 * <p>Implementations: the schema records carry Jackson annotations ({@code @JsonProperty},
 * {@code @JsonInclude}, {@code @JsonTypeInfo}, {@code @JsonSubTypes}, {@code @JsonCreator},
 * {@code @JsonValue}, {@code @JsonAnyGetter} and {@code @JsonAnySetter}), so a mapper must honour
 * them to read and write ACP. A transport calls its mapper from several threads at once, so it must
 * be safe for concurrent use. Reads and writes report failures as {@link IOException}; the SDK
 * answers text that is not JSON with a parse error ({@code -32700}). A conversion whose value does
 * not fit the target type must fail with {@link IllegalArgumentException}, which the SDK answers as
 * invalid params ({@code -32602}). Register an implementation through an
 * {@link AcpJsonMapperSupplier}.
 *
 * @author Mark Pollack
 */
public interface AcpJsonMapper {

	/**
	 * Returns a new mapper from the {@link AcpJsonMapperSupplier}s that {@link ServiceLoader} finds
	 * with the current thread's context class loader. The choice does not depend on classpath
	 * order:
	 * <ol>
	 * <li>if the system property {@code acp.json.mapper.supplier} is set (and not blank), the
	 * supplier whose class has exactly that fully qualified name is used;</li>
	 * <li>otherwise the supplier with the highest {@link AcpJsonMapperSupplier#priority()} is used,
	 * ties broken by class name. With both SDK modules present, {@code acp-json-jackson3} (priority
	 * {@code -100}) wins over {@code acp-json-jackson2} ({@code -200}); an application supplier
	 * that keeps the default priority ({@code 0}) wins over both.</li>
	 * </ol>
	 *
	 * <p>Each call looks the suppliers up again and asks the chosen one for a mapper; the SDK's
	 * suppliers build a new one every time, so keep the result rather than calling this per
	 * message.
	 * @return the chosen supplier's mapper
	 * @throws java.util.ServiceConfigurationError if no supplier is found, or none has the class
	 * named by the system property
	 * @throws IllegalStateException if the chosen SDK module finds a Jackson older than it supports
	 * (2.18.1 for Jackson 2, 3.0.0 for Jackson 3)
	 */
	static AcpJsonMapper createDefault() {
		return AcpJsonMapperSelector.select().get();
	}

	/**
	 * Reads a JSON text as the given type.
	 * @param content the JSON text
	 * @param type the type to read
	 * @param <T> the type to read
	 * @return the value, or {@code null} when the text is the JSON literal {@code null}
	 * @throws IOException if the text is not JSON or does not fit the type
	 */
	<T> @Nullable T readValue(String content, Class<T> type) throws IOException;

	/**
	 * Reads JSON bytes as the given type, like {@link #readValue(String, Class)}.
	 * @param content the JSON bytes
	 * @param type the type to read
	 * @param <T> the type to read
	 * @return the value, or {@code null} when the bytes are the JSON literal {@code null}
	 * @throws IOException if the bytes are not JSON or do not fit the type
	 */
	<T> @Nullable T readValue(byte[] content, Class<T> type) throws IOException;

	/**
	 * Reads a JSON text as a generic type, such as {@code List<AcpSchema.ContentBlock>}, that a
	 * {@link TypeRef} captures.
	 * @param content the JSON text
	 * @param type the type to read
	 * @param <T> the type to read
	 * @return the value, or {@code null} when the text is the JSON literal {@code null}
	 * @throws IOException if the text is not JSON or does not fit the type
	 */
	<T> @Nullable T readValue(String content, TypeRef<T> type) throws IOException;

	/**
	 * Reads JSON bytes as a generic type that a {@link TypeRef} captures, like
	 * {@link #readValue(String, TypeRef)}.
	 * @param content the JSON bytes
	 * @param type the type to read
	 * @param <T> the type to read
	 * @return the value, or {@code null} when the bytes are the JSON literal {@code null}
	 * @throws IOException if the bytes are not JSON or do not fit the type
	 */
	<T> @Nullable T readValue(byte[] content, TypeRef<T> type) throws IOException;

	/**
	 * Converts a value to the given type as if it were written as JSON and read back. The
	 * transports use it to turn the params or result of a message, read as maps and lists, into the
	 * record the method expects.
	 * @param fromValue the value, usually maps and lists read from JSON
	 * @param type the type to convert to
	 * @param <T> the type to convert to
	 * @return the converted value
	 * @throws IllegalArgumentException if the value does not fit the type
	 */
	<T> T convertValue(Object fromValue, Class<T> type);

	/**
	 * Converts a value to a generic type that a {@link TypeRef} captures, like
	 * {@link #convertValue(Object, Class)}.
	 * @param fromValue the value, usually maps and lists read from JSON
	 * @param type the type to convert to
	 * @param <T> the type to convert to
	 * @return the converted value
	 * @throws IllegalArgumentException if the value does not fit the type
	 */
	<T> T convertValue(Object fromValue, TypeRef<T> type);

	/**
	 * Writes a value, such as a JSON-RPC message, as JSON text.
	 * @param value the value to write
	 * @return the JSON text
	 * @throws IOException if the value cannot be written as JSON
	 */
	String writeValueAsString(Object value) throws IOException;

	/**
	 * Writes a value as JSON bytes in UTF-8.
	 * @param value the value to write
	 * @return the JSON bytes
	 * @throws IOException if the value cannot be written as JSON
	 */
	byte[] writeValueAsBytes(Object value) throws IOException;

}
