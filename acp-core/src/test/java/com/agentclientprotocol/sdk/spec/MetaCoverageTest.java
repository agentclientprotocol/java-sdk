/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every object the ACP schema gives a {@code _meta} property has it in Java.
 *
 * <p>
 * The test walks {@code schema/v1/schema.json} (a copy of the spec's stable schema, v1.9.1,
 * revision {@code 7628b15}): each definition with a {@code _meta} property must map to one
 * or more {@link AcpSchema} records that declare a {@code @JsonProperty("_meta")}
 * component, or be listed in {@link #NOT_MODELLED_AS_A_RECORD} with the reason. A
 * definition added to the schema copy fails the test until it is mapped or classified, so
 * the coverage stays complete when the copy is updated.
 * </p>
 */
class MetaCoverageTest {

	/** Schema definitions whose Java record has another name, or several records. */
	private static final Map<String, List<String>> RECORDS = Map.ofEntries(
			Map.entry("FileSystemCapabilities", List.of("FileSystemCapability")),
			Map.entry("KillTerminalRequest", List.of("KillTerminalCommandRequest")),
			Map.entry("KillTerminalResponse", List.of("KillTerminalCommandResponse")),
			Map.entry("ContentChunk", List.of("UserMessageChunk", "AgentMessageChunk", "AgentThoughtChunk")),
			Map.entry("Content", List.of("ToolCallContentBlock")), Map.entry("Diff", List.of("ToolCallDiff")),
			Map.entry("Terminal", List.of("ToolCallTerminal")), Map.entry("EmbeddedResource", List.of("Resource")),
			Map.entry("SelectedPermissionOutcome", List.of("PermissionSelected")),
			Map.entry("UnstructuredCommandInput", List.of("AvailableCommandInput")),
			Map.entry("ToolCallUpdate", List.of("ToolCallUpdate", "ToolCallUpdateNotification")),
			Map.entry("SessionConfigOption", List.of("SessionConfigSelect", "SessionConfigBoolean")));

	/**
	 * Schema definitions with {@code _meta} that have no record of their own, and why.
	 */
	private static final Map<String, String> NOT_MODELLED_AS_A_RECORD = Map.ofEntries(
			Map.entry("SessionListCapabilities", "presence marker typed Object: keeps _meta as a map entry"),
			Map.entry("SessionCloseCapabilities", "presence marker typed Object: keeps _meta as a map entry"),
			Map.entry("SessionResumeCapabilities", "presence marker typed Object: keeps _meta as a map entry"),
			Map.entry("SessionDeleteCapabilities", "presence marker typed Object: keeps _meta as a map entry"),
			Map.entry("SessionAdditionalDirectoriesCapabilities",
					"presence marker typed Object: keeps _meta as a map entry"),
			Map.entry("ElicitationFormCapabilities", "presence marker typed Object: keeps _meta as a map entry"),
			Map.entry("ElicitationUrlCapabilities", "presence marker typed Object: keeps _meta as a map entry"),
			Map.entry("SessionConfigSelectGroup",
					"grouped select options are not modelled: SessionConfigSelect.options is the flat list only"),
			Map.entry("CancelRequestNotification", "$/cancel_request is not implemented yet (parity item P2)"),
			// Elicitation records: parity item P4 owns them; it adds _meta and drops these entries
			Map.entry("ElicitationSchema", "elicitation record without _meta, added with P4"),
			Map.entry("StringPropertySchema", "elicitation record without _meta, added with P4"),
			Map.entry("NumberPropertySchema", "elicitation record without _meta, added with P4"),
			Map.entry("IntegerPropertySchema", "elicitation record without _meta, added with P4"),
			Map.entry("BooleanPropertySchema", "elicitation record without _meta, added with P4"),
			Map.entry("MultiSelectPropertySchema", "elicitation record without _meta, added with P4"),
			Map.entry("StringMultiSelectItems", "elicitation record without _meta, added with P4"),
			Map.entry("TitledMultiSelectItems", "elicitation record without _meta, added with P4"),
			Map.entry("EnumOption", "elicitation record without _meta, added with P4"));

	@Test
	void everySchemaObjectWithMetaHasItInJava() throws Exception {
		Map<String, Map<String, Object>> definitions = definitionsWithMeta();
		List<String> missing = new ArrayList<>();
		int checked = 0;
		for (String definition : new TreeSet<>(definitions.keySet())) {
			if (NOT_MODELLED_AS_A_RECORD.containsKey(definition)) {
				continue;
			}
			for (String record : RECORDS.getOrDefault(definition, List.of(definition))) {
				checked++;
				Class<?> type = nested(record);
				if (type == null || !type.isRecord()) {
					missing.add(definition + " -> no record " + record);
				}
				else if (!hasMeta(type)) {
					missing.add(definition + " -> " + record + " has no _meta component");
				}
			}
		}

		assertThat(missing).as("schema objects with _meta whose Java record lacks it").isEmpty();
		assertThat(checked).as("records checked").isGreaterThan(100);
	}

	@Test
	void everyExclusionIsStillAnObjectWithMeta() throws Exception {
		assertThat(definitionsWithMeta().keySet()).containsAll(NOT_MODELLED_AS_A_RECORD.keySet())
			.containsAll(RECORDS.keySet());
	}

	@Test
	void metaRoundTripsOnARecordThatGainedIt() throws Exception {
		AcpJsonMapper mapper = AcpJsonMapper.createDefault();
		String json = "{\"sessionId\":\"s\",\"path\":\"/a\",\"line\":3,\"_meta\":{\"trace\":\"t\"}}";

		var request = mapper.readValue(json, AcpSchema.ReadTextFileRequest.class);

		assertThat(request.meta()).isEqualTo(Map.of("trace", "t"));
		assertThat(mapper.writeValueAsString(request)).isEqualTo(json);
		assertThat(new AcpSchema.ReadTextFileRequest("s", "/a", 3, null).meta()).isNull();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Map<String, Object>> definitionsWithMeta() throws Exception {
		String text;
		try (InputStream in = MetaCoverageTest.class.getResourceAsStream("/schema/v1/schema.json")) {
			assertThat(in).as("schema copy on the test classpath").isNotNull();
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		Map<String, Object> schema = AcpJsonMapper.createDefault().readValue(text, new TypeRef<Map<String, Object>>() {
		});
		Map<String, Map<String, Object>> definitions = (Map<String, Map<String, Object>>) schema.get("$defs");
		Map<String, Map<String, Object>> withMeta = new java.util.TreeMap<>();
		definitions.forEach((name, definition) -> {
			Object properties = definition.get("properties");
			if (properties instanceof Map<?, ?> map && map.containsKey("_meta")) {
				withMeta.put(name, definition);
			}
		});
		return withMeta;
	}

	private static Class<?> nested(String simpleName) {
		return Arrays.stream(AcpSchema.class.getDeclaredClasses())
			.filter(c -> c.getSimpleName().equals(simpleName))
			.findFirst()
			.orElse(null);
	}

	private static boolean hasMeta(Class<?> record) throws NoSuchFieldException {
		for (RecordComponent component : record.getRecordComponents()) {
			// JsonProperty does not target record components; it lands on the field
			JsonProperty property = record.getDeclaredField(component.getName()).getAnnotation(JsonProperty.class);
			if (property != null && "_meta".equals(property.value()) && Map.class.equals(component.getType())) {
				return true;
			}
		}
		return false;
	}

}
