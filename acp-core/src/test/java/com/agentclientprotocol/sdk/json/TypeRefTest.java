/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypeRefTest {

	@Test
	void capturesSimpleType() {
		TypeRef<String> ref = new TypeRef<>() {
		};
		assertThat(ref.getType()).isEqualTo(String.class);
	}

	@Test
	void capturesParameterizedType() {
		TypeRef<List<String>> ref = new TypeRef<>() {
		};
		Type type = ref.getType();
		assertThat(type).isInstanceOf(ParameterizedType.class);
		ParameterizedType pt = (ParameterizedType) type;
		assertThat(pt.getRawType()).isEqualTo(List.class);
		assertThat(pt.getActualTypeArguments()[0]).isEqualTo(String.class);
	}

	@Test
	void capturesNestedParameterizedType() {
		TypeRef<Map<String, List<Integer>>> ref = new TypeRef<>() {
		};
		Type type = ref.getType();
		assertThat(type).isInstanceOf(ParameterizedType.class);
		ParameterizedType pt = (ParameterizedType) type;
		assertThat(pt.getRawType()).isEqualTo(Map.class);
	}

	@Test
	void capturesHashMapType() {
		TypeRef<HashMap<String, Object>> ref = new TypeRef<>() {
		};
		Type type = ref.getType();
		assertThat(type).isInstanceOf(ParameterizedType.class);
		ParameterizedType pt = (ParameterizedType) type;
		assertThat(pt.getRawType()).isEqualTo(HashMap.class);
	}

	@Test
	void twoTypeRefsWithSameTypeAreDistinctButEqual() {
		TypeRef<String> ref1 = new TypeRef<>() {
		};
		TypeRef<String> ref2 = new TypeRef<>() {
		};
		assertThat(ref1.getType()).isEqualTo(ref2.getType());
	}

}
