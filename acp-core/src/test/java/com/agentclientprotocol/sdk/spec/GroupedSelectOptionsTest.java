/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A select config option's {@code options} is the schema's {@code SessionConfigSelectOptions}:
 * a flat list of options, or a list of {@code SessionConfigSelectGroup}s. Both shapes read
 * and write; the JSON modules run this class against Jackson 2 and Jackson 3.
 */
class GroupedSelectOptionsTest {

	private static final TypeRef<Map<String, Object>> MAP = new TypeRef<>() {
	};

	private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();

	private void assertWritesBack(Object value, String json) throws Exception {
		assertThat(mapper.readValue(mapper.writeValueAsString(value), MAP)).isEqualTo(mapper.readValue(json, MAP));
	}

	private static final AcpSchema.SessionConfigSelectOption FAST = new AcpSchema.SessionConfigSelectOption("fast",
			"Fast");

	private static final AcpSchema.SessionConfigSelectOption SMART = new AcpSchema.SessionConfigSelectOption("smart",
			"Smart", "Slower", Map.of("k", "v"));

	@Test
	void groupedOptionsRoundTrip() throws Exception {
		String json = """
				{"type":"select","id":"model","name":"Model","currentValue":"fast","options":[
				 {"group":"anthropic","name":"Anthropic","options":[{"value":"fast","name":"Fast"}],"_meta":{"g":1}},
				 {"group":"openai","name":"OpenAI","options":[{"value":"smart","name":"Smart","description":"Slower","_meta":{"k":"v"}}]}]}""";

		var select = (AcpSchema.SessionConfigSelect) mapper.readValue(json, AcpSchema.SessionConfigOption.class);

		assertThat(select.options()).isEqualTo(AcpSchema.SessionConfigSelectOptions.grouped(List.of(
				new AcpSchema.SessionConfigSelectGroup("anthropic", "Anthropic", List.of(FAST), Map.of("g", 1)),
				new AcpSchema.SessionConfigSelectGroup("openai", "OpenAI", List.of(SMART)))));
		assertThat(select.options().allOptions()).containsExactly(FAST, SMART);
		assertWritesBack(select, json);
	}

	@Test
	void ungroupedOptionsRoundTrip() throws Exception {
		String json = """
				{"type":"select","id":"model","name":"Model","currentValue":"fast","options":[
				 {"value":"fast","name":"Fast"},{"value":"smart","name":"Smart","description":"Slower","_meta":{"k":"v"}}]}""";

		var select = (AcpSchema.SessionConfigSelect) mapper.readValue(json, AcpSchema.SessionConfigOption.class);

		assertThat(select.options()).isEqualTo(AcpSchema.SessionConfigSelectOptions.ungrouped(List.of(FAST, SMART)));
		assertThat(select.options().allOptions()).containsExactly(FAST, SMART);
		assertThat(select).isEqualTo(new AcpSchema.SessionConfigSelect("model", "Model", "fast", List.of(FAST, SMART)));
		assertWritesBack(select, json);
	}

	@Test
	void anEmptyListIsUngrouped() throws Exception {
		var select = (AcpSchema.SessionConfigSelect) mapper.readValue(
				"{\"type\":\"select\",\"id\":\"m\",\"name\":\"M\",\"currentValue\":\"x\",\"options\":[]}",
				AcpSchema.SessionConfigOption.class);

		assertThat(select.options()).isEqualTo(AcpSchema.SessionConfigSelectOptions.ungrouped(List.of()));
	}

	@Test
	void aListMixingOptionsAndGroupsIsRejected() {
		assertThatThrownBy(() -> AcpSchema.SessionConfigSelectOptions.of(List.of(FAST,
				new AcpSchema.SessionConfigSelectGroup("g", "G", List.of(SMART)))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("mixes");
	}

}
