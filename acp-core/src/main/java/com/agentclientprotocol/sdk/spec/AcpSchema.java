/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
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
	public static JSONRPCMessage deserializeJsonRpcMessage(AcpJsonMapper jsonMapper, String jsonText)
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

	public static final String METHOD_LOGOUT = "logout";

	public static final String METHOD_SESSION_NEW = "session/new";

	public static final String METHOD_SESSION_LOAD = "session/load";

	public static final String METHOD_SESSION_PROMPT = "session/prompt";

	public static final String METHOD_SESSION_SET_MODE = "session/set_mode";

	public static final String METHOD_SESSION_SET_MODEL = "session/set_model";

	public static final String METHOD_SESSION_CANCEL = "session/cancel";

	public static final String METHOD_SESSION_LIST = "session/list";

	public static final String METHOD_SESSION_CLOSE = "session/close";

	public static final String METHOD_SESSION_DELETE = "session/delete";

	public static final String METHOD_SESSION_RESUME = "session/resume";

	public static final String METHOD_SESSION_FORK = "session/fork";

	public static final String METHOD_SESSION_SET_CONFIG_OPTION = "session/set_config_option";

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

	public static final String METHOD_ELICITATION_CREATE = "elicitation/create";

	public static final String METHOD_ELICITATION_COMPLETE = "elicitation/complete";

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
			@JsonProperty("clientInfo") Implementation clientInfo,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public InitializeRequest(Integer protocolVersion, ClientCapabilities clientCapabilities) {
			this(protocolVersion, clientCapabilities, null, null);
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
			@JsonProperty("agentInfo") Implementation agentInfo,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public InitializeResponse(Integer protocolVersion, AgentCapabilities agentCapabilities,
				List<AuthMethod> authMethods) {
			this(protocolVersion, agentCapabilities, authMethods, null, null);
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
	 * Logout request - clears stored credentials, terminating the current
	 * authenticated session.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LogoutRequest(@JsonProperty("_meta") Map<String, Object> meta) {
		public LogoutRequest() {
			this(null);
		}
	}

	/**
	 * Logout response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LogoutResponse(@JsonProperty("_meta") Map<String, Object> meta) {
		public LogoutResponse() {
			this(null);
		}
	}

	/**
	 * Create new session request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record NewSessionRequest(@JsonProperty("cwd") String cwd,
			@JsonProperty("mcpServers") List<McpServer> mcpServers,
			@JsonProperty("additionalDirectories") List<String> additionalDirectories,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public NewSessionRequest(String cwd, List<McpServer> mcpServers) {
			this(cwd, mcpServers, null, null);
		}

		public NewSessionRequest(String cwd, List<McpServer> mcpServers, List<String> additionalDirectories) {
			this(cwd, mcpServers, additionalDirectories, null);
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
			@JsonProperty("additionalDirectories") List<String> additionalDirectories,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public LoadSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers) {
			this(sessionId, cwd, mcpServers, null, null);
		}

		public LoadSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers,
				List<String> additionalDirectories) {
			this(sessionId, cwd, mcpServers, additionalDirectories, null);
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

	/**
	 * List sessions request - lists all sessions, optionally filtered by working directory
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ListSessionsRequest(@JsonProperty("cwd") String cwd, @JsonProperty("cursor") String cursor,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public ListSessionsRequest(String cwd) {
			this(cwd, null, null);
		}
	}

	/**
	 * List sessions response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ListSessionsResponse(@JsonProperty("sessions") List<SessionInfo> sessions,
			@JsonProperty("nextCursor") String nextCursor,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public ListSessionsResponse(List<SessionInfo> sessions) {
			this(sessions, null, null);
		}
	}

	/**
	 * Close session request - closes a session and cancels in-flight work
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CloseSessionRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public CloseSessionRequest(String sessionId) {
			this(sessionId, null);
		}
	}

	/**
	 * Close session response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CloseSessionResponse(@JsonProperty("_meta") Map<String, Object> meta) {
		public CloseSessionResponse() {
			this(null);
		}
	}

	/**
	 * Delete session request - permanently deletes a stored session.
	 *
	 * <p>Only available if the agent advertises the {@code sessionCapabilities.delete}
	 * capability.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record DeleteSessionRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public DeleteSessionRequest(String sessionId) {
			this(sessionId, null);
		}
	}

	/**
	 * Delete session response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record DeleteSessionResponse(@JsonProperty("_meta") Map<String, Object> meta) {
		public DeleteSessionResponse() {
			this(null);
		}
	}

	/**
	 * Resume session request - reconnects to existing session without replaying history
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResumeSessionRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("cwd") String cwd,
			@JsonProperty("mcpServers") List<McpServer> mcpServers,
			@JsonProperty("additionalDirectories") List<String> additionalDirectories,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public ResumeSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers) {
			this(sessionId, cwd, mcpServers, null, null);
		}

		public ResumeSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers,
				List<String> additionalDirectories) {
			this(sessionId, cwd, mcpServers, additionalDirectories, null);
		}
	}

	/**
	 * Resume session response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResumeSessionResponse(@JsonProperty("modes") SessionModeState modes,
			@JsonProperty("models") SessionModelState models,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public ResumeSessionResponse(SessionModeState modes, SessionModelState models) {
			this(modes, models, null);
		}
	}

	/**
	 * Fork session request - creates a new session branched from an existing one
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ForkSessionRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("cwd") String cwd,
			@JsonProperty("mcpServers") List<McpServer> mcpServers,
			@JsonProperty("additionalDirectories") List<String> additionalDirectories,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public ForkSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers) {
			this(sessionId, cwd, mcpServers, null, null);
		}

		public ForkSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers,
				List<String> additionalDirectories) {
			this(sessionId, cwd, mcpServers, additionalDirectories, null);
		}
	}

	/**
	 * Fork session response - returns the new forked session ID
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ForkSessionResponse(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("modes") SessionModeState modes, @JsonProperty("models") SessionModelState models,
			@JsonProperty("configOptions") List<SessionConfigOption> configOptions,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public ForkSessionResponse(String sessionId, SessionModeState modes, SessionModelState models) {
			this(sessionId, modes, models, null, null);
		}
	}

	/**
	 * Set session config option request - changes a configuration value
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SetSessionConfigOptionRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("configId") String configId, @JsonProperty("value") Object value,
			@JsonProperty("type") String type,
			@JsonProperty("_meta") Map<String, Object> meta) {

		/**
		 * Creates a request to set a select-type config option.
		 */
		public static SetSessionConfigOptionRequest select(String sessionId, String configId, String value) {
			return new SetSessionConfigOptionRequest(sessionId, configId, value, null, null);
		}

		/**
		 * Creates a request to set a boolean-type config option.
		 */
		public static SetSessionConfigOptionRequest bool(String sessionId, String configId, boolean value) {
			return new SetSessionConfigOptionRequest(sessionId, configId, value, "boolean", null);
		}
	}

	/**
	 * Set session config option response - returns full config state
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SetSessionConfigOptionResponse(
			@JsonProperty("configOptions") List<SessionConfigOption> configOptions,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public SetSessionConfigOptionResponse(List<SessionConfigOption> configOptions) {
			this(configOptions, null);
		}
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
	// Elicitation (UNSTABLE)
	// ---------------------------

	/**
	 * Create elicitation request - agent asks client for structured user input.
	 * Supports form mode (JSON Schema) and URL mode (out-of-band).
	 * Scope is either session (sessionId) or request (requestId).
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CreateElicitationRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("toolCallId") String toolCallId, @JsonProperty("requestId") Object requestId,
			@JsonProperty("message") String message, @JsonProperty("mode") String mode,
			@JsonProperty("requestedSchema") ElicitationSchema requestedSchema,
			@JsonProperty("elicitationId") String elicitationId, @JsonProperty("url") String url,
			@JsonProperty("_meta") Map<String, Object> meta) {

		/**
		 * Creates a form-mode elicitation request scoped to a session.
		 */
		public static CreateElicitationRequest form(String sessionId, String message,
				ElicitationSchema schema) {
			return new CreateElicitationRequest(sessionId, null, null, message, "form", schema, null,
					null, null);
		}

		/**
		 * Creates a URL-mode elicitation request scoped to a session.
		 */
		public static CreateElicitationRequest url(String sessionId, String message,
				String elicitationId, String url) {
			return new CreateElicitationRequest(sessionId, null, null, message, "url", null,
					elicitationId, url, null);
		}
	}

	/**
	 * Create elicitation response - client returns user's input.
	 * Action is "accept" (with content), "decline", or "cancel".
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CreateElicitationResponse(@JsonProperty("action") ElicitationAction action,
			@JsonProperty("content") Map<String, Object> content,
			@JsonProperty("_meta") Map<String, Object> meta) {

		public static CreateElicitationResponse accept(Map<String, Object> content) {
			return new CreateElicitationResponse(ElicitationAction.ACCEPT, content, null);
		}

		public static CreateElicitationResponse decline() {
			return new CreateElicitationResponse(ElicitationAction.DECLINE, null, null);
		}

		public static CreateElicitationResponse cancel() {
			return new CreateElicitationResponse(ElicitationAction.CANCEL, null, null);
		}
	}

	/**
	 * Elicitation action - user's response to an elicitation.
	 */
	@UnstableAcpApi
	public enum ElicitationAction {

		@JsonProperty("accept")
		ACCEPT, @JsonProperty("decline")
		DECLINE, @JsonProperty("cancel")
		CANCEL

	}

	/**
	 * Complete elicitation notification - signals URL-mode elicitation is done.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CompleteElicitationNotification(@JsonProperty("elicitationId") String elicitationId,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public CompleteElicitationNotification(String elicitationId) {
			this(elicitationId, null);
		}
	}

	/**
	 * Elicitation schema - JSON Schema describing form fields for user input.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ElicitationSchema(@JsonProperty("type") String type,
			@JsonProperty("properties") Map<String, ElicitationPropertySchema> properties,
			@JsonProperty("required") List<String> required, @JsonProperty("title") String title,
			@JsonProperty("description") String description) {
		public ElicitationSchema(Map<String, ElicitationPropertySchema> properties, List<String> required) {
			this("object", properties, required, null, null);
		}
	}

	/**
	 * Elicitation property schema - defines a single form field.
	 */
	@UnstableAcpApi
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = StringPropertySchema.class, name = "string"),
			@JsonSubTypes.Type(value = NumberPropertySchema.class, name = "number"),
			@JsonSubTypes.Type(value = IntegerPropertySchema.class, name = "integer"),
			@JsonSubTypes.Type(value = BooleanPropertySchema.class, name = "boolean"),
			@JsonSubTypes.Type(value = MultiSelectPropertySchema.class, name = "array") })
	public interface ElicitationPropertySchema {

	}

	/**
	 * String property schema - text input or single-select enum.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record StringPropertySchema(@JsonProperty("type") String type, @JsonProperty("title") String title,
			@JsonProperty("description") String description, @JsonProperty("default") String defaultValue,
			@JsonProperty("minLength") Integer minLength, @JsonProperty("maxLength") Integer maxLength,
			@JsonProperty("pattern") String pattern, @JsonProperty("format") String format,
			@JsonProperty("enum") List<String> enumValues,
			@JsonProperty("oneOf") List<EnumOption> oneOf) implements ElicitationPropertySchema {

		public static StringPropertySchema text(String title) {
			return new StringPropertySchema("string", title, null, null, null, null, null, null, null, null);
		}

		public static StringPropertySchema singleSelect(String title, List<EnumOption> options) {
			return new StringPropertySchema("string", title, null, null, null, null, null, null, null, options);
		}
	}

	/**
	 * Number property schema - floating-point input.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record NumberPropertySchema(@JsonProperty("type") String type, @JsonProperty("title") String title,
			@JsonProperty("description") String description, @JsonProperty("default") Double defaultValue,
			@JsonProperty("minimum") Double minimum,
			@JsonProperty("maximum") Double maximum) implements ElicitationPropertySchema {
	}

	/**
	 * Integer property schema - whole number input.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record IntegerPropertySchema(@JsonProperty("type") String type, @JsonProperty("title") String title,
			@JsonProperty("description") String description, @JsonProperty("default") Long defaultValue,
			@JsonProperty("minimum") Long minimum,
			@JsonProperty("maximum") Long maximum) implements ElicitationPropertySchema {
	}

	/**
	 * Boolean property schema - checkbox/toggle input.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record BooleanPropertySchema(@JsonProperty("type") String type, @JsonProperty("title") String title,
			@JsonProperty("description") String description,
			@JsonProperty("default") Boolean defaultValue) implements ElicitationPropertySchema {
	}

	/**
	 * Multi-select property schema - array of selected values.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record MultiSelectPropertySchema(@JsonProperty("type") String type, @JsonProperty("title") String title,
			@JsonProperty("description") String description, @JsonProperty("default") List<String> defaultValues,
			@JsonProperty("items") MultiSelectItems items, @JsonProperty("minItems") Long minItems,
			@JsonProperty("maxItems") Long maxItems) implements ElicitationPropertySchema {
	}

	/**
	 * Multi-select items - defines allowed values for multi-select.
	 */
	@UnstableAcpApi
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
	@JsonSubTypes({ @JsonSubTypes.Type(value = UntitledMultiSelectItems.class),
			@JsonSubTypes.Type(value = TitledMultiSelectItems.class) })
	public interface MultiSelectItems {

	}

	/**
	 * Untitled multi-select items - plain string enum values.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UntitledMultiSelectItems(@JsonProperty("type") String type,
			@JsonProperty("enum") List<String> enumValues) implements MultiSelectItems {
	}

	/**
	 * Titled multi-select items - options with const/title pairs.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TitledMultiSelectItems(
			@JsonProperty("anyOf") List<EnumOption> anyOf) implements MultiSelectItems {
	}

	/**
	 * Enum option - a named value for single-select or multi-select.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record EnumOption(@JsonProperty("const") String constValue,
			@JsonProperty("title") String title) {
	}

	/**
	 * Elicitation capabilities - advertised by client during initialize.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ElicitationCapabilities(@JsonProperty("form") Object form,
			@JsonProperty("url") Object url,
			@JsonProperty("_meta") Map<String, Object> meta) {
		/**
		 * Creates capabilities indicating form-mode support (the default).
		 */
		public ElicitationCapabilities() {
			this(Map.of(), null, null);
		}
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
			@UnstableAcpApi @JsonProperty("elicitation") ElicitationCapabilities elicitation,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public ClientCapabilities() {
			this(new FileSystemCapability(), false, null, null);
		}

		public ClientCapabilities(FileSystemCapability fs, Boolean terminal) {
			this(fs, terminal, null, null);
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
			@JsonProperty("sessionCapabilities") SessionCapabilities sessionCapabilities,
			@JsonProperty("mcpCapabilities") McpCapabilities mcpCapabilities,
			@JsonProperty("promptCapabilities") PromptCapabilities promptCapabilities,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public AgentCapabilities() {
			this(false, null, new McpCapabilities(), new PromptCapabilities(), null);
		}

		public AgentCapabilities(Boolean loadSession, McpCapabilities mcpCapabilities,
				PromptCapabilities promptCapabilities) {
			this(loadSession, null, mcpCapabilities, promptCapabilities, null);
		}
	}

	/**
	 * Session capabilities advertised by the agent. Presence of a non-null field
	 * signals support for that session method.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionCapabilities(@JsonProperty("list") Object list, @JsonProperty("close") Object close,
			@JsonProperty("resume") Object resume, @JsonProperty("delete") Object delete,
			@JsonProperty("additionalDirectories") Object additionalDirectories,
			@UnstableAcpApi @JsonProperty("fork") Object fork) {
		public SessionCapabilities(Object list, Object close, Object resume) {
			this(list, close, resume, null, null, null);
		}

		public SessionCapabilities(Object list, Object close, Object resume, Object fork) {
			this(list, close, resume, null, null, fork);
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
	 * Session information returned by session/list
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionInfo(@JsonProperty("sessionId") String sessionId, @JsonProperty("cwd") String cwd,
			@JsonProperty("title") String title, @JsonProperty("updatedAt") String updatedAt,
			@JsonProperty("additionalDirectories") List<String> additionalDirectories,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public SessionInfo(String sessionId, String cwd) {
			this(sessionId, cwd, null, null, null, null);
		}
	}

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
	// Session Config Types (UNSTABLE)
	// ---------------------------

	/**
	 * Session config option - a configurable setting exposed by the agent.
	 * Discriminated by type: "select" or "boolean".
	 */
	@UnstableAcpApi
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = SessionConfigSelect.class, name = "select"),
			@JsonSubTypes.Type(value = SessionConfigBoolean.class, name = "boolean") })
	public interface SessionConfigOption {

	}

	/**
	 * Select-type config option - a dropdown with named values.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionConfigSelect(@JsonProperty("type") String type, @JsonProperty("id") String id,
			@JsonProperty("name") String name, @JsonProperty("description") String description,
			@JsonProperty("category") String category,
			@JsonProperty("currentValue") String currentValue,
			@JsonProperty("options") List<SessionConfigSelectOption> options,
			@JsonProperty("_meta") Map<String, Object> meta) implements SessionConfigOption {
		public SessionConfigSelect(String id, String name, String currentValue,
				List<SessionConfigSelectOption> options) {
			this("select", id, name, null, null, currentValue, options, null);
		}
	}

	/**
	 * Boolean-type config option - a toggle.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionConfigBoolean(@JsonProperty("type") String type, @JsonProperty("id") String id,
			@JsonProperty("name") String name, @JsonProperty("description") String description,
			@JsonProperty("category") String category,
			@JsonProperty("currentValue") Boolean currentValue,
			@JsonProperty("_meta") Map<String, Object> meta) implements SessionConfigOption {
		public SessionConfigBoolean(String id, String name, Boolean currentValue) {
			this("boolean", id, name, null, null, currentValue, null);
		}
	}

	/**
	 * A selectable option within a select-type config option.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionConfigSelectOption(@JsonProperty("value") String value,
			@JsonProperty("name") String name, @JsonProperty("description") String description,
			@JsonProperty("_meta") Map<String, Object> meta) {
		public SessionConfigSelectOption(String value, String name) {
			this(value, name, null, null);
		}
	}

	/**
	 * Config option update - pushed by agent via session/update notification.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ConfigOptionUpdate(@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("configOptions") List<SessionConfigOption> configOptions,
			@JsonProperty("_meta") Map<String, Object> meta) implements SessionUpdate {
		public ConfigOptionUpdate(String sessionUpdate, List<SessionConfigOption> configOptions) {
			this(sessionUpdate, configOptions, null);
		}
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
			@JsonSubTypes.Type(value = UsageUpdate.class, name = "usage_update"),
			@JsonSubTypes.Type(value = ConfigOptionUpdate.class, name = "config_option_update") })
	public interface SessionUpdate {

	}

	/**
	 * User message chunk
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UserMessageChunk(@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("content") ContentBlock content, @JsonProperty("messageId") String messageId,
			@JsonProperty("_meta") Map<String, Object> meta) implements SessionUpdate {
		public UserMessageChunk(String sessionUpdate, ContentBlock content) {
			this(sessionUpdate, content, null, null);
		}

		public UserMessageChunk(String sessionUpdate, ContentBlock content, String messageId) {
			this(sessionUpdate, content, messageId, null);
		}
	}

	/**
	 * Agent message chunk
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AgentMessageChunk(@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("content") ContentBlock content, @JsonProperty("messageId") String messageId,
			@JsonProperty("_meta") Map<String, Object> meta) implements SessionUpdate {
		public AgentMessageChunk(String sessionUpdate, ContentBlock content) {
			this(sessionUpdate, content, null, null);
		}

		public AgentMessageChunk(String sessionUpdate, ContentBlock content, String messageId) {
			this(sessionUpdate, content, messageId, null);
		}
	}

	/**
	 * Agent thought chunk
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AgentThoughtChunk(@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("content") ContentBlock content, @JsonProperty("messageId") String messageId,
			@JsonProperty("_meta") Map<String, Object> meta) implements SessionUpdate {
		public AgentThoughtChunk(String sessionUpdate, ContentBlock content) {
			this(sessionUpdate, content, null, null);
		}

		public AgentThoughtChunk(String sessionUpdate, ContentBlock content, String messageId) {
			this(sessionUpdate, content, messageId, null);
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
	 * Usage update - context window and cost update for the session (UNSTABLE)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UsageUpdate(@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("used") Long used, @JsonProperty("size") Long size,
			@JsonProperty("cost") Cost cost,
			@JsonProperty("_meta") Map<String, Object> meta) implements SessionUpdate {
		public UsageUpdate(String sessionUpdate, Long used, Long size) {
			this(sessionUpdate, used, size, null, null);
		}
	}

	/**
	 * Cost information for a session (UNSTABLE)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Cost(@JsonProperty("amount") Double amount,
			@JsonProperty("currency") String currency) {
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
	 * Metadata about an implementation (client or agent).
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Implementation(@JsonProperty("name") String name, @JsonProperty("version") String version,
			@JsonProperty("title") String title) {
		public Implementation(String name, String version) {
			this(name, version, null);
		}
	}

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
