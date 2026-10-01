/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema.ElicitationCapabilities;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The elicitation records against the stable ACP v1.9.1 schema and the examples in
 * {@code docs/protocol/v1/elicitation.mdx}.
 */
class ElicitationWireTest {

	private final AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();

	private <T> T read(String json, TypeRef<T> type) throws IOException {
		return jsonMapper.readValue(json, type);
	}

	@Test
	void specFormExampleParses() throws IOException {
		AcpSchema.CreateElicitationRequest request = read("""
				{"sessionId":"sess_abc123","mode":"form","message":"How should I approach this refactoring?",
				 "requestedSchema":{"type":"object","properties":{"strategy":{"type":"string",
				 "enum":["conservative","balanced","aggressive"]}},"required":["strategy"]}}
				""", new TypeRef<>() {
		});

		assertThat(request.mode()).isEqualTo(AcpSchema.CreateElicitationRequest.MODE_FORM);
		assertThat(request.sessionId()).isEqualTo("sess_abc123");
		assertThat(request.requestedSchema()).isNotNull();
		assertThat(request.requestedSchema().required()).containsExactly("strategy");
		assertThat(request.requestedSchema().properties()).containsKey("strategy");
		AcpSchema.StringPropertySchema strategy = (AcpSchema.StringPropertySchema) request.requestedSchema()
			.properties()
			.get("strategy");
		assertThat(strategy.enumValues()).containsExactly("conservative", "balanced", "aggressive");
	}

	@Test
	void specUrlExampleParsesWithARequestScope() throws IOException {
		AcpSchema.CreateElicitationRequest request = read("""
				{"requestId":12,"mode":"url","elicitationId":"github-oauth-001",
				 "url":"https://agent.example.com/connect?elicitationId=github-oauth-001",
				 "message":"Please authorize access to your repositories."}
				""", new TypeRef<>() {
		});

		assertThat(request.mode()).isEqualTo(AcpSchema.CreateElicitationRequest.MODE_URL);
		assertThat(request.requestId()).isEqualTo(12);
		assertThat(request.sessionId()).isNull();
		assertThat(request.elicitationId()).isEqualTo("github-oauth-001");
	}

	@Test
	void capabilityModesAreTypedObjects() throws IOException {
		ElicitationCapabilities both = read("{\"form\":{},\"url\":{\"_meta\":{\"k\":1}}}", new TypeRef<>() {
		});
		assertThat(both.form()).isEqualTo(new AcpSchema.ElicitationFormCapabilities());
		assertThat(both.url()).isNotNull();
		assertThat(both.url().meta()).containsEntry("k", 1);

		assertThat(jsonMapper.writeValueAsString(ElicitationCapabilities.formOnly())).isEqualTo("{\"form\":{}}");
		assertThat(jsonMapper.writeValueAsString(ElicitationCapabilities.urlOnly())).isEqualTo("{\"url\":{}}");
		assertThat(jsonMapper.writeValueAsString(ElicitationCapabilities.formAndUrl()))
			.isEqualTo("{\"form\":{},\"url\":{}}");
	}

	@Test
	void anEmptyOrNullCapabilityAdvertisesNoMode() throws IOException {
		ElicitationCapabilities empty = read("{}", new TypeRef<>() {
		});
		ElicitationCapabilities nulls = read("{\"form\":null,\"url\":null}", new TypeRef<>() {
		});
		for (ElicitationCapabilities caps : List.of(empty, nulls)) {
			assertThat(caps.form()).isNull();
			assertThat(caps.url()).isNull();
		}
	}

	@Test
	void schemaPropertiesItemsAndOptionsCarryMeta() throws IOException {
		AcpSchema.ElicitationSchema schema = read("""
				{"type":"object","title":"T","description":"D","_meta":{"s":1},
				 "properties":{
				  "a":{"type":"string","title":"A","oneOf":[{"const":"x","title":"X","description":"the x",
				       "_meta":{"o":1}}],"_meta":{"p":1}},
				  "b":{"type":"number","_meta":{"p":2}},
				  "c":{"type":"integer","_meta":{"p":3}},
				  "d":{"type":"boolean","_meta":{"p":4}},
				  "e":{"type":"array","items":{"type":"string","enum":["u"],"_meta":{"i":1}},"_meta":{"p":5}},
				  "f":{"type":"array","items":{"anyOf":[{"const":"v","title":"V"}],"_meta":{"i":2}}}
				 }}
				""", new TypeRef<>() {
		});

		assertThat(schema.meta()).containsEntry("s", 1);
		Map<String, AcpSchema.ElicitationPropertySchema> p = schema.properties();
		AcpSchema.StringPropertySchema a = (AcpSchema.StringPropertySchema) p.get("a");
		assertThat(a.meta()).containsEntry("p", 1);
		assertThat(a.oneOf()).containsExactly(new AcpSchema.EnumOption("x", "X", "the x", Map.of("o", 1)));
		assertThat(((AcpSchema.NumberPropertySchema) p.get("b")).meta()).containsEntry("p", 2);
		assertThat(((AcpSchema.IntegerPropertySchema) p.get("c")).meta()).containsEntry("p", 3);
		assertThat(((AcpSchema.BooleanPropertySchema) p.get("d")).meta()).containsEntry("p", 4);
		AcpSchema.MultiSelectPropertySchema e = (AcpSchema.MultiSelectPropertySchema) p.get("e");
		assertThat(e.meta()).containsEntry("p", 5);
		assertThat(((AcpSchema.UntitledMultiSelectItems) e.items()).meta()).containsEntry("i", 1);
		AcpSchema.MultiSelectPropertySchema f = (AcpSchema.MultiSelectPropertySchema) p.get("f");
		assertThat(((AcpSchema.TitledMultiSelectItems) f.items()).meta()).containsEntry("i", 2);

		String json = jsonMapper.writeValueAsString(schema);
		assertThat(read(json, new TypeRef<AcpSchema.ElicitationSchema>() {
		})).isEqualTo(schema);
	}

	@Test
	void responsesFollowTheSchemasThreeActions() throws IOException {
		AcpSchema.CreateElicitationResponse accept = read("{\"action\":\"accept\"}", new TypeRef<>() {
		});
		assertThat(accept).isEqualTo(AcpSchema.CreateElicitationResponse.accept());
		assertThat(jsonMapper.writeValueAsString(AcpSchema.CreateElicitationResponse.accept()))
			.isEqualTo("{\"action\":\"accept\"}");

		AcpSchema.CreateElicitationResponse withContent = read(
				"{\"action\":\"accept\",\"content\":{\"s\":\"x\",\"i\":3,\"n\":1.5,\"b\":true,\"l\":[\"a\"]}}",
				new TypeRef<>() {
				});
		assertThat(withContent.content()).containsEntry("s", "x")
			.containsEntry("i", 3)
			.containsEntry("n", 1.5)
			.containsEntry("b", true)
			.containsEntry("l", List.of("a"));

		assertThat(jsonMapper.writeValueAsString(AcpSchema.CreateElicitationResponse.decline()))
			.isEqualTo("{\"action\":\"decline\"}");
		assertThat(jsonMapper.writeValueAsString(AcpSchema.CreateElicitationResponse.cancel()))
			.isEqualTo("{\"action\":\"cancel\"}");
	}

	@Test
	void completeNotificationCarriesTheElicitationId() throws IOException {
		AcpSchema.CompleteElicitationNotification notification = read("{\"elicitationId\":\"github-oauth-001\"}",
				new TypeRef<>() {
				});
		assertThat(notification).isEqualTo(new AcpSchema.CompleteElicitationNotification("github-oauth-001"));
		assertThat(jsonMapper.writeValueAsString(notification)).isEqualTo("{\"elicitationId\":\"github-oauth-001\"}");
	}

}
