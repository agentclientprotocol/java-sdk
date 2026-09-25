/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json.jackson3;

import java.io.IOException;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jackson 3 ({@code tools.jackson}) implementation of {@link AcpJsonMapper}, shipped in
 * {@code acp-json-jackson3}. Wraps a Jackson {@link JsonMapper} but keeps the SDK
 * decoupled from Jackson at the API level.
 *
 * <p>
 * The schema records carry Jackson 2 annotations ({@code com.fasterxml.jackson.annotation}),
 * which Jackson 3 reads unchanged, so both JSON modules serialize the same records. With
 * {@link #defaultJsonMapper()} the bytes on the wire are the same as with the Jackson 2
 * module.
 * </p>
 *
 * <p>
 * Jackson 3 reports errors with the unchecked {@link JacksonException}. To keep the
 * {@link AcpJsonMapper} contract, reads and writes rethrow it as an {@link IOException}
 * (the original is the cause) and conversions as an {@link IllegalArgumentException}.
 * </p>
 *
 * @author Mark Pollack
 */
public final class Jackson3AcpJsonMapper implements AcpJsonMapper {

	private static final Logger logger = LoggerFactory.getLogger(Jackson3AcpJsonMapper.class);

	private final JsonMapper jsonMapper;

	/**
	 * The {@link JsonMapper} the SDK uses by default. Like the Jackson 2 module's
	 * default it is <em>lenient</em> about unknown properties, because the ACP
	 * specification adds fields between releases and an agent newer than this SDK must
	 * keep working, and it logs each unknown property at DEBUG under this class's logger,
	 * so spec drift is observable without making the wire strict.
	 *
	 * <p>
	 * Jackson 3 changed several defaults from Jackson 2. Where the change would alter
	 * what the SDK writes or accepts, this mapper restores the Jackson 2 behaviour:
	 * </p>
	 * <ul>
	 * <li>{@link MapperFeature#SORT_PROPERTIES_ALPHABETICALLY} is disabled, so properties
	 * are written in record-component order, as with Jackson 2;</li>
	 * <li>{@link DeserializationFeature#FAIL_ON_NULL_FOR_PRIMITIVES} is disabled, so a
	 * JSON {@code null} for a primitive component reads as its default value;</li>
	 * <li>{@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS} is disabled, so content
	 * after the first JSON value is ignored rather than rejected;</li>
	 * <li>{@link EnumFeature#READ_ENUMS_USING_TO_STRING} and
	 * {@link EnumFeature#WRITE_ENUMS_USING_TO_STRING} are disabled, so enums use their
	 * {@code @JsonProperty} name or constant name, never {@code toString()}.</li>
	 * </ul>
	 * <p>
	 * {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} is disabled explicitly
	 * (it is already off by default in Jackson 3, unlike Jackson 2).
	 * </p>
	 *
	 * <p>
	 * Strictness is the mapper's decision, not the schema's: the schema records carry no
	 * {@code @JsonIgnoreProperties}, so a mapper with
	 * {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} enabled fails on the
	 * first unknown field. Jackson 3 mappers are immutable; to customise this one, start
	 * from {@code defaultJsonMapper().rebuild()}.
	 * </p>
	 * @return a new, independently configurable lenient mapper
	 */
	public static JsonMapper defaultJsonMapper() {
		return JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
			.disable(EnumFeature.READ_ENUMS_USING_TO_STRING, EnumFeature.WRITE_ENUMS_USING_TO_STRING)
			.addHandler(new UnknownPropertyLogger())
			.build();
	}

	/** Skips an unknown property after logging it, so drift is visible at DEBUG. */
	static final class UnknownPropertyLogger extends DeserializationProblemHandler {

		@Override
		public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
				ValueDeserializer<?> deserializer, Object beanOrClass, String propertyName) throws JacksonException {
			if (logger.isDebugEnabled()) {
				Object type = beanOrClass instanceof Class<?> c ? c.getSimpleName()
						: beanOrClass != null ? beanOrClass.getClass().getSimpleName() : "?";
				logger.debug("Ignoring unknown property '{}' on {} (the peer is newer than this SDK, or off-spec)",
						propertyName, type);
			}
			p.skipChildren();
			return true;
		}

	}

	/**
	 * Constructs a new Jackson3AcpJsonMapper with the given JsonMapper, used as is.
	 * @param jsonMapper the JsonMapper to use. Must not be null.
	 * @throws IllegalArgumentException if the provided JsonMapper is null.
	 */
	public Jackson3AcpJsonMapper(JsonMapper jsonMapper) {
		if (jsonMapper == null) {
			throw new IllegalArgumentException("JsonMapper must not be null");
		}
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Returns the underlying Jackson {@link JsonMapper}.
	 * @return the JsonMapper instance
	 */
	public JsonMapper getJsonMapper() {
		return jsonMapper;
	}

	@Override
	public <T> T readValue(String content, Class<T> type) throws IOException {
		try {
			return jsonMapper.readValue(content, type);
		}
		catch (JacksonException ex) {
			throw asIOException(ex);
		}
	}

	@Override
	public <T> T readValue(byte[] content, Class<T> type) throws IOException {
		try {
			return jsonMapper.readValue(content, type);
		}
		catch (JacksonException ex) {
			throw asIOException(ex);
		}
	}

	@Override
	public <T> T readValue(String content, TypeRef<T> type) throws IOException {
		try {
			return jsonMapper.readValue(content, javaType(type));
		}
		catch (JacksonException ex) {
			throw asIOException(ex);
		}
	}

	@Override
	public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
		try {
			return jsonMapper.readValue(content, javaType(type));
		}
		catch (JacksonException ex) {
			throw asIOException(ex);
		}
	}

	@Override
	public <T> T convertValue(Object fromValue, Class<T> type) {
		try {
			return jsonMapper.convertValue(fromValue, type);
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException(ex.getMessage(), ex);
		}
	}

	@Override
	public <T> T convertValue(Object fromValue, TypeRef<T> type) {
		try {
			return jsonMapper.convertValue(fromValue, javaType(type));
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException(ex.getMessage(), ex);
		}
	}

	@Override
	public String writeValueAsString(Object value) throws IOException {
		try {
			return jsonMapper.writeValueAsString(value);
		}
		catch (JacksonException ex) {
			throw asIOException(ex);
		}
	}

	@Override
	public byte[] writeValueAsBytes(Object value) throws IOException {
		try {
			return jsonMapper.writeValueAsBytes(value);
		}
		catch (JacksonException ex) {
			throw asIOException(ex);
		}
	}

	private JavaType javaType(TypeRef<?> type) {
		return jsonMapper.getTypeFactory().constructType(type.getType());
	}

	private static IOException asIOException(JacksonException ex) {
		return new IOException(ex.getMessage(), ex);
	}

}
