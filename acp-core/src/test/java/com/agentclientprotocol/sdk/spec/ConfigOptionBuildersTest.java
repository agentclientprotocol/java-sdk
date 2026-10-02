/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Building select and boolean config options with a category, description and
 * {@code _meta} without the canonical constructor, and the spec's category values.
 */
class ConfigOptionBuildersTest {

	private static final AcpSchema.SessionConfigSelectOption A = new AcpSchema.SessionConfigSelectOption("model-a",
			"Model A");

	private static final AcpSchema.SessionConfigSelectOption B = new AcpSchema.SessionConfigSelectOption("model-b",
			"Model B");

	private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();

	@Test
	void modelFactorySetsTheModelCategory() throws Exception {
		var model = AcpSchema.SessionConfigSelect.model("model", "Model", "model-a", List.of(A, B));

		assertThat(model).isEqualTo(new AcpSchema.SessionConfigSelect("select", "model", "Model", null, "model",
				"model-a", AcpSchema.SessionConfigSelectOptions.ungrouped(List.of(A, B)), null));
		assertThat(mapper.writeValueAsString(model)).isEqualTo("{\"type\":\"select\",\"id\":\"model\","
				+ "\"name\":\"Model\",\"category\":\"model\",\"currentValue\":\"model-a\",\"options\":["
				+ "{\"value\":\"model-a\",\"name\":\"Model A\"},{\"value\":\"model-b\",\"name\":\"Model B\"}]}");
	}

	@Test
	void modelFactoryTakesGroupedOptions() {
		var grouped = AcpSchema.SessionConfigSelectOptions
			.grouped(List.of(new AcpSchema.SessionConfigSelectGroup("fast", "Fast", List.of(A))));

		var model = AcpSchema.SessionConfigSelect.model("model", "Model", "model-a", grouped);

		assertThat(model.category()).isEqualTo(AcpSchema.SessionConfigOptionCategory.MODEL);
		assertThat(model.options()).isEqualTo(grouped);
	}

	@Test
	void selectBuilderReachesEveryField() {
		var select = AcpSchema.SessionConfigSelect.builder()
			.id("effort")
			.name("Effort")
			.description("How hard to think")
			.category(AcpSchema.SessionConfigOptionCategory.THOUGHT_LEVEL)
			.currentValue("model-b")
			.options(List.of(A, B))
			.meta(Map.of("k", "v"))
			.build();

		assertThat(select).isEqualTo(new AcpSchema.SessionConfigSelect("select", "effort", "Effort",
				"How hard to think", "thought_level", "model-b",
				AcpSchema.SessionConfigSelectOptions.ungrouped(List.of(A, B)), Map.of("k", "v")));
	}

	@Test
	void selectBuilderTakesGroups() {
		var groups = List.of(new AcpSchema.SessionConfigSelectGroup("fast", "Fast", List.of(A)),
				new AcpSchema.SessionConfigSelectGroup("smart", "Smart", List.of(B)));

		var select = AcpSchema.SessionConfigSelect.builder()
			.id("model")
			.name("Model")
			.currentValue("model-a")
			.groups(groups)
			.build();

		assertThat(select.options()).isEqualTo(AcpSchema.SessionConfigSelectOptions.grouped(groups));
		assertThat(select.category()).isNull();
		assertThat(select.description()).isNull();
		assertThat(select.meta()).isNull();
	}

	@Test
	void selectBuilderRequiresIdNameCurrentValueAndOptions() {
		assertThatThrownBy(() -> AcpSchema.SessionConfigSelect.builder().name("n").currentValue("v").options(List.of(A)).build())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("id");
		assertThatThrownBy(() -> AcpSchema.SessionConfigSelect.builder().id("i").currentValue("v").options(List.of(A)).build())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("name");
		assertThatThrownBy(() -> AcpSchema.SessionConfigSelect.builder().id("i").name("n").options(List.of(A)).build())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("currentValue");
		assertThatThrownBy(() -> AcpSchema.SessionConfigSelect.builder().id("i").name("n").currentValue("v").build())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("options");
	}

	@Test
	void booleanBuilderReachesEveryField() {
		var toggle = AcpSchema.SessionConfigBoolean.builder()
			.id("web")
			.name("Web search")
			.description("Search the web")
			.category("_tools")
			.currentValue(true)
			.meta(Map.of("k", "v"))
			.build();

		assertThat(toggle).isEqualTo(new AcpSchema.SessionConfigBoolean("boolean", "web", "Web search",
				"Search the web", "_tools", true, Map.of("k", "v")));
	}

	@Test
	void booleanBuilderRequiresIdNameAndCurrentValue() {
		assertThatThrownBy(() -> AcpSchema.SessionConfigBoolean.builder().name("n").currentValue(true).build())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("id");
		assertThatThrownBy(() -> AcpSchema.SessionConfigBoolean.builder().id("i").currentValue(true).build())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("name");
		assertThatThrownBy(() -> AcpSchema.SessionConfigBoolean.builder().id("i").name("n").build())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("currentValue");
	}

	@Test
	void categoryConstantsAreTheSchemasReservedValues() throws Exception {
		Set<String> constants = Set.of(AcpSchema.SessionConfigOptionCategory.MODE,
				AcpSchema.SessionConfigOptionCategory.MODEL, AcpSchema.SessionConfigOptionCategory.MODEL_CONFIG,
				AcpSchema.SessionConfigOptionCategory.THOUGHT_LEVEL);

		assertThat(constants).isEqualTo(schemaCategories());
	}

	@SuppressWarnings("unchecked")
	private static Set<String> schemaCategories() throws Exception {
		String text;
		try (InputStream in = ConfigOptionBuildersTest.class.getResourceAsStream("/schema/v1/schema.json")) {
			assertThat(in).as("schema copy on the test classpath").isNotNull();
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		Map<String, Object> schema = AcpJsonMapper.createDefault().readValue(text, new TypeRef<Map<String, Object>>() {
		});
		Map<String, Object> category = (Map<String, Object>) ((Map<String, Object>) schema.get("$defs"))
			.get("SessionConfigOptionCategory");
		return ((List<Map<String, Object>>) category.get("anyOf")).stream()
			.filter(branch -> branch.containsKey("const"))
			.map(branch -> (String) branch.get("const"))
			.collect(Collectors.toSet());
	}

}
