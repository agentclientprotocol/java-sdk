/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonAcpJsonMapperTest {

	private final JacksonAcpJsonMapper mapper = new JacksonAcpJsonMapper(new ObjectMapper());

	@Test
	void nullObjectMapperThrows() {
		assertThatThrownBy(() -> new JacksonAcpJsonMapper(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("ObjectMapper must not be null");
	}

	@Test
	void getObjectMapperReturnsInstance() {
		ObjectMapper om = new ObjectMapper();
		JacksonAcpJsonMapper m = new JacksonAcpJsonMapper(om);
		assertThat(m.getObjectMapper()).isSameAs(om);
	}

	// -- readValue with Class --

	@SuppressWarnings("unchecked")
	@Test
	void readValueStringWithClass() throws IOException {
		String json = "{\"name\":\"Alice\",\"age\":30}";
		Map<String, Object> result = mapper.readValue(json, Map.class);
		assertThat(result).containsEntry("name", "Alice").containsEntry("age", 30);
	}

	@SuppressWarnings("unchecked")
	@Test
	void readValueBytesWithClass() throws IOException {
		byte[] json = "{\"key\":\"value\"}".getBytes();
		Map<String, Object> result = mapper.readValue(json, Map.class);
		assertThat(result).containsEntry("key", "value");
	}

	// -- readValue with TypeRef --

	@Test
	void readValueStringWithTypeRef() throws IOException {
		String json = "{\"a\":1,\"b\":2}";
		HashMap<String, Integer> result = mapper.readValue(json, new TypeRef<HashMap<String, Integer>>() {
		});
		assertThat(result).containsEntry("a", 1).containsEntry("b", 2);
	}

	@Test
	void readValueBytesWithTypeRef() throws IOException {
		byte[] json = "[\"x\",\"y\",\"z\"]".getBytes();
		List<String> result = mapper.readValue(json, new TypeRef<List<String>>() {
		});
		assertThat(result).containsExactly("x", "y", "z");
	}

	// -- convertValue --

	@Test
	void convertValueWithClass() {
		Map<String, Object> map = Map.of("name", "test", "age", 25);
		TestPojo result = mapper.convertValue(map, TestPojo.class);
		assertThat(result.name).isEqualTo("test");
		assertThat(result.age).isEqualTo(25);
	}

	@Test
	void convertValueWithTypeRef() {
		Map<String, Object> map = Map.of("key", "value");
		HashMap<String, String> result = mapper.convertValue(map, new TypeRef<HashMap<String, String>>() {
		});
		assertThat(result).containsEntry("key", "value");
	}

	// -- writeValueAsString / writeValueAsBytes --

	@Test
	void writeValueAsString() throws IOException {
		Map<String, String> data = Map.of("hello", "world");
		String json = mapper.writeValueAsString(data);
		assertThat(json).contains("\"hello\"").contains("\"world\"");
	}

	@Test
	void writeValueAsBytes() throws IOException {
		Map<String, String> data = Map.of("hello", "world");
		byte[] bytes = mapper.writeValueAsBytes(data);
		String json = new String(bytes);
		assertThat(json).contains("\"hello\"").contains("\"world\"");
	}

	// -- round-trip --

	@Test
	void roundTripWithTypeRef() throws IOException {
		TestPojo original = new TestPojo();
		original.name = "round-trip";
		original.age = 42;

		String json = mapper.writeValueAsString(original);
		TestPojo deserialized = mapper.readValue(json, TestPojo.class);

		assertThat(deserialized.name).isEqualTo("round-trip");
		assertThat(deserialized.age).isEqualTo(42);
	}

	// Simple POJO for testing
	public static class TestPojo {

		public String name;

		public int age;

	}

}
