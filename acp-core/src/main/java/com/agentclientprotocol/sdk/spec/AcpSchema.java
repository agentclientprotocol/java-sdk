/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ACP v1 messages as Java records, with the method names and the JSON-RPC 2.0 envelope
 * that carries them. Each request, response and notification a client and an agent exchange
 * is a nested type here: {@link PromptRequest} and {@link PromptResponse} for
 * {@code session/prompt}, {@link SessionNotification} for {@code session/update}, and so on.
 * Application code builds and reads these records; the client and agent facades take and
 * return them, and the transports turn them into JSON with an {@link AcpJsonMapper}.
 *
 * <h2>How the types are named</h2>
 * <p>
 * Each method has a {@code METHOD_*} constant with its wire name. A request method has a
 * params record whose name ends in {@code Request} and a result record whose name ends in
 * {@code Response}: {@link #METHOD_SESSION_PROMPT} has {@link PromptRequest} and
 * {@link PromptResponse}. A notification has a params record whose name ends in
 * {@code Notification}. A record's components are its JSON properties (the {@code Unknown*}
 * records described below also keep a map of the fields they do not know), and {@code meta},
 * the {@code _meta} property reserved for extensions, is the last component of every record
 * the schema gives it. The canonical constructor takes every component; a shorter
 * constructor leaves some optional ones out, usually as {@code null}.
 *
 * <h2>Required and optional components</h2>
 * <p>
 * A component marked {@code @Nullable} is optional in the schema and is left out of the JSON
 * when it is {@code null}. Every other component is required and must not be {@code null}.
 * The SDK checks this for the params of inbound requests and notifications: params without a
 * required component never reach a handler. A request is answered with {@code -32602}
 * (Invalid params), and a notification is logged and skipped. The result of a response is
 * checked the same way: a result without a required component fails the request with
 * {@code -32603} (Internal error), naming the component.
 *
 * <h2>Forward compatibility</h2>
 * <p>
 * A peer on a newer protocol version can send a union variant or an enumeration value this
 * SDK does not know. It never fails the message that carries it:
 * <ul>
 * <li>A union whose schema names a default variant reads an unknown variant as that default:
 * an MCP server without a known {@code type} is a {@link McpServerStdio}, and an
 * authentication method without a known {@code type} is an {@link AuthMethodAgent}.</li>
 * <li>Every other union reads an unknown variant as its {@code Unknown*} record
 * ({@link UnknownSessionUpdate}, {@link UnknownContentBlock},
 * {@link UnknownToolCallContent}, {@link UnknownSessionConfigOption},
 * {@link UnknownPermissionOutcome}, {@link UnknownElicitationPropertySchema},
 * {@link UnknownMultiSelectItems}). The record keeps the discriminator and every other field
 * and writes them back unchanged, so a proxy forwards what it received and an application
 * can log or count what it does not understand. A receiver that does not understand the
 * variant ignores it, as the schema's {@code x-deserialize-skip-invalid-items} asks for list
 * items.</li>
 * <li>{@link ToolKind}, whose schema has the catch-all value {@code other}, reads an unknown
 * kind as {@link ToolKind#OTHER}. It is a Java enum.</li>
 * <li>Every other enumeration ({@link StopReason}, {@link ToolCallStatus},
 * {@link PermissionOptionKind}, {@link PlanEntryStatus}, {@link PlanEntryPriority},
 * {@link Role}, {@link ElicitationAction}) is an open value: a record over the wire string,
 * with a constant for each value ACP v1 defines. An unknown value is kept and written back
 * unchanged, and its {@code isKnown()} is {@code false}. Compare open values with
 * {@code equals}, not {@code ==}, or switch on {@code value()}.</li>
 * <li>Open strings in the schema stay {@code String}: a config option's {@code category},
 * whose reserved values are the constants of {@link SessionConfigOptionCategory}, and a
 * string property's {@code format}.</li>
 * </ul>
 * <p>
 * Only {@link JSONRPCMessage} is sealed. The union interfaces are not, so a chain of
 * {@code instanceof} checks over a union is never complete: handle the {@code Unknown*}
 * record and end with a branch for anything else.
 * <p>
 * Each union variant record has its discriminator as a component (for example
 * {@code TextContent.type}). Pass {@code null} for it, or use a shorter constructor, and it
 * becomes the variant's own name; any other value is rejected with
 * {@link IllegalArgumentException}, so a record never claims to be a different variant.
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

	/**
	 * The discriminator a known union variant writes: {@code expected} when the caller
	 * passed {@code null}, so the convenience of not repeating it is kept.
	 * @throws IllegalArgumentException when the caller passed a different name, which
	 * would otherwise go on the wire as is
	 */
	static String discriminator(@Nullable String given, String expected) {
		if (given == null) {
			return expected;
		}
		if (!given.equals(expected)) {
			throw new IllegalArgumentException(
					"Discriminator '" + given + "' does not name this variant; expected '" + expected + "'");
		}
		return given;
	}

	/**
	 * The fields of an unknown union variant, copied and unmodifiable, in wire order.
	 */
	static Map<String, Object> unknownFields(@Nullable Map<String, Object> fields) {
		// Null when the variant had no fields besides its discriminator under some mappers
		return fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
	}

	/**
	 * A value a builder requires.
	 * @throws IllegalStateException when it was not set
	 */
	static <T> T required(@Nullable T value, String name) {
		if (value == null) {
			throw new IllegalStateException(name + " is required");
		}
		return value;
	}

	/**
	 * The known value whose wire value is {@code value}, or a new one: the factory of the
	 * open enum-like values.
	 */
	static <T> T knownOrNew(List<T> known, Function<T, String> wire, String value, Function<String, T> create) {
		Objects.requireNonNull(value, "value");
		for (T candidate : known) {
			if (wire.apply(candidate).equals(value)) {
				return candidate;
			}
		}
		return create.apply(value);
	}

	/** The JSON-RPC version every message carries in its {@code jsonrpc} member: {@value}. */
	public static final String JSONRPC_VERSION = "2.0";

	/**
	 * The ACP protocol version this SDK speaks: {@value}. The client sends it in
	 * {@link InitializeRequest#protocolVersion()} unless told otherwise, and
	 * {@link InitializeResponse#ok()} answers with the same version.
	 */
	public static final int LATEST_PROTOCOL_VERSION = 1;

	/**
	 * Returns the error response JSON-RPC 2.0 prescribes for a message that could not be read:
	 * {@code -32700} (Parse error) when the text is not JSON, {@code -32600} (Invalid Request)
	 * when it is JSON but not a valid JSON-RPC message. The response carries the request's id
	 * when the text is a request whose id can be read, and otherwise a {@code null} id, written
	 * as {@code "id": null}. The SDK's transports send it when
	 * {@link #deserializeJsonRpcMessage} refuses a message; a custom transport can do the same.
	 * @param jsonMapper the JSON mapper that failed to read the message
	 * @param jsonText the text that {@link #deserializeJsonRpcMessage} refused
	 * @return the error response to send to the peer
	 */
	public static JSONRPCResponse unreadableMessageResponse(AcpJsonMapper jsonMapper, String jsonText) {
		boolean isJson;
		try {
			jsonMapper.readValue(jsonText, Object.class);
			isJson = true;
		}
		catch (IOException e) {
			isJson = false;
		}
		JSONRPCError error = isJson ? new JSONRPCError(AcpErrorCodes.INVALID_REQUEST, "Invalid Request", null)
				: new JSONRPCError(AcpErrorCodes.PARSE_ERROR, "Parse error", null);
		return new JSONRPCResponse(JSONRPC_VERSION, isJson ? readableRequestId(jsonMapper, jsonText) : null, null,
				error);
	}

	/**
	 * The id of an invalid request, when it can be read: JSON-RPC 2.0 section 5 answers with
	 * null only when the id could not be detected. Only a message with a method is a request;
	 * an unreadable response is never answered with its own id.
	 */
	private static @Nullable Object readableRequestId(AcpJsonMapper jsonMapper, String jsonText) {
		try {
			Map<String, Object> map = jsonMapper.readValue(jsonText, MAP_TYPE_REF);
			if (map == null || !map.containsKey("method")) {
				return null;
			}
			Object id = map.get("id");
			return isValidId(id) ? id : null;
		}
		catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** JSON-RPC 2.0 section 4: an id is a string, a number or null. */
	private static boolean isValidId(@Nullable Object id) {
		return id == null || id instanceof String || id instanceof Number;
	}

	/**
	 * Refuses a request or notification that JSON-RPC 2.0 section 4 does not allow:
	 * {@code jsonrpc} other than "2.0", a method that is not a string, or an id that is not a
	 * string, number or null. Its answer is -32600 Invalid Request.
	 */
	private static void validateRequest(Map<String, Object> message, String jsonText) {
		if (!JSONRPC_VERSION.equals(message.get("jsonrpc"))) {
			throw new IllegalArgumentException("Invalid Request: jsonrpc must be \"2.0\": " + jsonText);
		}
		if (!(message.get("method") instanceof String)) {
			throw new IllegalArgumentException("Invalid Request: method must be a string: " + jsonText);
		}
		if (!isValidId(message.get("id"))) {
			throw new IllegalArgumentException("Invalid Request: id must be a string, number or null: " + jsonText);
		}
	}

	/**
	 * Reads one JSON-RPC message from its JSON text. An object with a {@code method} is a
	 * {@link JSONRPCRequest} when it has an {@code id} and a {@link JSONRPCNotification} when it
	 * has none; an object with an {@code id}, a {@code result} or an {@code error} but no method
	 * is a {@link JSONRPCResponse}. Params and results stay as the mapper read them (maps, lists
	 * and plain values); the protocol sessions convert them to the method's record. The SDK's
	 * transports call this for each message they receive and answer a failure with
	 * {@link #unreadableMessageResponse}.
	 * @param jsonMapper the JSON mapper to read with
	 * @param jsonText the text of one message
	 * @return the message
	 * @throws IOException if the text is not JSON or not a JSON object; a JSON-RPC batch (an
	 * array) is not supported and fails here
	 * @throws IllegalArgumentException if the text is the JSON {@code null} literal, an object
	 * with none of {@code method}, {@code id}, {@code result} and {@code error}, or a request or
	 * notification JSON-RPC 2.0 does not allow: a {@code jsonrpc} other than {@code "2.0"}, a
	 * method that is not a string, or an id that is not a string, a number or {@code null}
	 */
	public static JSONRPCMessage deserializeJsonRpcMessage(AcpJsonMapper jsonMapper, String jsonText)
			throws IOException {

		logger.debug("Received JSON message ({} characters)", jsonText.length());

		var map = jsonMapper.readValue(jsonText, MAP_TYPE_REF);
		if (map == null) {
			// The JSON null literal: valid JSON, but no JSON-RPC message
			throw new IllegalArgumentException("Cannot deserialize JSONRPCMessage: the JSON null literal");
		}

		Class<? extends JSONRPCMessage> messageType = messageType(map);
		if (messageType == null) {
			// Not the text: a transport logs this exception, and the text is the peer's payload.
			throw new IllegalArgumentException(
					"Cannot deserialize JSONRPCMessage: a JSON object with no method, id, result or error");
		}
		if (messageType != JSONRPCResponse.class) {
			validateRequest(map, jsonText);
		}
		return jsonMapper.convertValue(map, messageType);
	}

	/**
	 * The kind of JSON-RPC message a JSON object is, by its members: a method makes a request
	 * (with an id) or a notification (without); otherwise a result, an error or an id makes a
	 * response, since a message with an id and no method answers a request even without a
	 * result. Null when it is none of them.
	 */
	private static @Nullable Class<? extends JSONRPCMessage> messageType(Map<String, Object> message) {
		boolean hasId = message.containsKey("id");
		if (message.containsKey("method")) {
			return hasId ? JSONRPCRequest.class : JSONRPCNotification.class;
		}
		if (hasId || message.containsKey("result") || message.containsKey("error")) {
			return JSONRPCResponse.class;
		}
		return null;
	}

	// ---------------------------
	// Method Names (Agent Methods - client calls these)
	// ---------------------------

	/**
	 * Client to agent: the first request on a connection, which agrees on the protocol version
	 * and capabilities ({@link InitializeRequest}, {@link InitializeResponse}).
	 */
	public static final String METHOD_INITIALIZE = "initialize";

	/**
	 * Client to agent: logs in with one of the authentication methods the agent listed at
	 * {@code initialize} ({@link AuthenticateRequest}, {@link AuthenticateResponse}).
	 */
	public static final String METHOD_AUTHENTICATE = "authenticate";

	/**
	 * Client to agent: logs out, clearing the agent's stored credentials
	 * ({@link LogoutRequest}, {@link LogoutResponse}).
	 */
	public static final String METHOD_LOGOUT = "logout";

	/**
	 * Client to agent: starts an ACP session and returns its id ({@link NewSessionRequest},
	 * {@link NewSessionResponse}).
	 */
	public static final String METHOD_SESSION_NEW = "session/new";

	/**
	 * Client to agent: loads an existing ACP session ({@link LoadSessionRequest},
	 * {@link LoadSessionResponse}).
	 */
	public static final String METHOD_SESSION_LOAD = "session/load";

	/**
	 * Client to agent: sends a user message and runs a prompt turn, answered when the turn
	 * ends ({@link PromptRequest}, {@link PromptResponse}).
	 */
	public static final String METHOD_SESSION_PROMPT = "session/prompt";

	/**
	 * Client to agent: changes the session's mode ({@link SetSessionModeRequest},
	 * {@link SetSessionModeResponse}).
	 */
	public static final String METHOD_SESSION_SET_MODE = "session/set_mode";

	/**
	 * Client to agent, a notification: asks the agent to end the session's prompt turn
	 * ({@link CancelNotification}). The cancelled prompt answers {@link StopReason#CANCELLED}.
	 */
	public static final String METHOD_SESSION_CANCEL = "session/cancel";

	/**
	 * Client to agent: lists existing ACP sessions ({@link ListSessionsRequest},
	 * {@link ListSessionsResponse}).
	 */
	public static final String METHOD_SESSION_LIST = "session/list";

	/**
	 * Client to agent: closes an active ACP session ({@link CloseSessionRequest},
	 * {@link CloseSessionResponse}).
	 */
	public static final String METHOD_SESSION_CLOSE = "session/close";

	/**
	 * Client to agent: deletes an ACP session that {@code session/list} returned
	 * ({@link DeleteSessionRequest}, {@link DeleteSessionResponse}).
	 */
	public static final String METHOD_SESSION_DELETE = "session/delete";

	/**
	 * Client to agent: resumes an existing ACP session ({@link ResumeSessionRequest},
	 * {@link ResumeSessionResponse}).
	 */
	public static final String METHOD_SESSION_RESUME = "session/resume";

	/**
	 * Client to agent: starts a new ACP session from an existing one
	 * ({@link ForkSessionRequest}, {@link ForkSessionResponse}).
	 */
	@UnstableAcpApi
	public static final String METHOD_SESSION_FORK = "session/fork";

	/**
	 * Client to agent: sets a session config option, such as the model
	 * ({@link SetSessionConfigOptionRequest}, {@link SetSessionConfigOptionResponse}).
	 */
	public static final String METHOD_SESSION_SET_CONFIG_OPTION = "session/set_config_option";

	// ---------------------------
	// Method Names (Client Methods - agent calls these)
	// ---------------------------

	/**
	 * Agent to client: asks the user for permission to run a tool call
	 * ({@link RequestPermissionRequest}, {@link RequestPermissionResponse}).
	 */
	public static final String METHOD_SESSION_REQUEST_PERMISSION = "session/request_permission";

	/**
	 * Agent to client, a notification: streams one session update during a prompt turn
	 * ({@link SessionNotification}).
	 */
	public static final String METHOD_SESSION_UPDATE = "session/update";

	/**
	 * Agent to client: reads a text file through the client ({@link ReadTextFileRequest},
	 * {@link ReadTextFileResponse}).
	 */
	public static final String METHOD_FS_READ_TEXT_FILE = "fs/read_text_file";

	/**
	 * Agent to client: writes a text file through the client ({@link WriteTextFileRequest},
	 * {@link WriteTextFileResponse}).
	 */
	public static final String METHOD_FS_WRITE_TEXT_FILE = "fs/write_text_file";

	/**
	 * Agent to client: creates a terminal and runs a command in it
	 * ({@link CreateTerminalRequest}, {@link CreateTerminalResponse}).
	 */
	public static final String METHOD_TERMINAL_CREATE = "terminal/create";

	/**
	 * Agent to client: returns a terminal's output so far and its exit status, if it has
	 * exited ({@link TerminalOutputRequest}, {@link TerminalOutputResponse}).
	 */
	public static final String METHOD_TERMINAL_OUTPUT = "terminal/output";

	/**
	 * Agent to client: releases a terminal and frees its resources
	 * ({@link ReleaseTerminalRequest}, {@link ReleaseTerminalResponse}).
	 */
	public static final String METHOD_TERMINAL_RELEASE = "terminal/release";

	/**
	 * Agent to client: waits for a terminal's command to exit
	 * ({@link WaitForTerminalExitRequest}, {@link WaitForTerminalExitResponse}).
	 */
	public static final String METHOD_TERMINAL_WAIT_FOR_EXIT = "terminal/wait_for_exit";

	/**
	 * Agent to client: kills a terminal's command but keeps the terminal
	 * ({@link KillTerminalCommandRequest}, {@link KillTerminalCommandResponse}).
	 */
	public static final String METHOD_TERMINAL_KILL = "terminal/kill";

	/**
	 * Agent to client: asks the user for structured input, or to visit a URL
	 * ({@link CreateElicitationRequest}, {@link CreateElicitationResponse}).
	 */
	public static final String METHOD_ELICITATION_CREATE = "elicitation/create";

	/**
	 * Agent to client, a notification: tells the client that a URL elicitation is complete
	 * ({@link CompleteElicitationNotification}).
	 */
	public static final String METHOD_ELICITATION_COMPLETE = "elicitation/complete";

	// ---------------------------
	// Method Names (protocol level, both sides)
	// ---------------------------

	/**
	 * Either direction, a notification: cancels a request the sender sent earlier
	 * ({@link CancelRequestNotification}). The SDK sends and handles it itself.
	 */
	public static final String METHOD_CANCEL_REQUEST = "$/cancel_request";

	// Provider configuration (UNSTABLE)
	/**
	 * Client to agent: lists the agent's providers ({@link ListProvidersRequest},
	 * {@link ListProvidersResponse}).
	 */
	@UnstableAcpApi
	public static final String METHOD_PROVIDERS_LIST = "providers/list";

	/**
	 * Client to agent: sets a provider's configuration ({@link SetProviderRequest},
	 * {@link SetProviderResponse}).
	 */
	@UnstableAcpApi
	public static final String METHOD_PROVIDERS_SET = "providers/set";

	/**
	 * Client to agent: disables a provider ({@link DisableProviderRequest},
	 * {@link DisableProviderResponse}).
	 */
	@UnstableAcpApi
	public static final String METHOD_PROVIDERS_DISABLE = "providers/disable";

	// ---------------------------
	// JSON-RPC Message Types
	// ---------------------------

	/**
	 * A JSON-RPC 2.0 request: a call that expects one {@link JSONRPCResponse} with the same id.
	 * The protocol sessions build these from the facades' calls and number them; build one
	 * yourself only to work on the wire, for example in a transport test.
	 *
	 * <p>
	 * When sent, the params are the method's ACP record, such as a {@link PromptRequest} for
	 * {@code session/prompt}. When read from JSON they are the parsed JSON (maps, lists and plain
	 * values), which the receiving session converts to that record.
	 *
	 * @param jsonrpc the JSON-RPC version, {@value AcpSchema#JSONRPC_VERSION}
	 * @param id the request id, a string or a number; requests this SDK sends have string ids
	 * @param method the method name: a {@code METHOD_*} constant, or an extension method whose
	 * name starts with {@code _}
	 * @param params the method's params, or {@code null} for none
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record JSONRPCRequest(@JsonProperty("jsonrpc") String jsonrpc, @JsonProperty("id") @Nullable Object id,
			@JsonProperty("method") String method, @JsonProperty("params") @Nullable Object params) implements JSONRPCMessage {
		/**
		 * Creates a request with the version {@value AcpSchema#JSONRPC_VERSION}. The method comes
		 * first here, unlike in the canonical constructor.
		 * @param method the method name
		 * @param id the request id
		 * @param params the params, or {@code null}
		 */
		public JSONRPCRequest(String method, @Nullable Object id, @Nullable Object params) {
			this(JSONRPC_VERSION, id, method, params);
		}
	}

	/**
	 * A JSON-RPC 2.0 notification: a message that gets no response, such as
	 * {@code session/update} or {@code session/cancel}. The protocol sessions build these when a
	 * facade sends a notification; build one yourself only to work on the wire.
	 *
	 * <p>
	 * When sent, the params are the method's ACP record, such as a {@link SessionNotification}.
	 * When read from JSON they are the parsed JSON, which the receiving session converts to that
	 * record.
	 *
	 * @param jsonrpc the JSON-RPC version, {@value AcpSchema#JSONRPC_VERSION}
	 * @param method the method name: a {@code METHOD_*} constant, or an extension method whose
	 * name starts with {@code _}
	 * @param params the method's params, or {@code null} for none
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record JSONRPCNotification(@JsonProperty("jsonrpc") String jsonrpc, @JsonProperty("method") String method,
			@JsonProperty("params") @Nullable Object params) implements JSONRPCMessage {
		/**
		 * Creates a notification with the version {@value AcpSchema#JSONRPC_VERSION}.
		 * @param method the method name
		 * @param params the params, or {@code null}
		 */
		public JSONRPCNotification(String method, @Nullable Object params) {
			this(JSONRPC_VERSION, method, params);
		}
	}

	/**
	 * A JSON-RPC 2.0 response: the answer to the {@link JSONRPCRequest} with the same id,
	 * carrying either a result or an error. The protocol sessions match it to the waiting call
	 * and complete that call with the result, or fail it with an {@link AcpError}.
	 *
	 * <p>
	 * A response this SDK sends has exactly one of {@code result} and {@code error}. A received
	 * response with neither reads as a {@code null} result, which completes the call only for a
	 * response type that implements {@link DefaultOnNull} or an extension method; any other call
	 * fails.
	 *
	 * @param jsonrpc the JSON-RPC version, {@value AcpSchema#JSONRPC_VERSION}
	 * @param id the id of the request this answers; {@code null}, written as {@code "id": null}
	 * as JSON-RPC 2.0 requires, when the request could not be read
	 * @param result the result, or {@code null} when the request failed
	 * @param error the error, or {@code null} when the request succeeded
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record JSONRPCResponse(@JsonProperty("jsonrpc") String jsonrpc,
			@JsonProperty("id") @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable Object id,
			@JsonProperty("result") @Nullable Object result,
			@JsonProperty("error") @Nullable JSONRPCError error) implements JSONRPCMessage {
	}

	/**
	 * The error of a failed JSON-RPC response: a code, a message and optional data. A handler
	 * fails a request by throwing an {@link AcpProtocolException}, which the SDK sends as this
	 * record ({@link #from}); a caller whose request failed gets an {@link AcpError} that carries
	 * it ({@link AcpError#getError()}).
	 *
	 * <p>
	 * The codes are the constants of {@link AcpErrorCodes}: the JSON-RPC 2.0 codes, and the ACP
	 * ones such as {@code -32000} (authentication required) and {@code -32800} (request
	 * cancelled).
	 *
	 * @param code the error code
	 * @param message a short description of the error
	 * @param data more detail, or {@code null}; its shape depends on the error
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record JSONRPCError(@JsonProperty("code") int code, @JsonProperty("message") String message,
			@JsonProperty("data") @Nullable Object data) {

		/**
		 * Returns the wire form of a protocol exception: its code, its data, and its message
		 * without the code prefix that {@link AcpProtocolException#getMessage()} adds for logs.
		 * @param exception the exception to send
		 * @return an error with the exception's code, message and data
		 */
		public static JSONRPCError from(AcpProtocolException exception) {
			return new JSONRPCError(exception.getCode(), exception.getErrorMessage(), exception.getData());
		}

		/**
		 * Returns this error as an {@link AcpProtocolException} with the same code, message and
		 * data, for example so a proxy's handler can throw an error it received and pass it on
		 * unchanged. The SDK does not use it for the errors it receives: the caller of a failed
		 * request gets an {@link AcpError}.
		 * @return a protocol exception carrying this error's code, message and data
		 */
		public AcpProtocolException toException() {
			return new AcpProtocolException(code, message, data);
		}
	}

	/**
	 * A response payload whose fields are all optional, so that a peer may answer with
	 * {@code "result": null} (legal JSON-RPC, and what the Python SDK sends when a handler
	 * returns {@code None}) or omit the result. Either reads as if the peer had sent
	 * {@code {}}. A response type that does not implement this interface still fails a
	 * request answered with no result.
	 *
	 * <p>
	 * This is the rule of the Rust SDK's {@code default_on_null} payloads
	 * (agent-client-protocol-schema 1.9.1): opt-in per type, and declared exactly by the
	 * response types whose every component is optional.
	 * </p>
	 */
	public interface DefaultOnNull {

	}

	/**
	 * One JSON-RPC 2.0 message: a {@link JSONRPCRequest}, a {@link JSONRPCNotification} or a
	 * {@link JSONRPCResponse}. Transports move these between client and agent, and the protocol
	 * sessions turn them into calls of the right handler. Application code meets them only when
	 * it writes a transport or a test that works on the wire; everywhere else the ACP records
	 * travel inside them as params and results.
	 *
	 * <p>
	 * The interface is sealed, so these three types are all there are.
	 * {@link AcpSchema#deserializeJsonRpcMessage} reads one from JSON text.
	 */
	public sealed interface JSONRPCMessage permits JSONRPCRequest, JSONRPCNotification, JSONRPCResponse {

		/**
		 * Returns the JSON-RPC version, {@value AcpSchema#JSONRPC_VERSION} in every message this
		 * SDK sends.
		 * @return the {@code jsonrpc} member
		 */
		String jsonrpc();

	}

	// ---------------------------
	// Agent Methods (Client → Agent)
	// ---------------------------

	/**
	 * The params of {@code initialize}, the first request on a connection: the protocol version
	 * the client speaks, what it can do and who it is. {@code AcpAsyncClient} and
	 * {@code AcpSyncClient} build it in {@code initialize()} from the capabilities and client
	 * info set on their builder. The agent's
	 * {@link com.agentclientprotocol.sdk.agent.AcpAgent.InitializeHandler} receives it and
	 * answers with an {@link InitializeResponse}.
	 *
	 * <p>
	 * The agent records {@code clientCapabilities} as
	 * {@link com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities} before its
	 * initialize handler runs, and checks them before it calls the client's file system,
	 * terminal and elicitation methods. A {@code null} {@code clientCapabilities} counts as a
	 * client that offers none of them.
	 *
	 * @param protocolVersion the latest protocol version the client supports; this SDK sends
	 * {@value AcpSchema#LATEST_PROTOCOL_VERSION} unless told otherwise
	 * @param clientCapabilities what the client offers the agent, or {@code null}
	 * @param clientInfo the client's name and version, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record InitializeRequest(@JsonProperty("protocolVersion") Integer protocolVersion,
			@JsonProperty("clientCapabilities") @Nullable ClientCapabilities clientCapabilities,
			@JsonProperty("clientInfo") @Nullable Implementation clientInfo,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without client info or {@code _meta}.
		 * @param protocolVersion the latest protocol version the client supports
		 * @param clientCapabilities what the client offers, or {@code null}
		 */
		public InitializeRequest(Integer protocolVersion, @Nullable ClientCapabilities clientCapabilities) {
			this(protocolVersion, clientCapabilities, null, null);
		}
	}

	/**
	 * The result of {@code initialize}: the protocol version the agent agrees to, what it can do
	 * and how a client can log in. The agent's initialize handler builds it, most simply with
	 * {@link #ok()} or {@link #ok(AgentCapabilities)}. The client's {@code initialize()}
	 * completes with it and records {@code agentCapabilities} as the connection's
	 * {@link com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities}.
	 *
	 * <p>
	 * By the protocol, the agent answers with the client's protocol version if it supports it,
	 * and otherwise with the latest version it supports; a client that does not support the
	 * answer should disconnect. The SDK enforces neither side of this: the initialize handler
	 * chooses the version, and the client does not check it.
	 *
	 * @param protocolVersion the protocol version for this connection
	 * @param agentCapabilities what the agent offers, or {@code null}
	 * @param authMethods the ways a client can authenticate, or {@code null} for none
	 * @param agentInfo the agent's name and version, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record InitializeResponse(@JsonProperty("protocolVersion") Integer protocolVersion,
			@JsonProperty("agentCapabilities") @Nullable AgentCapabilities agentCapabilities,
			@JsonProperty("authMethods") @Nullable List<AuthMethod> authMethods,
			@JsonProperty("agentInfo") @Nullable Implementation agentInfo,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a response without agent info or {@code _meta}.
		 * @param protocolVersion the protocol version for this connection
		 * @param agentCapabilities what the agent offers, or {@code null}
		 * @param authMethods the ways a client can authenticate, or {@code null}
		 */
		public InitializeResponse(Integer protocolVersion, @Nullable AgentCapabilities agentCapabilities,
				@Nullable List<AuthMethod> authMethods) {
			this(protocolVersion, agentCapabilities, authMethods, null, null);
		}

		/**
		 * Returns a response with protocol version 1 and {@code new AgentCapabilities()}: no
		 * session loading, no MCP servers over HTTP or SSE, and only text and resource links in
		 * prompts. Enough for a minimal agent.
		 * @return a response for protocol version 1 with the default capabilities
		 */
		public static InitializeResponse ok() {
			return new InitializeResponse(1, new AgentCapabilities(), null);
		}

		/**
		 * Returns a response with protocol version 1 and the given capabilities.
		 * @param capabilities what the agent offers
		 * @return a response for protocol version 1 with those capabilities
		 */
		public static InitializeResponse ok(AgentCapabilities capabilities) {
			return new InitializeResponse(1, capabilities, null);
		}
	}

	/**
	 * Authenticate request - authenticates using specified method
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AuthenticateRequest(@JsonProperty("methodId") String methodId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public AuthenticateRequest(String methodId) {
			this(methodId, null);
		}
	}

	/**
	 * Authenticate response
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AuthenticateResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		public AuthenticateResponse() {
			this(null);
		}
	}

	/**
	 * Logout request - clears stored credentials, terminating the current
	 * authenticated session.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LogoutRequest(@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public LogoutRequest() {
			this(null);
		}
	}

	/**
	 * Logout response
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LogoutResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		public LogoutResponse() {
			this(null);
		}
	}

	/**
	 * The params of {@code session/new}: asks the agent to start an ACP session in a working
	 * directory, with the MCP servers it should connect to. A client sends it with
	 * {@link com.agentclientprotocol.sdk.client.AcpSyncClient#newSession AcpSyncClient.newSession}
	 * or the {@code AcpAsyncClient} method of the same name. The agent's
	 * {@link com.agentclientprotocol.sdk.agent.AcpAgent.NewSessionHandler} receives it and
	 * answers with a {@link NewSessionResponse} that carries the new session's id.
	 *
	 * <p>
	 * The protocol requires absolute paths for {@code cwd} and {@code additionalDirectories}; the
	 * SDK does not check them. Pass an empty list, not {@code null}, when there are no MCP
	 * servers.
	 *
	 * @param cwd the session's working directory, an absolute path; relative paths in the
	 * session resolve against it
	 * @param mcpServers the MCP servers the agent should connect to, possibly empty
	 * @param additionalDirectories more workspace roots as absolute paths, or {@code null} for
	 * none
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record NewSessionRequest(@JsonProperty("cwd") String cwd,
			@JsonProperty("mcpServers") List<McpServer> mcpServers,
			@JsonProperty("additionalDirectories") @Nullable List<String> additionalDirectories,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without additional directories or {@code _meta}.
		 * @param cwd the working directory, an absolute path
		 * @param mcpServers the MCP servers, possibly empty
		 */
		public NewSessionRequest(String cwd, List<McpServer> mcpServers) {
			this(cwd, mcpServers, null, null);
		}

		/**
		 * Creates a request without {@code _meta}.
		 * @param cwd the working directory, an absolute path
		 * @param mcpServers the MCP servers, possibly empty
		 * @param additionalDirectories more workspace roots, or {@code null}
		 */
		public NewSessionRequest(String cwd, List<McpServer> mcpServers, @Nullable List<String> additionalDirectories) {
			this(cwd, mcpServers, additionalDirectories, null);
		}
	}

	/**
	 * The result of {@code session/new}: the id of the new ACP session, with its modes and config
	 * options when the agent has them. The agent's new-session handler builds it and chooses the
	 * id. The client gets it from {@code newSession(...)} and passes {@link #sessionId()} in every
	 * later message about that session, starting with {@link PromptRequest}.
	 *
	 * <p>
	 * A minimal agent answers {@code new NewSessionResponse(id, null, null)}. An annotated agent
	 * without a {@link com.agentclientprotocol.sdk.annotation.NewSession @NewSession} method
	 * answers with a random UUID as the id.
	 *
	 * @param sessionId the id of the new ACP session
	 * @param modes the session's modes and the current one, or {@code null} if the agent has no
	 * modes
	 * @param configOptions the session's config options with their current values, or
	 * {@code null} if the agent has none
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record NewSessionResponse(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("modes") @Nullable SessionModeState modes,
			@JsonProperty("configOptions") @Nullable List<SessionConfigOption> configOptions,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a response without config options or {@code _meta}.
		 * @param sessionId the id of the new ACP session
		 * @param modes the session's modes, or {@code null}
		 */
		public NewSessionResponse(String sessionId, @Nullable SessionModeState modes) {
			this(sessionId, modes, null, null);
		}

		/**
		 * Creates a response without {@code _meta}.
		 * @param sessionId the id of the new ACP session
		 * @param modes the session's modes, or {@code null}
		 * @param configOptions the session's config options, or {@code null}
		 */
		public NewSessionResponse(String sessionId, @Nullable SessionModeState modes,
				@Nullable List<SessionConfigOption> configOptions) {
			this(sessionId, modes, configOptions, null);
		}
	}

	/**
	 * Load existing session request
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LoadSessionRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("cwd") String cwd,
			@JsonProperty("mcpServers") List<McpServer> mcpServers,
			@JsonProperty("additionalDirectories") @Nullable List<String> additionalDirectories,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public LoadSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers) {
			this(sessionId, cwd, mcpServers, null, null);
		}

		public LoadSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers,
				@Nullable List<String> additionalDirectories) {
			this(sessionId, cwd, mcpServers, additionalDirectories, null);
		}
	}

	/**
	 * Load session response
	 *
	 * @param modes the session's modes and the current one, if the agent has modes
	 * @param configOptions the session's config options and their current values, if the
	 * agent has any
	 * @param meta reserved metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LoadSessionResponse(@JsonProperty("modes") @Nullable SessionModeState modes,
			@JsonProperty("configOptions") @Nullable List<SessionConfigOption> configOptions,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		public LoadSessionResponse(@Nullable SessionModeState modes) {
			this(modes, null, null);
		}

		public LoadSessionResponse(@Nullable SessionModeState modes,
				@Nullable List<SessionConfigOption> configOptions) {
			this(modes, configOptions, null);
		}
	}

	/**
	 * The params of {@code session/prompt}: one user message for an ACP session, which starts a
	 * prompt turn. A client sends it with
	 * {@link com.agentclientprotocol.sdk.client.AcpSyncClient#prompt AcpSyncClient.prompt} or the
	 * {@code AcpAsyncClient} method of the same name. The agent's prompt handler
	 * ({@link com.agentclientprotocol.sdk.agent.AcpAgent.PromptHandler} or a
	 * {@link com.agentclientprotocol.sdk.annotation.Prompt @Prompt} method) receives it with a
	 * prompt context, streams session updates, and answers with a {@link PromptResponse}.
	 *
	 * <p>
	 * An ACP session has at most one prompt turn at a time. A second prompt on the same session
	 * is refused with {@code -32600} (Invalid Request) until the first one has been answered,
	 * even after a {@code session/cancel}. The protocol requires every agent to accept
	 * {@link TextContent} and {@link ResourceLink} blocks; other block types only when the
	 * agent's {@link PromptCapabilities} allow them.
	 *
	 * <pre>{@code
	 * AcpSchema.PromptResponse response = client.prompt(new AcpSchema.PromptRequest(sessionId,
	 *         List.of(new AcpSchema.TextContent("Explain this stack trace"))));
	 * }</pre>
	 *
	 * @param sessionId the ACP session to prompt, from {@link NewSessionResponse#sessionId()}
	 * @param prompt the content blocks of the user's message
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PromptRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("prompt") List<ContentBlock> prompt,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without {@code _meta}.
		 * @param sessionId the ACP session to prompt
		 * @param prompt the content blocks of the user's message
		 */
		public PromptRequest(String sessionId, List<ContentBlock> prompt) {
			this(sessionId, prompt, null);
		}

		/**
		 * Returns the text of the first {@link TextContent} block, a shortcut for prompt handlers
		 * that only read plain text. Other blocks, and any later text blocks, are ignored.
		 * @return the first text block's text, or an empty string when the prompt has no text block
		 */
		public String text() {
			// Required by the schema, but Jackson does not enforce it: a peer can omit it.
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
	 * The result of {@code session/prompt}: why the agent ended the prompt turn. The agent's
	 * prompt handler returns it after the turn's last session update. The client's
	 * {@code prompt(...)} completes with it, and only after the client has handled every session
	 * update the agent sent before it.
	 *
	 * <p>
	 * The stop reason is an open value, so compare it with {@code equals}. A prompt can also end
	 * with an error instead, which fails the client's call with an {@link AcpError}: for example
	 * {@code -32800} when the agent's {@code maxPromptDuration} passes. After a
	 * {@code session/cancel} the agent must answer {@link StopReason#CANCELLED}; if the prompt
	 * handler of an agent built with this SDK has not answered when the cancel grace period
	 * passes, the SDK answers that itself (see {@link PromptTimeouts}).
	 *
	 * @param stopReason why the turn ended
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PromptResponse(@JsonProperty("stopReason") StopReason stopReason,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a response without {@code _meta}.
		 * @param stopReason why the turn ended
		 */
		public PromptResponse(StopReason stopReason) {
			this(stopReason, null);
		}

		/**
		 * Returns a response with stop reason {@link StopReason#END_TURN}: the turn ended normally.
		 * @return a response that ends the turn
		 */
		public static PromptResponse endTurn() {
			return new PromptResponse(StopReason.END_TURN);
		}

		/**
		 * Returns a response with stop reason {@link StopReason#REFUSAL}: the agent refused to
		 * continue.
		 * @return a response that ends the turn as refused
		 */
		public static PromptResponse refusal() {
			return new PromptResponse(StopReason.REFUSAL);
		}

		/**
		 * Creates the response to a cancelled prompt, which ACP requires once the client sent
		 * {@code session/cancel} (ACP v1, prompt turn, Cancellation).
		 * @return A PromptResponse with CANCELLED stop reason
		 */
		public static PromptResponse cancelled() {
			return new PromptResponse(StopReason.CANCELLED);
		}
	}

	/**
	 * Set session mode request
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SetSessionModeRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("modeId") String modeId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public SetSessionModeRequest(String sessionId, String modeId) {
			this(sessionId, modeId, null);
		}
	}

	/**
	 * Set session mode response
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SetSessionModeResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		public SetSessionModeResponse() {
			this(null);
		}
	}

	/**
	 * The params of {@code session/cancel}: asks the agent to end the session's current prompt
	 * turn. A client sends it with {@code cancel(...)} on {@code AcpAsyncClient} or
	 * {@code AcpSyncClient}. The agent's cancel handler
	 * ({@link com.agentclientprotocol.sdk.agent.AcpAgent.CancelHandler} or a
	 * {@link com.agentclientprotocol.sdk.annotation.Cancel @Cancel} method) receives it, and the
	 * running prompt's context reports it ({@code PromptContext.isCancelled()}). It is a
	 * notification, so it gets no answer of its own.
	 *
	 * <p>
	 * The cancel does not end the turn; the cancelled prompt's answer does. The agent may still
	 * send session updates, and then answers the prompt with {@link StopReason#CANCELLED}. Until
	 * that answer, a new prompt on the session is refused. If the prompt handler has not answered
	 * when the cancel grace period passes (60 seconds by default), the SDK cancels the handler
	 * and answers {@code cancelled} itself (see {@link PromptTimeouts}). To cancel any other
	 * request, the SDK uses {@link CancelRequestNotification}.
	 *
	 * @param sessionId the ACP session whose prompt turn to cancel
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CancelNotification(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a notification without {@code _meta}.
		 * @param sessionId the ACP session whose prompt turn to cancel
		 */
		public CancelNotification(String sessionId) {
			this(sessionId, null);
		}
	}

	/**
	 * The params of {@code $/cancel_request}: cancels one request its sender sent earlier, in
	 * either direction. The SDK sends it when a caller gives up on a request: the request's
	 * {@code Mono} is cancelled, its request timeout passes, or a
	 * {@link RequestCancellation#cancelWhen} trigger fires. The receiving SDK handles it itself
	 * and cancels the request's handler; no application handler sees it.
	 *
	 * <p>
	 * The receiver still answers the cancelled request: with its result, if the handler finished
	 * first, and otherwise with the error {@code -32800} (Request cancelled). A
	 * {@code session/prompt} already cancelled with a {@link CancelNotification} answers
	 * {@link StopReason#CANCELLED} instead. A {@code $/cancel_request} whose id is missing, or is
	 * not a string or an integer, is logged and ignored.
	 *
	 * @param requestId the id of the request to cancel: a string or an integer
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CancelRequestNotification(@JsonProperty("requestId") Object requestId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a notification without {@code _meta}.
		 * @param requestId the id of the request to cancel
		 */
		public CancelRequestNotification(Object requestId) {
			this(requestId, null);
		}
	}

	/**
	 * List sessions request - lists all sessions, optionally filtered by working directory
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ListSessionsRequest(@JsonProperty("cwd") @Nullable String cwd, @JsonProperty("cursor") @Nullable String cursor,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ListSessionsRequest(@Nullable String cwd) {
			this(cwd, null, null);
		}
	}

	/**
	 * List sessions response
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ListSessionsResponse(@JsonProperty("sessions") List<SessionInfo> sessions,
			@JsonProperty("nextCursor") @Nullable String nextCursor,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ListSessionsResponse(List<SessionInfo> sessions) {
			this(sessions, null, null);
		}
	}

	/**
	 * Close session request - closes a session and cancels in-flight work
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CloseSessionRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public CloseSessionRequest(String sessionId) {
			this(sessionId, null);
		}
	}

	/**
	 * Close session response
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CloseSessionResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
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
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record DeleteSessionRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public DeleteSessionRequest(String sessionId) {
			this(sessionId, null);
		}
	}

	/**
	 * Delete session response
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record DeleteSessionResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		public DeleteSessionResponse() {
			this(null);
		}
	}

	/**
	 * Resume session request - reconnects to existing session without replaying history
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResumeSessionRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("cwd") String cwd,
			@JsonProperty("mcpServers") @Nullable List<McpServer> mcpServers,
			@JsonProperty("additionalDirectories") @Nullable List<String> additionalDirectories,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ResumeSessionRequest(String sessionId, String cwd, @Nullable List<McpServer> mcpServers) {
			this(sessionId, cwd, mcpServers, null, null);
		}

		public ResumeSessionRequest(String sessionId, String cwd, @Nullable List<McpServer> mcpServers,
				@Nullable List<String> additionalDirectories) {
			this(sessionId, cwd, mcpServers, additionalDirectories, null);
		}
	}

	/**
	 * Resume session response
	 *
	 * @param modes the session's modes and the current one, if the agent has modes
	 * @param configOptions the session's config options and their current values, if the
	 * agent has any
	 * @param meta reserved metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResumeSessionResponse(@JsonProperty("modes") @Nullable SessionModeState modes,
			@JsonProperty("configOptions") @Nullable List<SessionConfigOption> configOptions,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		public ResumeSessionResponse(@Nullable SessionModeState modes) {
			this(modes, null, null);
		}

		public ResumeSessionResponse(@Nullable SessionModeState modes,
				@Nullable List<SessionConfigOption> configOptions) {
			this(modes, configOptions, null);
		}
	}

	/**
	 * Fork session request - creates a new session branched from an existing one
	 */
	@UnstableAcpApi
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ForkSessionRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("cwd") String cwd,
			@JsonProperty("mcpServers") @Nullable List<McpServer> mcpServers,
			@JsonProperty("additionalDirectories") @Nullable List<String> additionalDirectories,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ForkSessionRequest(String sessionId, String cwd, @Nullable List<McpServer> mcpServers) {
			this(sessionId, cwd, mcpServers, null, null);
		}

		public ForkSessionRequest(String sessionId, String cwd, @Nullable List<McpServer> mcpServers,
				@Nullable List<String> additionalDirectories) {
			this(sessionId, cwd, mcpServers, additionalDirectories, null);
		}
	}

	/**
	 * Fork session response - returns the new forked session ID
	 */
	@UnstableAcpApi
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ForkSessionResponse(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("modes") @Nullable SessionModeState modes,
			@JsonProperty("configOptions") @Nullable List<SessionConfigOption> configOptions,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ForkSessionResponse(String sessionId, @Nullable SessionModeState modes) {
			this(sessionId, modes, null, null);
		}
	}

	/**
	 * Set session config option request - changes a configuration value
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SetSessionConfigOptionRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("configId") String configId, @JsonProperty("value") Object value,
			@JsonProperty("type") @Nullable String type,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {

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
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SetSessionConfigOptionResponse(
			@JsonProperty("configOptions") List<SessionConfigOption> configOptions,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
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
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RequestPermissionRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("toolCall") ToolCallUpdate toolCall,
			@JsonProperty("options") List<PermissionOption> options,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public RequestPermissionRequest(String sessionId, ToolCallUpdate toolCall, List<PermissionOption> options) {
			this(sessionId, toolCall, options, null);
		}
	}

	/**
	 * Permission response from user
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RequestPermissionResponse(@JsonProperty("outcome") RequestPermissionOutcome outcome,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public RequestPermissionResponse(RequestPermissionOutcome outcome) {
			this(outcome, null);
		}
	}

	/**
	 * Session update notification - real-time progress
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionNotification(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("update") SessionUpdate update,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public SessionNotification(String sessionId, SessionUpdate update) {
			this(sessionId, update, null);
		}
	}

	/**
	 * Read text file request
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ReadTextFileRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("path") String path,
			@JsonProperty("line") @Nullable Integer line, @JsonProperty("limit") @Nullable Integer limit,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ReadTextFileRequest(String sessionId, String path, @Nullable Integer line, @Nullable Integer limit) {
			this(sessionId, path, line, limit, null);
		}
	}

	/**
	 * Read text file response
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ReadTextFileResponse(@JsonProperty("content") String content,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ReadTextFileResponse(String content) {
			this(content, null);
		}
	}

	/**
	 * Write text file request
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WriteTextFileRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("path") String path,
			@JsonProperty("content") String content,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public WriteTextFileRequest(String sessionId, String path, String content) {
			this(sessionId, path, content, null);
		}
	}

	/**
	 * Write text file response
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WriteTextFileResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		public WriteTextFileResponse() {
			this(null);
		}
	}

	/**
	 * Create terminal request
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CreateTerminalRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("command") String command, @JsonProperty("args") @Nullable List<String> args,
			@JsonProperty("cwd") @Nullable String cwd, @JsonProperty("env") @Nullable List<EnvVariable> env,
			@JsonProperty("outputByteLimit") @Nullable Long outputByteLimit,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public CreateTerminalRequest(String sessionId, String command, @Nullable List<String> args,
				@Nullable String cwd, @Nullable List<EnvVariable> env, @Nullable Long outputByteLimit) {
			this(sessionId, command, args, cwd, env, outputByteLimit, null);
		}
	}

	/**
	 * Create terminal response
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CreateTerminalResponse(@JsonProperty("terminalId") String terminalId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public CreateTerminalResponse(String terminalId) {
			this(terminalId, null);
		}
	}

	/**
	 * Terminal output request
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TerminalOutputRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("terminalId") String terminalId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public TerminalOutputRequest(String sessionId, String terminalId) {
			this(sessionId, terminalId, null);
		}
	}

	/**
	 * Terminal output response
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TerminalOutputResponse(@JsonProperty("output") String output,
			@JsonProperty("truncated") boolean truncated, @JsonProperty("exitStatus") @Nullable TerminalExitStatus exitStatus,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public TerminalOutputResponse(String output, boolean truncated, @Nullable TerminalExitStatus exitStatus) {
			this(output, truncated, exitStatus, null);
		}
	}

	/**
	 * Release terminal request
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ReleaseTerminalRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("terminalId") String terminalId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ReleaseTerminalRequest(String sessionId, String terminalId) {
			this(sessionId, terminalId, null);
		}
	}

	/**
	 * Release terminal response
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ReleaseTerminalResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		public ReleaseTerminalResponse() {
			this(null);
		}
	}

	/**
	 * Wait for terminal exit request
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WaitForTerminalExitRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("terminalId") String terminalId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public WaitForTerminalExitRequest(String sessionId, String terminalId) {
			this(sessionId, terminalId, null);
		}
	}

	/**
	 * Wait for terminal exit response
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WaitForTerminalExitResponse(@JsonProperty("exitCode") @Nullable Integer exitCode,
			@JsonProperty("signal") @Nullable String signal,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		public WaitForTerminalExitResponse(@Nullable Integer exitCode, @Nullable String signal) {
			this(exitCode, signal, null);
		}
	}

	/**
	 * Kill terminal request
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record KillTerminalCommandRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("terminalId") String terminalId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public KillTerminalCommandRequest(String sessionId, String terminalId) {
			this(sessionId, terminalId, null);
		}
	}

	/**
	 * Kill terminal response
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record KillTerminalCommandResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		public KillTerminalCommandResponse() {
			this(null);
		}
	}

	// ---------------------------
	// Elicitation
	// ---------------------------

	/**
	 * Create elicitation request: the agent asks the client for structured user input.
	 * The mode is {@code "form"} (a restricted JSON Schema in {@code requestedSchema}) or
	 * {@code "url"} (an out-of-band interaction at {@code url}, identified by
	 * {@code elicitationId}). The scope is a session ({@code sessionId}, optionally with
	 * {@code toolCallId}) or a request ({@code requestId}). An agent must not request a
	 * mode the client did not advertise in {@link ElicitationCapabilities}.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CreateElicitationRequest(@JsonProperty("sessionId") @Nullable String sessionId,
			@JsonProperty("toolCallId") @Nullable String toolCallId, @JsonProperty("requestId") @Nullable Object requestId,
			@JsonProperty("message") String message, @JsonProperty("mode") String mode,
			@JsonProperty("requestedSchema") @Nullable ElicitationSchema requestedSchema,
			@JsonProperty("elicitationId") @Nullable String elicitationId, @JsonProperty("url") @Nullable String url,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {

		/** The form mode: the client renders {@code requestedSchema} as a form. */
		public static final String MODE_FORM = "form";

		/** The URL mode: the client opens {@code url} out of band, with the user's consent. */
		public static final String MODE_URL = "url";

		/**
		 * Creates a form-mode elicitation request scoped to a session.
		 */
		public static CreateElicitationRequest form(String sessionId, String message,
				ElicitationSchema schema) {
			return new CreateElicitationRequest(sessionId, null, null, message, MODE_FORM, schema, null,
					null, null);
		}

		/**
		 * Creates a URL-mode elicitation request scoped to a session.
		 */
		public static CreateElicitationRequest url(String sessionId, String message,
				String elicitationId, String url) {
			return new CreateElicitationRequest(sessionId, null, null, message, MODE_URL, null,
					elicitationId, url, null);
		}
	}

	/**
	 * Create elicitation response: the user's answer. The action is {@code accept},
	 * {@code decline} or {@code cancel}; {@code content} is optional and only meaningful
	 * on {@code accept} (an accepted URL elicitation normally has none). Its values are
	 * strings, integers, numbers, booleans or string arrays.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CreateElicitationResponse(@JsonProperty("action") ElicitationAction action,
			@JsonProperty("content") @Nullable Map<String, Object> content,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {

		/** The user accepted, submitting the given form content. */
		public static CreateElicitationResponse accept(Map<String, Object> content) {
			return new CreateElicitationResponse(ElicitationAction.ACCEPT, content, null);
		}

		/** The user accepted without content, as for a URL elicitation the user agreed to open. */
		public static CreateElicitationResponse accept() {
			return new CreateElicitationResponse(ElicitationAction.ACCEPT, null, null);
		}

		public static CreateElicitationResponse decline() {
			return new CreateElicitationResponse(ElicitationAction.DECLINE, null, null);
		}

		public static CreateElicitationResponse cancel() {
			return new CreateElicitationResponse(ElicitationAction.CANCEL, null, null);
		}
	}

	/**
	 * The user's answer to an elicitation ({@code CreateElicitationResponse.action}). An
	 * open value: a value this SDK does not know (a newer peer) is kept and written back
	 * unchanged, so it never fails the message (see {@link AcpSchema} on forward
	 * compatibility). The constants name the values ACP v1 defines; {@link #of} returns
	 * them for their wire values, so a known value read from the wire is one of them.
	 *
	 * @param value the wire value
	 */
	public record ElicitationAction(@JsonValue String value) {

		/** {@code "accept"}: the user submitted the form or consented to open the URL. */
		public static final ElicitationAction ACCEPT = new ElicitationAction("accept");

		/** {@code "decline"}: the user explicitly declined. */
		public static final ElicitationAction DECLINE = new ElicitationAction("decline");

		/** {@code "cancel"}: the user dismissed the interaction without choosing. */
		public static final ElicitationAction CANCEL = new ElicitationAction("cancel");

		private static final List<ElicitationAction> KNOWN = List.of(ACCEPT, DECLINE, CANCEL);

		public ElicitationAction {
			Objects.requireNonNull(value, "value");
		}

		/**
		 * The value for a wire string: the constant when ACP v1 defines it.
		 * @param value the wire value
		 * @return the constant, or a new value for an unknown string
		 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static ElicitationAction of(String value) {
			return knownOrNew(KNOWN, ElicitationAction::value, value, ElicitationAction::new);
		}

		/**
		 * The values ACP v1 defines, in schema order.
		 * @return the known values
		 */
		public static List<ElicitationAction> known() {
			return KNOWN;
		}

		/**
		 * Whether ACP v1 defines this value.
		 * @return true for a known value
		 */
		public boolean isKnown() {
			return KNOWN.contains(this);
		}

		@Override
		public String toString() {
			return value;
		}

	}

	/**
	 * Complete elicitation notification: the agent tells the client that the external
	 * interaction of an accepted URL-mode elicitation has finished. Clients must ignore
	 * unknown or already-completed IDs.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CompleteElicitationNotification(@JsonProperty("elicitationId") String elicitationId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public CompleteElicitationNotification(String elicitationId) {
			this(elicitationId, null);
		}
	}

	/**
	 * Elicitation schema - JSON Schema describing form fields for user input.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ElicitationSchema(@JsonProperty("type") @Nullable String type,
			@JsonProperty("properties") @Nullable Map<String, ElicitationPropertySchema> properties,
			@JsonProperty("required") @Nullable List<String> required, @JsonProperty("title") @Nullable String title,
			@JsonProperty("description") @Nullable String description,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ElicitationSchema(@Nullable String type, @Nullable Map<String, ElicitationPropertySchema> properties,
				@Nullable List<String> required, @Nullable String title, @Nullable String description) {
			this(type, properties, required, title, description, null);
		}

		public ElicitationSchema(@Nullable Map<String, ElicitationPropertySchema> properties, @Nullable List<String> required) {
			this("object", properties, required, null, null, null);
		}
	}

	/**
	 * Elicitation property schema - defines a single form field. A property of a type this
	 * SDK does not know reads as an {@link UnknownElicitationPropertySchema}.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.EXISTING_PROPERTY,
			visible = true, defaultImpl = UnknownElicitationPropertySchema.class)
	@JsonSubTypes({ @JsonSubTypes.Type(value = StringPropertySchema.class, name = "string"),
			@JsonSubTypes.Type(value = NumberPropertySchema.class, name = "number"),
			@JsonSubTypes.Type(value = IntegerPropertySchema.class, name = "integer"),
			@JsonSubTypes.Type(value = BooleanPropertySchema.class, name = "boolean"),
			@JsonSubTypes.Type(value = MultiSelectPropertySchema.class, name = "array") })
	public interface ElicitationPropertySchema {

	}

	/**
	 * A form property of a type this SDK does not know: the peer is newer, or sent an
	 * extension. It keeps the {@code type} discriminator (null when the peer sent none) and
	 * every other field, and writes them back unchanged.
	 *
	 * @param type the discriminator as received
	 * @param fields every other field, in wire order
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UnknownElicitationPropertySchema(@JsonProperty("type") @Nullable String type,
			@JsonAnySetter @JsonAnyGetter Map<String, Object> fields) implements ElicitationPropertySchema {
		public UnknownElicitationPropertySchema {
			fields = unknownFields(fields);
		}
	}

	/**
	 * String property schema - text input or single-select enum.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record StringPropertySchema(@JsonProperty("type") String type,
			@JsonProperty("title") @Nullable String title, @JsonProperty("description") @Nullable String description,
			@JsonProperty("default") @Nullable String defaultValue, @JsonProperty("minLength") @Nullable Integer minLength,
			@JsonProperty("maxLength") @Nullable Integer maxLength, @JsonProperty("pattern") @Nullable String pattern,
			@JsonProperty("format") @Nullable String format, @JsonProperty("enum") @Nullable List<String> enumValues,
			@JsonProperty("oneOf") @Nullable List<EnumOption> oneOf,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ElicitationPropertySchema {

		public StringPropertySchema {
			type = discriminator(type, "string");
		}

		public static StringPropertySchema text(String title) {
			return new StringPropertySchema("string", title, null, null, null, null, null, null, null, null, null);
		}

		public static StringPropertySchema singleSelect(String title, List<EnumOption> options) {
			return new StringPropertySchema("string", title, null, null, null, null, null, null, null, options, null);
		}
	}

	/**
	 * Number property schema - floating-point input.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record NumberPropertySchema(@JsonProperty("type") String type,
			@JsonProperty("title") @Nullable String title, @JsonProperty("description") @Nullable String description,
			@JsonProperty("default") @Nullable Double defaultValue, @JsonProperty("minimum") @Nullable Double minimum,
			@JsonProperty("maximum") @Nullable Double maximum,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ElicitationPropertySchema {

		public NumberPropertySchema {
			type = discriminator(type, "number");
		}

		public NumberPropertySchema(String type, @Nullable String title, @Nullable String description,
				@Nullable Double defaultValue, @Nullable Double minimum, @Nullable Double maximum) {
			this(type, title, description, defaultValue, minimum, maximum, null);
		}
	}

	/**
	 * Integer property schema - whole number input.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record IntegerPropertySchema(@JsonProperty("type") String type,
			@JsonProperty("title") @Nullable String title, @JsonProperty("description") @Nullable String description,
			@JsonProperty("default") @Nullable Long defaultValue, @JsonProperty("minimum") @Nullable Long minimum,
			@JsonProperty("maximum") @Nullable Long maximum,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ElicitationPropertySchema {

		public IntegerPropertySchema {
			type = discriminator(type, "integer");
		}

		public IntegerPropertySchema(String type, @Nullable String title, @Nullable String description,
				@Nullable Long defaultValue, @Nullable Long minimum, @Nullable Long maximum) {
			this(type, title, description, defaultValue, minimum, maximum, null);
		}
	}

	/**
	 * Boolean property schema - checkbox/toggle input.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record BooleanPropertySchema(@JsonProperty("type") String type,
			@JsonProperty("title") @Nullable String title, @JsonProperty("description") @Nullable String description,
			@JsonProperty("default") @Nullable Boolean defaultValue,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ElicitationPropertySchema {

		public BooleanPropertySchema {
			type = discriminator(type, "boolean");
		}

		public BooleanPropertySchema(String type, @Nullable String title, @Nullable String description,
				@Nullable Boolean defaultValue) {
			this(type, title, description, defaultValue, null);
		}
	}

	/**
	 * Multi-select property schema - array of selected values.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record MultiSelectPropertySchema(@JsonProperty("type") String type,
			@JsonProperty("title") @Nullable String title, @JsonProperty("description") @Nullable String description,
			@JsonProperty("default") @Nullable List<String> defaultValues, @JsonProperty("items") MultiSelectItems items,
			@JsonProperty("minItems") @Nullable Long minItems, @JsonProperty("maxItems") @Nullable Long maxItems,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ElicitationPropertySchema {

		public MultiSelectPropertySchema {
			type = discriminator(type, "array");
		}
	}

	/**
	 * Multi-select items - defines allowed values for multi-select.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = UnknownMultiSelectItems.class)
	@JsonSubTypes({ @JsonSubTypes.Type(value = UntitledMultiSelectItems.class),
			@JsonSubTypes.Type(value = TitledMultiSelectItems.class) })
	public interface MultiSelectItems {

	}

	/**
	 * Multi-select items of a shape this SDK does not know: the peer is newer, or sent an
	 * extension. It keeps every field and writes them back unchanged.
	 *
	 * @param fields every field, in wire order
	 */
	public record UnknownMultiSelectItems(@JsonAnySetter @JsonAnyGetter Map<String, Object> fields)
			implements MultiSelectItems {
		public UnknownMultiSelectItems {
			fields = unknownFields(fields);
		}
	}

	/**
	 * Untitled multi-select items - plain string enum values.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UntitledMultiSelectItems(@JsonProperty("type") String type,
			@JsonProperty("enum") List<String> enumValues,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements MultiSelectItems {
		public UntitledMultiSelectItems(String type, List<String> enumValues) {
			this(type, enumValues, null);
		}
	}

	/**
	 * Titled multi-select items - options with const/title pairs.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TitledMultiSelectItems(@JsonProperty("anyOf") List<EnumOption> anyOf,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements MultiSelectItems {
		public TitledMultiSelectItems(List<EnumOption> anyOf) {
			this(anyOf, null);
		}
	}

	/**
	 * Enum option - a named value for single-select or multi-select.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record EnumOption(@JsonProperty("const") String constValue, @JsonProperty("title") String title,
			@JsonProperty("description") @Nullable String description,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public EnumOption(String constValue, String title) {
			this(constValue, title, null, null);
		}
	}

	/**
	 * Elicitation capabilities, advertised by the client during initialize. A mode is
	 * supported only when its object is present and non-null: {@code {}} advertises no
	 * mode. Use {@link #formOnly()}, {@link #urlOnly()} or {@link #formAndUrl()}.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ElicitationCapabilities(@JsonProperty("form") @Nullable ElicitationFormCapabilities form,
			@JsonProperty("url") @Nullable ElicitationUrlCapabilities url,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {

		/** Advertises form mode only. */
		public static ElicitationCapabilities formOnly() {
			return new ElicitationCapabilities(new ElicitationFormCapabilities(), null, null);
		}

		/** Advertises URL mode only. */
		public static ElicitationCapabilities urlOnly() {
			return new ElicitationCapabilities(null, new ElicitationUrlCapabilities(), null);
		}

		/** Advertises both form and URL mode. */
		public static ElicitationCapabilities formAndUrl() {
			return new ElicitationCapabilities(new ElicitationFormCapabilities(), new ElicitationUrlCapabilities(),
					null);
		}
	}

	/**
	 * Form-mode elicitation capabilities. Its presence advertises form mode.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ElicitationFormCapabilities(@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ElicitationFormCapabilities() {
			this(null);
		}
	}

	/**
	 * URL-mode elicitation capabilities. Its presence advertises URL mode.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ElicitationUrlCapabilities(@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ElicitationUrlCapabilities() {
			this(null);
		}
	}

	// ---------------------------
	// Capabilities
	// ---------------------------

	/**
	 * Client capabilities. Build them with {@link #builder()}, which reaches every field;
	 * the no-argument constructor is the builder's starting point (no file system access,
	 * no terminal).
	 *
	 * <pre>{@code
	 * ClientCapabilities caps = ClientCapabilities.builder()
	 *     .session(ClientSessionCapabilities.withBooleanConfigOptions())
	 *     .build();
	 * }</pre>
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ClientCapabilities(@JsonProperty("fs") @Nullable FileSystemCapability fs,
			@JsonProperty("terminal") @Nullable Boolean terminal,
			@JsonProperty("session") @Nullable ClientSessionCapabilities session,
			@JsonProperty("auth") @Nullable AuthCapabilities auth,
			@JsonProperty("elicitation") @Nullable ElicitationCapabilities elicitation,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ClientCapabilities() {
			this(new FileSystemCapability(), false, null, null, null, null);
		}

		public ClientCapabilities(@Nullable FileSystemCapability fs, @Nullable Boolean terminal) {
			this(fs, terminal, null, null, null, null);
		}

		/**
		 * A builder starting from {@code new ClientCapabilities()}: no file system access,
		 * no terminal, and no session, auth, elicitation or {@code _meta}.
		 * @return a new builder
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builds {@link ClientCapabilities}.
		 */
		public static final class Builder {

			private @Nullable FileSystemCapability fs = new FileSystemCapability();

			private @Nullable Boolean terminal = false;

			private @Nullable ClientSessionCapabilities session;

			private @Nullable AuthCapabilities auth;

			private @Nullable ElicitationCapabilities elicitation;

			private @Nullable Map<String, Object> meta;

			private Builder() {
			}

			/**
			 * Sets {@code fs}.
			 * @param fs the {@code fs/*} methods the client serves
			 * @return this builder
			 */
			public Builder fs(@Nullable FileSystemCapability fs) {
				this.fs = fs;
				return this;
			}

			/**
			 * Sets {@code terminal}.
			 * @param terminal whether the client serves the {@code terminal/*} methods
			 * @return this builder
			 */
			public Builder terminal(@Nullable Boolean terminal) {
				this.terminal = terminal;
				return this;
			}

			/**
			 * Sets {@code session}.
			 * @param session session capabilities, such as boolean config options
			 * @return this builder
			 */
			public Builder session(@Nullable ClientSessionCapabilities session) {
				this.session = session;
				return this;
			}

			/**
			 * Sets {@code auth}.
			 * @param auth authentication capabilities, such as terminal auth
			 * @return this builder
			 */
			public Builder auth(@Nullable AuthCapabilities auth) {
				this.auth = auth;
				return this;
			}

			/**
			 * Sets {@code elicitation}.
			 * @param elicitation the elicitation modes the client supports
			 * @return this builder
			 */
			public Builder elicitation(@Nullable ElicitationCapabilities elicitation) {
				this.elicitation = elicitation;
				return this;
			}

			/**
			 * Sets {@code meta}.
			 * @param meta optional {@code _meta}
			 * @return this builder
			 */
			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			/**
			 * Builds the capabilities.
			 * @return the capabilities
			 */
			public ClientCapabilities build() {
				return new ClientCapabilities(this.fs, this.terminal, this.session, this.auth, this.elicitation,
						this.meta);
			}

		}
	}

	/**
	 * Session capabilities the client advertises.
	 *
	 * @param configOptions which config option kinds beyond {@code select} the client
	 * supports
	 * @param meta reserved metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ClientSessionCapabilities(
			@JsonProperty("configOptions") @Nullable SessionConfigOptionsCapabilities configOptions,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ClientSessionCapabilities(@Nullable SessionConfigOptionsCapabilities configOptions) {
			this(configOptions, null);
		}

		/**
		 * The session capabilities of a client that supports {@code boolean} config options.
		 * @return {@code {"configOptions": {"boolean": {}}}}
		 */
		public static ClientSessionCapabilities withBooleanConfigOptions() {
			return new ClientSessionCapabilities(SessionConfigOptionsCapabilities.withBoolean());
		}
	}

	/**
	 * Config option kinds the client supports beyond {@code select}.
	 *
	 * @param booleanOptions present (even empty) when the client supports {@code boolean}
	 * config options; written as {@code "boolean"}
	 * @param meta reserved metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionConfigOptionsCapabilities(
			@JsonProperty("boolean") @Nullable BooleanConfigOptionCapabilities booleanOptions,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public SessionConfigOptionsCapabilities(@Nullable BooleanConfigOptionCapabilities booleanOptions) {
			this(booleanOptions, null);
		}

		/**
		 * The capabilities of a client that supports {@code boolean} config options.
		 * @return {@code {"boolean": {}}}
		 */
		public static SessionConfigOptionsCapabilities withBoolean() {
			return new SessionConfigOptionsCapabilities(new BooleanConfigOptionCapabilities());
		}
	}

	/**
	 * Present when the client supports {@code boolean} config options.
	 *
	 * @param meta reserved metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record BooleanConfigOptionCapabilities(@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public BooleanConfigOptionCapabilities() {
			this(null);
		}
	}

	/**
	 * File system capabilities
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FileSystemCapability(@JsonProperty("readTextFile") @Nullable Boolean readTextFile,
			@JsonProperty("writeTextFile") @Nullable Boolean writeTextFile,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public FileSystemCapability(@Nullable Boolean readTextFile, @Nullable Boolean writeTextFile) {
			this(readTextFile, writeTextFile, null);
		}

		public FileSystemCapability() {
			this(false, false);
		}
	}

	/**
	 * Agent capabilities. Build them with {@link #builder()}, which reaches every field;
	 * the no-argument constructor is the builder's starting point.
	 *
	 * <pre>{@code
	 * AgentCapabilities caps = AgentCapabilities.builder()
	 *     .loadSession(true)
	 *     .auth(AgentAuthCapabilities.withLogout())
	 *     .build();
	 * }</pre>
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AgentCapabilities(@JsonProperty("loadSession") @Nullable Boolean loadSession,
			@JsonProperty("sessionCapabilities") @Nullable SessionCapabilities sessionCapabilities,
			@JsonProperty("mcpCapabilities") @Nullable McpCapabilities mcpCapabilities,
			@JsonProperty("promptCapabilities") @Nullable PromptCapabilities promptCapabilities,
			@JsonProperty("auth") @Nullable AgentAuthCapabilities auth,
			@UnstableAcpApi @JsonProperty("providers") @Nullable ProvidersCapabilities providers,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public AgentCapabilities() {
			this(false, null, new McpCapabilities(), new PromptCapabilities(), null, null, null);
		}

		public AgentCapabilities(@Nullable Boolean loadSession, @Nullable McpCapabilities mcpCapabilities,
				@Nullable PromptCapabilities promptCapabilities) {
			this(loadSession, null, mcpCapabilities, promptCapabilities, null, null, null);
		}

		public AgentCapabilities(@Nullable Boolean loadSession, @Nullable SessionCapabilities sessionCapabilities,
				@Nullable McpCapabilities mcpCapabilities, @Nullable PromptCapabilities promptCapabilities, @Nullable Map<String, Object> meta) {
			this(loadSession, sessionCapabilities, mcpCapabilities, promptCapabilities, null, null, meta);
		}

		/**
		 * A builder starting from {@code new AgentCapabilities()}: no {@code session/load},
		 * default MCP and prompt capabilities, and no session, auth or provider capabilities
		 * or {@code _meta}.
		 * @return a new builder
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builds {@link AgentCapabilities}.
		 */
		public static final class Builder {

			private @Nullable Boolean loadSession = false;

			private @Nullable SessionCapabilities sessionCapabilities;

			private @Nullable McpCapabilities mcpCapabilities = new McpCapabilities();

			private @Nullable PromptCapabilities promptCapabilities = new PromptCapabilities();

			private @Nullable AgentAuthCapabilities auth;

			private @Nullable ProvidersCapabilities providers;

			private @Nullable Map<String, Object> meta;

			private Builder() {
			}

			/**
			 * Sets {@code loadSession}.
			 * @param loadSession whether the agent supports {@code session/load}
			 * @return this builder
			 */
			public Builder loadSession(@Nullable Boolean loadSession) {
				this.loadSession = loadSession;
				return this;
			}

			/**
			 * Sets {@code sessionCapabilities}.
			 * @param sessionCapabilities the optional session methods the agent supports
			 * @return this builder
			 */
			public Builder sessionCapabilities(@Nullable SessionCapabilities sessionCapabilities) {
				this.sessionCapabilities = sessionCapabilities;
				return this;
			}

			/**
			 * Sets {@code mcpCapabilities}.
			 * @param mcpCapabilities the MCP transports the agent supports
			 * @return this builder
			 */
			public Builder mcpCapabilities(@Nullable McpCapabilities mcpCapabilities) {
				this.mcpCapabilities = mcpCapabilities;
				return this;
			}

			/**
			 * Sets {@code promptCapabilities}.
			 * @param promptCapabilities the prompt content the agent accepts
			 * @return this builder
			 */
			public Builder promptCapabilities(@Nullable PromptCapabilities promptCapabilities) {
				this.promptCapabilities = promptCapabilities;
				return this;
			}

			/**
			 * Sets {@code auth}.
			 * @param auth authentication capabilities, such as logout
			 * @return this builder
			 */
			public Builder auth(@Nullable AgentAuthCapabilities auth) {
				this.auth = auth;
				return this;
			}

			/**
			 * Sets {@code providers}.
			 * @param providers provider capabilities (unstable)
			 * @return this builder
			 */
			@UnstableAcpApi
			public Builder providers(@Nullable ProvidersCapabilities providers) {
				this.providers = providers;
				return this;
			}

			/**
			 * Sets {@code meta}.
			 * @param meta optional {@code _meta}
			 * @return this builder
			 */
			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			/**
			 * Builds the capabilities.
			 * @return the capabilities
			 */
			public AgentCapabilities build() {
				return new AgentCapabilities(this.loadSession, this.sessionCapabilities, this.mcpCapabilities,
						this.promptCapabilities, this.auth, this.providers, this.meta);
			}

		}
	}

	/**
	 * Authentication capabilities the agent advertises.
	 *
	 * @param logout present (even empty) when the agent supports the {@code logout} method
	 * @param meta reserved metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AgentAuthCapabilities(@JsonProperty("logout") @Nullable LogoutCapabilities logout,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public AgentAuthCapabilities(@Nullable LogoutCapabilities logout) {
			this(logout, null);
		}

		/**
		 * The capabilities of an agent that supports {@code logout}.
		 * @return {@code {"logout": {}}}
		 */
		public static AgentAuthCapabilities withLogout() {
			return new AgentAuthCapabilities(new LogoutCapabilities());
		}
	}

	/**
	 * Present when the agent supports the {@code logout} method.
	 *
	 * @param meta reserved metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LogoutCapabilities(@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public LogoutCapabilities() {
			this(null);
		}
	}

	/**
	 * Session capabilities advertised by the agent. Presence of a non-null field
	 * signals support for that session method.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionCapabilities(@JsonProperty("list") @Nullable Object list, @JsonProperty("close") @Nullable Object close,
			@JsonProperty("resume") @Nullable Object resume, @JsonProperty("delete") @Nullable Object delete,
			@JsonProperty("additionalDirectories") @Nullable Object additionalDirectories,
			@UnstableAcpApi @JsonProperty("fork") @Nullable Object fork,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public SessionCapabilities(@Nullable Object list, @Nullable Object close, @Nullable Object resume,
				@Nullable Object delete, @Nullable Object additionalDirectories, @Nullable Object fork) {
			this(list, close, resume, delete, additionalDirectories, fork, null);
		}

		public SessionCapabilities(@Nullable Object list, @Nullable Object close, @Nullable Object resume) {
			this(list, close, resume, null, null, null);
		}

		public SessionCapabilities(@Nullable Object list, @Nullable Object close, @Nullable Object resume,
				@Nullable Object fork) {
			this(list, close, resume, null, null, fork);
		}
	}

	/**
	 * MCP capabilities supported by agent
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record McpCapabilities(@JsonProperty("http") @Nullable Boolean http, @JsonProperty("sse") @Nullable Boolean sse,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public McpCapabilities(@Nullable Boolean http, @Nullable Boolean sse) {
			this(http, sse, null);
		}

		public McpCapabilities() {
			this(false, false);
		}
	}

	/**
	 * Prompt capabilities
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PromptCapabilities(@JsonProperty("audio") @Nullable Boolean audio,
			@JsonProperty("embeddedContext") @Nullable Boolean embeddedContext, @JsonProperty("image") @Nullable Boolean image,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public PromptCapabilities(@Nullable Boolean audio, @Nullable Boolean embeddedContext, @Nullable Boolean image) {
			this(audio, embeddedContext, image, null);
		}

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
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionInfo(@JsonProperty("sessionId") String sessionId, @JsonProperty("cwd") String cwd,
			@JsonProperty("title") @Nullable String title, @JsonProperty("updatedAt") @Nullable String updatedAt,
			@JsonProperty("additionalDirectories") @Nullable List<String> additionalDirectories,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public SessionInfo(String sessionId, String cwd) {
			this(sessionId, cwd, null, null, null, null);
		}
	}

	/**
	 * Session mode state
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionModeState(@JsonProperty("currentModeId") String currentModeId,
			@JsonProperty("availableModes") List<SessionMode> availableModes,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public SessionModeState(String currentModeId, List<SessionMode> availableModes) {
			this(currentModeId, availableModes, null);
		}
	}

	/**
	 * Session mode
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionMode(@JsonProperty("id") String id, @JsonProperty("name") String name,
			@JsonProperty("description") @Nullable String description,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public SessionMode(String id, String name, @Nullable String description) {
			this(id, name, description, null);
		}
	}

	// ---------------------------
	// Session Config Types
	// ---------------------------

	/**
	 * Session config option - a configurable setting exposed by the agent. Discriminated by
	 * type: {@code "select"} or {@code "boolean"}, both stable. An agent sends boolean
	 * options only to a client that advertises
	 * {@code clientCapabilities.session.configOptions.boolean}.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.EXISTING_PROPERTY,
			visible = true, defaultImpl = UnknownSessionConfigOption.class)
	@JsonSubTypes({ @JsonSubTypes.Type(value = SessionConfigSelect.class, name = "select"),
			@JsonSubTypes.Type(value = SessionConfigBoolean.class, name = "boolean") })
	public interface SessionConfigOption {

	}

	/**
	 * A config option of a kind this SDK does not know: the peer is newer, or sent an
	 * extension. It keeps the {@code type} discriminator (null when the peer sent none)
	 * and every other field, and writes them back unchanged.
	 *
	 * @param type the discriminator as received
	 * @param fields every other field, in wire order
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UnknownSessionConfigOption(@JsonProperty("type") @Nullable String type,
			@JsonAnySetter @JsonAnyGetter Map<String, Object> fields) implements SessionConfigOption {
		public UnknownSessionConfigOption {
			fields = unknownFields(fields);
		}
	}

	/**
	 * The {@code category} values ACP v1 reserves for a config option (the schema's
	 * {@code SessionConfigOptionCategory}). A category helps a client place and style an
	 * option; it is never required for correctness. Any other string is allowed: names
	 * starting with {@code _} are free for custom use, and a client treats an unknown
	 * category as uncategorized. A holder of constants, not meant to be implemented.
	 */
	public interface SessionConfigOptionCategory {

		/** Session mode selector. */
		String MODE = "mode";

		/** Model selector: the config option that replaces {@code session/set_model}. */
		String MODEL = "model";

		/** Model-related configuration parameter. */
		String MODEL_CONFIG = "model_config";

		/** Thought or reasoning level selector. */
		String THOUGHT_LEVEL = "thought_level";

	}

	/**
	 * Select-type config option - a dropdown with named values. The short constructors set
	 * no description, category or {@code _meta}; {@link #model} builds the model picker
	 * (category {@code "model"}) and {@link #builder()} reaches every field.
	 *
	 * <pre>{@code
	 * SessionConfigSelect model = SessionConfigSelect.model("model", "Model", "fast",
	 *         List.of(new SessionConfigSelectOption("fast", "Fast"), new SessionConfigSelectOption("smart", "Smart")));
	 *
	 * SessionConfigSelect effort = SessionConfigSelect.builder()
	 *     .id("effort").name("Effort").category(SessionConfigOptionCategory.THOUGHT_LEVEL)
	 *     .currentValue("low").options(List.of(low, high))
	 *     .build();
	 * }</pre>
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionConfigSelect(
			@JsonProperty("type") String type,
			@JsonProperty("id") String id, @JsonProperty("name") String name,
			@JsonProperty("description") @Nullable String description, @JsonProperty("category") @Nullable String category,
			@JsonProperty("currentValue") String currentValue,
			@JsonProperty("options") SessionConfigSelectOptions options,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionConfigOption {
		public SessionConfigSelect {
			type = discriminator(type, "select");
		}

		public SessionConfigSelect(String id, String name, String currentValue,
				List<SessionConfigSelectOption> options) {
			this("select", id, name, null, null, currentValue, SessionConfigSelectOptions.ungrouped(options), null);
		}

		public SessionConfigSelect(String id, String name, String currentValue, SessionConfigSelectOptions options) {
			this("select", id, name, null, null, currentValue, options, null);
		}

		/**
		 * A model picker: a select option with category
		 * {@link SessionConfigOptionCategory#MODEL}, the way ACP v1 offers model choice.
		 * @param id the option's id, for example {@code "model"}
		 * @param name the human-readable name
		 * @param currentValue the value of the selected model
		 * @param options the models, ungrouped
		 * @return the model option
		 */
		public static SessionConfigSelect model(String id, String name, String currentValue,
				List<SessionConfigSelectOption> options) {
			return model(id, name, currentValue, SessionConfigSelectOptions.ungrouped(options));
		}

		/**
		 * A model picker whose models may be grouped.
		 * @param id the option's id, for example {@code "model"}
		 * @param name the human-readable name
		 * @param currentValue the value of the selected model
		 * @param options the models, grouped or ungrouped
		 * @return the model option
		 * @see #model(String, String, String, List)
		 */
		public static SessionConfigSelect model(String id, String name, String currentValue,
				SessionConfigSelectOptions options) {
			return new SessionConfigSelect("select", id, name, null, SessionConfigOptionCategory.MODEL, currentValue,
					options, null);
		}

		/**
		 * A builder that reaches every field. {@code id}, {@code name},
		 * {@code currentValue} and the options are required.
		 * @return a new builder
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builds a {@link SessionConfigSelect}.
		 */
		public static final class Builder {

			private @Nullable String id;

			private @Nullable String name;

			private @Nullable String description;

			private @Nullable String category;

			private @Nullable String currentValue;

			private @Nullable SessionConfigSelectOptions options;

			private @Nullable Map<String, Object> meta;

			private Builder() {
			}

			/**
			 * Sets {@code id}.
			 * @param id the option's id, sent back in {@code session/set_config_option}
			 * @return this builder
			 */
			public Builder id(String id) {
				this.id = id;
				return this;
			}

			/**
			 * Sets {@code name}.
			 * @param name the human-readable name
			 * @return this builder
			 */
			public Builder name(String name) {
				this.name = name;
				return this;
			}

			/**
			 * Sets {@code description}.
			 * @param description an optional description
			 * @return this builder
			 */
			public Builder description(@Nullable String description) {
				this.description = description;
				return this;
			}

			/**
			 * Sets {@code category}.
			 * @param category an optional category, one of
			 * {@link SessionConfigOptionCategory} or a custom one starting with {@code _}
			 * @return this builder
			 */
			public Builder category(@Nullable String category) {
				this.category = category;
				return this;
			}

			/**
			 * Sets {@code currentValue}.
			 * @param currentValue the value of the selected option
			 * @return this builder
			 */
			public Builder currentValue(String currentValue) {
				this.currentValue = currentValue;
				return this;
			}

			/**
			 * Sets {@code options}.
			 * @param options the options, ungrouped
			 * @return this builder
			 */
			public Builder options(List<SessionConfigSelectOption> options) {
				return options(SessionConfigSelectOptions.ungrouped(options));
			}

			/**
			 * Sets {@code groups}.
			 * @param groups the options, in groups
			 * @return this builder
			 */
			public Builder groups(List<SessionConfigSelectGroup> groups) {
				return options(SessionConfigSelectOptions.grouped(groups));
			}

			/**
			 * Sets {@code options}.
			 * @param options the options, grouped or ungrouped
			 * @return this builder
			 */
			public Builder options(SessionConfigSelectOptions options) {
				this.options = options;
				return this;
			}

			/**
			 * Sets {@code meta}.
			 * @param meta optional {@code _meta}
			 * @return this builder
			 */
			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			/**
			 * Builds the option.
			 * @return the option
			 * @throws IllegalStateException when {@code id}, {@code name},
			 * {@code currentValue} or the options were not set
			 */
			public SessionConfigSelect build() {
				return new SessionConfigSelect("select", required(this.id, "id"), required(this.name, "name"),
						this.description, this.category, required(this.currentValue, "currentValue"),
						required(this.options, "options"), this.meta);
			}

		}
	}

	/**
	 * Boolean-type config option - a toggle (stable in ACP v1 since 2026-07-06). Set it with
	 * {@link SetSessionConfigOptionRequest#bool}. The short constructor sets no
	 * description, category or {@code _meta}; {@link #builder()} reaches every field.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionConfigBoolean(
			@JsonProperty("type") String type,
			@JsonProperty("id") String id, @JsonProperty("name") String name,
			@JsonProperty("description") @Nullable String description, @JsonProperty("category") @Nullable String category,
			@JsonProperty("currentValue") Boolean currentValue,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionConfigOption {
		public SessionConfigBoolean {
			type = discriminator(type, "boolean");
		}

		public SessionConfigBoolean(String id, String name, Boolean currentValue) {
			this("boolean", id, name, null, null, currentValue, null);
		}

		/**
		 * A builder that reaches every field. {@code id}, {@code name} and
		 * {@code currentValue} are required.
		 * @return a new builder
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builds a {@link SessionConfigBoolean}.
		 */
		public static final class Builder {

			private @Nullable String id;

			private @Nullable String name;

			private @Nullable String description;

			private @Nullable String category;

			private @Nullable Boolean currentValue;

			private @Nullable Map<String, Object> meta;

			private Builder() {
			}

			/**
			 * Sets {@code id}.
			 * @param id the option's id, sent back in {@code session/set_config_option}
			 * @return this builder
			 */
			public Builder id(String id) {
				this.id = id;
				return this;
			}

			/**
			 * Sets {@code name}.
			 * @param name the human-readable name
			 * @return this builder
			 */
			public Builder name(String name) {
				this.name = name;
				return this;
			}

			/**
			 * Sets {@code description}.
			 * @param description an optional description
			 * @return this builder
			 */
			public Builder description(@Nullable String description) {
				this.description = description;
				return this;
			}

			/**
			 * Sets {@code category}.
			 * @param category an optional category, one of
			 * {@link SessionConfigOptionCategory} or a custom one starting with {@code _}
			 * @return this builder
			 */
			public Builder category(@Nullable String category) {
				this.category = category;
				return this;
			}

			/**
			 * Sets {@code currentValue}.
			 * @param currentValue whether the option is on
			 * @return this builder
			 */
			public Builder currentValue(boolean currentValue) {
				this.currentValue = currentValue;
				return this;
			}

			/**
			 * Sets {@code meta}.
			 * @param meta optional {@code _meta}
			 * @return this builder
			 */
			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			/**
			 * Builds the option.
			 * @return the option
			 * @throws IllegalStateException when {@code id}, {@code name} or
			 * {@code currentValue} was not set
			 */
			public SessionConfigBoolean build() {
				return new SessionConfigBoolean("boolean", required(this.id, "id"), required(this.name, "name"),
						this.description, this.category, required(this.currentValue, "currentValue"), this.meta);
			}

		}
	}

	/**
	 * The options of a select config option: a flat list ({@link UngroupedSelectOptions})
	 * or a list of groups ({@link GroupedSelectOptions}), as the schema's
	 * {@code SessionConfigSelectOptions}. Both are written as a JSON array; a list whose
	 * items have a {@code group} reads as grouped, any other list as ungrouped.
	 */
	public interface SessionConfigSelectOptions {

		/**
		 * Reads the wire list: groups when its items are groups, options otherwise.
		 * @param items the list's items
		 * @return grouped or ungrouped options
		 * @throws IllegalArgumentException when the list mixes options and groups, which
		 * the schema does not allow
		 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		static SessionConfigSelectOptions of(List<SessionConfigSelectItem> items) {
			long groups = items.stream().filter(SessionConfigSelectGroup.class::isInstance).count();
			if (groups != 0 && groups != items.size()) {
				throw new IllegalArgumentException(
						"A select option list mixes options and groups; it must be one or the other");
			}
			return groups == 0 ? ungrouped(items.stream().map(SessionConfigSelectOption.class::cast).toList())
					: grouped(items.stream().map(SessionConfigSelectGroup.class::cast).toList());
		}

		/**
		 * A flat list of options.
		 * @param options the options
		 * @return ungrouped options
		 */
		static SessionConfigSelectOptions ungrouped(List<SessionConfigSelectOption> options) {
			return new UngroupedSelectOptions(options);
		}

		/**
		 * Options in groups.
		 * @param groups the groups
		 * @return grouped options
		 */
		static SessionConfigSelectOptions grouped(List<SessionConfigSelectGroup> groups) {
			return new GroupedSelectOptions(groups);
		}

		/**
		 * Every option, in order, across groups when grouped.
		 * @return the options
		 */
		List<SessionConfigSelectOption> allOptions();

	}

	/**
	 * A flat list of select options.
	 *
	 * @param options the options
	 */
	public record UngroupedSelectOptions(@JsonValue List<SessionConfigSelectOption> options)
			implements SessionConfigSelectOptions {
		@Override
		public List<SessionConfigSelectOption> allOptions() {
			return options;
		}
	}

	/**
	 * Select options organised in groups.
	 *
	 * @param groups the groups
	 */
	public record GroupedSelectOptions(@JsonValue List<SessionConfigSelectGroup> groups)
			implements SessionConfigSelectOptions {
		@Override
		public List<SessionConfigSelectOption> allOptions() {
			return groups.stream().flatMap(group -> group.options().stream()).toList();
		}
	}

	/**
	 * An item of a select option list on the wire: an option or a group, told apart by
	 * their fields ({@code value} or {@code group}).
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
	@JsonSubTypes({ @JsonSubTypes.Type(SessionConfigSelectOption.class),
			@JsonSubTypes.Type(SessionConfigSelectGroup.class) })
	public interface SessionConfigSelectItem {

	}

	/**
	 * A named group of select options.
	 *
	 * @param group the group's id
	 * @param name human-readable name of the group
	 * @param options the options in the group
	 * @param meta reserved metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionConfigSelectGroup(@JsonProperty("group") String group, @JsonProperty("name") String name,
			@JsonProperty("options") List<SessionConfigSelectOption> options,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionConfigSelectItem {
		public SessionConfigSelectGroup(String group, String name, List<SessionConfigSelectOption> options) {
			this(group, name, options, null);
		}
	}

	/**
	 * A selectable option within a select-type config option.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionConfigSelectOption(@JsonProperty("value") String value,
			@JsonProperty("name") String name, @JsonProperty("description") @Nullable String description,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionConfigSelectItem {
		public SessionConfigSelectOption(String value, String name) {
			this(value, name, null, null);
		}
	}

	/**
	 * Config option update - pushed by agent via session/update notification. It carries the
	 * full list of config options, like {@link SetSessionConfigOptionResponse}.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ConfigOptionUpdate(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("configOptions") List<SessionConfigOption> configOptions,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		public ConfigOptionUpdate {
			sessionUpdate = discriminator(sessionUpdate, "config_option_update");
		}

		/**
		 * The update an agent sends when its config options changed.
		 * @param configOptions every config option with its current value, not only the
		 * changed ones
		 */
		public ConfigOptionUpdate(List<SessionConfigOption> configOptions) {
			this("config_option_update", configOptions, null);
		}
	}

	// ---------------------------
	// Provider Types (UNSTABLE)
	// ---------------------------

	/**
	 * Provider configuration capabilities advertised by the agent. Presence (a non-null
	 * value, including {@code {}}) signals that the agent supports the {@code providers/*}
	 * methods.
	 */
	@UnstableAcpApi
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ProvidersCapabilities(@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ProvidersCapabilities() {
			this(null);
		}
	}

	/**
	 * The current effective (non-secret) routing config for a provider.
	 *
	 * <p>{@code apiType} is a well-known {@code LlmProtocol} identifier (for example
	 * {@code "anthropic"}, {@code "openai"}, {@code "azure"}, {@code "vertex"},
	 * {@code "bedrock"}) or a custom string.
	 */
	@UnstableAcpApi
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ProviderCurrentConfig(@JsonProperty("apiType") String apiType,
			@JsonProperty("baseUrl") String baseUrl) {
	}

	/**
	 * Describes a configurable provider returned by {@code providers/list}.
	 *
	 * @param providerId provider identifier, for example {@code "main"} or {@code "openai"}
	 * @param supported supported {@code LlmProtocol} identifiers for this provider
	 * @param required whether this provider is mandatory and cannot be disabled
	 * @param current current effective non-secret routing config, or {@code null}
	 * @param meta reserved metadata
	 */
	@UnstableAcpApi
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ProviderInfo(@JsonProperty("providerId") String providerId,
			@JsonProperty("supported") List<String> supported,
			@JsonProperty("required") Boolean required, @JsonProperty("current") @Nullable ProviderCurrentConfig current,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ProviderInfo(String providerId, List<String> supported, Boolean required,
				@Nullable ProviderCurrentConfig current) {
			this(providerId, supported, required, current, null);
		}
	}

	/**
	 * Request for {@code providers/list} - lists configurable providers.
	 */
	@UnstableAcpApi
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ListProvidersRequest(@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ListProvidersRequest() {
			this(null);
		}
	}

	/**
	 * Response to {@code providers/list}.
	 */
	@UnstableAcpApi
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ListProvidersResponse(@JsonProperty("providers") List<ProviderInfo> providers,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ListProvidersResponse(List<ProviderInfo> providers) {
			this(providers, null);
		}
	}

	/**
	 * Request for {@code providers/set} - configures a provider.
	 *
	 * @param providerId provider id to configure
	 * @param apiType protocol type for this provider (an {@code LlmProtocol} identifier)
	 * @param baseUrl base URL for requests sent through this provider
	 * @param headers full headers map for this provider (may include authorization), or {@code null}
	 * @param meta reserved metadata
	 */
	@UnstableAcpApi
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SetProviderRequest(@JsonProperty("providerId") String providerId, @JsonProperty("apiType") String apiType,
			@JsonProperty("baseUrl") String baseUrl, @JsonProperty("headers") @Nullable Map<String, String> headers,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public SetProviderRequest(String providerId, String apiType, String baseUrl) {
			this(providerId, apiType, baseUrl, null, null);
		}

		public SetProviderRequest(String providerId, String apiType, String baseUrl,
				@Nullable Map<String, String> headers) {
			this(providerId, apiType, baseUrl, headers, null);
		}
	}

	/**
	 * Response to {@code providers/set}.
	 */
	@UnstableAcpApi
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SetProviderResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		public SetProviderResponse() {
			this(null);
		}
	}

	/**
	 * Request for {@code providers/disable} - disables a provider by id.
	 */
	@UnstableAcpApi
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record DisableProviderRequest(@JsonProperty("providerId") String providerId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public DisableProviderRequest(String providerId) {
			this(providerId, null);
		}
	}

	/**
	 * Response to {@code providers/disable}.
	 */
	@UnstableAcpApi
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record DisableProviderResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		public DisableProviderResponse() {
			this(null);
		}
	}

	// ---------------------------
	// Content Types
	// ---------------------------

	/**
	 * Content block - base type for all content. A block of a type this SDK does not know
	 * reads as an {@link UnknownContentBlock} (see {@link AcpSchema} on forward
	 * compatibility).
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.EXISTING_PROPERTY,
			visible = true, defaultImpl = UnknownContentBlock.class)
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextContent.class, name = "text"),
			@JsonSubTypes.Type(value = ImageContent.class, name = "image"),
			@JsonSubTypes.Type(value = AudioContent.class, name = "audio"),
			@JsonSubTypes.Type(value = ResourceLink.class, name = "resource_link"),
			@JsonSubTypes.Type(value = Resource.class, name = "resource") })
	public interface ContentBlock {

	}

	/**
	 * A content block of a kind this SDK does not know: the peer is newer, or sent an
	 * extension. It keeps the {@code type} discriminator (null when the peer sent none)
	 * and every other field, and writes them back unchanged.
	 *
	 * @param type the discriminator as received
	 * @param fields every other field, in wire order
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UnknownContentBlock(@JsonProperty("type") @Nullable String type,
			@JsonAnySetter @JsonAnyGetter Map<String, Object> fields) implements ContentBlock {
		public UnknownContentBlock {
			fields = unknownFields(fields);
		}
	}

	/**
	 * Text content
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TextContent(@JsonProperty("type") String type,
			@JsonProperty("text") String text, @JsonProperty("annotations") @Nullable Annotations annotations,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ContentBlock {
		public TextContent {
			type = discriminator(type, "text");
		}

		public TextContent(String text) {
			this("text", text, null, null);
		}
	}

	/**
	 * Image content
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ImageContent(@JsonProperty("type") String type,
			@JsonProperty("data") String data, @JsonProperty("mimeType") String mimeType,
			@JsonProperty("uri") @Nullable String uri, @JsonProperty("annotations") @Nullable Annotations annotations,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ContentBlock {
		public ImageContent {
			type = discriminator(type, "image");
		}
	}

	/**
	 * Audio content
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AudioContent(@JsonProperty("type") String type,
			@JsonProperty("data") String data, @JsonProperty("mimeType") String mimeType,
			@JsonProperty("annotations") @Nullable Annotations annotations,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ContentBlock {
		public AudioContent {
			type = discriminator(type, "audio");
		}
	}

	/**
	 * Resource link
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResourceLink(@JsonProperty("type") String type,
			@JsonProperty("name") String name, @JsonProperty("uri") String uri, @JsonProperty("title") @Nullable String title,
			@JsonProperty("description") @Nullable String description, @JsonProperty("mimeType") @Nullable String mimeType,
			@JsonProperty("size") @Nullable Long size, @JsonProperty("annotations") @Nullable Annotations annotations,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ContentBlock {
		public ResourceLink {
			type = discriminator(type, "resource_link");
		}
	}

	/**
	 * Embedded resource
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Resource(@JsonProperty("type") String type,
			@JsonProperty("resource") EmbeddedResourceResource resource,
			@JsonProperty("annotations") @Nullable Annotations annotations,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ContentBlock {
		public Resource {
			type = discriminator(type, "resource");
		}
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
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TextResourceContents(@JsonProperty("text") String text, @JsonProperty("uri") String uri,
			@JsonProperty("mimeType") @Nullable String mimeType,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements EmbeddedResourceResource {
		public TextResourceContents(String text, String uri, @Nullable String mimeType) {
			this(text, uri, mimeType, null);
		}
	}

	/**
	 * Blob resource contents
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record BlobResourceContents(@JsonProperty("blob") String blob, @JsonProperty("uri") String uri,
			@JsonProperty("mimeType") @Nullable String mimeType,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements EmbeddedResourceResource {
		public BlobResourceContents(String blob, String uri, @Nullable String mimeType) {
			this(blob, uri, mimeType, null);
		}
	}

	/**
	 * Annotations for content
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Annotations(@JsonProperty("audience") @Nullable List<Role> audience, @JsonProperty("priority") @Nullable Double priority,
			@JsonProperty("lastModified") @Nullable String lastModified,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public Annotations(@Nullable List<Role> audience, @Nullable Double priority, @Nullable String lastModified) {
			this(audience, priority, lastModified, null);
		}
	}

	// ---------------------------
	// Session Updates
	// ---------------------------

	/**
	 * Session update - different types of updates. An update of a type this SDK does not
	 * know reads as an {@link UnknownSessionUpdate}, so the notification that carries it
	 * still reaches the client's consumers (see {@link AcpSchema} on forward
	 * compatibility).
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "sessionUpdate", include = JsonTypeInfo.As.EXISTING_PROPERTY,
			visible = true, defaultImpl = UnknownSessionUpdate.class)
	@JsonSubTypes({ @JsonSubTypes.Type(value = UserMessageChunk.class, name = "user_message_chunk"),
			@JsonSubTypes.Type(value = AgentMessageChunk.class, name = "agent_message_chunk"),
			@JsonSubTypes.Type(value = AgentThoughtChunk.class, name = "agent_thought_chunk"),
			@JsonSubTypes.Type(value = ToolCall.class, name = "tool_call"),
			@JsonSubTypes.Type(value = ToolCallUpdateNotification.class, name = "tool_call_update"),
			@JsonSubTypes.Type(value = Plan.class, name = "plan"),
			@JsonSubTypes.Type(value = AvailableCommandsUpdate.class, name = "available_commands_update"),
			@JsonSubTypes.Type(value = CurrentModeUpdate.class, name = "current_mode_update"),
			@JsonSubTypes.Type(value = UsageUpdate.class, name = "usage_update"),
			@JsonSubTypes.Type(value = ConfigOptionUpdate.class, name = "config_option_update"),
			@JsonSubTypes.Type(value = SessionInfoUpdate.class, name = "session_info_update") })
	public interface SessionUpdate {

	}

	/**
	 * A session update of a kind this SDK does not know: the peer is newer, or sent an
	 * extension. It keeps the {@code sessionUpdate} discriminator (null when the peer sent none)
	 * and every other field, and writes them back unchanged.
	 *
	 * @param sessionUpdate the discriminator as received
	 * @param fields every other field, in wire order
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UnknownSessionUpdate(@JsonProperty("sessionUpdate") @Nullable String sessionUpdate,
			@JsonAnySetter @JsonAnyGetter Map<String, Object> fields) implements SessionUpdate {
		public UnknownSessionUpdate {
			fields = unknownFields(fields);
		}
	}

	/**
	 * User message chunk
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UserMessageChunk(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("content") ContentBlock content, @JsonProperty("messageId") @Nullable String messageId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		public UserMessageChunk {
			sessionUpdate = discriminator(sessionUpdate, "user_message_chunk");
		}

		public UserMessageChunk(ContentBlock content) {
			this("user_message_chunk", content, null, null);
		}

		public UserMessageChunk(ContentBlock content, @Nullable String messageId) {
			this("user_message_chunk", content, messageId, null);
		}
	}

	/**
	 * Agent message chunk
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AgentMessageChunk(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("content") ContentBlock content, @JsonProperty("messageId") @Nullable String messageId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		public AgentMessageChunk {
			sessionUpdate = discriminator(sessionUpdate, "agent_message_chunk");
		}

		public AgentMessageChunk(ContentBlock content) {
			this("agent_message_chunk", content, null, null);
		}

		public AgentMessageChunk(ContentBlock content, @Nullable String messageId) {
			this("agent_message_chunk", content, messageId, null);
		}
	}

	/**
	 * Agent thought chunk
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AgentThoughtChunk(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("content") ContentBlock content, @JsonProperty("messageId") @Nullable String messageId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		public AgentThoughtChunk {
			sessionUpdate = discriminator(sessionUpdate, "agent_thought_chunk");
		}

		public AgentThoughtChunk(ContentBlock content) {
			this("agent_thought_chunk", content, null, null);
		}

		public AgentThoughtChunk(ContentBlock content, @Nullable String messageId) {
			this("agent_thought_chunk", content, messageId, null);
		}
	}

	/**
	 * Tool call. {@code name} is the programmatic name of the tool being invoked, if the
	 * agent knows it ({@code title} is the human-readable label).
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCall(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("toolCallId") String toolCallId, @JsonProperty("title") String title,
			@JsonProperty("name") @Nullable String name, @JsonProperty("kind") @Nullable ToolKind kind, @JsonProperty("status") @Nullable ToolCallStatus status,
			@JsonProperty("content") @Nullable List<ToolCallContent> content,
			@JsonProperty("locations") @Nullable List<ToolCallLocation> locations, @JsonProperty("rawInput") @Nullable Object rawInput,
			@JsonProperty("rawOutput") @Nullable Object rawOutput,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		public ToolCall {
			sessionUpdate = discriminator(sessionUpdate, "tool_call");
		}
	}

	/**
	 * Tool call update, as carried by a permission request. Every field but
	 * {@code toolCallId} is optional; {@code name} is the tool's programmatic name.
	 */
	// CPD-OFF: the same components as ToolCallUpdateNotification, by design. Both are the
	// schema's ToolCallUpdate: bare in a permission request, and as a session update with its
	// discriminator.
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallUpdate(@JsonProperty("toolCallId") String toolCallId, @JsonProperty("title") @Nullable String title,
			@JsonProperty("name") @Nullable String name, @JsonProperty("kind") @Nullable ToolKind kind, @JsonProperty("status") @Nullable ToolCallStatus status,
			@JsonProperty("content") @Nullable List<ToolCallContent> content,
			@JsonProperty("locations") @Nullable List<ToolCallLocation> locations, @JsonProperty("rawInput") @Nullable Object rawInput,
			@JsonProperty("rawOutput") @Nullable Object rawOutput,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * A tool call for a permission request: its id, title, kind and status.
		 */
		public ToolCallUpdate(String toolCallId, @Nullable String title, @Nullable ToolKind kind,
				@Nullable ToolCallStatus status) {
			this(toolCallId, title, null, kind, status, null, null, null, null, null);
		}
	}
	// CPD-ON

	/**
	 * Tool call update notification. Every field but {@code toolCallId} is optional;
	 * {@code name} is the tool's programmatic name.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallUpdateNotification(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("toolCallId") String toolCallId, @JsonProperty("title") @Nullable String title,
			@JsonProperty("name") @Nullable String name, @JsonProperty("kind") @Nullable ToolKind kind, @JsonProperty("status") @Nullable ToolCallStatus status,
			@JsonProperty("content") @Nullable List<ToolCallContent> content,
			@JsonProperty("locations") @Nullable List<ToolCallLocation> locations, @JsonProperty("rawInput") @Nullable Object rawInput,
			@JsonProperty("rawOutput") @Nullable Object rawOutput,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		public ToolCallUpdateNotification {
			sessionUpdate = discriminator(sessionUpdate, "tool_call_update");
		}
	}

	/**
	 * Plan update
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Plan(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("entries") List<PlanEntry> entries,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		public Plan {
			sessionUpdate = discriminator(sessionUpdate, "plan");
		}

		public Plan(List<PlanEntry> entries) {
			this("plan", entries, null);
		}
	}

	/**
	 * Available commands update
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AvailableCommandsUpdate(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("availableCommands") List<AvailableCommand> availableCommands,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		public AvailableCommandsUpdate {
			sessionUpdate = discriminator(sessionUpdate, "available_commands_update");
		}

		public AvailableCommandsUpdate(List<AvailableCommand> availableCommands) {
			this("available_commands_update", availableCommands, null);
		}
	}

	/**
	 * Current mode update
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CurrentModeUpdate(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("currentModeId") String currentModeId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		public CurrentModeUpdate {
			sessionUpdate = discriminator(sessionUpdate, "current_mode_update");
		}

		public CurrentModeUpdate(String currentModeId) {
			this("current_mode_update", currentModeId, null);
		}
	}

	/**
	 * Session info update - the agent changed the session's metadata (title, last
	 * activity). Every field is optional: a field left out is unchanged.
	 *
	 * <p>
	 * The schema also lets a peer send {@code null} to clear a field. This record cannot
	 * tell an explicit {@code null} from a missing field (both read as {@code null}) and
	 * never writes {@code null}, so it can neither receive nor send a clear.
	 * </p>
	 *
	 * @param sessionUpdate the discriminator, {@code "session_info_update"}
	 * @param title human-readable title for the session
	 * @param updatedAt ISO 8601 timestamp of the last activity
	 * @param meta reserved metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionInfoUpdate(@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("title") @Nullable String title, @JsonProperty("updatedAt") @Nullable String updatedAt,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		public SessionInfoUpdate {
			sessionUpdate = discriminator(sessionUpdate, "session_info_update");
		}

		public SessionInfoUpdate(@Nullable String title, @Nullable String updatedAt) {
			this("session_info_update", title, updatedAt, null);
		}
	}

	/**
	 * Usage update - context window and cost update for the session (UNSTABLE)
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UsageUpdate(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("used") Long used, @JsonProperty("size") Long size, @JsonProperty("cost") @Nullable Cost cost,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		public UsageUpdate {
			sessionUpdate = discriminator(sessionUpdate, "usage_update");
		}

		public UsageUpdate(Long used, Long size) {
			this("usage_update", used, size, null, null);
		}
	}

	/**
	 * Cost information for a session (UNSTABLE)
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Cost(@JsonProperty("amount") Double amount,
			@JsonProperty("currency") String currency,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public Cost(Double amount, String currency) {
			this(amount, currency, null);
		}
	}

	// ---------------------------
	// Tool Call Types
	// ---------------------------

	/**
	 * Tool call content. Content of a type this SDK does not know reads as an
	 * {@link UnknownToolCallContent}.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.EXISTING_PROPERTY,
			visible = true, defaultImpl = UnknownToolCallContent.class)
	@JsonSubTypes({ @JsonSubTypes.Type(value = ToolCallContentBlock.class, name = "content"),
			@JsonSubTypes.Type(value = ToolCallDiff.class, name = "diff"),
			@JsonSubTypes.Type(value = ToolCallTerminal.class, name = "terminal") })
	public interface ToolCallContent {

	}

	/**
	 * Tool call content of a kind this SDK does not know: the peer is newer, or sent an
	 * extension. It keeps the {@code type} discriminator (null when the peer sent none)
	 * and every other field, and writes them back unchanged.
	 *
	 * @param type the discriminator as received
	 * @param fields every other field, in wire order
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UnknownToolCallContent(@JsonProperty("type") @Nullable String type,
			@JsonAnySetter @JsonAnyGetter Map<String, Object> fields) implements ToolCallContent {
		public UnknownToolCallContent {
			fields = unknownFields(fields);
		}
	}

	/**
	 * Tool call content block
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallContentBlock(
			@JsonProperty("type") String type,
			@JsonProperty("content") ContentBlock content,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ToolCallContent {
		public ToolCallContentBlock(String type, ContentBlock content) {
			this(type, content, null);
		}

		public ToolCallContentBlock {
			type = discriminator(type, "content");
		}
	}

	/**
	 * Tool call diff
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallDiff(@JsonProperty("type") String type,
			@JsonProperty("path") String path, @JsonProperty("oldText") @Nullable String oldText,
			@JsonProperty("newText") String newText,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ToolCallContent {
		public ToolCallDiff(String type, String path, @Nullable String oldText, String newText) {
			this(type, path, oldText, newText, null);
		}

		public ToolCallDiff {
			type = discriminator(type, "diff");
		}
	}

	/**
	 * Tool call terminal
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallTerminal(@JsonProperty("type") String type,
			@JsonProperty("terminalId") String terminalId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ToolCallContent {
		public ToolCallTerminal(String type, String terminalId) {
			this(type, terminalId, null);
		}

		public ToolCallTerminal {
			type = discriminator(type, "terminal");
		}
	}

	/**
	 * Tool call location
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallLocation(@JsonProperty("path") String path, @JsonProperty("line") @Nullable Integer line,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public ToolCallLocation(String path, @Nullable Integer line) {
			this(path, line, null);
		}
	}

	// ---------------------------
	// Enums
	// ---------------------------

	/**
	 * Why the agent ended a prompt turn: the {@link PromptResponse#stopReason()} of every
	 * answered {@code session/prompt}. Agents answer with one of the constants; clients compare
	 * the value they receive with {@code equals}, or switch on {@link #value()}.
	 *
	 * <p>
	 * An open value: a value this SDK does not know, from a newer peer, is kept and written back
	 * unchanged, and its {@link #isKnown()} is {@code false}, so it never fails the message (see
	 * {@link AcpSchema} on forward compatibility). {@link #of} returns the constant for a known
	 * wire value, so a known value read from the wire is one of the constants.
	 *
	 * @param value the wire value, such as {@code "end_turn"}
	 */
	public record StopReason(@JsonValue String value) {

		/** {@code "end_turn"}: the turn ended normally. */
		public static final StopReason END_TURN = new StopReason("end_turn");

		/** {@code "max_tokens"}: the agent reached its maximum number of tokens. */
		public static final StopReason MAX_TOKENS = new StopReason("max_tokens");

		/**
		 * {@code "max_turn_requests"}: the agent reached the maximum number of agent requests it
		 * allows between user turns.
		 */
		public static final StopReason MAX_TURN_REQUESTS = new StopReason("max_turn_requests");

		/**
		 * {@code "refusal"}: the agent refused to continue. The refused prompt and everything after
		 * it will not be part of the next prompt, and a client should show this to the user.
		 */
		public static final StopReason REFUSAL = new StopReason("refusal");

		/**
		 * {@code "cancelled"}: the client cancelled the turn with {@code session/cancel}. An agent
		 * must answer with this after a cancel, even when the cancel made its work fail.
		 */
		public static final StopReason CANCELLED = new StopReason("cancelled");

		private static final List<StopReason> KNOWN = List.of(END_TURN, MAX_TOKENS, MAX_TURN_REQUESTS, REFUSAL, CANCELLED);

		/**
		 * Creates a stop reason for a wire value. Prefer {@link #of}, which returns the constant
		 * for a known value; a value created here still equals that constant.
		 * @param value the wire value
		 * @throws NullPointerException if {@code value} is {@code null}
		 */
		public StopReason {
			Objects.requireNonNull(value, "value");
		}

		/**
		 * Returns the stop reason for a wire value: the constant when ACP v1 defines the value, and
		 * otherwise a new, unknown value.
		 * @param value the wire value
		 * @return the constant, or a new value for an unknown string
		 * @throws NullPointerException if {@code value} is {@code null}
		 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static StopReason of(String value) {
			return knownOrNew(KNOWN, StopReason::value, value, StopReason::new);
		}

		/**
		 * Returns the values ACP v1 defines, in schema order.
		 * @return the constants, in an unmodifiable list
		 */
		public static List<StopReason> known() {
			return KNOWN;
		}

		/**
		 * Returns whether ACP v1 defines this value; a value from a newer peer is not known.
		 * @return {@code true} for the value of one of the constants
		 */
		public boolean isKnown() {
			return KNOWN.contains(this);
		}

		/**
		 * Returns the wire value, such as {@code end_turn}.
		 * @return the wire value
		 */
		@Override
		public String toString() {
			return value;
		}

	}

	/**
	 * The execution status of a tool call. An open value: a value this SDK does not know (a newer peer) is kept and
	 * written back unchanged, so it never fails the message (see {@link AcpSchema} on forward
	 * compatibility). The constants name the values ACP v1 defines; {@link #of} returns them
	 * for their wire values, so a known value read from the wire is one of them.
	 *
	 * @param value the wire value
	 */
	public record ToolCallStatus(@JsonValue String value) {

		/** {@code "pending"}. */
		public static final ToolCallStatus PENDING = new ToolCallStatus("pending");

		/** {@code "in_progress"}. */
		public static final ToolCallStatus IN_PROGRESS = new ToolCallStatus("in_progress");

		/** {@code "completed"}. */
		public static final ToolCallStatus COMPLETED = new ToolCallStatus("completed");

		/** {@code "failed"}. */
		public static final ToolCallStatus FAILED = new ToolCallStatus("failed");

		private static final List<ToolCallStatus> KNOWN = List.of(PENDING, IN_PROGRESS, COMPLETED, FAILED);

		public ToolCallStatus {
			Objects.requireNonNull(value, "value");
		}

		/**
		 * The value for a wire string: the constant when ACP v1 defines it.
		 * @param value the wire value
		 * @return the constant, or a new value for an unknown string
		 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static ToolCallStatus of(String value) {
			return knownOrNew(KNOWN, ToolCallStatus::value, value, ToolCallStatus::new);
		}

		/**
		 * The values ACP v1 defines, in schema order.
		 * @return the known values
		 */
		public static List<ToolCallStatus> known() {
			return KNOWN;
		}

		/**
		 * Whether ACP v1 defines this value.
		 * @return true for a known value
		 */
		public boolean isKnown() {
			return KNOWN.contains(this);
		}

		@Override
		public String toString() {
			return value;
		}

	}

	/**
	 * The kind of tool a tool call invokes. The schema's {@code other} is the catch-all: a
	 * kind this SDK does not know reads as {@link #OTHER}, as in the Rust SDK (see
	 * {@link AcpSchema} on forward compatibility).
	 */
	public enum ToolKind {

		READ("read"), EDIT("edit"), DELETE("delete"), MOVE("move"), SEARCH("search"), EXECUTE("execute"),
		THINK("think"), FETCH("fetch"), SWITCH_MODE("switch_mode"), OTHER("other");

		private final String value;

		ToolKind(String value) {
			this.value = value;
		}

		/**
		 * The wire value.
		 * @return the kind's name in ACP
		 */
		@JsonValue
		public String value() {
			return value;
		}

		/**
		 * The kind for a wire value; {@link #OTHER} for one this SDK does not know.
		 * @param value the wire value
		 * @return the kind
		 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static ToolKind of(String value) {
			for (ToolKind kind : values()) {
				if (kind.value.equals(value)) {
					return kind;
				}
			}
			return OTHER;
		}

	}

	/**
	 * A conversation role, as named in content annotations. An open value: a value this SDK does not know (a newer peer) is kept and
	 * written back unchanged, so it never fails the message (see {@link AcpSchema} on forward
	 * compatibility). The constants name the values ACP v1 defines; {@link #of} returns them
	 * for their wire values, so a known value read from the wire is one of them.
	 *
	 * @param value the wire value
	 */
	public record Role(@JsonValue String value) {

		/** {@code "assistant"}. */
		public static final Role ASSISTANT = new Role("assistant");

		/** {@code "user"}. */
		public static final Role USER = new Role("user");

		private static final List<Role> KNOWN = List.of(ASSISTANT, USER);

		public Role {
			Objects.requireNonNull(value, "value");
		}

		/**
		 * The value for a wire string: the constant when ACP v1 defines it.
		 * @param value the wire value
		 * @return the constant, or a new value for an unknown string
		 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static Role of(String value) {
			return knownOrNew(KNOWN, Role::value, value, Role::new);
		}

		/**
		 * The values ACP v1 defines, in schema order.
		 * @return the known values
		 */
		public static List<Role> known() {
			return KNOWN;
		}

		/**
		 * Whether ACP v1 defines this value.
		 * @return true for a known value
		 */
		public boolean isKnown() {
			return KNOWN.contains(this);
		}

		@Override
		public String toString() {
			return value;
		}

	}

	/**
	 * The kind of a permission option, which a client may use to choose an icon or a default. An open value: a value this SDK does not know (a newer peer) is kept and
	 * written back unchanged, so it never fails the message (see {@link AcpSchema} on forward
	 * compatibility). The constants name the values ACP v1 defines; {@link #of} returns them
	 * for their wire values, so a known value read from the wire is one of them.
	 *
	 * @param value the wire value
	 */
	public record PermissionOptionKind(@JsonValue String value) {

		/** {@code "allow_once"}. */
		public static final PermissionOptionKind ALLOW_ONCE = new PermissionOptionKind("allow_once");

		/** {@code "allow_always"}. */
		public static final PermissionOptionKind ALLOW_ALWAYS = new PermissionOptionKind("allow_always");

		/** {@code "reject_once"}. */
		public static final PermissionOptionKind REJECT_ONCE = new PermissionOptionKind("reject_once");

		/** {@code "reject_always"}. */
		public static final PermissionOptionKind REJECT_ALWAYS = new PermissionOptionKind("reject_always");

		private static final List<PermissionOptionKind> KNOWN = List.of(ALLOW_ONCE, ALLOW_ALWAYS, REJECT_ONCE, REJECT_ALWAYS);

		public PermissionOptionKind {
			Objects.requireNonNull(value, "value");
		}

		/**
		 * The value for a wire string: the constant when ACP v1 defines it.
		 * @param value the wire value
		 * @return the constant, or a new value for an unknown string
		 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static PermissionOptionKind of(String value) {
			return knownOrNew(KNOWN, PermissionOptionKind::value, value, PermissionOptionKind::new);
		}

		/**
		 * The values ACP v1 defines, in schema order.
		 * @return the known values
		 */
		public static List<PermissionOptionKind> known() {
			return KNOWN;
		}

		/**
		 * Whether ACP v1 defines this value.
		 * @return true for a known value
		 */
		public boolean isKnown() {
			return KNOWN.contains(this);
		}

		@Override
		public String toString() {
			return value;
		}

	}

	/**
	 * The status of a plan entry. An open value: a value this SDK does not know (a newer peer) is kept and
	 * written back unchanged, so it never fails the message (see {@link AcpSchema} on forward
	 * compatibility). The constants name the values ACP v1 defines; {@link #of} returns them
	 * for their wire values, so a known value read from the wire is one of them.
	 *
	 * @param value the wire value
	 */
	public record PlanEntryStatus(@JsonValue String value) {

		/** {@code "pending"}. */
		public static final PlanEntryStatus PENDING = new PlanEntryStatus("pending");

		/** {@code "in_progress"}. */
		public static final PlanEntryStatus IN_PROGRESS = new PlanEntryStatus("in_progress");

		/** {@code "completed"}. */
		public static final PlanEntryStatus COMPLETED = new PlanEntryStatus("completed");

		private static final List<PlanEntryStatus> KNOWN = List.of(PENDING, IN_PROGRESS, COMPLETED);

		public PlanEntryStatus {
			Objects.requireNonNull(value, "value");
		}

		/**
		 * The value for a wire string: the constant when ACP v1 defines it.
		 * @param value the wire value
		 * @return the constant, or a new value for an unknown string
		 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static PlanEntryStatus of(String value) {
			return knownOrNew(KNOWN, PlanEntryStatus::value, value, PlanEntryStatus::new);
		}

		/**
		 * The values ACP v1 defines, in schema order.
		 * @return the known values
		 */
		public static List<PlanEntryStatus> known() {
			return KNOWN;
		}

		/**
		 * Whether ACP v1 defines this value.
		 * @return true for a known value
		 */
		public boolean isKnown() {
			return KNOWN.contains(this);
		}

		@Override
		public String toString() {
			return value;
		}

	}

	/**
	 * The priority of a plan entry. An open value: a value this SDK does not know (a newer peer) is kept and
	 * written back unchanged, so it never fails the message (see {@link AcpSchema} on forward
	 * compatibility). The constants name the values ACP v1 defines; {@link #of} returns them
	 * for their wire values, so a known value read from the wire is one of them.
	 *
	 * @param value the wire value
	 */
	public record PlanEntryPriority(@JsonValue String value) {

		/** {@code "high"}. */
		public static final PlanEntryPriority HIGH = new PlanEntryPriority("high");

		/** {@code "medium"}. */
		public static final PlanEntryPriority MEDIUM = new PlanEntryPriority("medium");

		/** {@code "low"}. */
		public static final PlanEntryPriority LOW = new PlanEntryPriority("low");

		private static final List<PlanEntryPriority> KNOWN = List.of(HIGH, MEDIUM, LOW);

		public PlanEntryPriority {
			Objects.requireNonNull(value, "value");
		}

		/**
		 * The value for a wire string: the constant when ACP v1 defines it.
		 * @param value the wire value
		 * @return the constant, or a new value for an unknown string
		 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static PlanEntryPriority of(String value) {
			return knownOrNew(KNOWN, PlanEntryPriority::value, value, PlanEntryPriority::new);
		}

		/**
		 * The values ACP v1 defines, in schema order.
		 * @return the known values
		 */
		public static List<PlanEntryPriority> known() {
			return KNOWN;
		}

		/**
		 * Whether ACP v1 defines this value.
		 * @return true for a known value
		 */
		public boolean isKnown() {
			return KNOWN.contains(this);
		}

		@Override
		public String toString() {
			return value;
		}

	}

	// ---------------------------
	// Supporting Types
	// ---------------------------

	/**
	 * The name and version of a client or agent, exchanged at {@code initialize}: the client
	 * sends its own as {@link InitializeRequest#clientInfo()}, and the agent answers with
	 * {@link InitializeResponse#agentInfo()}. Use it for display, logs and metrics; the protocol
	 * attaches no behaviour to it.
	 *
	 * @param name the name for programs, also shown when there is no title
	 * @param version the version, such as {@code "1.0.0"}
	 * @param title a human-readable name for user interfaces, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Implementation(@JsonProperty("name") String name, @JsonProperty("version") String version,
			@JsonProperty("title") @Nullable String title,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates an implementation without {@code _meta}.
		 * @param name the name for programs
		 * @param version the version
		 * @param title a human-readable name, or {@code null}
		 */
		public Implementation(String name, String version, @Nullable String title) {
			this(name, version, title, null);
		}

		/**
		 * Creates an implementation without a title or {@code _meta}.
		 * @param name the name for programs
		 * @param version the version
		 */
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
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record McpServerStdio(@JsonProperty("name") String name, @JsonProperty("command") String command,
			@JsonProperty("args") List<String> args, @JsonProperty("env") List<EnvVariable> env,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements McpServer {
		public McpServerStdio(String name, String command, List<String> args, List<EnvVariable> env) {
			this(name, command, args, env, null);
		}
	}

	/**
	 * HTTP MCP server.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record McpServerHttp(@JsonProperty("name") String name, @JsonProperty("url") String url,
			@JsonProperty("headers") List<HttpHeader> headers,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements McpServer {
		public McpServerHttp(String name, String url, List<HttpHeader> headers) {
			this(name, url, headers, null);
		}

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
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record McpServerSse(@JsonProperty("name") String name, @JsonProperty("url") String url,
			@JsonProperty("headers") List<HttpHeader> headers,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements McpServer {
		public McpServerSse(String name, String url, List<HttpHeader> headers) {
			this(name, url, headers, null);
		}

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
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record EnvVariable(@JsonProperty("name") String name, @JsonProperty("value") String value,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public EnvVariable(String name, String value) {
			this(name, value, null);
		}
	}

	/**
	 * HTTP header
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record HttpHeader(@JsonProperty("name") String name, @JsonProperty("value") String value,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public HttpHeader(String name, String value) {
			this(name, value, null);
		}
	}

	/**
	 * Terminal exit status
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TerminalExitStatus(@JsonProperty("exitCode") @Nullable Integer exitCode,
			@JsonProperty("signal") @Nullable String signal,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public TerminalExitStatus(@Nullable Integer exitCode, @Nullable String signal) {
			this(exitCode, signal, null);
		}
	}

	/**
	 * An authentication method the agent offers, discriminated by {@code type}.
	 *
	 * <p>
	 * A method without a {@code type} is an {@link AuthMethodAgent}: the client calls
	 * {@code authenticate} with its id and the agent does the rest. {@code "terminal"} is an
	 * {@link AuthMethodTerminal}: the client runs the agent program itself, interactively,
	 * with the method's extra arguments and environment. A method of any other type,
	 * including {@code "agent"} (which the Kotlin SDK writes), also reads as an
	 * {@link AuthMethodAgent}, as in the Rust SDK, where the agent variant is the untagged
	 * fallback (see {@link AcpSchema} on forward compatibility); its {@code type} is not
	 * kept.
	 * </p>
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.EXISTING_PROPERTY,
			defaultImpl = AuthMethodAgent.class)
	@JsonSubTypes({ @JsonSubTypes.Type(value = AuthMethodTerminal.class, name = "terminal") })
	public interface AuthMethod {

		/** The id the client passes to {@code authenticate}. */
		String id();

		/** Human-readable name of the method. */
		String name();

		/** Optional description of the method. */
		@Nullable String description();

	}

	/**
	 * Agent authentication: the agent handles it in {@code authenticate}. Written without a
	 * {@code type}, which is how the schema marks the agent method.
	 *
	 * @param id the method id
	 * @param name human-readable name
	 * @param description optional description
	 * @param meta reserved metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AuthMethodAgent(@JsonProperty("id") String id, @JsonProperty("name") String name,
			@JsonProperty("description") @Nullable String description,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements AuthMethod {
		public AuthMethodAgent(String id, String name, @Nullable String description) {
			this(id, name, description, null);
		}
	}

	/**
	 * Terminal authentication: the client runs the agent program as a separate interactive
	 * process (a TUI login), adding {@code args} to its arguments and {@code env} to its
	 * environment, and does not pass this method to {@code authenticate}. Offered only to a
	 * client that advertises {@code clientCapabilities.auth.terminal}.
	 *
	 * @param id the method id
	 * @param name human-readable name
	 * @param description optional description
	 * @param args extra arguments for the agent program
	 * @param env extra environment variables for the agent program
	 * @param meta reserved metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AuthMethodTerminal(@JsonProperty("id") String id, @JsonProperty("name") String name,
			@JsonProperty("description") @Nullable String description,
			@JsonProperty("args") @Nullable List<String> args, @JsonProperty("env") @Nullable Map<String, String> env,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements AuthMethod {
		public AuthMethodTerminal(String id, String name, @Nullable List<String> args,
				@Nullable Map<String, String> env) {
			this(id, name, null, args, env, null);
		}

		/**
		 * The discriminator, {@code "terminal"}.
		 * @return {@code "terminal"}
		 */
		@JsonProperty("type")
		public String type() {
			return "terminal";
		}
	}

	/**
	 * Authentication capabilities the client advertises.
	 *
	 * @param terminal whether the client supports {@code terminal} auth methods (default
	 * false)
	 * @param meta reserved metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AuthCapabilities(@JsonProperty("terminal") @Nullable Boolean terminal,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public AuthCapabilities(@Nullable Boolean terminal) {
			this(terminal, null);
		}
	}

	/**
	 * Permission option
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PermissionOption(@JsonProperty("optionId") String optionId, @JsonProperty("name") String name,
			@JsonProperty("kind") PermissionOptionKind kind,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public PermissionOption(String optionId, String name, PermissionOptionKind kind) {
			this(optionId, name, kind, null);
		}
	}

	/**
	 * Request permission outcome. An outcome this SDK does not know reads as an
	 * {@link UnknownPermissionOutcome}.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "outcome", include = JsonTypeInfo.As.EXISTING_PROPERTY,
			visible = true, defaultImpl = UnknownPermissionOutcome.class)
	@JsonSubTypes({ @JsonSubTypes.Type(value = PermissionCancelled.class, name = "cancelled"),
			@JsonSubTypes.Type(value = PermissionSelected.class, name = "selected") })
	public interface RequestPermissionOutcome {

	}

	/**
	 * A permission outcome of a kind this SDK does not know: the peer is newer, or sent an
	 * extension. It keeps the {@code outcome} discriminator (null when the peer sent none)
	 * and every other field, and writes them back unchanged.
	 *
	 * @param outcome the discriminator as received
	 * @param fields every other field, in wire order
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UnknownPermissionOutcome(@JsonProperty("outcome") @Nullable String outcome,
			@JsonAnySetter @JsonAnyGetter Map<String, Object> fields) implements RequestPermissionOutcome {
		public UnknownPermissionOutcome {
			fields = unknownFields(fields);
		}
	}

	/**
	 * Permission cancelled
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PermissionCancelled(
			@JsonProperty("outcome") String outcome)
			implements RequestPermissionOutcome {
		public PermissionCancelled {
			outcome = discriminator(outcome, "cancelled");
		}

		public PermissionCancelled() {
			this("cancelled");
		}
	}

	/**
	 * Permission selected
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PermissionSelected(
			@JsonProperty("outcome") String outcome,
			@JsonProperty("optionId") String optionId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements RequestPermissionOutcome {
		public PermissionSelected(String outcome, String optionId) {
			this(outcome, optionId, null);
		}

		public PermissionSelected {
			outcome = discriminator(outcome, "selected");
		}

		public PermissionSelected(String optionId) {
			this("selected", optionId);
		}
	}

	/**
	 * Plan entry
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PlanEntry(@JsonProperty("content") String content,
			@JsonProperty("priority") PlanEntryPriority priority, @JsonProperty("status") PlanEntryStatus status,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public PlanEntry(String content, PlanEntryPriority priority, PlanEntryStatus status) {
			this(content, priority, status, null);
		}
	}

	/**
	 * Available command
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AvailableCommand(@JsonProperty("name") String name, @JsonProperty("description") String description,
			@JsonProperty("input") @Nullable AvailableCommandInput input,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public AvailableCommand(String name, String description, @Nullable AvailableCommandInput input) {
			this(name, description, input, null);
		}
	}

	/**
	 * Available command input
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AvailableCommandInput(@JsonProperty("hint") String hint,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		public AvailableCommandInput(String hint) {
			this(hint, null);
		}
	}

}
