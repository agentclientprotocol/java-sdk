/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.io.IOException;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jackson 2 implementation of {@link AcpJsonMapper}, shipped in {@code acp-json-jackson2}.
 * Wraps a Jackson {@link ObjectMapper} but keeps the SDK decoupled from Jackson at the API
 * level. It keeps the package it had when it lived in {@code acp-core}, so code that
 * constructs it compiles unchanged.
 *
 * @author Mark Pollack
 */
public final class JacksonAcpJsonMapper implements AcpJsonMapper {

	private static final Logger logger = LoggerFactory.getLogger(JacksonAcpJsonMapper.class);

	private final ObjectMapper objectMapper;

	/**
	 * The {@link ObjectMapper} the SDK uses by default: <em>lenient</em> about unknown
	 * properties, because the ACP specification adds fields between releases and an agent
	 * newer than this SDK must keep working. Each unknown property is logged once per
	 * occurrence at DEBUG under this class's logger, so spec drift is observable without
	 * making the wire strict.
	 *
	 * <p>
	 * Strictness is the mapper's decision, not the schema's: the schema records carry no
	 * {@code @JsonIgnoreProperties}, so a mapper with
	 * {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} enabled (Jackson's own
	 * default for a bare {@code new ObjectMapper()}) fails on the first unknown field.
	 * Consumers who want their own configuration on top of the SDK's defaults should start
	 * from this method's result.
	 * </p>
	 * @return a new, independently configurable lenient mapper
	 */
	public static ObjectMapper defaultObjectMapper() {
		return JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.addHandler(new UnknownPropertyLogger())
			.build();
	}

	/** Skips an unknown property after logging it, so drift is visible at DEBUG. */
	static final class UnknownPropertyLogger extends DeserializationProblemHandler {

		@Override
		public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
				JsonDeserializer<?> deserializer, Object beanOrClass, String propertyName) throws IOException {
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
	 * Constructs a new JacksonAcpJsonMapper with the given ObjectMapper.
	 * @param objectMapper the ObjectMapper to use. Must not be null.
	 * @throws IllegalArgumentException if the provided ObjectMapper is null.
	 */
	public JacksonAcpJsonMapper(ObjectMapper objectMapper) {
		if (objectMapper == null) {
			throw new IllegalArgumentException("ObjectMapper must not be null");
		}
		this.objectMapper = objectMapper;
	}

	/**
	 * Returns the underlying Jackson {@link ObjectMapper}.
	 * @return the ObjectMapper instance
	 */
	public ObjectMapper getObjectMapper() {
		return objectMapper;
	}

	@Override
	public <T> T readValue(String content, Class<T> type) throws IOException {
		return objectMapper.readValue(content, type);
	}

	@Override
	public <T> T readValue(byte[] content, Class<T> type) throws IOException {
		return objectMapper.readValue(content, type);
	}

	@Override
	public <T> T readValue(String content, TypeRef<T> type) throws IOException {
		JavaType javaType = objectMapper.getTypeFactory().constructType(type.getType());
		return objectMapper.readValue(content, javaType);
	}

	@Override
	public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
		JavaType javaType = objectMapper.getTypeFactory().constructType(type.getType());
		return objectMapper.readValue(content, javaType);
	}

	@Override
	public <T> T convertValue(Object fromValue, Class<T> type) {
		return objectMapper.convertValue(fromValue, type);
	}

	@Override
	public <T> T convertValue(Object fromValue, TypeRef<T> type) {
		JavaType javaType = objectMapper.getTypeFactory().constructType(type.getType());
		return objectMapper.convertValue(fromValue, javaType);
	}

	@Override
	public String writeValueAsString(Object value) throws IOException {
		return objectMapper.writeValueAsString(value);
	}

	@Override
	public byte[] writeValueAsBytes(Object value) throws IOException {
		return objectMapper.writeValueAsBytes(value);
	}

}
