/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for MCP server configuration serialization and deserialization.
 *
 * <p>
 * Per the ACP spec:
 * <ul>
 * <li>Stdio transport: NO type field (default)</li>
 * <li>HTTP transport: type="http"</li>
 * <li>SSE transport: type="sse"</li>
 * </ul>
 * </p>
 *
 * @author Mark Pollack
 */
class McpServerConfigurationTest {

	private final AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();

	private final ObjectMapper objectMapper = new ObjectMapper();

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
	// McpServerStdio Tests
	// ---------------------------

	@Test
	void stdioServerDeserializationWithEnv() throws IOException {
		String json = loadGolden("session-new-with-mcp-stdio.json");

		AcpSchema.NewSessionRequest request = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.NewSessionRequest>() {
				});

		assertThat(request.cwd()).isEqualTo("/home/user/project");
		assertThat(request.mcpServers()).hasSize(1);

		AcpSchema.McpServer server = request.mcpServers().get(0);
		assertThat(server).isInstanceOf(AcpSchema.McpServerStdio.class);

		AcpSchema.McpServerStdio stdio = (AcpSchema.McpServerStdio) server;
		assertThat(stdio.name()).isEqualTo("filesystem");
		assertThat(stdio.command()).isEqualTo("/path/to/mcp-server");
		assertThat(stdio.args()).containsExactly("--stdio");
		assertThat(stdio.env()).hasSize(1);
		assertThat(stdio.env().get(0).name()).isEqualTo("API_KEY");
		assertThat(stdio.env().get(0).value()).isEqualTo("secret123");
	}

	@Test
	void stdioServerSerializationHasNoTypeField() throws IOException {
		AcpSchema.McpServerStdio stdio = new AcpSchema.McpServerStdio("test-server", "/usr/bin/mcp",
				List.of("--arg1"), List.of(new AcpSchema.EnvVariable("KEY", "value")));

		String json = jsonMapper.writeValueAsString(stdio);
		JsonNode node = objectMapper.readTree(json);

		// Stdio should NOT have a type field
		assertThat(node.has("type")).isFalse();
		assertThat(node.get("name").asText()).isEqualTo("test-server");
		assertThat(node.get("command").asText()).isEqualTo("/usr/bin/mcp");
	}

	@Test
	void stdioServerRoundTrip() throws IOException {
		AcpSchema.McpServerStdio original = new AcpSchema.McpServerStdio("filesystem", "/path/to/mcp",
				List.of("--stdio", "--mode", "read"), List.of(new AcpSchema.EnvVariable("TOKEN", "abc123")));

		String json = jsonMapper.writeValueAsString(original);
		AcpSchema.McpServer deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.McpServer>() {
		});

		assertThat(deserialized).isInstanceOf(AcpSchema.McpServerStdio.class);
		AcpSchema.McpServerStdio result = (AcpSchema.McpServerStdio) deserialized;
		assertThat(result.name()).isEqualTo(original.name());
		assertThat(result.command()).isEqualTo(original.command());
		assertThat(result.args()).isEqualTo(original.args());
		assertThat(result.env()).hasSize(1);
		assertThat(result.env().get(0).name()).isEqualTo("TOKEN");
	}

	// ---------------------------
	// McpServerHttp Tests
	// ---------------------------

	@Test
	void httpServerDeserialization() throws IOException {
		String json = loadGolden("session-new-with-mcp-http.json");

		AcpSchema.NewSessionRequest request = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.NewSessionRequest>() {
				});

		assertThat(request.mcpServers()).hasSize(1);

		AcpSchema.McpServer server = request.mcpServers().get(0);
		assertThat(server).isInstanceOf(AcpSchema.McpServerHttp.class);

		AcpSchema.McpServerHttp http = (AcpSchema.McpServerHttp) server;
		assertThat(http.name()).isEqualTo("api-server");
		assertThat(http.url()).isEqualTo("https://api.example.com/mcp");
		assertThat(http.headers()).hasSize(2);
		assertThat(http.headers().get(0).name()).isEqualTo("Authorization");
		assertThat(http.headers().get(0).value()).isEqualTo("Bearer token123");
	}

	@Test
	void httpServerSerializationHasTypeField() throws IOException {
		AcpSchema.McpServerHttp http = new AcpSchema.McpServerHttp("api", "https://api.example.com",
				List.of(new AcpSchema.HttpHeader("Auth", "token")));

		String json = jsonMapper.writeValueAsString(http);
		JsonNode node = objectMapper.readTree(json);

		// HTTP should have type="http"
		assertThat(node.has("type")).isTrue();
		assertThat(node.get("type").asText()).isEqualTo("http");
		assertThat(node.get("name").asText()).isEqualTo("api");
		assertThat(node.get("url").asText()).isEqualTo("https://api.example.com");
	}

	@Test
	void httpServerRoundTrip() throws IOException {
		AcpSchema.McpServerHttp original = new AcpSchema.McpServerHttp("remote-api", "https://example.com/mcp",
				List.of(new AcpSchema.HttpHeader("Authorization", "Bearer xyz")));

		String json = jsonMapper.writeValueAsString(original);
		AcpSchema.McpServer deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.McpServer>() {
		});

		assertThat(deserialized).isInstanceOf(AcpSchema.McpServerHttp.class);
		AcpSchema.McpServerHttp result = (AcpSchema.McpServerHttp) deserialized;
		assertThat(result.name()).isEqualTo(original.name());
		assertThat(result.url()).isEqualTo(original.url());
		assertThat(result.headers()).hasSize(1);
	}

	// ---------------------------
	// McpServerSse Tests
	// ---------------------------

	@Test
	void sseServerDeserialization() throws IOException {
		String json = loadGolden("session-new-with-mcp-sse.json");

		AcpSchema.NewSessionRequest request = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.NewSessionRequest>() {
				});

		assertThat(request.mcpServers()).hasSize(1);

		AcpSchema.McpServer server = request.mcpServers().get(0);
		assertThat(server).isInstanceOf(AcpSchema.McpServerSse.class);

		AcpSchema.McpServerSse sse = (AcpSchema.McpServerSse) server;
		assertThat(sse.name()).isEqualTo("event-stream");
		assertThat(sse.url()).isEqualTo("https://events.example.com/mcp");
		assertThat(sse.headers()).hasSize(1);
		assertThat(sse.headers().get(0).name()).isEqualTo("X-API-Key");
	}

	@Test
	void sseServerSerializationHasTypeField() throws IOException {
		AcpSchema.McpServerSse sse = new AcpSchema.McpServerSse("events", "https://events.example.com",
				List.of(new AcpSchema.HttpHeader("X-Key", "secret")));

		String json = jsonMapper.writeValueAsString(sse);
		JsonNode node = objectMapper.readTree(json);

		// SSE should have type="sse"
		assertThat(node.has("type")).isTrue();
		assertThat(node.get("type").asText()).isEqualTo("sse");
		assertThat(node.get("name").asText()).isEqualTo("events");
	}

	@Test
	void sseServerRoundTrip() throws IOException {
		AcpSchema.McpServerSse original = new AcpSchema.McpServerSse("realtime", "https://realtime.example.com/events",
				List.of(new AcpSchema.HttpHeader("X-API-Key", "key123")));

		String json = jsonMapper.writeValueAsString(original);
		AcpSchema.McpServer deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.McpServer>() {
		});

		assertThat(deserialized).isInstanceOf(AcpSchema.McpServerSse.class);
		AcpSchema.McpServerSse result = (AcpSchema.McpServerSse) deserialized;
		assertThat(result.name()).isEqualTo(original.name());
		assertThat(result.url()).isEqualTo(original.url());
	}

	// ---------------------------
	// Mixed MCP Server Tests
	// ---------------------------

	@Test
	void mixedMcpServersDeserialization() throws IOException {
		String json = loadGolden("session-new-with-mcp-mixed.json");

		AcpSchema.NewSessionRequest request = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.NewSessionRequest>() {
				});

		assertThat(request.cwd()).isEqualTo("/workspace");
		assertThat(request.mcpServers()).hasSize(3);

		// First: stdio (no type field)
		assertThat(request.mcpServers().get(0)).isInstanceOf(AcpSchema.McpServerStdio.class);
		AcpSchema.McpServerStdio stdio = (AcpSchema.McpServerStdio) request.mcpServers().get(0);
		assertThat(stdio.name()).isEqualTo("filesystem");
		assertThat(stdio.command()).isEqualTo("/usr/bin/mcp-fs");

		// Second: http
		assertThat(request.mcpServers().get(1)).isInstanceOf(AcpSchema.McpServerHttp.class);
		AcpSchema.McpServerHttp http = (AcpSchema.McpServerHttp) request.mcpServers().get(1);
		assertThat(http.name()).isEqualTo("remote-api");

		// Third: sse
		assertThat(request.mcpServers().get(2)).isInstanceOf(AcpSchema.McpServerSse.class);
		AcpSchema.McpServerSse sse = (AcpSchema.McpServerSse) request.mcpServers().get(2);
		assertThat(sse.name()).isEqualTo("realtime");
	}

	@Test
	void mixedMcpServersRoundTrip() throws IOException {
		List<AcpSchema.McpServer> servers = List.of(
				new AcpSchema.McpServerStdio("fs", "/bin/mcp", List.of(), List.of()),
				new AcpSchema.McpServerHttp("api", "https://api.com", List.of()),
				new AcpSchema.McpServerSse("events", "https://events.com", List.of()));

		AcpSchema.NewSessionRequest original = new AcpSchema.NewSessionRequest("/project", servers);

		String json = jsonMapper.writeValueAsString(original);
		AcpSchema.NewSessionRequest result = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.NewSessionRequest>() {
				});

		assertThat(result.mcpServers()).hasSize(3);
		assertThat(result.mcpServers().get(0)).isInstanceOf(AcpSchema.McpServerStdio.class);
		assertThat(result.mcpServers().get(1)).isInstanceOf(AcpSchema.McpServerHttp.class);
		assertThat(result.mcpServers().get(2)).isInstanceOf(AcpSchema.McpServerSse.class);
	}

	// ---------------------------
	// Edge Cases
	// ---------------------------

	@Test
	void stdioServerWithEmptyEnv() throws IOException {
		AcpSchema.McpServerStdio stdio = new AcpSchema.McpServerStdio("test", "/bin/mcp", List.of("--arg"),
				List.of());

		String json = jsonMapper.writeValueAsString(stdio);
		AcpSchema.McpServer result = jsonMapper.readValue(json, new TypeRef<AcpSchema.McpServer>() {
		});

		assertThat(result).isInstanceOf(AcpSchema.McpServerStdio.class);
		assertThat(((AcpSchema.McpServerStdio) result).env()).isEmpty();
	}

	@Test
	void httpServerWithEmptyHeaders() throws IOException {
		AcpSchema.McpServerHttp http = new AcpSchema.McpServerHttp("api", "https://api.com", List.of());

		String json = jsonMapper.writeValueAsString(http);
		AcpSchema.McpServer result = jsonMapper.readValue(json, new TypeRef<AcpSchema.McpServer>() {
		});

		assertThat(result).isInstanceOf(AcpSchema.McpServerHttp.class);
		assertThat(((AcpSchema.McpServerHttp) result).headers()).isEmpty();
	}

}
