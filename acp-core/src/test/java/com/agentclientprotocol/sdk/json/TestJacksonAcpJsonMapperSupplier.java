/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.io.IOException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The mapper acp-core's own tests run with. acp-core ships no JSON implementation and
 * cannot depend on {@code acp-json-jackson2} (that module depends on acp-core), so its
 * tests register this minimal, test-scoped Jackson 2 mapper instead. It is configured
 * like the shipped default (lenient about unknown properties) and is deliberately left
 * out of the acp-core test-jar, so the JSON modules run the contract suite against their
 * own mapper.
 */
public final class TestJacksonAcpJsonMapperSupplier implements AcpJsonMapperSupplier {

	@Override
	public AcpJsonMapper get() {
		return new Mapper(JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build());
	}

	private record Mapper(ObjectMapper om) implements AcpJsonMapper {

		@Override
		public <T> T readValue(String content, Class<T> type) throws IOException {
			return om.readValue(content, type);
		}

		@Override
		public <T> T readValue(byte[] content, Class<T> type) throws IOException {
			return om.readValue(content, type);
		}

		@Override
		public <T> T readValue(String content, TypeRef<T> type) throws IOException {
			return om.readValue(content, javaType(type));
		}

		@Override
		public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
			return om.readValue(content, javaType(type));
		}

		@Override
		public <T> T convertValue(Object fromValue, Class<T> type) {
			return om.convertValue(fromValue, type);
		}

		@Override
		public <T> T convertValue(Object fromValue, TypeRef<T> type) {
			return om.convertValue(fromValue, javaType(type));
		}

		@Override
		public String writeValueAsString(Object value) throws IOException {
			return om.writeValueAsString(value);
		}

		@Override
		public byte[] writeValueAsBytes(Object value) throws IOException {
			return om.writeValueAsBytes(value);
		}

		private JavaType javaType(TypeRef<?> type) {
			return om.getTypeFactory().constructType(type.getType());
		}

	}

}
