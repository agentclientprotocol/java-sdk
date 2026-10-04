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
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Jackson 2 {@link AcpJsonMapper}, from the {@code acp-json-jackson2} module: it reads and
 * writes ACP messages with a Jackson {@link ObjectMapper}. {@link AcpJsonMapper#createDefault()}
 * returns one built on {@link #defaultObjectMapper()} when this is the only JSON module on the
 * classpath; create one yourself to give a transport an {@code ObjectMapper} you configured. Use
 * {@code acp-json-jackson3} instead when the application already runs on Jackson 3.
 *
 * <p>It needs jackson-core and jackson-databind 2.18.1 or later, and checks both when it is
 * created: an older Jackson 2 fails then with an {@link IllegalStateException} that names the
 * versions found, not later while reading a message. It lives in the package of
 * {@link AcpJsonMapper}, which acp-core and this module share.
 *
 * <p>Reads and writes fail with Jackson's {@link IOException} subclasses, and conversions with
 * {@link IllegalArgumentException}, as {@link AcpJsonMapper} requires. It is safe for concurrent
 * use as long as the {@code ObjectMapper} is not reconfigured once in use.
 *
 * @author Mark Pollack
 */
public final class JacksonAcpJsonMapper implements AcpJsonMapper {

	private static final Logger logger = LoggerFactory.getLogger(JacksonAcpJsonMapper.class);

	private final ObjectMapper objectMapper;

	/**
	 * Returns a new {@link ObjectMapper} set up the way the SDK reads and writes ACP; the module's
	 * supplier builds its mapper on it. It differs from a bare {@code new ObjectMapper()} in two
	 * ways:
	 * <ul>
	 * <li>unknown properties are skipped, not rejected, because the ACP specification adds fields
	 * between releases and an agent newer than this SDK must keep working. Each skipped property is
	 * logged at DEBUG under this class's logger, so drift can be seen without making the wire
	 * strict;</li>
	 * <li>a scalar of the wrong JSON type is refused rather than coerced (a number or boolean where
	 * a string is expected, a string where a number or boolean is expected): the SDK answers such
	 * params {@code -32602} (Invalid params), and a coerced value would hide a peer's bug.</li>
	 * </ul>
	 *
	 * <p>Strictness is the mapper's choice, not the schema's: the schema records carry no
	 * {@code @JsonIgnoreProperties}, so a mapper with
	 * {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} enabled (a bare
	 * {@code ObjectMapper}'s default) fails on the first unknown field. To customise the SDK's
	 * settings, start from this method's result and pass it to
	 * {@link #JacksonAcpJsonMapper(ObjectMapper)}.
	 * @return a new mapper, shared with no other caller
	 */
	public static ObjectMapper defaultObjectMapper() {
		return JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
			.withCoercionConfig(LogicalType.Textual,
					config -> config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
			.addHandler(new UnknownPropertyLogger())
			.build();
	}

	/** Skips an unknown property after logging it, so drift is visible at DEBUG. */
	static final class UnknownPropertyLogger extends DeserializationProblemHandler {

		@Override
		public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
				JsonDeserializer<?> deserializer, @Nullable Object beanOrClass, String propertyName) throws IOException {
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
	 * Creates a mapper that reads and writes with the given {@code ObjectMapper}, used as is: none
	 * of the settings of {@link #defaultObjectMapper()} are added.
	 * @param objectMapper the Jackson mapper; start from {@link #defaultObjectMapper()} to keep the
	 * SDK's settings
	 * @throws IllegalArgumentException if {@code objectMapper} is null
	 * @throws IllegalStateException if the jackson-core or jackson-databind on the classpath is
	 * older than 2.18.1
	 */
	public JacksonAcpJsonMapper(ObjectMapper objectMapper) {
		JacksonVersions.requireSupported();
		if (objectMapper == null) {
			throw new IllegalArgumentException("ObjectMapper must not be null");
		}
		this.objectMapper = objectMapper;
	}

	/**
	 * Returns the {@code ObjectMapper} this mapper reads and writes with: the one given to the
	 * constructor, not a copy.
	 * @return the Jackson mapper
	 */
	public ObjectMapper getObjectMapper() {
		return objectMapper;
	}

	@Override
	public <T> @Nullable T readValue(String content, Class<T> type) throws IOException {
		return objectMapper.readValue(content, type);
	}

	@Override
	public <T> @Nullable T readValue(byte[] content, Class<T> type) throws IOException {
		return objectMapper.readValue(content, type);
	}

	@Override
	public <T> @Nullable T readValue(String content, TypeRef<T> type) throws IOException {
		JavaType javaType = objectMapper.getTypeFactory().constructType(type.getType());
		return objectMapper.readValue(content, javaType);
	}

	@Override
	public <T> @Nullable T readValue(byte[] content, TypeRef<T> type) throws IOException {
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
