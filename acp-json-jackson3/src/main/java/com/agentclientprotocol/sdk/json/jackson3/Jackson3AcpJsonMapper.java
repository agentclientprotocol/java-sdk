/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json.jackson3;

import java.io.IOException;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * The Jackson 3 {@link AcpJsonMapper}, from the {@code acp-json-jackson3} module: it reads and
 * writes ACP messages with a Jackson 3 ({@code tools.jackson}) {@link JsonMapper}.
 * {@link AcpJsonMapper#createDefault()} returns one built on {@link #defaultJsonMapper()} when this
 * module is on the classpath, even next to {@code acp-json-jackson2}; create one yourself to give a
 * transport a {@code JsonMapper} you configured. Use it when the application already runs on
 * Jackson 3, as under Spring Boot 4.
 *
 * <p>How it differs from the Jackson 2 module's {@code JacksonAcpJsonMapper}: it needs jackson-core
 * and jackson-databind 3.0.0 or later, and checks both when it is created (an
 * {@link IllegalStateException} names the versions found). Jackson 3 reports errors with the
 * unchecked {@link JacksonException}; to keep the {@link AcpJsonMapper} contract, this mapper
 * rethrows it as an {@link IOException} from reads and writes and as an
 * {@link IllegalArgumentException} from conversions, with the original as the cause. Jackson 3
 * mappers are immutable, so it is safe for concurrent use.
 *
 * <p>The schema records carry Jackson 2 annotations ({@code com.fasterxml.jackson.annotation}),
 * which Jackson 3 reads unchanged, so both modules serialize the same records. With
 * {@link #defaultJsonMapper()} the bytes on the wire are the same as with the Jackson 2 module.
 *
 * @author Mark Pollack
 */
public final class Jackson3AcpJsonMapper implements AcpJsonMapper {

	private static final Logger logger = LoggerFactory.getLogger(Jackson3AcpJsonMapper.class);

	private final JsonMapper jsonMapper;

	/**
	 * Returns a new {@link JsonMapper} set up the way the SDK reads and writes ACP; the module's
	 * supplier builds its mapper on it. Like the Jackson 2 module's default, it skips unknown
	 * properties instead of rejecting them, because the ACP specification adds fields between
	 * releases and an agent newer than this SDK must keep working, and logs each one at DEBUG under
	 * this class's logger, so drift can be seen without making the wire strict. It also refuses a
	 * scalar of the wrong JSON type rather than coercing it (a number or boolean where a string is
	 * expected, a string where a number or boolean is expected): the SDK answers such params
	 * {@code -32602} (Invalid params), and a coerced value would hide a peer's bug.
	 *
	 * <p>Jackson 3 changed several defaults from Jackson 2. Where the change would alter what the
	 * SDK writes or accepts, this mapper restores the Jackson 2 behaviour:
	 * <ul>
	 * <li>{@link MapperFeature#SORT_PROPERTIES_ALPHABETICALLY} is disabled, so properties are
	 * written in record-component order;</li>
	 * <li>{@link DeserializationFeature#FAIL_ON_NULL_FOR_PRIMITIVES} is disabled, so a JSON
	 * {@code null} for a primitive component reads as its default value;</li>
	 * <li>{@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS} is disabled, so content after the
	 * first JSON value is ignored rather than rejected;</li>
	 * <li>{@link EnumFeature#READ_ENUMS_USING_TO_STRING} and
	 * {@link EnumFeature#WRITE_ENUMS_USING_TO_STRING} are disabled, so enums use their
	 * {@code @JsonProperty} name or constant name, never {@code toString()}.</li>
	 * </ul>
	 *
	 * <p>{@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} is disabled explicitly; Jackson
	 * 3 already has it off by default. Strictness is the mapper's choice, not the schema's: the
	 * schema records carry no {@code @JsonIgnoreProperties}, so a mapper with that feature enabled
	 * fails on the first unknown field. Jackson 3 mappers are immutable; to customise this one,
	 * start from {@code defaultJsonMapper().rebuild()}, which keeps the logging of unknown
	 * properties, and pass the result to {@link #Jackson3AcpJsonMapper(JsonMapper)}.
	 * @return a new mapper, shared with no other caller
	 */
	public static JsonMapper defaultJsonMapper() {
		return JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
			.disable(EnumFeature.READ_ENUMS_USING_TO_STRING, EnumFeature.WRITE_ENUMS_USING_TO_STRING)
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
				ValueDeserializer<?> deserializer, @Nullable Object beanOrClass, String propertyName) throws JacksonException {
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
	 * Creates a mapper that reads and writes with the given {@code JsonMapper}, used as is: none of
	 * the settings of {@link #defaultJsonMapper()} are added.
	 * @param jsonMapper the Jackson mapper; start from {@link #defaultJsonMapper()} to keep the
	 * SDK's settings
	 * @throws IllegalArgumentException if {@code jsonMapper} is null
	 * @throws IllegalStateException if the jackson-core or jackson-databind on the classpath is
	 * older than 3.0.0
	 */
	public Jackson3AcpJsonMapper(JsonMapper jsonMapper) {
		Jackson3Versions.requireSupported();
		if (jsonMapper == null) {
			throw new IllegalArgumentException("JsonMapper must not be null");
		}
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Returns the {@code JsonMapper} this mapper reads and writes with: the one given to the
	 * constructor.
	 * @return the Jackson mapper
	 */
	public JsonMapper getJsonMapper() {
		return jsonMapper;
	}

	@Override
	public <T> @Nullable T readValue(String content, Class<T> type) throws IOException {
		try {
			return jsonMapper.readValue(content, type);
		}
		catch (JacksonException ex) {
			throw asIOException(ex);
		}
	}

	@Override
	public <T> @Nullable T readValue(byte[] content, Class<T> type) throws IOException {
		try {
			return jsonMapper.readValue(content, type);
		}
		catch (JacksonException ex) {
			throw asIOException(ex);
		}
	}

	@Override
	public <T> @Nullable T readValue(String content, TypeRef<T> type) throws IOException {
		try {
			return jsonMapper.readValue(content, javaType(type));
		}
		catch (JacksonException ex) {
			throw asIOException(ex);
		}
	}

	@Override
	public <T> @Nullable T readValue(byte[] content, TypeRef<T> type) throws IOException {
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
