/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.AcpTestFixtures;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests JSON serialization and deserialization of ACP schema types.
 *
 * <p>
 * Verifies that all ACP protocol types can be correctly serialized to JSON and
 * deserialized back to Java objects with all fields preserved.
 * </p>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
class AcpSchemaSerializationTest {

	private final AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();

	// ---------------------------
	// Request/Response Serialization
	// ---------------------------

	@Test
	void initializeRequestSerialization() throws IOException {
		AcpSchema.InitializeRequest request = AcpTestFixtures.createInitializeRequest();

		String json = jsonMapper.writeValueAsString(request);
		AcpSchema.InitializeRequest deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.InitializeRequest>() {
				});

		assertThat(deserialized.protocolVersion()).isEqualTo(1);
		assertThat(deserialized.clientCapabilities()).isNotNull();
	}

	@Test
	void initializeResponseSerialization() throws IOException {
		AcpSchema.InitializeResponse response = AcpTestFixtures.createInitializeResponse();

		String json = jsonMapper.writeValueAsString(response);
		AcpSchema.InitializeResponse deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.InitializeResponse>() {
				});

		assertThat(deserialized.protocolVersion()).isEqualTo(1);
		assertThat(deserialized.agentCapabilities()).isNotNull();
		assertThat(deserialized.authMethods()).isEmpty();
	}

	@Test
	void newSessionRequestSerialization() throws IOException {
		AcpSchema.NewSessionRequest request = AcpTestFixtures.createNewSessionRequest();

		String json = jsonMapper.writeValueAsString(request);
		AcpSchema.NewSessionRequest deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.NewSessionRequest>() {
				});

		assertThat(deserialized.cwd()).isEqualTo("/test/workspace");
		assertThat(deserialized.mcpServers()).isEmpty();
	}

	@Test
	@SuppressWarnings("removal") // asserts on the deprecated-for-removal models field
	void newSessionResponseSerialization() throws IOException {
		AcpSchema.NewSessionResponse response = AcpTestFixtures.createNewSessionResponse();

		String json = jsonMapper.writeValueAsString(response);
		AcpSchema.NewSessionResponse deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.NewSessionResponse>() {
				});

		assertThat(deserialized.sessionId()).isEqualTo("test-session-id");
		assertThat(deserialized.modes()).isNotNull();
		assertThat(deserialized.models()).isNotNull();
	}

	@Test
	void promptRequestSerialization() throws IOException {
		AcpSchema.PromptRequest request = AcpTestFixtures.createPromptRequest();

		String json = jsonMapper.writeValueAsString(request);
		AcpSchema.PromptRequest deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.PromptRequest>() {
		});

		assertThat(deserialized.sessionId()).isEqualTo("test-session-id");
		assertThat(deserialized.prompt()).hasSize(1);
	}

	@Test
	void promptResponseSerialization() throws IOException {
		AcpSchema.PromptResponse response = AcpTestFixtures.createPromptResponse();

		String json = jsonMapper.writeValueAsString(response);
		AcpSchema.PromptResponse deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.PromptResponse>() {
		});

		assertThat(deserialized.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
	}

	// ---------------------------
	// Capabilities Serialization
	// ---------------------------

	@Test
	void clientCapabilitiesSerialization() throws IOException {
		AcpSchema.ClientCapabilities capabilities = AcpTestFixtures.createClientCapabilitiesWithFs();

		String json = jsonMapper.writeValueAsString(capabilities);
		AcpSchema.ClientCapabilities deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.ClientCapabilities>() {
				});

		assertThat(deserialized.fs()).isNotNull();
		assertThat(deserialized.fs().readTextFile()).isTrue();
		assertThat(deserialized.fs().writeTextFile()).isTrue();
	}

	@Test
	void agentCapabilitiesSerialization() throws IOException {
		AcpSchema.AgentCapabilities capabilities = AcpTestFixtures.createAgentCapabilitiesWithLoadSession();

		String json = jsonMapper.writeValueAsString(capabilities);
		AcpSchema.AgentCapabilities deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.AgentCapabilities>() {
				});

		assertThat(deserialized.loadSession()).isTrue();
		assertThat(deserialized.mcpCapabilities()).isNotNull();
		assertThat(deserialized.promptCapabilities()).isNotNull();
	}

	// ---------------------------
	// Content Block Serialization
	// ---------------------------

	@Test
	void textContentSerialization() throws IOException {
		AcpSchema.TextContent content = AcpTestFixtures.createTextContent("Hello, world!");

		String json = jsonMapper.writeValueAsString(content);
		AcpSchema.TextContent deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.TextContent>() {
		});

		assertThat(deserialized.text()).isEqualTo("Hello, world!");
		assertThat(deserialized.type()).isEqualTo("text");
	}

	@Test
	void imageContentSerialization() throws IOException {
		AcpSchema.ImageContent content = AcpTestFixtures.createImageContent();

		String json = jsonMapper.writeValueAsString(content);
		AcpSchema.ImageContent deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.ImageContent>() {
		});

		assertThat(deserialized.type()).isEqualTo("image");
		assertThat(deserialized.data()).isEqualTo("base64-encoded-data");
		assertThat(deserialized.mimeType()).isEqualTo("image/png");
	}

	@Test
	void audioContentSerialization() throws IOException {
		AcpSchema.AudioContent content = AcpTestFixtures.createAudioContent();

		String json = jsonMapper.writeValueAsString(content);
		AcpSchema.AudioContent deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.AudioContent>() {
		});

		assertThat(deserialized.type()).isEqualTo("audio");
		assertThat(deserialized.data()).isEqualTo("base64-encoded-data");
		assertThat(deserialized.mimeType()).isEqualTo("audio/wav");
	}

	// ---------------------------
	// JSON-RPC Message Serialization
	// ---------------------------

	@Test
	void jsonRpcRequestSerialization() throws IOException {
		AcpSchema.JSONRPCRequest request = AcpTestFixtures.createJsonRpcRequest("test/method", 1,
				Collections.singletonMap("key", "value"));

		String json = jsonMapper.writeValueAsString(request);
		AcpSchema.JSONRPCRequest deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.JSONRPCRequest>() {
		});

		assertThat(deserialized.jsonrpc()).isEqualTo("2.0");
		assertThat(deserialized.id()).isEqualTo(1);
		assertThat(deserialized.method()).isEqualTo("test/method");
		assertThat(deserialized.params()).isNotNull();
	}

	@Test
	void jsonRpcNotificationSerialization() throws IOException {
		AcpSchema.JSONRPCNotification notification = AcpTestFixtures.createJsonRpcNotification("test/notification",
				Collections.singletonMap("key", "value"));

		String json = jsonMapper.writeValueAsString(notification);
		AcpSchema.JSONRPCNotification deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.JSONRPCNotification>() {
				});

		assertThat(deserialized.jsonrpc()).isEqualTo("2.0");
		assertThat(deserialized.method()).isEqualTo("test/notification");
		assertThat(deserialized.params()).isNotNull();
	}

	@Test
	void jsonRpcResponseSerialization() throws IOException {
		AcpSchema.JSONRPCResponse response = AcpTestFixtures.createJsonRpcResponse(1,
				Collections.singletonMap("result", "success"));

		String json = jsonMapper.writeValueAsString(response);
		AcpSchema.JSONRPCResponse deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.JSONRPCResponse>() {
		});

		assertThat(deserialized.jsonrpc()).isEqualTo("2.0");
		assertThat(deserialized.id()).isEqualTo(1);
		assertThat(deserialized.result()).isNotNull();
		assertThat(deserialized.error()).isNull();
	}

	@Test
	void jsonRpcErrorResponseSerialization() throws IOException {
		AcpSchema.JSONRPCResponse response = AcpTestFixtures.createJsonRpcErrorResponse(1, -32600,
				"Invalid Request");

		String json = jsonMapper.writeValueAsString(response);
		AcpSchema.JSONRPCResponse deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.JSONRPCResponse>() {
		});

		assertThat(deserialized.jsonrpc()).isEqualTo("2.0");
		assertThat(deserialized.id()).isEqualTo(1);
		assertThat(deserialized.result()).isNull();
		assertThat(deserialized.error()).isNotNull();
		assertThat(deserialized.error().code()).isEqualTo(-32600);
		assertThat(deserialized.error().message()).isEqualTo("Invalid Request");
	}

	// ---------------------------
	// Session State Serialization
	// ---------------------------

	@Test
	void sessionModeStateSerialization() throws IOException {
		AcpSchema.SessionModeState state = AcpTestFixtures.createSessionModeState();

		String json = jsonMapper.writeValueAsString(state);
		AcpSchema.SessionModeState deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.SessionModeState>() {
				});

		assertThat(deserialized.currentModeId()).isEqualTo("code");
		assertThat(deserialized.availableModes()).hasSize(1);
		assertThat(deserialized.availableModes().get(0).id()).isEqualTo("code");
	}

	@Test
	@SuppressWarnings("removal") // exercises the deprecated-for-removal SessionModelState
	void sessionModelStateSerialization() throws IOException {
		AcpSchema.SessionModelState state = AcpTestFixtures.createSessionModelState();

		String json = jsonMapper.writeValueAsString(state);
		AcpSchema.SessionModelState deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.SessionModelState>() {
				});

		assertThat(deserialized.currentModelId()).isEqualTo("test-model");
		assertThat(deserialized.availableModels()).hasSize(1);
		assertThat(deserialized.availableModels().get(0).modelId()).isEqualTo("test-model");
	}

	// ---------------------------
	// _meta Field Serialization Tests
	// ---------------------------

	@Test
	void initializeRequestWithMetaSerialization() throws IOException {
		Map<String, Object> meta = Map.of("zed.dev/debugMode", true, "custom/version", "1.0.0");
		AcpSchema.InitializeRequest request = new AcpSchema.InitializeRequest(1,
				new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(true, true), true), null, meta);

		String json = jsonMapper.writeValueAsString(request);
		assertThat(json).contains("\"_meta\"");
		assertThat(json).contains("zed.dev/debugMode");

		AcpSchema.InitializeRequest deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.InitializeRequest>() {
				});

		assertThat(deserialized.meta()).isNotNull();
		assertThat(deserialized.meta()).containsKey("zed.dev/debugMode");
		assertThat(deserialized.meta().get("zed.dev/debugMode")).isEqualTo(true);
		assertThat(deserialized.meta().get("custom/version")).isEqualTo("1.0.0");
	}

	@Test
	void initializeRequestWithoutMetaOmitsField() throws IOException {
		AcpSchema.InitializeRequest request = new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities());

		String json = jsonMapper.writeValueAsString(request);
		assertThat(json).doesNotContain("\"_meta\"");

		AcpSchema.InitializeRequest deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.InitializeRequest>() {
				});
		assertThat(deserialized.meta()).isNull();
	}

	@Test
	void promptRequestWithMetaSerialization() throws IOException {
		Map<String, Object> meta = Map.of("zed.dev/debugMode", true);
		AcpSchema.PromptRequest request = new AcpSchema.PromptRequest("sess_123",
				List.of(new AcpSchema.TextContent("Hello")), meta);

		String json = jsonMapper.writeValueAsString(request);
		assertThat(json).contains("\"_meta\"");

		AcpSchema.PromptRequest deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.PromptRequest>() {
		});

		assertThat(deserialized.meta()).isNotNull();
		assertThat(deserialized.meta().get("zed.dev/debugMode")).isEqualTo(true);
	}

	@Test
	void agentCapabilitiesWithMetaSerialization() throws IOException {
		// Nested _meta object as shown in spec for advertising custom capabilities
		Map<String, Object> zedCapabilities = Map.of("workspace", true, "fileNotifications", true);
		Map<String, Object> meta = Map.of("zed.dev", zedCapabilities);
		AcpSchema.AgentCapabilities caps = new AcpSchema.AgentCapabilities(true, null,
				new AcpSchema.McpCapabilities(true, false),
				new AcpSchema.PromptCapabilities(false, true, true), meta);

		String json = jsonMapper.writeValueAsString(caps);
		assertThat(json).contains("\"_meta\"");
		assertThat(json).contains("zed.dev");

		AcpSchema.AgentCapabilities deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.AgentCapabilities>() {
				});

		assertThat(deserialized.meta()).isNotNull();
		@SuppressWarnings("unchecked")
		Map<String, Object> zedCaps = (Map<String, Object>) deserialized.meta().get("zed.dev");
		assertThat(zedCaps).containsEntry("workspace", true);
		assertThat(zedCaps).containsEntry("fileNotifications", true);
	}

	@Test
	void sessionUpdateWithMetaSerialization() throws IOException {
		Map<String, Object> meta = Map.of("custom/field", "value");
		AcpSchema.AgentMessageChunk update = new AcpSchema.AgentMessageChunk("agent_message_chunk",
				new AcpSchema.TextContent("Hello"), null, meta);

		String json = jsonMapper.writeValueAsString(update);
		assertThat(json).contains("\"_meta\"");

		AcpSchema.AgentMessageChunk deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.AgentMessageChunk>() {
				});

		assertThat(deserialized.meta()).isNotNull();
		assertThat(deserialized.meta().get("custom/field")).isEqualTo("value");
	}

	@Test
	void messageChunkWithMessageIdSerialization() throws IOException {
		AcpSchema.AgentMessageChunk update = new AcpSchema.AgentMessageChunk("agent_message_chunk",
				new AcpSchema.TextContent("Hello"), "msg-42");

		String json = jsonMapper.writeValueAsString(update);
		assertThat(json).contains("\"messageId\":\"msg-42\"");

		AcpSchema.AgentMessageChunk deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.AgentMessageChunk>() {
				});

		assertThat(deserialized.messageId()).isEqualTo("msg-42");
	}

	@Test
	void messageChunkWithoutMessageIdOmitsField() throws IOException {
		AcpSchema.AgentMessageChunk update = new AcpSchema.AgentMessageChunk("agent_message_chunk",
				new AcpSchema.TextContent("Hello"));

		String json = jsonMapper.writeValueAsString(update);
		assertThat(json).doesNotContain("messageId");
	}

	@Test
	void newSessionRequestWithAdditionalDirectoriesSerialization() throws IOException {
		AcpSchema.NewSessionRequest request = new AcpSchema.NewSessionRequest("/workspace", Collections.emptyList(),
				List.of("/extra/one", "/extra/two"));

		String json = jsonMapper.writeValueAsString(request);
		assertThat(json).contains("\"additionalDirectories\"");

		AcpSchema.NewSessionRequest deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.NewSessionRequest>() {
				});

		assertThat(deserialized.additionalDirectories()).containsExactly("/extra/one", "/extra/two");
	}

	@Test
	void sessionInfoWithAdditionalDirectoriesSerialization() throws IOException {
		AcpSchema.SessionInfo info = new AcpSchema.SessionInfo("session-1", "/workspace", null, null,
				List.of("/extra"), null);

		String json = jsonMapper.writeValueAsString(info);

		AcpSchema.SessionInfo deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.SessionInfo>() {
		});

		assertThat(deserialized.additionalDirectories()).containsExactly("/extra");
	}

	@Test
	void logoutRequestResponseSerialization() throws IOException {
		String reqJson = jsonMapper.writeValueAsString(new AcpSchema.LogoutRequest());
		AcpSchema.LogoutRequest req = jsonMapper.readValue(reqJson, new TypeRef<AcpSchema.LogoutRequest>() {
		});
		assertThat(req).isNotNull();

		String respJson = jsonMapper.writeValueAsString(new AcpSchema.LogoutResponse());
		AcpSchema.LogoutResponse resp = jsonMapper.readValue(respJson, new TypeRef<AcpSchema.LogoutResponse>() {
		});
		assertThat(resp).isNotNull();
	}

	@Test
	void deleteSessionRequestResponseSerialization() throws IOException {
		String reqJson = jsonMapper.writeValueAsString(new AcpSchema.DeleteSessionRequest("session-9"));
		assertThat(reqJson).contains("\"sessionId\":\"session-9\"");
		AcpSchema.DeleteSessionRequest req = jsonMapper.readValue(reqJson,
				new TypeRef<AcpSchema.DeleteSessionRequest>() {
				});
		assertThat(req.sessionId()).isEqualTo("session-9");

		String respJson = jsonMapper.writeValueAsString(new AcpSchema.DeleteSessionResponse());
		AcpSchema.DeleteSessionResponse resp = jsonMapper.readValue(respJson,
				new TypeRef<AcpSchema.DeleteSessionResponse>() {
				});
		assertThat(resp).isNotNull();
	}

	// ---------------------------
	// Elicitation Serialization
	// ---------------------------

	@Test
	void elicitationSchemaRoundTrip() throws IOException {
		var schema = new AcpSchema.ElicitationSchema(
				Map.of("name", AcpSchema.StringPropertySchema.text("Your Name"),
						"strategy", AcpSchema.StringPropertySchema.singleSelect("Strategy",
								List.of(new AcpSchema.EnumOption("fast", "Fast"),
										new AcpSchema.EnumOption("safe", "Safe")))),
				List.of("name"));

		String json = jsonMapper.writeValueAsString(schema);
		assertThat(json).contains("\"type\":\"object\"");
		assertThat(json).contains("\"required\"");

		AcpSchema.ElicitationSchema deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.ElicitationSchema>() {
				});

		assertThat(deserialized.type()).isEqualTo("object");
		assertThat(deserialized.properties()).hasSize(2);
		assertThat(deserialized.required()).containsExactly("name");
		assertThat(deserialized.properties().get("name")).isInstanceOf(AcpSchema.StringPropertySchema.class);
		assertThat(deserialized.properties().get("strategy")).isInstanceOf(AcpSchema.StringPropertySchema.class);

		AcpSchema.StringPropertySchema strategy = (AcpSchema.StringPropertySchema) deserialized.properties()
				.get("strategy");
		assertThat(strategy.oneOf()).hasSize(2);
		assertThat(strategy.oneOf().get(0).constValue()).isEqualTo("fast");
		assertThat(strategy.oneOf().get(0).title()).isEqualTo("Fast");
	}

	@Test
	void elicitationPropertySchemaPolymorphism() throws IOException {
		// All 5 property types should round-trip correctly
		Map<String, AcpSchema.ElicitationPropertySchema> props = Map.of(
				"text", new AcpSchema.StringPropertySchema("string", "Name", null, "default", null, null,
						null, null, null, null),
				"count", new AcpSchema.IntegerPropertySchema("integer", "Count", null, 5L, 1L, 100L),
				"ratio", new AcpSchema.NumberPropertySchema("number", "Ratio", null, 0.5, 0.0, 1.0),
				"agree", new AcpSchema.BooleanPropertySchema("boolean", "Agree?", null, false),
				"tags", new AcpSchema.MultiSelectPropertySchema("array", "Tags", null, null,
						new AcpSchema.UntitledMultiSelectItems("string", List.of("a", "b", "c")),
						null, null));

		var schema = new AcpSchema.ElicitationSchema("object", props, null, null, null);
		String json = jsonMapper.writeValueAsString(schema);

		AcpSchema.ElicitationSchema deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.ElicitationSchema>() {
				});

		assertThat(deserialized.properties().get("text")).isInstanceOf(AcpSchema.StringPropertySchema.class);
		assertThat(deserialized.properties().get("count")).isInstanceOf(AcpSchema.IntegerPropertySchema.class);
		assertThat(deserialized.properties().get("ratio")).isInstanceOf(AcpSchema.NumberPropertySchema.class);
		assertThat(deserialized.properties().get("agree")).isInstanceOf(AcpSchema.BooleanPropertySchema.class);
		assertThat(deserialized.properties().get("tags")).isInstanceOf(AcpSchema.MultiSelectPropertySchema.class);
	}

	@Test
	void createElicitationRequestFormMode() throws IOException {
		var request = AcpSchema.CreateElicitationRequest.form("sess-1", "Pick a strategy",
				new AcpSchema.ElicitationSchema(Map.of(), null));

		String json = jsonMapper.writeValueAsString(request);
		assertThat(json).contains("\"mode\":\"form\"");
		assertThat(json).contains("\"sessionId\":\"sess-1\"");
		assertThat(json).contains("\"requestedSchema\"");

		AcpSchema.CreateElicitationRequest deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.CreateElicitationRequest>() {
				});

		assertThat(deserialized.mode()).isEqualTo("form");
		assertThat(deserialized.sessionId()).isEqualTo("sess-1");
		assertThat(deserialized.requestedSchema()).isNotNull();
		assertThat(deserialized.url()).isNull();
	}

	@Test
	void createElicitationResponseAccept() throws IOException {
		var response = AcpSchema.CreateElicitationResponse.accept(
				Map.of("strategy", "fast", "count", 3));

		String json = jsonMapper.writeValueAsString(response);

		AcpSchema.CreateElicitationResponse deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.CreateElicitationResponse>() {
				});

		assertThat(deserialized.action()).isEqualTo(AcpSchema.ElicitationAction.ACCEPT);
		assertThat(deserialized.content()).containsEntry("strategy", "fast");
		assertThat(deserialized.content()).containsEntry("count", 3);
	}

	@Test
	void createElicitationResponseDecline() throws IOException {
		var response = AcpSchema.CreateElicitationResponse.decline();
		String json = jsonMapper.writeValueAsString(response);

		AcpSchema.CreateElicitationResponse deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.CreateElicitationResponse>() {
				});

		assertThat(deserialized.action()).isEqualTo(AcpSchema.ElicitationAction.DECLINE);
		assertThat(deserialized.content()).isNull();
	}

	@Test
	void forkSessionRequestRoundTrip() throws IOException {
		var request = new AcpSchema.ForkSessionRequest("sess-parent", "/workspace", List.of());

		String json = jsonMapper.writeValueAsString(request);
		AcpSchema.ForkSessionRequest deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.ForkSessionRequest>() {});

		assertThat(deserialized.sessionId()).isEqualTo("sess-parent");
		assertThat(deserialized.cwd()).isEqualTo("/workspace");
	}

	@Test
	void sessionConfigOptionPolymorphism() throws IOException {
		AcpSchema.SessionConfigSelect select = new AcpSchema.SessionConfigSelect(
				"mode", "Mode", "code",
				List.of(new AcpSchema.SessionConfigSelectOption("ask", "Ask"),
						new AcpSchema.SessionConfigSelectOption("code", "Code")));

		String json = jsonMapper.writeValueAsString(select);
		assertThat(json).contains("\"type\":\"select\"");

		AcpSchema.SessionConfigOption deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.SessionConfigOption>() {});

		assertThat(deserialized).isInstanceOf(AcpSchema.SessionConfigSelect.class);
		AcpSchema.SessionConfigSelect asSelect = (AcpSchema.SessionConfigSelect) deserialized;
		assertThat(asSelect.currentValue()).isEqualTo("code");
		assertThat(asSelect.options()).hasSize(2);
	}

	@Test
	void sessionConfigBooleanRoundTrip() throws IOException {
		AcpSchema.SessionConfigBoolean toggle = new AcpSchema.SessionConfigBoolean(
				"thinking", "Enable Thinking", true);

		String json = jsonMapper.writeValueAsString(toggle);
		AcpSchema.SessionConfigOption deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.SessionConfigOption>() {});

		assertThat(deserialized).isInstanceOf(AcpSchema.SessionConfigBoolean.class);
		assertThat(((AcpSchema.SessionConfigBoolean) deserialized).currentValue()).isTrue();
	}

	@Test
	void setSessionConfigOptionRequestSelectMode() throws IOException {
		var request = AcpSchema.SetSessionConfigOptionRequest.select("sess-1", "mode", "code");

		String json = jsonMapper.writeValueAsString(request);
		assertThat(json).contains("\"value\":\"code\"");
		assertThat(json).doesNotContain("\"type\":\"boolean\"");

		AcpSchema.SetSessionConfigOptionRequest deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.SetSessionConfigOptionRequest>() {});

		assertThat(deserialized.configId()).isEqualTo("mode");
		assertThat(deserialized.value()).isEqualTo("code");
		assertThat(deserialized.type()).isNull();
	}

	@Test
	void configOptionUpdateAsSessionUpdate() throws IOException {
		var update = new AcpSchema.ConfigOptionUpdate("config_option_update",
				List.of(new AcpSchema.SessionConfigSelect("mode", "Mode", "code",
						List.of(new AcpSchema.SessionConfigSelectOption("code", "Code")))));

		String json = jsonMapper.writeValueAsString(update);
		AcpSchema.SessionUpdate deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.SessionUpdate>() {});

		assertThat(deserialized).isInstanceOf(AcpSchema.ConfigOptionUpdate.class);
		assertThat(((AcpSchema.ConfigOptionUpdate) deserialized).configOptions()).hasSize(1);
	}

	@Test
	void elicitationCapabilitiesOnClientCapabilities() throws IOException {
		var caps = new AcpSchema.ClientCapabilities(
				new AcpSchema.FileSystemCapability(true, true), true,
				new AcpSchema.ElicitationCapabilities(), null);

		String json = jsonMapper.writeValueAsString(caps);
		assertThat(json).contains("\"elicitation\"");

		AcpSchema.ClientCapabilities deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.ClientCapabilities>() {
				});

		assertThat(deserialized.elicitation()).isNotNull();
		assertThat(deserialized.elicitation().form()).isNotNull();
	}

	@Test
	void metaFieldRoundTripFromGoldenFile() throws IOException {
		// Read golden file and verify round-trip
		String goldenJson = new String(
				getClass().getResourceAsStream("/golden/initialize-request-with-meta.json").readAllBytes());

		AcpSchema.InitializeRequest deserialized = jsonMapper.readValue(goldenJson,
				new TypeRef<AcpSchema.InitializeRequest>() {
				});

		assertThat(deserialized.protocolVersion()).isEqualTo(1);
		assertThat(deserialized.meta()).isNotNull();
		assertThat(deserialized.meta()).containsKey("zed.dev/debugMode");
		assertThat(deserialized.meta().get("zed.dev/debugMode")).isEqualTo(true);
		assertThat(deserialized.meta().get("custom/version")).isEqualTo("1.0.0");
	}

}
