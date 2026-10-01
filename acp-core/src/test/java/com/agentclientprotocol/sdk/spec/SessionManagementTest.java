/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.JsonTree;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for session management types: session/load, session/set_mode, and session modes.
 *
 * @author Mark Pollack
 */
class SessionManagementTest {

	private final AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();


	// ---------------------------
	// Helper Methods
	// ---------------------------

	private String loadGolden(String name) throws IOException {
		String path = "/golden/" + name;
		try (InputStream is = getClass().getResourceAsStream(path)) {
			if (is == null) {
				throw new IOException("Golden file not found: " + path);
			}
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	// ---------------------------
	// LoadSessionRequest Tests
	// ---------------------------

	@Test
	void loadSessionRequestDeserialization() throws IOException {
		String json = loadGolden("session-load-request.json");

		AcpSchema.LoadSessionRequest request = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.LoadSessionRequest>() {
				});

		assertThat(request.sessionId()).isEqualTo("sess_789xyz");
		assertThat(request.cwd()).isEqualTo("/home/user/project");
		assertThat(request.mcpServers()).hasSize(1);
		assertThat(request.mcpServers().get(0)).isInstanceOf(AcpSchema.McpServerStdio.class);

		AcpSchema.McpServerStdio server = (AcpSchema.McpServerStdio) request.mcpServers().get(0);
		assertThat(server.name()).isEqualTo("filesystem");
		assertThat(server.command()).isEqualTo("/path/to/mcp-server");
	}

	@Test
	void loadSessionRequestRoundTrip() throws IOException {
		AcpSchema.LoadSessionRequest original = new AcpSchema.LoadSessionRequest("sess_test123", "/workspace",
				List.of(new AcpSchema.McpServerStdio("fs", "/bin/mcp", List.of(), List.of())));

		String json = jsonMapper.writeValueAsString(original);
		AcpSchema.LoadSessionRequest result = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.LoadSessionRequest>() {
				});

		assertThat(result.sessionId()).isEqualTo(original.sessionId());
		assertThat(result.cwd()).isEqualTo(original.cwd());
		assertThat(result.mcpServers()).hasSize(1);
	}

	// ---------------------------
	// LoadSessionResponse Tests
	// ---------------------------

	@Test
	void loadSessionResponseWithModesDeserialization() throws IOException {
		String json = loadGolden("session-load-response.json");

		AcpSchema.LoadSessionResponse response = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.LoadSessionResponse>() {
				});

		assertThat(response.modes()).isNotNull();
		assertThat(response.modes().currentModeId()).isEqualTo("ask");
		assertThat(response.modes().availableModes()).hasSize(3);

		// Verify first mode
		AcpSchema.SessionMode askMode = response.modes().availableModes().get(0);
		assertThat(askMode.id()).isEqualTo("ask");
		assertThat(askMode.name()).isEqualTo("Ask");
		assertThat(askMode.description()).isEqualTo("Request permission before making any changes");

		// Verify other modes exist
		assertThat(response.modes().availableModes().get(1).id()).isEqualTo("architect");
		assertThat(response.modes().availableModes().get(2).id()).isEqualTo("code");
	}

	@Test
	void loadSessionResponseRoundTrip() throws IOException {
		List<AcpSchema.SessionMode> modes = List.of(new AcpSchema.SessionMode("ask", "Ask", "Ask for permission"),
				new AcpSchema.SessionMode("code", "Code", "Full access"));

		AcpSchema.LoadSessionResponse original = new AcpSchema.LoadSessionResponse(
				new AcpSchema.SessionModeState("ask", modes), null);

		String json = jsonMapper.writeValueAsString(original);
		AcpSchema.LoadSessionResponse result = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.LoadSessionResponse>() {
				});

		assertThat(result.modes().currentModeId()).isEqualTo("ask");
		assertThat(result.modes().availableModes()).hasSize(2);
	}

	/** configOptions as the v1.9.1 schema puts it on new, load and resume responses. */
	private static final String CONFIG_OPTIONS = """
			"configOptions":[{"type":"select","id":"model","name":"Model","category":"model",\
			"currentValue":"fast","options":[{"value":"fast","name":"Fast"}]},\
			{"type":"boolean","id":"web","name":"Web search","currentValue":false}]""";

	@Test
	void sessionResponsesReadAndWriteConfigOptions() throws IOException {
		var map = new TypeRef<java.util.Map<String, Object>>() {
		};
		String created = "{\"sessionId\":\"s1\"," + CONFIG_OPTIONS + "}";
		String loaded = "{" + CONFIG_OPTIONS + "}";

		var newResponse = jsonMapper.readValue(created, AcpSchema.NewSessionResponse.class);
		var loadResponse = jsonMapper.readValue(loaded, AcpSchema.LoadSessionResponse.class);
		var resumeResponse = jsonMapper.readValue(loaded, AcpSchema.ResumeSessionResponse.class);

		var expected = List.of(
				new AcpSchema.SessionConfigSelect("select", "model", "Model", null, "model", "fast",
						List.of(new AcpSchema.SessionConfigSelectOption("fast", "Fast")), null),
				new AcpSchema.SessionConfigBoolean("web", "Web search", false));
		assertThat(newResponse.configOptions()).isEqualTo(expected);
		assertThat(loadResponse.configOptions()).isEqualTo(expected);
		assertThat(resumeResponse.configOptions()).isEqualTo(expected);
		assertThat(jsonMapper.readValue(jsonMapper.writeValueAsString(newResponse), map))
			.isEqualTo(jsonMapper.readValue(created, map));
		assertThat(jsonMapper.readValue(jsonMapper.writeValueAsString(loadResponse), map))
			.isEqualTo(jsonMapper.readValue(loaded, map));
		assertThat(jsonMapper.readValue(jsonMapper.writeValueAsString(resumeResponse), map))
			.isEqualTo(jsonMapper.readValue(loaded, map));
		assertThat(new AcpSchema.LoadSessionResponse(null).configOptions()).isNull();
		assertThat(new AcpSchema.ResumeSessionResponse(null).configOptions()).isNull();
		assertThat(new AcpSchema.NewSessionResponse("s", null).configOptions()).isNull();
	}

	// ---------------------------
	// SetSessionModeRequest Tests
	// ---------------------------

	@Test
	void setSessionModeRequestDeserialization() throws IOException {
		String json = loadGolden("session-set-mode-request.json");

		AcpSchema.SetSessionModeRequest request = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.SetSessionModeRequest>() {
				});

		assertThat(request.sessionId()).isEqualTo("sess_abc123def456");
		assertThat(request.modeId()).isEqualTo("code");
	}

	@Test
	void setSessionModeRequestRoundTrip() throws IOException {
		AcpSchema.SetSessionModeRequest original = new AcpSchema.SetSessionModeRequest("sess_test", "architect");

		String json = jsonMapper.writeValueAsString(original);
		AcpSchema.SetSessionModeRequest result = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.SetSessionModeRequest>() {
				});

		assertThat(result.sessionId()).isEqualTo(original.sessionId());
		assertThat(result.modeId()).isEqualTo(original.modeId());
	}

	// ---------------------------
	// NewSessionResponse with Modes Tests
	// ---------------------------

	@Test
	void newSessionResponseWithModesDeserialization() throws IOException {
		String json = loadGolden("session-new-response-with-modes.json");

		AcpSchema.NewSessionResponse response = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.NewSessionResponse>() {
				});

		assertThat(response.sessionId()).isEqualTo("sess_abc123def456");
		assertThat(response.modes()).isNotNull();
		assertThat(response.modes().currentModeId()).isEqualTo("ask");
		assertThat(response.modes().availableModes()).hasSize(3);
	}

	@Test
	void newSessionResponseWithModesRoundTrip() throws IOException {
		List<AcpSchema.SessionMode> modes = List.of(new AcpSchema.SessionMode("ask", "Ask", null),
				new AcpSchema.SessionMode("code", "Code", "Write code"));

		AcpSchema.NewSessionResponse original = new AcpSchema.NewSessionResponse("sess_new123",
				new AcpSchema.SessionModeState("ask", modes), null);

		String json = jsonMapper.writeValueAsString(original);
		AcpSchema.NewSessionResponse result = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.NewSessionResponse>() {
				});

		assertThat(result.sessionId()).isEqualTo("sess_new123");
		assertThat(result.modes().currentModeId()).isEqualTo("ask");
		assertThat(result.modes().availableModes()).hasSize(2);
	}

	// ---------------------------
	// SessionModeState Tests
	// ---------------------------

	@Test
	void sessionModeStateWithAllFields() throws IOException {
		List<AcpSchema.SessionMode> modes = List.of(
				new AcpSchema.SessionMode("ask", "Ask", "Request permission before making any changes"),
				new AcpSchema.SessionMode("architect", "Architect",
						"Design and plan software systems without implementation"),
				new AcpSchema.SessionMode("code", "Code", "Write and modify code with full tool access"));

		AcpSchema.SessionModeState modeState = new AcpSchema.SessionModeState("architect", modes);

		String json = jsonMapper.writeValueAsString(modeState);
		JsonTree node = JsonTree.parse(jsonMapper, json);

		assertThat(node.get("currentModeId").asText()).isEqualTo("architect");
		assertThat(node.get("availableModes").isArray()).isTrue();
		assertThat(node.get("availableModes").size()).isEqualTo(3);
	}

	@Test
	void sessionModeWithNullDescription() throws IOException {
		AcpSchema.SessionMode mode = new AcpSchema.SessionMode("custom", "Custom Mode", null);

		String json = jsonMapper.writeValueAsString(mode);
		JsonTree node = JsonTree.parse(jsonMapper, json);

		assertThat(node.get("id").asText()).isEqualTo("custom");
		assertThat(node.get("name").asText()).isEqualTo("Custom Mode");
		// Null description should not be serialized (NON_NULL)
		assertThat(node.has("description")).isFalse();
	}

	// ---------------------------
	// CurrentModeUpdate Tests
	// ---------------------------

	@Test
	void currentModeUpdateDeserialization() throws IOException {
		// Already tested in SessionUpdateDeserializationTest, but verify structure
		String json = loadGolden("session-update-current-mode.json");

		AcpSchema.SessionUpdate update = jsonMapper.readValue(json, new TypeRef<AcpSchema.SessionUpdate>() {
		});

		assertThat(update).isInstanceOf(AcpSchema.CurrentModeUpdate.class);
		AcpSchema.CurrentModeUpdate modeUpdate = (AcpSchema.CurrentModeUpdate) update;
		assertThat(modeUpdate.currentModeId()).isEqualTo("architect");
	}

	@Test
	void currentModeUpdateRoundTrip() throws IOException {
		AcpSchema.CurrentModeUpdate original = new AcpSchema.CurrentModeUpdate("current_mode_update", "code");

		String json = jsonMapper.writeValueAsString(original);
		AcpSchema.SessionUpdate result = jsonMapper.readValue(json, new TypeRef<AcpSchema.SessionUpdate>() {
		});

		assertThat(result).isInstanceOf(AcpSchema.CurrentModeUpdate.class);
		AcpSchema.CurrentModeUpdate modeUpdate = (AcpSchema.CurrentModeUpdate) result;
		assertThat(modeUpdate.sessionUpdate()).isEqualTo("current_mode_update");
		assertThat(modeUpdate.currentModeId()).isEqualTo("code");
	}

	// ---------------------------
	// Edge Cases
	// ---------------------------

	@Test
	void loadSessionResponseWithNullModes() throws IOException {
		AcpSchema.LoadSessionResponse response = new AcpSchema.LoadSessionResponse(null, null);

		String json = jsonMapper.writeValueAsString(response);
		JsonTree node = JsonTree.parse(jsonMapper, json);

		// Null fields should not be serialized
		assertThat(node.has("modes")).isFalse();
		assertThat(node.has("_meta")).isFalse();
	}

	@Test
	void loadSessionRequestWithEmptyMcpServers() throws IOException {
		AcpSchema.LoadSessionRequest request = new AcpSchema.LoadSessionRequest("sess_test", "/project", List.of());

		String json = jsonMapper.writeValueAsString(request);
		AcpSchema.LoadSessionRequest result = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.LoadSessionRequest>() {
				});

		assertThat(result.mcpServers()).isEmpty();
	}

}
