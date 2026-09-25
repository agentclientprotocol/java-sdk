/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@link AcpJsonMapper} contract, checked against the mapper that
 * {@link AcpJsonMapper#createDefault()} returns, so every JSON module runs it. Errors
 * surface the way the interface declares them: {@link IOException} from reads and writes,
 * {@link IllegalArgumentException} from conversions.
 */
class AcpJsonMapperContractTest {

	private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();

	@Test
	void createDefaultReturnsNewInstanceEachCall() {
		assertThat(AcpJsonMapper.createDefault()).isNotSameAs(AcpJsonMapper.createDefault());
	}

	@SuppressWarnings("unchecked")
	@Test
	void readValueStringWithClass() throws IOException {
		Map<String, Object> result = mapper.readValue("{\"name\":\"Alice\",\"age\":30}", Map.class);
		assertThat(result).containsEntry("name", "Alice").containsEntry("age", 30);
	}

	@SuppressWarnings("unchecked")
	@Test
	void readValueBytesWithClass() throws IOException {
		Map<String, Object> result = mapper.readValue("{\"key\":\"value\"}".getBytes(), Map.class);
		assertThat(result).containsEntry("key", "value");
	}

	@Test
	void readValueStringWithTypeRef() throws IOException {
		HashMap<String, Integer> result = mapper.readValue("{\"a\":1,\"b\":2}", new TypeRef<HashMap<String, Integer>>() {
		});
		assertThat(result).containsEntry("a", 1).containsEntry("b", 2);
	}

	@Test
	void readValueBytesWithTypeRef() throws IOException {
		List<String> result = mapper.readValue("[\"x\",\"y\",\"z\"]".getBytes(), new TypeRef<List<String>>() {
		});
		assertThat(result).containsExactly("x", "y", "z");
	}

	@Test
	void numbersInUntypedValuesKeepJacksonsNaturalTypes() throws IOException {
		Map<String, Object> result = mapper.readValue("{\"i\":1,\"l\":3000000000,\"d\":1.5}",
				new TypeRef<Map<String, Object>>() {
				});
		assertThat(result.get("i")).isInstanceOf(Integer.class);
		assertThat(result.get("l")).isInstanceOf(Long.class);
		assertThat(result.get("d")).isInstanceOf(Double.class);
	}

	@Test
	void convertValueWithClass() {
		TestPojo result = mapper.convertValue(Map.of("name", "test", "age", 25), TestPojo.class);
		assertThat(result.name).isEqualTo("test");
		assertThat(result.age).isEqualTo(25);
	}

	@Test
	void convertValueWithTypeRef() {
		HashMap<String, String> result = mapper.convertValue(Map.of("key", "value"),
				new TypeRef<HashMap<String, String>>() {
				});
		assertThat(result).containsEntry("key", "value");
	}

	@Test
	void writeValueAsStringAndBytes() throws IOException {
		assertThat(mapper.writeValueAsString(Map.of("hello", "world"))).isEqualTo("{\"hello\":\"world\"}");
		assertThat(new String(mapper.writeValueAsBytes(Map.of("hello", "world")))).isEqualTo("{\"hello\":\"world\"}");
	}

	@Test
	void roundTripPojo() throws IOException {
		TestPojo original = new TestPojo();
		original.name = "round-trip";
		original.age = 42;

		TestPojo deserialized = mapper.readValue(mapper.writeValueAsString(original), TestPojo.class);

		assertThat(deserialized.name).isEqualTo("round-trip");
		assertThat(deserialized.age).isEqualTo(42);
	}

	@Test
	void malformedJsonIsAnIOException() {
		assertThatThrownBy(() -> mapper.readValue("{not json", Map.class)).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> mapper.readValue("{not json".getBytes(), new TypeRef<Map<String, Object>>() {
		})).isInstanceOf(IOException.class);
	}

	@Test
	void typeMismatchOnReadIsAnIOException() {
		assertThatThrownBy(() -> mapper.readValue("{\"protocolVersion\":\"one\"}", AcpSchema.InitializeRequest.class))
			.isInstanceOf(IOException.class);
	}

	@Test
	void failedConversionIsAnIllegalArgumentException() {
		assertThatThrownBy(() -> mapper.convertValue(Map.of("protocolVersion", "one"), AcpSchema.InitializeRequest.class))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> mapper.convertValue(Map.of("protocolVersion", "one"),
				new TypeRef<AcpSchema.InitializeRequest>() {
				}))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void unserializableValueIsAnIOException() {
		Object selfReferencing = new Object() {
			public Object getSelf() {
				return this;
			}
		};
		assertThatThrownBy(() -> mapper.writeValueAsString(selfReferencing)).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> mapper.writeValueAsBytes(selfReferencing)).isInstanceOf(IOException.class);
	}

	@Test
	void nullForAPrimitiveReadsAsItsDefault() throws IOException {
		AcpSchema.JSONRPCError error = mapper.readValue("{\"code\":null,\"message\":\"m\"}", AcpSchema.JSONRPCError.class);
		assertThat(error.code()).isZero();
		assertThat(error.message()).isEqualTo("m");
	}

	/** Simple POJO for testing. */
	public static class TestPojo {

		public String name;

		public int age;

	}

}
