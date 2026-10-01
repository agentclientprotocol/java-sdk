/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every polymorphic type must write its discriminator exactly once.
 *
 * <p>
 * A type whose discriminator is declared both as Jackson's {@link JsonTypeInfo} property
 * <em>and</em> as an explicit record component writes it twice, producing a duplicate JSON
 * key on the wire — for example
 * {@code {"type":"text","type":"text","text":"hello"}} for a text content block.
 *
 * <p>
 * Round-trip tests cannot see this. Jackson accepts duplicate keys and takes the last one,
 * so a serialize-then-deserialize assertion passes with every field intact. Strict parsers
 * do not: a Rust {@code serde_json} agent rejects the message with
 * {@code -32602 Invalid params: duplicate field}, which fails the request outright. That
 * is the failure this test exists to prevent, and it is only visible by looking at the
 * bytes.
 *
 * <p>
 * The cases are derived by reflection from {@link JsonSubTypes}, so a subtype added later
 * is covered without editing this test.
 *
 * @author Mark Pollack
 */
class AcpSchemaDiscriminatorTest {

	private static final AcpJsonMapper MAPPER = AcpJsonMapper.createDefault();

	@ParameterizedTest(name = "{0} writes \"{1}\" exactly once")
	@MethodSource("polymorphicSubtypes")
	@DisplayName("A polymorphic type writes its discriminator exactly once")
	void discriminatorIsWrittenExactlyOnce(String subtypeName, String discriminator, Object instance)
			throws Exception {
		String json = MAPPER.writeValueAsString(instance);

		assertThat(occurrencesOfKey(json, discriminator)).as("%s serialised to %s", subtypeName, json).isEqualTo(1);
	}

	/**
	 * One populated instance per {@link JsonSubTypes} entry of every polymorphic type in
	 * {@link AcpSchema}.
	 *
	 * <p>
	 * The discriminator component is set to the subtype's registered name and everything
	 * else is left empty, which is what the schema's own convenience constructors produce.
	 */
	private static Stream<Arguments> polymorphicSubtypes() throws Exception {
		List<Arguments> cases = new ArrayList<>();
		for (Class<?> nested : AcpSchema.class.getDeclaredClasses()) {
			JsonTypeInfo typeInfo = nested.getAnnotation(JsonTypeInfo.class);
			JsonSubTypes subTypes = nested.getAnnotation(JsonSubTypes.class);
			if (typeInfo == null || subTypes == null || typeInfo.property().isEmpty()) {
				continue;
			}
			for (JsonSubTypes.Type subType : subTypes.value()) {
				if (!subType.value().isRecord()) {
					continue;
				}
				Object instance = instantiate(subType.value(), typeInfo.property(), subType.name());
				cases.add(Arguments.of(subType.value().getSimpleName(), typeInfo.property(), instance));
			}
		}
		assertThat(cases).as("reflection should have found polymorphic subtypes").isNotEmpty();
		return cases.stream();
	}

	private static Object instantiate(Class<?> record, String discriminator, String subTypeName) throws Exception {
		RecordComponent[] components = record.getRecordComponents();
		Class<?>[] parameterTypes = new Class<?>[components.length];
		Object[] arguments = new Object[components.length];
		for (int i = 0; i < components.length; i++) {
			RecordComponent component = components[i];
			parameterTypes[i] = component.getType();
			arguments[i] = isDiscriminator(component, discriminator) ? subTypeName : emptyValue(component.getType());
		}
		Constructor<?> canonical = record.getDeclaredConstructor(parameterTypes);
		canonical.setAccessible(true);
		return canonical.newInstance(arguments);
	}

	private static boolean isDiscriminator(RecordComponent component, String discriminator) {
		JsonProperty jsonProperty = component.getAnnotation(JsonProperty.class);
		String name = (jsonProperty != null && !jsonProperty.value().isEmpty()) ? jsonProperty.value()
				: component.getName();
		return name.equals(discriminator) && component.getType() == String.class;
	}

	private static Object emptyValue(Class<?> type) {
		if (!type.isPrimitive()) {
			return null;
		}
		if (type == boolean.class) {
			return false;
		}
		if (type == int.class) {
			return 0;
		}
		if (type == long.class) {
			return 0L;
		}
		if (type == double.class) {
			return 0.0d;
		}
		return null;
	}

	/**
	 * Count occurrences of {@code "key":} at object-key position.
	 *
	 * <p>
	 * Deliberately a string scan rather than a parse: parsing the JSON would collapse the
	 * duplicate keys and hide exactly what is being asserted.
	 */
	private static int occurrencesOfKey(String json, String key) {
		String needle = "\"" + key + "\":";
		int count = 0;
		int from = 0;
		while ((from = json.indexOf(needle, from)) >= 0) {
			count++;
			from += needle.length();
		}
		return count;
	}

}
