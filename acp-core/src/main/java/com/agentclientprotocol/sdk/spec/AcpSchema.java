/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent Client Protocol (ACP) Schema based on
 * <a href="https://agentclientprotocol.com/">Agent Client Protocol specification</a>.
 *
 * This schema defines all request, response, and notification types used in ACP. ACP is a
 * protocol for communication between code editors (clients) and coding agents.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
public final class AcpSchema {

	private static final Logger logger = LoggerFactory.getLogger(AcpSchema.class);

	private static final TypeRef<HashMap<String, Object>> MAP_TYPE_REF = new TypeRef<>() {
	};

	private AcpSchema() {
	}

	public static final String JSONRPC_VERSION = "2.0";

	public static final int LATEST_PROTOCOL_VERSION = 1;

	/**
	 * Deserializes a JSON-RPC message from a JSON string into the appropriate message
	 * type (request, response, or notification).
	 * @param jsonMapper The JSON mapper to use for deserialization
	 * @param jsonText The JSON text to deserialize
	 * @return The deserialized JSON-RPC message
	 * @throws IOException If deserialization fails
	 * @throws IllegalArgumentException If the JSON structure doesn't match any known
	 * message type
	 */
	public static JSONRPCMessage deserializeJsonRpcMessage(McpJsonMapper jsonMapper, String jsonText)
			throws IOException {

		logger.debug("Received JSON message: {}", jsonText);

		var map = jsonMapper.readValue(jsonText, MAP_TYPE_REF);

		// Determine message type based on specific JSON structure
		if (map.containsKey("method") && map.containsKey("id")) {
			return jsonMapper.convertValue(map, JSONRPCRequest.class);
		}
		else if (map.containsKey("method") && !map.containsKey("id")) {
			return jsonMapper.convertValue(map, JSONRPCNotification.class);
		}
		else if (map.containsKey("result") || map.containsKey("error")) {
			return jsonMapper.convertValue(map, JSONRPCResponse.class);
		}

		throw new IllegalArgumentException("Cannot deserialize JSONRPCMessage: " + jsonText);
	}

	// ---------------------------
	// Method Names (Agent Methods - client calls these)
	// ---------------------------

	public static final String METHOD_INITIALIZE = "initialize";

	public static final String METHOD_AUTHENTICATE = "authenticate";

	public static final String METHOD_SESSION_NEW = "session/new";

	public static final String METHOD_SESSION_LOAD = "session/load";

	public static final String METHOD_SESSION_PROMPT = "session/prompt";

	public static final String METHOD_SESSION_SET_MODE = "session/set_mode";

	public static final String METHOD_SESSION_SET_MODEL = "session/set_model";

	public static final String METHOD_SESSION_CANCEL = "session/cancel";

	// ---------------------------
	// Method Names (Client Methods - agent calls these)
	// ---------------------------

	public static final String METHOD_SESSION_REQUEST_PERMISSION = "session/request_permission";

	public static final String METHOD_SESSION_UPDATE = "session/update";

	public static final String METHOD_FS_READ_TEXT_FILE = "fs/read_text_file";

	public static final String METHOD_FS_WRITE_TEXT_FILE = "fs/write_text_file";

	public static final String METHOD_TERMINAL_CREATE = "terminal/create";

	public static final String METHOD_TERMINAL_OUTPUT = "terminal/output";

	public static final String METHOD_TERMINAL_RELEASE = "terminal/release";

	public static final String METHOD_TERMINAL_WAIT_FOR_EXIT = "terminal/wait_for_exit";

	public static final String METHOD_TERMINAL_KILL = "terminal/kill";

	// ---------------------------
	// JSON-RPC Message Types
	// ---------------------------

	/**
	 * A JSON-RPC request that expects a response.
	 *
	 * @param jsonrpc The JSON-RPC version (must be "2.0")
	 * @param id A unique identifier for the request
	 * @param method The name of the method to be invoked
	 * @param params Parameters for the method call
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record JSONRPCRequest(@JsonProperty("jsonrpc") String jsonrpc, @JsonProperty("id") Object id,
			@JsonProperty("method") String method, @JsonProperty("params") Object params) implements JSONRPCMessage {
		public JSONRPCRequest(String method, Object id, Object params) {
			this(JSONRPC_VERSION, id, method, params);
		}
	}

	/**
	 * A JSON-RPC notification that does not expect a response.
	 *
	 * @param jsonrpc The JSON-RPC version (must be "2.0")
	 * @param method The name of the method to be invoked
	 * @param params Parameters for the method call
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record JSONRPCNotification(@JsonProperty("jsonrpc") String jsonrpc, @JsonProperty("method") String method,
			@JsonProperty("params") Object params) implements JSONRPCMessage {
		public JSONRPCNotification(String method, Object params) {
			this(JSONRPC_VERSION, method, params);
		}
	}

	/**
	 * A JSON-RPC response to a request.
	 *
	 * @param jsonrpc The JSON-RPC version (must be "2.0")
	 * @param id The request ID this response corresponds to
	 * @param result The result of the method call (null if error occurred)
	 * @param error The error information (null if successful)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record JSONRPCResponse(@JsonProperty("jsonrpc") String jsonrpc, @JsonProperty("id") Object id,
			@JsonProperty("result") Object result,
			@JsonProperty("error") JSONRPCError error) implements JSONRPCMessage {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record JSONRPCError(@JsonProperty("code") int code, @JsonProperty("message") String message,
			@JsonProperty("data") Object data) {
	}

	/**
	 * Base type for all JSON-RPC messages.
	 */
	public sealed interface JSONRPCMessage permits JSONRPCRequest, JSONRPCNotification, JSONRPCResponse {

		String jsonrpc();

	}

	// ---------------------------
	// Agent Methods (Client → Agent)
	// ---------------------------

	/**
	 * Initialize request - establishes connection and negotiates capabilities
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record InitializeRequest(@JsonProperty("protocolVersion") Integer protocolVersion,
			@JsonProperty("clientCapabilities") ClientCapabilities clientCapabilities,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public InitializeRequest(Integer protocolVersion, ClientCapabilities clientCapabilities) {
			this(protocolVersion, clientCapabilities, null);
		}
	}

	/**
	 * Initialize response - returns agent capabilities and auth methods
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record InitializeResponse(@JsonProperty("protocolVersion") Integer protocolVersion,
			@JsonProperty("agentCapabilities") AgentCapabilities agentCapabilities,
			@JsonProperty("authMethods") List<AuthMethod> authMethods,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public InitializeResponse(Integer protocolVersion, AgentCapabilities agentCapabilities,
				List<AuthMethod> authMethods) {
			this(protocolVersion, agentCapabilities, authMethods, null);
		}

		/**
		 * Creates a default successful initialization response.
		 * Uses protocol version 1 and default agent capabilities.
		 * @return A default InitializeResponse
		 */
		public static InitializeResponse ok() {
			return new InitializeResponse(1, new AgentCapabilities(), null);
		}

		/**
		 * Creates a successful initialization response with the given capabilities.
		 * @param capabilities The agent capabilities to advertise
		 * @return An InitializeResponse with the specified capabilities
		 */
		public static InitializeResponse ok(AgentCapabilities capabilities) {
			return new InitializeResponse(1, capabilities, null);
		}
	}

	/**
	 * Authenticate request - authenticates using specified method
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AuthenticateRequest(@JsonProperty("methodId") String methodId) {
	}

	/**
	 * Authenticate response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AuthenticateResponse() {
	}

	/**
	 * Create new session request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record NewSessionRequest(@JsonProperty("cwd") String cwd,
			@JsonProperty("mcpServers") List<McpServer> mcpServers,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public NewSessionRequest(String cwd, List<McpServer> mcpServers) {
			this(cwd, mcpServers, null);
		}
	}

	/**
	 * Create new session response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record NewSessionResponse(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("modes") SessionModeState modes, @JsonProperty("models") SessionModelState models,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public NewSessionResponse(String sessionId, SessionModeState modes, SessionModelState models) {
			this(sessionId, modes, models, null);
		}
	}

	/**
	 * Load existing session request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LoadSessionRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("cwd") String cwd,
			@JsonProperty("mcpServers") List<McpServer> mcpServers,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public LoadSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers) {
			this(sessionId, cwd, mcpServers, null);
		}
	}

	/**
	 * Load session response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LoadSessionResponse(@JsonProperty("modes") SessionModeState modes,
			@JsonProperty("models") SessionModelState models,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public LoadSessionResponse(SessionModeState modes, SessionModelState models) {
			this(modes, models, null);
		}
	}

	/**
	 * Prompt request - sends user message to agent
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PromptRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("prompt") List<ContentBlock> prompt,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public PromptRequest(String sessionId, List<ContentBlock> prompt) {
			this(sessionId, prompt, null);
		}

		/**
		 * Returns the text of the first {@link TextContent} block in the prompt, or an empty
		 * string if no text content is present.
		 */
		public String text() {
			if (prompt == null) {
				return "";
			}
			return prompt.stream()
				.filter(c -> c instanceof TextContent)
				.map(c -> ((TextContent) c).text())
				.findFirst()
				.orElse("");
		}
	}

	/**
	 * Prompt response - indicates why agent stopped
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PromptResponse(@JsonProperty("stopReason") StopReason stopReason,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public PromptResponse(StopReason stopReason) {
			this(stopReason, null);
		}

		/**
		 * Creates a response indicating the agent has finished its turn.
		 * @return A PromptResponse with END_TURN stop reason
		 */
		public static PromptResponse endTurn() {
			return new PromptResponse(StopReason.END_TURN);
		}

		/**
		 * Creates a response indicating the agent has finished its turn with a text result.
		 * Note: The text content should be sent via the context before returning this response.
		 * @param text The text (for documentation purposes; actual content sent via context)
		 * @return A PromptResponse with END_TURN stop reason
		 */
		public static PromptResponse text(String text) {
			// Text content should be sent via context.sendMessage() before returning
			return new PromptResponse(StopReason.END_TURN);
		}

		/**
		 * Creates a response indicating the agent refused the request.
		 * @return A PromptResponse with REFUSAL stop reason
		 */
		public static PromptResponse refusal() {
			return new PromptResponse(StopReason.REFUSAL);
		}
	}

	/**
	 * Set session mode request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SetSessionModeRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("modeId") String modeId) {
	}

	/**
	 * Set session mode response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SetSessionModeResponse() {
	}

	/**
	 * Set session model request (UNSTABLE)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SetSessionModelRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("modelId") String modelId) {
	}

	/**
	 * Set session model response (UNSTABLE)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SetSessionModelResponse() {
	}

	/**
	 * Cancel notification - cancels ongoing operations
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CancelNotification(@JsonProperty("sessionId") String sessionId) {
	}

	// ---------------------------
	// Client Methods (Agent → Client)
	// ---------------------------

	/**
	 * Request permission from user
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RequestPermissionRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("toolCall") ToolCallUpdate toolCall,
			@JsonProperty("options") List<PermissionOption> options) {
	}

	/**
	 * Permission response from user
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RequestPermissionResponse(@JsonProperty("outcome") RequestPermissionOutcome outcome) {
	}

	/**
	 * Session update notification - real-time progress
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionNotification(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("update") SessionUpdate update,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public SessionNotification(String sessionId, SessionUpdate update) {
			this(sessionId, update, null);
		}
	}

	/**
	 * Read text file request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ReadTextFileRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("path") String path,
			@JsonProperty("line") Integer line, @JsonProperty("limit") Integer limit) {
	}

	/**
	 * Read text file response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ReadTextFileResponse(@JsonProperty("content") String content) {
	}

	/**
	 * Write text file request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WriteTextFileRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("path") String path,
			@JsonProperty("content") String content) {
	}

	/**
	 * Write text file response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WriteTextFileResponse() {
	}

	/**
	 * Create terminal request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CreateTerminalRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("command") String command, @JsonProperty("args") List<String> args,
			@JsonProperty("cwd") String cwd, @JsonProperty("env") List<EnvVariable> env,
			@JsonProperty("outputByteLimit") Long outputByteLimit) {
	}

	/**
	 * Create terminal response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CreateTerminalResponse(@JsonProperty("terminalId") String terminalId) {
	}

	/**
	 * Terminal output request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TerminalOutputRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("terminalId") String terminalId) {
	}

	/**
	 * Terminal output response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TerminalOutputResponse(@JsonProperty("output") String output,
			@JsonProperty("truncated") boolean truncated, @JsonProperty("exitStatus") TerminalExitStatus exitStatus) {
	}

	/**
	 * Release terminal request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ReleaseTerminalRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("terminalId") String terminalId) {
	}

	/**
	 * Release terminal response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ReleaseTerminalResponse() {
	}

	/**
	 * Wait for terminal exit request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WaitForTerminalExitRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("terminalId") String terminalId) {
	}

	/**
	 * Wait for terminal exit response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WaitForTerminalExitResponse(@JsonProperty("exitCode") Integer exitCode,
			@JsonProperty("signal") String signal) {
	}

	/**
	 * Kill terminal request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record KillTerminalCommandRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("terminalId") String terminalId) {
	}

	/**
	 * Kill terminal response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record KillTerminalCommandResponse() {
	}

	// ---------------------------
	// Capabilities
	// ---------------------------

	/**
	 * Client capabilities
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ClientCapabilities(@JsonProperty("fs") FileSystemCapability fs,
			@JsonProperty("terminal") Boolean terminal,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public ClientCapabilities() {
			this(new FileSystemCapability(), false, null);
		}

		public ClientCapabilities(FileSystemCapability fs, Boolean terminal) {
			this(fs, terminal, null);
		}
	}

	/**
	 * File system capabilities
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FileSystemCapability(@JsonProperty("readTextFile") Boolean readTextFile,
			@JsonProperty("writeTextFile") Boolean writeTextFile) {
		public FileSystemCapability() {
			this(false, false);
		}
	}

	/**
	 * Agent capabilities
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AgentCapabilities(@JsonProperty("loadSession") Boolean loadSession,
			@JsonProperty("mcpCapabilities") McpCapabilities mcpCapabilities,
			@JsonProperty("promptCapabilities") PromptCapabilities promptCapabilities,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public AgentCapabilities() {
			this(false, new McpCapabilities(), new PromptCapabilities(), null);
		}

		public AgentCapabilities(Boolean loadSession, McpCapabilities mcpCapabilities,
				PromptCapabilities promptCapabilities) {
			this(loadSession, mcpCapabilities, promptCapabilities, null);
		}
	}

	/**
	 * MCP capabilities supported by agent
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record McpCapabilities(@JsonProperty("http") Boolean http, @JsonProperty("sse") Boolean sse) {
		public McpCapabilities() {
			this(false, false);
		}
	}

	/**
	 * Prompt capabilities
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PromptCapabilities(@JsonProperty("audio") Boolean audio,
			@JsonProperty("embeddedContext") Boolean embeddedContext, @JsonProperty("image") Boolean image) {
		public PromptCapabilities() {
			this(false, false, false);
		}
	}

	// ---------------------------
	// Session Types
	// ---------------------------

	/**
	 * Session mode state
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionModeState(@JsonProperty("currentModeId") String currentModeId,
			@JsonProperty("availableModes") List<SessionMode> availableModes) {
	}

	/**
	 * Session mode
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionMode(@JsonProperty("id") String id, @JsonProperty("name") String name,
			@JsonProperty("description") String description) {
	}

	/**
	 * Session model state (UNSTABLE)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionModelState(@JsonProperty("currentModelId") String currentModelId,
			@JsonProperty("availableModels") List<ModelInfo> availableModels) {
	}

	/**
	 * Model info (UNSTABLE)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ModelInfo(@JsonProperty("modelId") String modelId, @JsonProperty("name") String name,
			@JsonProperty("description") String description) {
	}

	// ---------------------------
	// Content Types
	// ---------------------------

	/**
	 * Content block - base type for all content
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextContent.class, name = "text"),
			@JsonSubTypes.Type(value = ImageContent.class, name = "image"),
			@JsonSubTypes.Type(value = AudioContent.class, name = "audio"),
			@JsonSubTypes.Type(value = ResourceLink.class, name = "resource_link"),
			@JsonSubTypes.Type(value = Resource.class, name = "resource") })
	public interface ContentBlock {

	}

	/**
	 * Text content
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TextContent(@JsonProperty("type") String type, @JsonProperty("text") String text,
			@JsonProperty("annotations") Annotations annotations,
			@JsonProperty("_meta") Map<String, Object> meta) implements ContentBlock {
		public TextContent(String text) {
			this("text", text, null, null);
		}
	}

	/**
	 * Image content
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ImageContent(@JsonProperty("type") String type, @JsonProperty("data") String data,
			@JsonProperty("mimeType") String mimeType, @JsonProperty("uri") String uri,
			@JsonProperty("annotations") Annotations annotations,
			@JsonProperty("_meta") Map<String, Object> meta) implements ContentBlock {
	}

	/**
	 * Audio content
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AudioContent(@JsonProperty("type") String type, @JsonProperty("data") String data,
			@JsonProperty("mimeType") String mimeType, @JsonProperty("annotations") Annotations annotations,
			@JsonProperty("_meta") Map<String, Object> meta) implements ContentBlock {
	}

	/**
	 * Resource link
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResourceLink(@JsonProperty("type") String type, @JsonProperty("name") String name,
			@JsonProperty("uri") String uri, @JsonProperty("title") String title,
			@JsonProperty("description") String description, @JsonProperty("mimeType") String mimeType,
			@JsonProperty("size") Long size, @JsonProperty("annotations") Annotations annotations,
			@JsonProperty("_meta") Map<String, Object> meta) implements ContentBlock {
	}

	/**
	 * Embedded resource
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Resource(@JsonProperty("type") String type,
			@JsonProperty("resource") EmbeddedResourceResource resource,
			@JsonProperty("annotations") Annotations annotations,
			@JsonProperty("_meta") Map<String, Object> meta) implements ContentBlock {
	}

	/**
	 * Embedded resource content
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextResourceContents.class),
			@JsonSubTypes.Type(value = BlobResourceContents.class) })
	public interface EmbeddedResourceResource {

	}

	/**
	 * Text resource contents
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TextResourceContents(@JsonProperty("text") String text, @JsonProperty("uri") String uri,
			@JsonProperty("mimeType") String mimeType) implements EmbeddedResourceResource {
	}

	/**
	 * Blob resource contents
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record BlobResourceContents(@JsonProperty("blob") String blob, @JsonProperty("uri") String uri,
			@JsonProperty("mimeType") String mimeType) implements EmbeddedResourceResource {
	}

	/**
	 * Annotations for content
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Annotations(@JsonProperty("audience") List<Role> audience, @JsonProperty("priority") Double priority,
			@JsonProperty("lastModified") String lastModified) {
	}

	// ---------------------------
	// Session Updates
	// ---------------------------

	/**
	 * Session update - different types of updates
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "sessionUpdate", visible = true)
	@JsonSubTypes({ @JsonSubTypes.Type(value = UserMessageChunk.class, name = "user_message_chunk"),
			@JsonSubTypes.Type(value = AgentMessageChunk.class, name = "agent_message_chunk"),
			@JsonSubTypes.Type(value = AgentThoughtChunk.class, name = "agent_thought_chunk"),
			@JsonSubTypes.Type(value = ToolCall.class, name = "tool_call"),
			@JsonSubTypes.Type(value = ToolCallUpdateNotification.class, name = "tool_call_update"),
			@JsonSubTypes.Type(value = Plan.class, name = "plan"),
			@JsonSubTypes.Type(value = AvailableCommandsUpdate.class, name = "available_commands_update"),
			@JsonSubTypes.Type(value = CurrentModeUpdate.class, name = "current_mode_update"),
			@JsonSubTypes.Type(value = UsageUpdate.class, name = "usage_update") })
	public interface SessionUpdate {

	}

	/**
	 * User message chunk
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UserMessageChunk(@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("content") ContentBlock content,
			@JsonProperty("_meta") Map<String, Object> meta) implements SessionUpdate {
		public UserMessageChunk(String sessionUpdate, ContentBlock content) {
			this(sessionUpdate, content, null);
		}
	}

	/**
	 * Agent message chunk
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AgentMessageChunk(@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("content") ContentBlock content,
			@JsonProperty("_meta") Map<String, Object> meta) implements SessionUpdate {
		public AgentMessageChunk(String sessionUpdate, ContentBlock content) {
			this(sessionUpdate, content, null);
		}
	}

	/**
	 * Agent thought chunk
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AgentThoughtChunk(@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("content") ContentBlock content,
			@JsonProperty("_meta") Map<String, Object> meta) implements SessionUpdate {
		public AgentThoughtChunk(String sessionUpdate, ContentBlock content) {
			this(sessionUpdate, content, null);
		}
	}

	/**
	 * Tool call
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCall(@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("toolCallId") String toolCallId, @JsonProperty("title") String title,
			@JsonProperty("kind") ToolKind kind, @JsonProperty("status") ToolCallStatus status,
			@JsonProperty("content") List<ToolCallContent> content,
			@JsonProperty("locations") List<ToolCallLocation> locations, @JsonProperty("rawInput") Object rawInput,
			@JsonProperty("rawOutput") Object rawOutput,
			@JsonProperty("_meta") Map<String, Object> meta) implements SessionUpdate {
	}

	/**
	 * Tool call update
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallUpdate(@JsonProperty("toolCallId") String toolCallId, @JsonProperty("title") String title,
			@JsonProperty("kind") ToolKind kind, @JsonProperty("status") ToolCallStatus status,
			@JsonProperty("content") List<ToolCallContent> content,
			@JsonProperty("locations") List<ToolCallLocation> locations, @JsonProperty("rawInput") Object rawInput,
			@JsonProperty("rawOutput") Object rawOutput) {
	}

	/**
	 * Tool call update notification
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallUpdateNotification(@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("toolCallId") String toolCallId, @JsonProperty("title") String title,
			@JsonProperty("kind") ToolKind kind, @JsonProperty("status") ToolCallStatus status,
			@JsonProperty("content") List<ToolCallContent> content,
			@JsonProperty("locations") List<ToolCallLocation> locations, @JsonProperty("rawInput") Object rawInput,
			@JsonProperty("rawOutput") Object rawOutput,
			@JsonProperty("_meta") Map<String, Object> meta) implements SessionUpdate {
	}

	/**
	 * Plan update
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Plan(@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("entries") List<PlanEntry> entries,
			@JsonProperty("_meta") Map<String, Object> meta) implements SessionUpdate {
		public Plan(String sessionUpdate, List<PlanEntry> entries) {
			this(sessionUpdate, entries, null);
		}
	}

	/**
	 * Available commands update
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AvailableCommandsUpdate(@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("availableCommands") List<AvailableCommand> availableCommands,
			@JsonProperty("_meta") Map<String, Object> meta) implements SessionUpdate {
		public AvailableCommandsUpdate(String sessionUpdate, List<AvailableCommand> availableCommands) {
			this(sessionUpdate, availableCommands, null);
		}
	}

	/**
	 * Current mode update
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CurrentModeUpdate(@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("currentModeId") String currentModeId,
			@JsonProperty("_meta") Map<String, Object> meta) implements SessionUpdate {
		public CurrentModeUpdate(String sessionUpdate, String currentModeId) {
			this(sessionUpdate, currentModeId, null);
		}
	}

	/**
	 * Usage update - reports token/resource usage for the session
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UsageUpdate(@JsonProperty("sessionUpdate") String sessionUpdate,
									@JsonProperty("used") Long used,
									@JsonProperty("size") Long size,
									@JsonProperty("_meta") Map<String, Object> meta) implements SessionUpdate {
		public UsageUpdate(String sessionUpdate, Long used, Long size) {
			this(sessionUpdate, used, size, null);
		}
	}

	// ---------------------------
	// Tool Call Types
	// ---------------------------

	/**
	 * Tool call content
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = ToolCallContentBlock.class, name = "content"),
			@JsonSubTypes.Type(value = ToolCallDiff.class, name = "diff"),
			@JsonSubTypes.Type(value = ToolCallTerminal.class, name = "terminal") })
	public interface ToolCallContent {

	}

	/**
	 * Tool call content block
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallContentBlock(@JsonProperty("type") String type,
			@JsonProperty("content") ContentBlock content) implements ToolCallContent {
	}

	/**
	 * Tool call diff
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallDiff(@JsonProperty("type") String type, @JsonProperty("path") String path,
			@JsonProperty("oldText") String oldText,
			@JsonProperty("newText") String newText) implements ToolCallContent {
	}

	/**
	 * Tool call terminal
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallTerminal(@JsonProperty("type") String type,
			@JsonProperty("terminalId") String terminalId) implements ToolCallContent {
	}

	/**
	 * Tool call location
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallLocation(@JsonProperty("path") String path, @JsonProperty("line") Integer line) {
	}

	// ---------------------------
	// Enums
	// ---------------------------

	public enum StopReason {

		@JsonProperty("end_turn")
		END_TURN, @JsonProperty("max_tokens")
		MAX_TOKENS, @JsonProperty("max_turn_requests")
		MAX_TURN_REQUESTS, @JsonProperty("refusal")
		REFUSAL, @JsonProperty("cancelled")
		CANCELLED

	}

	public enum ToolCallStatus {

		@JsonProperty("pending")
		PENDING, @JsonProperty("in_progress")
		IN_PROGRESS, @JsonProperty("completed")
		COMPLETED, @JsonProperty("failed")
		FAILED

	}

	public enum ToolKind {

		@JsonProperty("read")
		READ, @JsonProperty("edit")
		EDIT, @JsonProperty("delete")
		DELETE, @JsonProperty("move")
		MOVE, @JsonProperty("search")
		SEARCH, @JsonProperty("execute")
		EXECUTE, @JsonProperty("think")
		THINK, @JsonProperty("fetch")
		FETCH, @JsonProperty("switch_mode")
		SWITCH_MODE, @JsonProperty("other")
		OTHER

	}

	public enum Role {

		@JsonProperty("assistant")
		ASSISTANT, @JsonProperty("user")
		USER

	}

	public enum PermissionOptionKind {

		@JsonProperty("allow_once")
		ALLOW_ONCE, @JsonProperty("allow_always")
		ALLOW_ALWAYS, @JsonProperty("reject_once")
		REJECT_ONCE, @JsonProperty("reject_always")
		REJECT_ALWAYS

	}

	public enum PlanEntryStatus {

		@JsonProperty("pending")
		PENDING, @JsonProperty("in_progress")
		IN_PROGRESS, @JsonProperty("completed")
		COMPLETED

	}

	public enum PlanEntryPriority {

		@JsonProperty("high")
		HIGH, @JsonProperty("medium")
		MEDIUM, @JsonProperty("low")
		LOW

	}

	// ---------------------------
	// Supporting Types
	// ---------------------------

	/**
	 * MCP server configuration.
	 * <p>
	 * Per the ACP spec:
	 * <ul>
	 * <li>Stdio transport: NO type field (default)</li>
	 * <li>HTTP transport: type="http"</li>
	 * <li>SSE transport: type="sse"</li>
	 * </ul>
	 * </p>
	 * <p>
	 * Uses {@code EXISTING_PROPERTY} so that:
	 * <ul>
	 * <li>McpServerStdio (no type method) serializes WITHOUT type field</li>
	 * <li>McpServerHttp/Sse (with type method) serialize WITH type field</li>
	 * </ul>
	 * </p>
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.EXISTING_PROPERTY,
			defaultImpl = McpServerStdio.class)
	@JsonSubTypes({ @JsonSubTypes.Type(value = McpServerHttp.class, name = "http"),
			@JsonSubTypes.Type(value = McpServerSse.class, name = "sse") })
	public interface McpServer {

	}

	/**
	 * STDIO MCP server (default transport, no type field in JSON).
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record McpServerStdio(@JsonProperty("name") String name, @JsonProperty("command") String command,
			@JsonProperty("args") List<String> args, @JsonProperty("env") List<EnvVariable> env) implements McpServer {
	}

	/**
	 * HTTP MCP server.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record McpServerHttp(@JsonProperty("name") String name, @JsonProperty("url") String url,
			@JsonProperty("headers") List<HttpHeader> headers) implements McpServer {

		/**
		 * Returns the transport type identifier.
		 */
		@JsonProperty("type")
		public String type() {
			return "http";
		}
	}

	/**
	 * SSE MCP server.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record McpServerSse(@JsonProperty("name") String name, @JsonProperty("url") String url,
			@JsonProperty("headers") List<HttpHeader> headers) implements McpServer {

		/**
		 * Returns the transport type identifier.
		 */
		@JsonProperty("type")
		public String type() {
			return "sse";
		}
	}

	/**
	 * Environment variable
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record EnvVariable(@JsonProperty("name") String name, @JsonProperty("value") String value) {
	}

	/**
	 * HTTP header
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record HttpHeader(@JsonProperty("name") String name, @JsonProperty("value") String value) {
	}

	/**
	 * Terminal exit status
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TerminalExitStatus(@JsonProperty("exitCode") Integer exitCode,
			@JsonProperty("signal") String signal) {
	}

	/**
	 * Authentication method
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AuthMethod(@JsonProperty("id") String id, @JsonProperty("name") String name,
			@JsonProperty("description") String description) {
	}

	/**
	 * Permission option
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PermissionOption(@JsonProperty("optionId") String optionId, @JsonProperty("name") String name,
			@JsonProperty("kind") PermissionOptionKind kind) {
	}

	/**
	 * Request permission outcome
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "outcome")
	@JsonSubTypes({ @JsonSubTypes.Type(value = PermissionCancelled.class, name = "cancelled"),
			@JsonSubTypes.Type(value = PermissionSelected.class, name = "selected") })
	public interface RequestPermissionOutcome {

	}

	/**
	 * Permission cancelled
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PermissionCancelled(@JsonProperty("outcome") String outcome) implements RequestPermissionOutcome {
		public PermissionCancelled() {
			this("cancelled");
		}
	}

	/**
	 * Permission selected
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PermissionSelected(@JsonProperty("outcome") String outcome,
			@JsonProperty("optionId") String optionId) implements RequestPermissionOutcome {
		public PermissionSelected(String optionId) {
			this("selected", optionId);
		}
	}

	/**
	 * Plan entry
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PlanEntry(@JsonProperty("content") String content,
			@JsonProperty("priority") PlanEntryPriority priority, @JsonProperty("status") PlanEntryStatus status) {
	}

	/**
	 * Available command
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AvailableCommand(@JsonProperty("name") String name, @JsonProperty("description") String description,
			@JsonProperty("input") AvailableCommandInput input) {
	}

	/**
	 * Available command input
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AvailableCommandInput(@JsonProperty("hint") String hint) {
	}

}
