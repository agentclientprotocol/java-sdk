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
import java.util.UUID;
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
		 * data. The SDK does not use it for the errors it receives: the caller of a failed request
		 * gets an {@link AcpError}, which a handler can let escape to pass the error on as it is.
		 * @return a protocol exception carrying this error's code, message and data
		 */
		public AcpProtocolException toException() {
			return new AcpProtocolException(code, message, data);
		}
	}

	/**
	 * Marks a response whose every component is optional, so that a peer may answer the request
	 * without a result. A received response with {@code "result": null}, which JSON-RPC 2.0 allows,
	 * or without a {@code result} member reads as if the peer had sent {@code {}}: the call
	 * completes with a record whose components are all {@code null}. Some peers answer this way
	 * when their handler returns nothing.
	 *
	 * <p>
	 * For a response type that does not implement it, a missing result fails the call with an
	 * {@link AcpError} of code {@code -32603} (Internal error); only the call of an extension
	 * method completes empty instead. In this class exactly the response types whose every
	 * component is optional implement it, such as {@link WriteTextFileResponse} and
	 * {@link AuthenticateResponse}. It has no methods, and it changes only how a received response
	 * is read, not what the SDK sends.
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
	 * The params of {@code authenticate}: the client logs in with one of the
	 * {@link AuthMethodAgent} methods the agent listed in {@link InitializeResponse#authMethods()},
	 * named by its id. A client sends it with
	 * {@link com.agentclientprotocol.sdk.client.AcpSyncClient#authenticate
	 * AcpSyncClient.authenticate} or the {@code AcpAsyncClient} method of the same name, when the
	 * agent requires a login: such an agent answers other requests with {@code -32000}
	 * (authentication required) until then. The agent's authenticate handler
	 * ({@link com.agentclientprotocol.sdk.agent.AcpAgent.AuthenticateHandler} or an
	 * {@link com.agentclientprotocol.sdk.annotation.Authenticate @Authenticate} method) receives it
	 * and answers with an {@link AuthenticateResponse}.
	 *
	 * <p>
	 * Pass only the id of an {@link AuthMethodAgent}. The protocol forbids passing an
	 * {@link AuthMethodTerminal}: the client runs that one itself, outside the connection. The SDK
	 * checks the id on neither side: the client sends any id, and the agent's handler receives any
	 * id, so a handler should reject an id it did not advertise. To refuse a login, the handler
	 * fails with an {@link AcpProtocolException} carrying
	 * {@link AcpErrorCodes#AUTHENTICATION_REQUIRED}. An agent without an authenticate handler
	 * answers {@code -32601} (method not found).
	 *
	 * @param methodId the id of the chosen {@link AuthMethodAgent}, one the agent advertised
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AuthenticateRequest(@JsonProperty("methodId") String methodId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without {@code _meta}.
		 * @param methodId the id of the chosen agent auth method
		 */
		public AuthenticateRequest(String methodId) {
			this(methodId, null);
		}
	}

	/**
	 * The result of {@code authenticate}: an empty answer that confirms the login succeeded. The
	 * agent's authenticate handler returns it once it has checked the login; the client's
	 * {@code authenticate(...)} completes with it. A failed login is an error answer, not this
	 * record.
	 *
	 * <p>
	 * The SDK does not record that a client has logged in. An agent's handlers that need a login
	 * check for one themselves, and fail with {@link AcpErrorCodes#AUTHENTICATION_REQUIRED} when
	 * there is none.
	 *
	 * <p>
	 * It has only {@code _meta}, so a peer that answers with an empty or {@code null} result gives
	 * this record, with no {@code _meta} (see {@link DefaultOnNull}).
	 *
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AuthenticateResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		/** Creates the empty response, without {@code _meta}. */
		public AuthenticateResponse() {
			this(null);
		}
	}

	/**
	 * The params of {@code logout}: asks the agent to end the client's authenticated state, so that
	 * new sessions need a login again. A client sends it with
	 * {@link com.agentclientprotocol.sdk.client.AcpSyncClient#logout AcpSyncClient.logout} or the
	 * {@code AcpAsyncClient} method of the same name. The agent's logout handler
	 * ({@link com.agentclientprotocol.sdk.agent.AcpAgent.LogoutHandler} or a
	 * {@link com.agentclientprotocol.sdk.annotation.Logout @Logout} method) receives it and answers
	 * with a {@link LogoutResponse}.
	 *
	 * <p>
	 * Only an agent that advertises {@code auth.logout}
	 * ({@link AgentAuthCapabilities#withLogout()}) supports it: for any other agent the client
	 * fails the call with an {@link com.agentclientprotocol.sdk.error.AcpCapabilityException}
	 * without sending it. A builder agent without an initialize handler and an annotated agent
	 * advertise {@code auth.logout} when they have a logout handler. The protocol does not say what
	 * happens to sessions already running: the agent may end them, keep them, or fail their later
	 * requests with {@code -32000} (authentication required).
	 *
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LogoutRequest(@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/** Creates a request without {@code _meta}. */
		public LogoutRequest() {
			this(null);
		}
	}

	/**
	 * The result of {@code logout}: an empty answer that confirms the client is logged out. The
	 * agent's logout handler returns it; the client's {@code logout(...)} completes with it.
	 *
	 * <p>
	 * It has only {@code _meta}, so a peer that answers with an empty or {@code null} result gives
	 * this record, with no {@code _meta} (see {@link DefaultOnNull}).
	 *
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LogoutResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		/** Creates the empty response, without {@code _meta}. */
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
	 * servers. A non-empty {@code additionalDirectories} needs an agent that advertises
	 * {@code sessionCapabilities.additionalDirectories}: for any other, the client fails the call
	 * with an {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} without sending it.
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
		 * Creates a request for a working directory, with no MCP servers, additional directories
		 * or {@code _meta}.
		 * @param cwd the working directory, an absolute path
		 */
		public NewSessionRequest(String cwd) {
			this(cwd, List.of(), null, null);
		}

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
	 * A minimal agent answers {@code new NewSessionResponse(id)}. An agent without a
	 * new-session handler (a builder agent without {@code newSessionHandler}, an annotated agent
	 * without a {@link com.agentclientprotocol.sdk.annotation.NewSession @NewSession} method)
	 * answers {@link #withGeneratedId()}: a random UUID as the id, and no modes or options.
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
		 * Creates a response with only the session id: no modes, config options or
		 * {@code _meta}.
		 * @param sessionId the id of the new ACP session
		 */
		public NewSessionResponse(String sessionId) {
			this(sessionId, null, null, null);
		}

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

		/**
		 * Returns a response for a new session with a random UUID as its id, and no modes,
		 * config options or {@code _meta}: what an agent without a new-session handler answers.
		 * @return a response with a fresh session id
		 */
		public static NewSessionResponse withGeneratedId() {
			return new NewSessionResponse(UUID.randomUUID().toString());
		}
	}

	/**
	 * The params of {@code session/load}: asks the agent to reopen an ACP session it kept, in a
	 * working directory and with the MCP servers it should connect to. A client sends it with
	 * {@link com.agentclientprotocol.sdk.client.AcpSyncClient#loadSession
	 * AcpSyncClient.loadSession} or the {@code AcpAsyncClient} method of the same name. The agent's
	 * load-session handler ({@link com.agentclientprotocol.sdk.agent.AcpAgent.LoadSessionHandler}
	 * or a {@link com.agentclientprotocol.sdk.annotation.LoadSession @LoadSession} method) receives
	 * it, replays the session's conversation to the client as session updates, and answers with a
	 * {@link LoadSessionResponse}.
	 *
	 * <p>
	 * Only an agent that advertises {@code loadSession} supports it: for any other agent the client
	 * fails the call with an {@link com.agentclientprotocol.sdk.error.AcpCapabilityException}
	 * without sending it. The replayed session updates reach the client's session update consumers
	 * before its call completes. To reopen a session without the replay, use
	 * {@link ResumeSessionRequest}. The protocol requires absolute paths for {@code cwd} and
	 * {@code additionalDirectories}; the SDK does not check them. A non-empty
	 * {@code additionalDirectories} is the complete list of additional workspace roots for the
	 * session. Only an agent that advertises {@code sessionCapabilities.additionalDirectories}
	 * accepts it: for any other, the client fails a call with a non-empty list with an
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} without sending it.
	 *
	 * @param sessionId the ACP session to reopen, as {@link NewSessionResponse#sessionId()} or
	 * {@link SessionInfo#sessionId()} gave it
	 * @param cwd the session's working directory, an absolute path
	 * @param mcpServers the MCP servers the agent should connect to, possibly empty
	 * @param additionalDirectories more workspace roots as absolute paths, or {@code null} for none
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LoadSessionRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("cwd") String cwd,
			@JsonProperty("mcpServers") List<McpServer> mcpServers,
			@JsonProperty("additionalDirectories") @Nullable List<String> additionalDirectories,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without additional directories or {@code _meta}.
		 * @param sessionId the ACP session to reopen
		 * @param cwd the working directory, an absolute path
		 * @param mcpServers the MCP servers, possibly empty
		 */
		public LoadSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers) {
			this(sessionId, cwd, mcpServers, null, null);
		}

		/**
		 * Creates a request without {@code _meta}.
		 * @param sessionId the ACP session to reopen
		 * @param cwd the working directory, an absolute path
		 * @param mcpServers the MCP servers, possibly empty
		 * @param additionalDirectories more workspace roots, or {@code null}
		 */
		public LoadSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers,
				@Nullable List<String> additionalDirectories) {
			this(sessionId, cwd, mcpServers, additionalDirectories, null);
		}
	}

	/**
	 * The result of {@code session/load}: the reopened ACP session's modes and config options, when
	 * the agent has them. The agent's load-session handler returns it after it has replayed the
	 * conversation. The client's {@code loadSession(...)} completes with it, and only after the
	 * client has handled every replayed session update.
	 *
	 * <p>
	 * Every component is optional: {@code new LoadSessionResponse(null)} answers for an agent
	 * without modes or config options, and a peer that answers with an empty or {@code null} result
	 * gives such a record (see {@link DefaultOnNull}).
	 *
	 * @param modes the session's modes and the current one, or {@code null} if the agent has no
	 * modes
	 * @param configOptions the session's config options with their current values, or {@code null}
	 * if the agent has none
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LoadSessionResponse(@JsonProperty("modes") @Nullable SessionModeState modes,
			@JsonProperty("configOptions") @Nullable List<SessionConfigOption> configOptions,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		/**
		 * Creates a response without config options or {@code _meta}.
		 * @param modes the session's modes, or {@code null}
		 */
		public LoadSessionResponse(@Nullable SessionModeState modes) {
			this(modes, null, null);
		}

		/**
		 * Creates a response without {@code _meta}.
		 * @param modes the session's modes, or {@code null}
		 * @param configOptions the session's config options, or {@code null}
		 */
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
		 * Creates a prompt of one text block, the common case:
		 * {@code client.prompt(PromptRequest.text(sessionId, "Fix the failing test"))}.
		 * @param sessionId the ACP session to prompt
		 * @param text the user's message
		 * @return a request whose prompt is one {@link TextContent} block
		 */
		public static PromptRequest text(String sessionId, String text) {
			return new PromptRequest(sessionId, List.of(new TextContent(text)));
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
	 * The params of {@code session/set_mode}: asks the agent to switch an ACP session to another of
	 * the modes it offered for it, such as "ask" or "code". A client sends it with
	 * {@link com.agentclientprotocol.sdk.client.AcpSyncClient#setSessionMode
	 * AcpSyncClient.setSessionMode} or the {@code AcpAsyncClient} method of the same name. The
	 * agent's set-mode handler
	 * ({@link com.agentclientprotocol.sdk.agent.AcpAgent.SetSessionModeHandler} or a
	 * {@link com.agentclientprotocol.sdk.annotation.SetSessionMode @SetSessionMode} method)
	 * receives it and answers with a {@link SetSessionModeResponse}.
	 *
	 * <p>
	 * The agent offers modes in the {@link SessionModeState} of its answers to {@code session/new},
	 * {@code session/load} and {@code session/resume}. No capability advertises them, so the client
	 * sends this request without a capability check. The protocol requires {@code modeId} to be one
	 * of the session's {@link SessionModeState#availableModes()}; the SDK does not check it.
	 *
	 * @param sessionId the ACP session to switch
	 * @param modeId the {@link SessionMode#id()} of the mode to switch to
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SetSessionModeRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("modeId") String modeId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without {@code _meta}.
		 * @param sessionId the ACP session to switch
		 * @param modeId the id of the mode to switch to
		 */
		public SetSessionModeRequest(String sessionId, String modeId) {
			this(sessionId, modeId, null);
		}
	}

	/**
	 * The result of {@code session/set_mode}: an empty answer that confirms the switch. The agent's
	 * set-mode handler returns it after switching the session; the client's
	 * {@code setSessionMode(...)} completes with it.
	 *
	 * <p>
	 * It has only {@code _meta}, so a peer that answers with an empty or {@code null} result gives
	 * this record, with no {@code _meta} (see {@link DefaultOnNull}). When the agent changes a
	 * session's mode on its own, it tells the client with a {@link CurrentModeUpdate} session
	 * update instead.
	 *
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SetSessionModeResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		/** Creates the empty response, without {@code _meta}. */
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
	 * The params of {@code session/list}: asks the agent which ACP sessions it knows, optionally
	 * only those of one working directory, one page at a time. A client sends it with
	 * {@link com.agentclientprotocol.sdk.client.AcpSyncClient#listSessions
	 * AcpSyncClient.listSessions} or the {@code AcpAsyncClient} method of the same name. The
	 * agent's list-sessions handler
	 * ({@link com.agentclientprotocol.sdk.agent.AcpAgent.ListSessionsHandler} or a
	 * {@link com.agentclientprotocol.sdk.annotation.ListSessions @ListSessions} method) receives it
	 * and answers with a {@link ListSessionsResponse}.
	 *
	 * <p>
	 * Only an agent that advertises {@code sessionCapabilities.list} supports it: for any other
	 * agent the client fails the call with an
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} without sending it. Listing
	 * reopens nothing; {@link LoadSessionRequest} and {@link ResumeSessionRequest} do. For the
	 * first page leave {@code cursor} {@code null}; for each next page send the
	 * {@link ListSessionsResponse#nextCursor()} of the previous answer.
	 *
	 * @param cwd the working directory whose sessions to list, an absolute path, or {@code null}
	 * for all sessions
	 * @param cursor the {@code nextCursor} of the previous page, or {@code null} for the first page
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ListSessionsRequest(@JsonProperty("cwd") @Nullable String cwd, @JsonProperty("cursor") @Nullable String cursor,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request for the first page, without {@code _meta}.
		 * @param cwd the working directory to filter by, or {@code null} for all sessions
		 */
		public ListSessionsRequest(@Nullable String cwd) {
			this(cwd, null, null);
		}
	}

	/**
	 * The result of {@code session/list}: one page of the ACP sessions the agent knows, as
	 * {@link SessionInfo} records, and a cursor when more pages remain. The agent's list-sessions
	 * handler builds it; the client's {@code listSessions(...)} completes with it.
	 *
	 * <p>
	 * When {@code nextCursor} is present, send it as the {@code cursor} of the next
	 * {@link ListSessionsRequest} to get the next page; when it is {@code null}, this is the last
	 * page. The cursor is opaque: the agent chooses it and the client only sends it back.
	 * {@code sessions} is required, so an agent with no sessions answers an empty list.
	 *
	 * @param sessions the sessions on this page, possibly empty
	 * @param nextCursor the cursor for the next page, or {@code null} on the last page
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ListSessionsResponse(@JsonProperty("sessions") List<SessionInfo> sessions,
			@JsonProperty("nextCursor") @Nullable String nextCursor,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a last-page response: no next cursor and no {@code _meta}.
		 * @param sessions the sessions, possibly empty
		 */
		public ListSessionsResponse(List<SessionInfo> sessions) {
			this(sessions, null, null);
		}
	}

	/**
	 * The params of {@code session/close}: tells the agent the client is done with an active ACP
	 * session, so that the agent stops the session's work and frees what it holds. A client sends
	 * it with {@link com.agentclientprotocol.sdk.client.AcpSyncClient#closeSession
	 * AcpSyncClient.closeSession} or the {@code AcpAsyncClient} method of the same name. The
	 * agent's close-session handler
	 * ({@link com.agentclientprotocol.sdk.agent.AcpAgent.CloseSessionHandler} or a
	 * {@link com.agentclientprotocol.sdk.annotation.CloseSession @CloseSession} method) receives it
	 * and answers with a {@link CloseSessionResponse}.
	 *
	 * <p>
	 * Only an agent that advertises {@code sessionCapabilities.close} supports it: for any other
	 * agent the client fails the call with an
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} without sending it. The
	 * protocol requires the agent to cancel the session's work as if a {@link CancelNotification}
	 * had arrived. An agent built with this SDK does that before its close handler runs: it calls
	 * its cancel handler, cancels a running prompt turn, and waits until that prompt has answered
	 * {@link StopReason#CANCELLED}. Closing does not delete the session;
	 * {@link DeleteSessionRequest} does.
	 *
	 * @param sessionId the ACP session to close
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CloseSessionRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without {@code _meta}.
		 * @param sessionId the ACP session to close
		 */
		public CloseSessionRequest(String sessionId) {
			this(sessionId, null);
		}
	}

	/**
	 * The result of {@code session/close}: an empty answer that confirms the session is closed. The
	 * agent's close-session handler returns it after freeing the session; the client's
	 * {@code closeSession(...)} completes with it.
	 *
	 * <p>
	 * It has only {@code _meta}, so a peer that answers with an empty or {@code null} result gives
	 * this record, with no {@code _meta} (see {@link DefaultOnNull}).
	 *
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CloseSessionResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		/** Creates the empty response, without {@code _meta}. */
		public CloseSessionResponse() {
			this(null);
		}
	}

	/**
	 * The params of {@code session/delete}: asks the agent to remove a stored ACP session for good,
	 * so that {@code session/list} no longer returns it. A client sends it with
	 * {@link com.agentclientprotocol.sdk.client.AcpSyncClient#deleteSession
	 * AcpSyncClient.deleteSession} or the {@code AcpAsyncClient} method of the same name. The
	 * agent's delete-session handler
	 * ({@link com.agentclientprotocol.sdk.agent.AcpAgent.DeleteSessionHandler} or a
	 * {@link com.agentclientprotocol.sdk.annotation.DeleteSession @DeleteSession} method) receives
	 * it and answers with a {@link DeleteSessionResponse}.
	 *
	 * <p>
	 * Only an agent that advertises {@code sessionCapabilities.delete} supports it: for any other
	 * agent the client fails the call with an
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} without sending it. Deleting
	 * is not closing: unlike for {@link CloseSessionRequest}, the SDK does not cancel the session's
	 * work first.
	 *
	 * @param sessionId the ACP session to delete
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record DeleteSessionRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without {@code _meta}.
		 * @param sessionId the ACP session to delete
		 */
		public DeleteSessionRequest(String sessionId) {
			this(sessionId, null);
		}
	}

	/**
	 * The result of {@code session/delete}: an empty answer that confirms the session is deleted.
	 * The agent's delete-session handler returns it; the client's {@code deleteSession(...)}
	 * completes with it.
	 *
	 * <p>
	 * It has only {@code _meta}, so a peer that answers with an empty or {@code null} result gives
	 * this record, with no {@code _meta} (see {@link DefaultOnNull}).
	 *
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record DeleteSessionResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		/** Creates the empty response, without {@code _meta}. */
		public DeleteSessionResponse() {
			this(null);
		}
	}

	/**
	 * The params of {@code session/resume}: asks the agent to reopen an ACP session it kept,
	 * without replaying its conversation. A client sends it with
	 * {@link com.agentclientprotocol.sdk.client.AcpSyncClient#resumeSession
	 * AcpSyncClient.resumeSession} or the {@code AcpAsyncClient} method of the same name. The
	 * agent's resume-session handler
	 * ({@link com.agentclientprotocol.sdk.agent.AcpAgent.ResumeSessionHandler} or a
	 * {@link com.agentclientprotocol.sdk.annotation.ResumeSession @ResumeSession} method) receives
	 * it and answers with a {@link ResumeSessionResponse}.
	 *
	 * <p>
	 * Only an agent that advertises {@code sessionCapabilities.resume} supports it: for any other
	 * agent the client fails the call with an
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} without sending it. Use it
	 * instead of {@link LoadSessionRequest} when the client keeps the conversation itself, or to
	 * reconnect to a session the agent still runs; an agent that can continue a session but cannot
	 * send its history offers only this. Unlike for {@code session/load}, {@code mcpServers} may be
	 * {@code null}. The protocol requires absolute paths for {@code cwd} and
	 * {@code additionalDirectories}; the SDK does not check them. A non-empty
	 * {@code additionalDirectories} is the complete list of additional workspace roots for the
	 * session. Only an agent that advertises {@code sessionCapabilities.additionalDirectories}
	 * accepts it: for any other, the client fails a call with a non-empty list with an
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} without sending it.
	 *
	 * @param sessionId the ACP session to reopen
	 * @param cwd the session's working directory, an absolute path
	 * @param mcpServers the MCP servers the agent should connect to, or {@code null}
	 * @param additionalDirectories more workspace roots as absolute paths, or {@code null} for none
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResumeSessionRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("cwd") String cwd,
			@JsonProperty("mcpServers") @Nullable List<McpServer> mcpServers,
			@JsonProperty("additionalDirectories") @Nullable List<String> additionalDirectories,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without additional directories or {@code _meta}.
		 * @param sessionId the ACP session to reopen
		 * @param cwd the working directory, an absolute path
		 * @param mcpServers the MCP servers, or {@code null}
		 */
		public ResumeSessionRequest(String sessionId, String cwd, @Nullable List<McpServer> mcpServers) {
			this(sessionId, cwd, mcpServers, null, null);
		}

		/**
		 * Creates a request without {@code _meta}.
		 * @param sessionId the ACP session to reopen
		 * @param cwd the working directory, an absolute path
		 * @param mcpServers the MCP servers, or {@code null}
		 * @param additionalDirectories more workspace roots, or {@code null}
		 */
		public ResumeSessionRequest(String sessionId, String cwd, @Nullable List<McpServer> mcpServers,
				@Nullable List<String> additionalDirectories) {
			this(sessionId, cwd, mcpServers, additionalDirectories, null);
		}
	}

	/**
	 * The result of {@code session/resume}: the resumed ACP session's modes and config options,
	 * when the agent has them. The agent's resume-session handler returns it without sending the
	 * earlier conversation; the client's {@code resumeSession(...)} completes with it.
	 *
	 * <p>
	 * Every component is optional: {@code new ResumeSessionResponse(null)} answers for an agent
	 * without modes or config options, and a peer that answers with an empty or {@code null} result
	 * gives such a record (see {@link DefaultOnNull}).
	 *
	 * @param modes the session's modes and the current one, or {@code null} if the agent has no
	 * modes
	 * @param configOptions the session's config options with their current values, or {@code null}
	 * if the agent has none
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResumeSessionResponse(@JsonProperty("modes") @Nullable SessionModeState modes,
			@JsonProperty("configOptions") @Nullable List<SessionConfigOption> configOptions,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		/**
		 * Creates a response without config options or {@code _meta}.
		 * @param modes the session's modes, or {@code null}
		 */
		public ResumeSessionResponse(@Nullable SessionModeState modes) {
			this(modes, null, null);
		}

		/**
		 * Creates a response without {@code _meta}.
		 * @param modes the session's modes, or {@code null}
		 * @param configOptions the session's config options, or {@code null}
		 */
		public ResumeSessionResponse(@Nullable SessionModeState modes,
				@Nullable List<SessionConfigOption> configOptions) {
			this(modes, configOptions, null);
		}
	}

	/**
	 * Fork session request - creates a new session branched from an existing one. A non-empty
	 * {@code additionalDirectories} needs an agent that advertises
	 * {@code sessionCapabilities.additionalDirectories}, as for {@link NewSessionRequest}.
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
	 * The params of {@code session/update}: one {@link SessionUpdate} for an ACP session. The agent
	 * sends it to show the client its work as it happens, such as its reply, its tool calls and its
	 * plan. During a prompt turn the agent sends it with
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#sendUpdate PromptContext.sendUpdate}
	 * or a shortcut such as {@code sendMessage}; at other times with
	 * {@link com.agentclientprotocol.sdk.agent.AcpSyncAgent#sendSessionUpdate
	 * AcpSyncAgent.sendSessionUpdate} or the {@code AcpAsyncAgent} method of the same name. Both
	 * wrap the update in this record. The client's session-update consumers
	 * ({@link com.agentclientprotocol.sdk.client.AcpClient.SyncSpec#sessionUpdateConsumer
	 * sessionUpdateConsumer} on the client builder) receive it. It is a notification, so it gets no
	 * answer of its own.
	 *
	 * <p>
	 * The Java client hands notifications to its consumers one at a time, in the order the agent
	 * sent them, and a response from the agent completes its caller only after the consumers have
	 * handled every notification sent before it: when {@code prompt(...)} returns, that turn's
	 * updates have all been handled. Updates are not limited to prompt turns. An agent replays a
	 * loaded session's conversation as updates before it answers {@code session/load}, and can send
	 * others, such as its available commands, at any time.
	 *
	 * <p>
	 * The protocol requires an agent to send a turn's updates before it answers the prompt, also
	 * after a {@code session/cancel}. The SDK's prompt contexts drop the updates a handler sends
	 * once its prompt has been answered, by the handler or by the SDK when the cancel grace period
	 * or the maximum prompt duration passed (see {@link PromptTimeouts}).
	 *
	 * <p>
	 * The Java client skips, with a warning in the log, a received notification it cannot read or
	 * that lacks a required member at any depth: an update without its content, a plan entry
	 * without its text. The whole notification is skipped, not only the bad part. An update of a
	 * kind this SDK does not know is not skipped: it reads as an {@link UnknownSessionUpdate}. A
	 * client without a session-update consumer ignores {@code session/update}, also with a warning.
	 *
	 * @param sessionId the ACP session the update belongs to
	 * @param update the update
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionNotification(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("update") SessionUpdate update,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a notification without {@code _meta}.
		 * @param sessionId the ACP session the update belongs to
		 * @param update the update
		 */
		public SessionNotification(String sessionId, SessionUpdate update) {
			this(sessionId, update, null);
		}
	}

	/**
	 * The params of {@code fs/read_text_file}: the agent asks the client for the content of a text
	 * file, including changes the user has not yet saved in the editor. An agent in a prompt turn
	 * usually sends it with
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#readFile(String, Integer, Integer)
	 * readFile}, which fills in the session;
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#readTextFile readTextFile(...)} on the
	 * prompt context, and the methods of the same name on {@code AcpAsyncAgent} and
	 * {@code AcpSyncAgent}, take the whole request. The client's handler, set with
	 * {@link com.agentclientprotocol.sdk.client.AcpClient.AsyncSpec#readTextFileHandler
	 * readTextFileHandler} on the client builder, receives it and answers with a
	 * {@link ReadTextFileResponse}.
	 *
	 * <p>
	 * Only a client that advertises {@code fs.readTextFile} ({@link FileSystemCapability}) takes
	 * it. The agent fails the call with an
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} without sending it when the
	 * client did not advertise it, and a client builder that advertises it without registering the
	 * handler fails at {@code build()}.
	 *
	 * <p>
	 * {@code line} counts from 1, so the first line of the file is line 1, and {@code limit} is a
	 * number of lines; leave both {@code null} to read the whole file. The path must be absolute.
	 * The SDK checks neither the path nor the numbers on either side: they reach the client's
	 * handler as the agent sent them, so a client handler that reads any path it is given lets the
	 * agent read every file the client process can read.
	 *
	 * @param sessionId the ACP session the read is for
	 * @param path the absolute path of the file
	 * @param line the first line to read, counting from 1, or {@code null} to start at the first
	 * line
	 * @param limit the most lines to read, or {@code null} for the rest of the file
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ReadTextFileRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("path") String path,
			@JsonProperty("line") @Nullable Integer line, @JsonProperty("limit") @Nullable Integer limit,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without {@code _meta}.
		 * @param sessionId the ACP session the read is for
		 * @param path the absolute path of the file
		 * @param line the first line to read, counting from 1, or {@code null}
		 * @param limit the most lines to read, or {@code null}
		 */
		public ReadTextFileRequest(String sessionId, String path, @Nullable Integer line, @Nullable Integer limit) {
			this(sessionId, path, line, limit, null);
		}
	}

	/**
	 * The result of {@code fs/read_text_file}: the text the client read. The client's read handler
	 * returns it; the agent's {@code readTextFile(...)} completes with it, and
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#readFile(String)
	 * PromptContext.readFile} returns its {@link #content()}.
	 *
	 * <p>
	 * Whether the content is the whole file or only the lines the request asked for is up to the
	 * client's handler; the SDK does not check it.
	 *
	 * @param content the text read: the whole file, or the requested lines
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ReadTextFileResponse(@JsonProperty("content") String content,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a response without {@code _meta}.
		 * @param content the text read
		 */
		public ReadTextFileResponse(String content) {
			this(content, null);
		}
	}

	/**
	 * The params of {@code fs/write_text_file}: the agent asks the client to write text to a file.
	 * An agent in a prompt turn usually sends it with
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#writeFile(String, String) writeFile},
	 * which fills in the session;
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#writeTextFile writeTextFile(...)} on
	 * the prompt context, and the methods of the same name on {@code AcpAsyncAgent} and
	 * {@code AcpSyncAgent}, take the whole request. The client's handler, set with
	 * {@link com.agentclientprotocol.sdk.client.AcpClient.AsyncSpec#writeTextFileHandler
	 * writeTextFileHandler} on the client builder, receives it and answers with a
	 * {@link WriteTextFileResponse} once the file is written.
	 *
	 * <p>
	 * Only a client that advertises {@code fs.writeTextFile} ({@link FileSystemCapability}) takes
	 * it, with the same checks as {@link ReadTextFileRequest}: the agent does not send it to a
	 * client that did not advertise it, and a client builder fails at {@code build()} if it
	 * advertises it without the handler. The protocol requires the client to create the file if it
	 * does not exist.
	 *
	 * <p>
	 * The path must be absolute. The SDK checks neither the path nor the content on either side, so
	 * a client handler that writes any path it is given lets the agent change every file the client
	 * process can write.
	 *
	 * @param sessionId the ACP session the write is for
	 * @param path the absolute path of the file
	 * @param content the text to write
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WriteTextFileRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("path") String path,
			@JsonProperty("content") String content,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without {@code _meta}.
		 * @param sessionId the ACP session the write is for
		 * @param path the absolute path of the file
		 * @param content the text to write
		 */
		public WriteTextFileRequest(String sessionId, String path, String content) {
			this(sessionId, path, content, null);
		}
	}

	/**
	 * The result of {@code fs/write_text_file}: an empty answer that confirms the file was written.
	 * The client's write handler returns it; the agent's {@code writeTextFile(...)} completes with
	 * it, and {@link com.agentclientprotocol.sdk.agent.PromptContext#writeFile(String, String)
	 * PromptContext.writeFile} completes. A failed write is an error answer, not this record.
	 *
	 * <p>
	 * It has only {@code _meta}, so a peer that answers with an empty or {@code null} result gives
	 * this record, with no {@code _meta} (see {@link DefaultOnNull}).
	 *
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WriteTextFileResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		/** Creates the empty response, without {@code _meta}. */
		public WriteTextFileResponse() {
			this(null);
		}
	}

	/**
	 * The params of {@code terminal/create}: the agent asks the client to start a command in a new
	 * terminal. The client answers at once with the terminal's ID ({@link CreateTerminalResponse}),
	 * without waiting for the command to end; the agent passes that ID to {@code terminal/output},
	 * {@code terminal/wait_for_exit}, {@code terminal/kill} and {@code terminal/release}. Most
	 * agents do not build this request:
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#execute(Command) execute} on the
	 * prompt context builds it from a {@link com.agentclientprotocol.sdk.agent.Command} and runs
	 * the whole sequence. To send it yourself, use
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#createTerminal createTerminal(...)} on
	 * the prompt context, or the method of the same name on {@code AcpAsyncAgent} or
	 * {@code AcpSyncAgent}. The client's handler, set with
	 * {@link com.agentclientprotocol.sdk.client.AcpClient.AsyncSpec#createTerminalHandler
	 * createTerminalHandler} on the client builder, receives it.
	 *
	 * <p>
	 * Only a client that advertises {@code terminal} ({@link ClientCapabilities#terminal()}) takes
	 * it. The agent fails the call with an
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} without sending it when the
	 * client did not advertise it. A client builder that advertises {@code terminal} must register
	 * all five terminal handlers, or {@code build()} fails.
	 *
	 * <p>
	 * The protocol requires the agent to release every terminal it creates
	 * ({@link ReleaseTerminalRequest}). {@code execute} does so in every case: after reading the
	 * output, when a step fails, and when its {@code Mono} is cancelled, for example because the
	 * prompt was cancelled. An agent that sends this request itself must release the terminal
	 * itself. To show the output live in a tool call, add a {@link ToolCallTerminal} with the ID
	 * before releasing the terminal.
	 *
	 * <p>
	 * {@code cwd} must be an absolute path. With {@code outputByteLimit}, the client keeps at most
	 * that many bytes of output, dropping output from the start, and the protocol requires it to
	 * cut at a character boundary; {@link TerminalOutputResponse#truncated()} then says it did. The
	 * SDK checks none of the values on either side, and runs nothing itself: a client handler that
	 * runs every command it is given lets the agent run any program the client process can run.
	 *
	 * @param sessionId the ACP session the command is for
	 * @param command the program to run
	 * @param args the command's arguments, or {@code null} for none
	 * @param cwd the absolute working directory, or {@code null} to leave it to the client
	 * @param env environment variables to set for the command, or {@code null} for none
	 * @param outputByteLimit the most bytes of output the client keeps, or {@code null} to leave it
	 * to the client
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CreateTerminalRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("command") String command, @JsonProperty("args") @Nullable List<String> args,
			@JsonProperty("cwd") @Nullable String cwd, @JsonProperty("env") @Nullable List<EnvVariable> env,
			@JsonProperty("outputByteLimit") @Nullable Long outputByteLimit,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without {@code _meta}.
		 * @param sessionId the ACP session the command is for
		 * @param command the program to run
		 * @param args the command's arguments, or {@code null}
		 * @param cwd the absolute working directory, or {@code null}
		 * @param env environment variables to set, or {@code null}
		 * @param outputByteLimit the most bytes of output the client keeps, or {@code null}
		 */
		public CreateTerminalRequest(String sessionId, String command, @Nullable List<String> args,
				@Nullable String cwd, @Nullable List<EnvVariable> env, @Nullable Long outputByteLimit) {
			this(sessionId, command, args, cwd, env, outputByteLimit, null);
		}
	}

	/**
	 * The result of {@code terminal/create}: the ID of the new terminal, which the client returns
	 * without waiting for the command to end. The client's create handler returns it; the agent's
	 * {@code createTerminal(...)} completes with it. Pass {@link #terminalId()} to the other
	 * terminal requests and, to show the output in a tool call, to a {@link ToolCallTerminal}.
	 *
	 * <p>
	 * The ID is valid until the agent releases the terminal, and the agent must release it
	 * ({@link ReleaseTerminalRequest}).
	 *
	 * @param terminalId the new terminal's ID
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CreateTerminalResponse(@JsonProperty("terminalId") String terminalId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a response without {@code _meta}.
		 * @param terminalId the new terminal's ID
		 */
		public CreateTerminalResponse(String terminalId) {
			this(terminalId, null);
		}
	}

	/**
	 * The params of {@code terminal/output}: the agent asks for a terminal's output so far, without
	 * waiting for the command to end. The answer, a {@link TerminalOutputResponse}, also says
	 * whether the output was truncated and, once the command has ended, how it ended. The agent
	 * sends it with {@link com.agentclientprotocol.sdk.agent.PromptContext#getTerminalOutput
	 * getTerminalOutput(...)} on the prompt context, or the method of the same name on
	 * {@code AcpAsyncAgent} or {@code AcpSyncAgent};
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#execute(Command) execute} sends it
	 * once the command has ended. The client's handler, set with
	 * {@link com.agentclientprotocol.sdk.client.AcpClient.AsyncSpec#terminalOutputHandler
	 * terminalOutputHandler} on the client builder, receives it.
	 *
	 * <p>
	 * The terminal must be one the agent created with {@code terminal/create} and has not released.
	 * The SDK checks the ID on neither side, and the agent sends this request without checking the
	 * client's terminal capability: only {@code terminal/create}, which is checked, gives an ID.
	 * After a {@code terminal/kill} the terminal can still be read.
	 *
	 * @param sessionId the ACP session the terminal belongs to
	 * @param terminalId the terminal's ID, from {@link CreateTerminalResponse#terminalId()}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TerminalOutputRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("terminalId") String terminalId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without {@code _meta}.
		 * @param sessionId the ACP session the terminal belongs to
		 * @param terminalId the terminal's ID
		 */
		public TerminalOutputRequest(String sessionId, String terminalId) {
			this(sessionId, terminalId, null);
		}
	}

	/**
	 * The result of {@code terminal/output}: a terminal's output so far, whether it was truncated,
	 * and how the command ended once it has. The client's output handler returns it; the agent's
	 * {@code getTerminalOutput(...)} completes with it, and
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#execute(Command) execute} copies its
	 * output and {@code truncated} into the
	 * {@link com.agentclientprotocol.sdk.agent.CommandResult}.
	 *
	 * <p>
	 * {@code truncated} is {@code true} when the client dropped output from the start to stay
	 * within the {@code outputByteLimit} of the {@link CreateTerminalRequest}. {@code exitStatus}
	 * is {@code null} while the command runs. Whether standard error is part of the output is up to
	 * the client. An answer without {@code truncated}, or with {@code null}, reads as
	 * {@code false}.
	 *
	 * @param output the output captured so far
	 * @param truncated whether the client dropped output from the start to stay within the output
	 * limit
	 * @param exitStatus how the command ended, or {@code null} while it runs
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TerminalOutputResponse(@JsonProperty("output") String output,
			@JsonProperty("truncated") boolean truncated, @JsonProperty("exitStatus") @Nullable TerminalExitStatus exitStatus,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a response without {@code _meta}.
		 * @param output the output captured so far
		 * @param truncated whether the client dropped output from the start
		 * @param exitStatus how the command ended, or {@code null} while it runs
		 */
		public TerminalOutputResponse(String output, boolean truncated, @Nullable TerminalExitStatus exitStatus) {
			this(output, truncated, exitStatus, null);
		}
	}

	/**
	 * The params of {@code terminal/release}: the agent is done with a terminal. The client kills
	 * the command if it is still running and frees the terminal; the ID is invalid afterwards for
	 * every other terminal request. A tool call that shows the terminal ({@link ToolCallTerminal})
	 * should keep showing its output. The agent sends it with
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#releaseTerminal releaseTerminal(...)}
	 * on the prompt context, or the method of the same name on {@code AcpAsyncAgent} or
	 * {@code AcpSyncAgent}. The client's handler, set with
	 * {@link com.agentclientprotocol.sdk.client.AcpClient.AsyncSpec#releaseTerminalHandler
	 * releaseTerminalHandler} on the client builder, receives it and answers with a
	 * {@link ReleaseTerminalResponse}.
	 *
	 * <p>
	 * The protocol requires the agent to release every terminal it creates.
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#execute(Command) execute} sends this
	 * request exactly once for each terminal it creates: after reading the output, after a step
	 * fails, and when its {@code Mono} is cancelled, for example because the prompt was cancelled;
	 * in that last case it sends the release on its own and logs a failure at WARN. An agent that
	 * sends {@code terminal/create} itself must send this itself, also on errors and cancellation.
	 * The SDK releases no terminal in any other case, not even when a session or the connection
	 * ends.
	 *
	 * <p>
	 * The terminal must be one the agent created with {@code terminal/create} and has not released.
	 * The SDK checks the ID on neither side, and the agent sends this request without checking the
	 * client's terminal capability: only {@code terminal/create}, which is checked, gives an ID.
	 *
	 * @param sessionId the ACP session the terminal belongs to
	 * @param terminalId the terminal's ID, from {@link CreateTerminalResponse#terminalId()}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ReleaseTerminalRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("terminalId") String terminalId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without {@code _meta}.
		 * @param sessionId the ACP session the terminal belongs to
		 * @param terminalId the terminal's ID
		 */
		public ReleaseTerminalRequest(String sessionId, String terminalId) {
			this(sessionId, terminalId, null);
		}
	}

	/**
	 * The result of {@code terminal/release}: an empty answer that confirms the terminal was
	 * released. The client's release handler returns it; the agent's {@code releaseTerminal(...)}
	 * completes with it.
	 *
	 * <p>
	 * It has only {@code _meta}, so a peer that answers with an empty or {@code null} result gives
	 * this record, with no {@code _meta} (see {@link DefaultOnNull}).
	 *
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ReleaseTerminalResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		/** Creates the empty response, without {@code _meta}. */
		public ReleaseTerminalResponse() {
			this(null);
		}
	}

	/**
	 * The params of {@code terminal/wait_for_exit}: the agent asks the client to answer once a
	 * terminal's command has ended. The answer, a {@link WaitForTerminalExitResponse}, carries the
	 * exit code or the signal that ended it. The agent sends it with
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#waitForTerminalExit
	 * waitForTerminalExit(...)} on the prompt context, or the method of the same name on
	 * {@code AcpAsyncAgent} or {@code AcpSyncAgent};
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#execute(Command) execute} sends it
	 * right after creating the terminal. The client's handler, set with
	 * {@link com.agentclientprotocol.sdk.client.AcpClient.AsyncSpec#waitForTerminalExitHandler
	 * waitForTerminalExitHandler} on the client builder, receives it.
	 *
	 * <p>
	 * The wait counts against the agent's request timeout (its builder's {@code requestTimeout}): a
	 * command that runs longer fails the call with a {@link java.util.concurrent.TimeoutException},
	 * which the blocking API wraps in an
	 * {@link com.agentclientprotocol.sdk.error.AcpTimeoutException}. The command keeps running
	 * until the agent kills or releases the terminal. To give a command its own time limit, race
	 * this request against a timer, then send {@code terminal/kill}, {@code terminal/output} and
	 * {@code terminal/release}.
	 *
	 * <p>
	 * The terminal must be one the agent created with {@code terminal/create} and has not released.
	 * The SDK checks the ID on neither side, and the agent sends this request without checking the
	 * client's terminal capability: only {@code terminal/create}, which is checked, gives an ID.
	 *
	 * @param sessionId the ACP session the terminal belongs to
	 * @param terminalId the terminal's ID, from {@link CreateTerminalResponse#terminalId()}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WaitForTerminalExitRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("terminalId") String terminalId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without {@code _meta}.
		 * @param sessionId the ACP session the terminal belongs to
		 * @param terminalId the terminal's ID
		 */
		public WaitForTerminalExitRequest(String sessionId, String terminalId) {
			this(sessionId, terminalId, null);
		}
	}

	/**
	 * The result of {@code terminal/wait_for_exit}: how a terminal's command ended, with an exit
	 * code or with the signal that ended it. The client's handler returns it once the command has
	 * ended; the agent's {@code waitForTerminalExit(...)} completes with it, and
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#execute(Command) execute} copies both
	 * into the {@link com.agentclientprotocol.sdk.agent.CommandResult}. It carries the same two
	 * values as a {@link TerminalExitStatus}.
	 *
	 * <p>
	 * A process ends with an exit code or with a signal, so one of the two is normally
	 * {@code null}. Both components are optional, so a peer that answers with an empty or
	 * {@code null} result gives this record with both {@code null} (see {@link DefaultOnNull}). The
	 * schema allows an exit code up to 4294967295 (an unsigned 32-bit number); one above
	 * {@link Integer#MAX_VALUE} cannot be read into this record, and the answer that carries it
	 * fails the agent's call with an {@link AcpError} of code {@code -32603}.
	 *
	 * @param exitCode the exit code, or {@code null} if a signal ended the process
	 * @param signal the signal that ended the process, or {@code null} if it exited
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WaitForTerminalExitResponse(@JsonProperty("exitCode") @Nullable Integer exitCode,
			@JsonProperty("signal") @Nullable String signal,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		/**
		 * Creates a response without {@code _meta}.
		 * @param exitCode the exit code, or {@code null}
		 * @param signal the signal that ended the process, or {@code null}
		 */
		public WaitForTerminalExitResponse(@Nullable Integer exitCode, @Nullable String signal) {
			this(exitCode, signal, null);
		}
	}

	/**
	 * The params of {@code terminal/kill}: the agent asks the client to kill a terminal's command
	 * but keep the terminal. Afterwards {@code terminal/output} still gives the final output and
	 * {@code terminal/wait_for_exit} the exit status, and the agent must still release the terminal
	 * ({@link ReleaseTerminalRequest}). The agent sends it with
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#killTerminal killTerminal(...)} on the
	 * prompt context, or the method of the same name on {@code AcpAsyncAgent} or
	 * {@code AcpSyncAgent}. The client's handler, set with
	 * {@link com.agentclientprotocol.sdk.client.AcpClient.AsyncSpec#killTerminalHandler
	 * killTerminalHandler} on the client builder, receives it and answers with a
	 * {@link KillTerminalCommandResponse}.
	 *
	 * <p>
	 * Use it to stop a command that runs too long: race {@code terminal/wait_for_exit} against a
	 * timer, kill the command when the timer fires, read the output, then release the terminal.
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#execute(Command) execute} does not
	 * send it: when its wait fails or is cancelled, it releases the terminal, which kills the
	 * command as well. The schema calls this type {@code KillTerminalRequest}.
	 *
	 * <p>
	 * The terminal must be one the agent created with {@code terminal/create} and has not released.
	 * The SDK checks the ID on neither side, and the agent sends this request without checking the
	 * client's terminal capability: only {@code terminal/create}, which is checked, gives an ID.
	 *
	 * @param sessionId the ACP session the terminal belongs to
	 * @param terminalId the terminal's ID, from {@link CreateTerminalResponse#terminalId()}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record KillTerminalCommandRequest(@JsonProperty("sessionId") String sessionId,
			@JsonProperty("terminalId") String terminalId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a request without {@code _meta}.
		 * @param sessionId the ACP session the terminal belongs to
		 * @param terminalId the terminal's ID
		 */
		public KillTerminalCommandRequest(String sessionId, String terminalId) {
			this(sessionId, terminalId, null);
		}
	}

	/**
	 * The result of {@code terminal/kill}: an empty answer that confirms the command was killed.
	 * The client's kill handler returns it; the agent's {@code killTerminal(...)} completes with
	 * it. The terminal stays valid until the agent releases it. The schema calls this type
	 * {@code KillTerminalResponse}.
	 *
	 * <p>
	 * It has only {@code _meta}, so a peer that answers with an empty or {@code null} result gives
	 * this record, with no {@code _meta} (see {@link DefaultOnNull}).
	 *
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record KillTerminalCommandResponse(@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements DefaultOnNull {
		/** Creates the empty response, without {@code _meta}. */
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
	 * What a client tells the agent it can do, sent in the {@code initialize} request: the file
	 * system and terminal methods it serves, the config option kinds and authentication method
	 * types it handles, and the elicitation modes it supports. Build it with {@link #builder()} and
	 * set it with {@link com.agentclientprotocol.sdk.client.AcpClient.AsyncSpec#clientCapabilities
	 * clientCapabilities(...)} on the client builder (or the sync builder's method of the same
	 * name); the client sends it in {@code initialize()}. The agent reads it as a
	 * {@link com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities
	 * NegotiatedCapabilities}.
	 *
	 * <p>
	 * Advertise exactly what the client serves. The client builder's {@code build()} throws an
	 * {@link IllegalStateException} when {@code fs.readTextFile}, {@code fs.writeTextFile},
	 * {@code terminal} or an elicitation mode is advertised without the handlers that serve it, and
	 * logs a warning for a handler whose capability is not advertised, since an agent will not call
	 * it. An agent built with this SDK fails its own file read and write, {@code terminal/create}
	 * and elicitation calls with an
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} when the client did not
	 * advertise them, without sending them.
	 *
	 * <p>
	 * {@code new ClientCapabilities()} and an empty builder advertise no file system access and no
	 * terminal. A {@code null} component is left out of the JSON and counts as not advertised.
	 * Capabilities this SDK does not model are dropped when the record is read from JSON; put
	 * custom capabilities in {@code _meta}.
	 *
	 * <pre>{@code
	 * ClientCapabilities caps = ClientCapabilities.builder()
	 *     .session(ClientSessionCapabilities.withBooleanConfigOptions())
	 *     .build();
	 * }</pre>
	 *
	 * @param fs the {@code fs/*} methods the client serves, or {@code null} for none
	 * @param terminal whether the client serves the {@code terminal/*} methods, or {@code null},
	 * read as {@code false}
	 * @param session the session features the client supports, such as boolean config options, or
	 * {@code null} for none
	 * @param auth the authentication method types the client handles, or {@code null} for none
	 * @param elicitation the elicitation modes the client supports, or {@code null} for none
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ClientCapabilities(@JsonProperty("fs") @Nullable FileSystemCapability fs,
			@JsonProperty("terminal") @Nullable Boolean terminal,
			@JsonProperty("session") @Nullable ClientSessionCapabilities session,
			@JsonProperty("auth") @Nullable AuthCapabilities auth,
			@JsonProperty("elicitation") @Nullable ElicitationCapabilities elicitation,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates the capabilities of a client that serves no file system or terminal methods:
		 * {@code fs} with both flags {@code false}, {@code terminal} {@code false}, and nothing
		 * else. {@link #builder()} starts from the same values.
		 */
		public ClientCapabilities() {
			this(new FileSystemCapability(), false, null, null, null, null);
		}

		/**
		 * Creates capabilities with file system and terminal support only, without session, auth,
		 * elicitation or {@code _meta}.
		 * @param fs the {@code fs/*} methods the client serves, or {@code null}
		 * @param terminal whether the client serves the {@code terminal/*} methods, or {@code null}
		 */
		public ClientCapabilities(@Nullable FileSystemCapability fs, @Nullable Boolean terminal) {
			this(fs, terminal, null, null, null, null);
		}

		/**
		 * Returns a builder that starts from {@code new ClientCapabilities()}: no file system
		 * access, no terminal, and no session, auth, elicitation or {@code _meta}.
		 * @return a new builder
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builds {@link ClientCapabilities} one component at a time, without positional
		 * {@code null}s. Get one from {@link ClientCapabilities#builder()}. Each setter replaces
		 * one component, and {@link #build()} can be called more than once. A builder is not safe
		 * for use by several threads at once.
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
			 * Sets the {@code fs/*} methods the client serves. For each flag set to {@code true},
			 * register the matching handler ({@code readTextFileHandler} or
			 * {@code writeTextFileHandler}) on the client builder as well, or its {@code build()}
			 * throws.
			 * @param fs the file system capability, or {@code null} to advertise none
			 * @return this builder
			 */
			public Builder fs(@Nullable FileSystemCapability fs) {
				this.fs = fs;
				return this;
			}

			/**
			 * Sets whether the client serves the {@code terminal/*} methods. When {@code true},
			 * register all five terminal handlers on the client builder as well, or its
			 * {@code build()} throws.
			 * @param terminal {@code true} if the client serves the terminal methods; {@code false}
			 * or {@code null} if not
			 * @return this builder
			 */
			public Builder terminal(@Nullable Boolean terminal) {
				this.terminal = terminal;
				return this;
			}

			/**
			 * Sets the session features the client supports, for example
			 * {@link ClientSessionCapabilities#withBooleanConfigOptions()} for a client that
			 * handles boolean config options.
			 * @param session the session capabilities, or {@code null} for none
			 * @return this builder
			 */
			public Builder session(@Nullable ClientSessionCapabilities session) {
				this.session = session;
				return this;
			}

			/**
			 * Sets the authentication method types the client handles, for example
			 * {@code new AuthCapabilities(true)} for a client that can run terminal auth methods.
			 * @param auth the auth capabilities, or {@code null} for none
			 * @return this builder
			 */
			public Builder auth(@Nullable AuthCapabilities auth) {
				this.auth = auth;
				return this;
			}

			/**
			 * Sets the elicitation modes the client supports, for example
			 * {@link ElicitationCapabilities#formOnly()}. With a mode set, register an elicitation
			 * handler on the client builder as well, or its {@code build()} throws.
			 * @param elicitation the elicitation capabilities, or {@code null} for none
			 * @return this builder
			 */
			public Builder elicitation(@Nullable ElicitationCapabilities elicitation) {
				this.elicitation = elicitation;
				return this;
			}

			/**
			 * Sets the {@code _meta} map.
			 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
			 * @return this builder
			 */
			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			/**
			 * Returns capabilities with the components set so far.
			 * @return the capabilities
			 */
			public ClientCapabilities build() {
				return new ClientCapabilities(this.fs, this.terminal, this.session, this.auth, this.elicitation,
						this.meta);
			}

		}
	}

	/**
	 * Session features a client supports beyond the baseline, as the {@code session} component of
	 * {@link ClientCapabilities}. Today that is only the config option kinds it handles beyond
	 * {@code select}, and ACP defines one: {@code boolean}. Use {@link #withBooleanConfigOptions()}
	 * for a client that handles boolean config options.
	 *
	 * <p>
	 * An agent checks it with {@code supportsBooleanConfigOptions()} on
	 * {@link com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities
	 * NegotiatedCapabilities}, and should offer {@link SessionConfigBoolean} options only to a
	 * client that advertises it. The SDK checks this on neither side. A {@code null}
	 * {@code configOptions} advertises no extra kinds.
	 *
	 * @param configOptions the config option kinds beyond {@code select} the client handles, or
	 * {@code null} for none
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ClientSessionCapabilities(
			@JsonProperty("configOptions") @Nullable SessionConfigOptionsCapabilities configOptions,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates the session capabilities without {@code _meta}.
		 * @param configOptions the config option kinds beyond {@code select}, or {@code null}
		 */
		public ClientSessionCapabilities(@Nullable SessionConfigOptionsCapabilities configOptions) {
			this(configOptions, null);
		}

		/**
		 * Returns the session capabilities of a client that handles boolean config options, written
		 * {@code {"configOptions":{"boolean":{}}}}. Pass it to
		 * {@link ClientCapabilities.Builder#session}.
		 * @return the session capabilities
		 */
		public static ClientSessionCapabilities withBooleanConfigOptions() {
			return new ClientSessionCapabilities(SessionConfigOptionsCapabilities.withBoolean());
		}
	}

	/**
	 * The config option kinds a client handles beyond {@code select}, as
	 * {@link ClientSessionCapabilities#configOptions()}. ACP defines one, {@code boolean}: when
	 * present, an agent may offer {@link SessionConfigBoolean} options, and the client may send
	 * boolean values in {@code session/set_config_option}. Most code uses
	 * {@link ClientSessionCapabilities#withBooleanConfigOptions()} rather than this record.
	 *
	 * <p>
	 * The component is named {@code booleanOptions} because {@code boolean} is a Java keyword; on
	 * the wire it is the member {@code "boolean"}.
	 *
	 * @param booleanOptions the marker for boolean config options, or {@code null} if the client
	 * does not handle them
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionConfigOptionsCapabilities(
			@JsonProperty("boolean") @Nullable BooleanConfigOptionCapabilities booleanOptions,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates the capabilities without {@code _meta}.
		 * @param booleanOptions the marker for boolean config options, or {@code null}
		 */
		public SessionConfigOptionsCapabilities(@Nullable BooleanConfigOptionCapabilities booleanOptions) {
			this(booleanOptions, null);
		}

		/**
		 * Returns the capabilities of a client that handles boolean config options, written
		 * {@code {"boolean":{}}}.
		 * @return the capabilities
		 */
		public static SessionConfigOptionsCapabilities withBoolean() {
			return new SessionConfigOptionsCapabilities(new BooleanConfigOptionCapabilities());
		}
	}

	/**
	 * The marker that a client handles boolean config options, as
	 * {@link SessionConfigOptionsCapabilities#booleanOptions()}. It carries only {@code _meta}: its
	 * presence, written {@code {}}, is the capability, and {@code null} in its place means the
	 * client does not handle them.
	 *
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record BooleanConfigOptionCapabilities(@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/** Creates the marker without {@code _meta}, written {@code {}}. */
		public BooleanConfigOptionCapabilities() {
			this(null);
		}
	}

	/**
	 * Which {@code fs/*} methods a client serves, as the {@code fs} component of
	 * {@link ClientCapabilities}: {@code fs/read_text_file} ({@link ReadTextFileRequest}) and
	 * {@code fs/write_text_file} ({@link WriteTextFileRequest}). With them an agent reads and
	 * writes files through the client, which can include unsaved changes in the user's editor. An
	 * agent checks them with {@code supportsReadTextFile()} and {@code supportsWriteTextFile()} on
	 * {@link com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities
	 * NegotiatedCapabilities}.
	 *
	 * <p>
	 * A flag set to {@code true} needs its handler on the client builder
	 * ({@code readTextFileHandler} or {@code writeTextFileHandler}), or the builder's
	 * {@code build()} throws. An agent built with this SDK fails a read or write the client did not
	 * advertise with an {@link com.agentclientprotocol.sdk.error.AcpCapabilityException}, without
	 * sending it. {@code null} counts as {@code false}.
	 *
	 * @param readTextFile whether the client serves {@code fs/read_text_file}, or {@code null},
	 * read as {@code false}
	 * @param writeTextFile whether the client serves {@code fs/write_text_file}, or {@code null},
	 * read as {@code false}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FileSystemCapability(@JsonProperty("readTextFile") @Nullable Boolean readTextFile,
			@JsonProperty("writeTextFile") @Nullable Boolean writeTextFile,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates the capability without {@code _meta}.
		 * @param readTextFile whether the client serves {@code fs/read_text_file}, or {@code null}
		 * @param writeTextFile whether the client serves {@code fs/write_text_file}, or
		 * {@code null}
		 */
		public FileSystemCapability(@Nullable Boolean readTextFile, @Nullable Boolean writeTextFile) {
			this(readTextFile, writeTextFile, null);
		}

		/**
		 * Creates the capability of a client that serves neither method: both flags {@code false}.
		 */
		public FileSystemCapability() {
			this(false, false);
		}
	}

	/**
	 * What an agent tells the client it can do, sent in its {@code initialize} answer as
	 * {@link InitializeResponse#agentCapabilities()}: whether it loads sessions, which other
	 * optional session methods it serves, which MCP server transports and prompt content it
	 * accepts, whether it supports {@code logout}, and its provider support. Build it with
	 * {@link #builder()}. The client reads it as a
	 * {@link com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities
	 * NegotiatedCapabilities}, returned by {@code getAgentCapabilities()} on the client.
	 *
	 * <p>
	 * Agents built with this SDK derive it from their handlers unless they answer
	 * {@code initialize} themselves. A builder agent without an initialize handler, and an
	 * annotated agent, advertise each optional method they have a handler for: a load-session
	 * handler advertises {@code loadSession}, a list-sessions handler
	 * {@code sessionCapabilities.list}, a logout handler {@code auth.logout}, and so on. An
	 * annotated agent also takes its MCP transports from
	 * {@link com.agentclientprotocol.sdk.annotation.AcpAgent @AcpAgent} and its prompt content from
	 * {@link com.agentclientprotocol.sdk.annotation.Prompt @Prompt}, and lays the answer of an
	 * {@link com.agentclientprotocol.sdk.annotation.Initialize @Initialize} method over the derived
	 * one. A builder agent with
	 * {@link com.agentclientprotocol.sdk.agent.AcpAgent.AsyncAgentBuilder#initializeHandler
	 * initializeHandler} sends what its handler returns. An annotated agent advertises
	 * {@code sessionCapabilities.additionalDirectories} when
	 * {@link com.agentclientprotocol.sdk.annotation.AcpAgent#additionalDirectories() @AcpAgent}
	 * declares it; a builder agent advertises it from its initialize handler.
	 *
	 * <p>
	 * The client fails {@code session/load}, {@code session/list}, {@code session/close},
	 * {@code session/delete}, {@code session/resume}, {@code logout} and the fork and provider
	 * calls with an {@link com.agentclientprotocol.sdk.error.AcpCapabilityException}, without
	 * sending them, when the agent did not advertise them, and so a session call that names
	 * additional directories when the agent did not advertise
	 * {@code sessionCapabilities.additionalDirectories}. It does not check prompt content or MCP
	 * server types against these capabilities; check those with {@code NegotiatedCapabilities}
	 * before sending.
	 *
	 * <p>
	 * {@code new AgentCapabilities()} and an empty builder advertise no {@code session/load}, no
	 * MCP servers over HTTP or SSE, and only text and resource links in prompts, which every agent
	 * must accept. A {@code null} component is left out of the JSON and counts as not advertised.
	 * Capabilities this SDK does not model are dropped when the record is read from JSON.
	 *
	 * <pre>{@code
	 * AgentCapabilities caps = AgentCapabilities.builder()
	 *     .loadSession(true)
	 *     .auth(AgentAuthCapabilities.withLogout())
	 *     .build();
	 * }</pre>
	 *
	 * @param loadSession whether the agent serves {@code session/load}, or {@code null}, read as
	 * {@code false}
	 * @param sessionCapabilities the other optional session methods the agent serves, or
	 * {@code null} for none
	 * @param mcpCapabilities the MCP server transports beyond stdio the agent accepts, or
	 * {@code null} for none
	 * @param promptCapabilities the prompt content beyond text and resource links the agent
	 * accepts, or {@code null} for none
	 * @param auth the authentication features the agent supports, such as {@code logout}, or
	 * {@code null} for none
	 * @param providers the agent's provider support, or {@code null} for none
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AgentCapabilities(@JsonProperty("loadSession") @Nullable Boolean loadSession,
			@JsonProperty("sessionCapabilities") @Nullable SessionCapabilities sessionCapabilities,
			@JsonProperty("mcpCapabilities") @Nullable McpCapabilities mcpCapabilities,
			@JsonProperty("promptCapabilities") @Nullable PromptCapabilities promptCapabilities,
			@JsonProperty("auth") @Nullable AgentAuthCapabilities auth,
			@UnstableAcpApi @JsonProperty("providers") @Nullable ProvidersCapabilities providers,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates the capabilities of a minimal agent: no {@code session/load}, no MCP servers over
		 * HTTP or SSE, and no image, audio or embedded context in prompts. {@link #builder()}
		 * starts from the same values.
		 */
		public AgentCapabilities() {
			this(false, null, new McpCapabilities(), new PromptCapabilities(), null, null, null);
		}

		/**
		 * Creates capabilities without session, auth or provider capabilities or {@code _meta}.
		 * @param loadSession whether the agent serves {@code session/load}, or {@code null}
		 * @param mcpCapabilities the MCP server transports beyond stdio, or {@code null}
		 * @param promptCapabilities the prompt content beyond text and resource links, or
		 * {@code null}
		 */
		public AgentCapabilities(@Nullable Boolean loadSession, @Nullable McpCapabilities mcpCapabilities,
				@Nullable PromptCapabilities promptCapabilities) {
			this(loadSession, null, mcpCapabilities, promptCapabilities, null, null, null);
		}

		/**
		 * Creates capabilities without auth or provider capabilities.
		 * @param loadSession whether the agent serves {@code session/load}, or {@code null}
		 * @param sessionCapabilities the other optional session methods, or {@code null}
		 * @param mcpCapabilities the MCP server transports beyond stdio, or {@code null}
		 * @param promptCapabilities the prompt content beyond text and resource links, or
		 * {@code null}
		 * @param meta the {@code _meta} map, or {@code null}
		 */
		public AgentCapabilities(@Nullable Boolean loadSession, @Nullable SessionCapabilities sessionCapabilities,
				@Nullable McpCapabilities mcpCapabilities, @Nullable PromptCapabilities promptCapabilities, @Nullable Map<String, Object> meta) {
			this(loadSession, sessionCapabilities, mcpCapabilities, promptCapabilities, null, null, meta);
		}

		/**
		 * Returns a builder that starts from {@code new AgentCapabilities()}: no
		 * {@code session/load}, no MCP servers over HTTP or SSE, only text and resource links in
		 * prompts, and no session, auth or provider capabilities or {@code _meta}.
		 * @return a new builder
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builds {@link AgentCapabilities} one component at a time, without positional
		 * {@code null}s. Get one from {@link AgentCapabilities#builder()}. Each setter replaces one
		 * component, and {@link #build()} can be called more than once. A builder is not safe for
		 * use by several threads at once.
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
			 * Sets whether the agent serves {@code session/load}.
			 * @param loadSession {@code true} if the agent serves {@code session/load};
			 * {@code false} or {@code null} if not
			 * @return this builder
			 */
			public Builder loadSession(@Nullable Boolean loadSession) {
				this.loadSession = loadSession;
				return this;
			}

			/**
			 * Sets the other optional session methods the agent serves, such as
			 * {@code session/list} and {@code session/close}.
			 * @param sessionCapabilities the session capabilities, or {@code null} for none
			 * @return this builder
			 */
			public Builder sessionCapabilities(@Nullable SessionCapabilities sessionCapabilities) {
				this.sessionCapabilities = sessionCapabilities;
				return this;
			}

			/**
			 * Sets the MCP server transports beyond stdio the agent accepts.
			 * @param mcpCapabilities the MCP capabilities, or {@code null} for none
			 * @return this builder
			 */
			public Builder mcpCapabilities(@Nullable McpCapabilities mcpCapabilities) {
				this.mcpCapabilities = mcpCapabilities;
				return this;
			}

			/**
			 * Sets the prompt content beyond text and resource links the agent accepts.
			 * @param promptCapabilities the prompt capabilities, or {@code null} for none
			 * @return this builder
			 */
			public Builder promptCapabilities(@Nullable PromptCapabilities promptCapabilities) {
				this.promptCapabilities = promptCapabilities;
				return this;
			}

			/**
			 * Sets the authentication features the agent supports, for example
			 * {@link AgentAuthCapabilities#withLogout()} for an agent that serves {@code logout}.
			 * @param auth the auth capabilities, or {@code null} for none
			 * @return this builder
			 */
			public Builder auth(@Nullable AgentAuthCapabilities auth) {
				this.auth = auth;
				return this;
			}

			/**
			 * Sets the agent's provider support, which advertises the {@code providers/*} methods.
			 * @param providers the provider capabilities, or {@code null} for none
			 * @return this builder
			 */
			@UnstableAcpApi
			public Builder providers(@Nullable ProvidersCapabilities providers) {
				this.providers = providers;
				return this;
			}

			/**
			 * Sets the {@code _meta} map.
			 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
			 * @return this builder
			 */
			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			/**
			 * Returns capabilities with the components set so far.
			 * @return the capabilities
			 */
			public AgentCapabilities build() {
				return new AgentCapabilities(this.loadSession, this.sessionCapabilities, this.mcpCapabilities,
						this.promptCapabilities, this.auth, this.providers, this.meta);
			}

		}
	}

	/**
	 * Authentication features an agent supports besides {@code authenticate}, as
	 * {@link AgentCapabilities#auth()}. Today that is only {@code logout}: use
	 * {@link #withLogout()} for an agent that serves it. The ways a client can log in are not here;
	 * they are {@link InitializeResponse#authMethods()}.
	 *
	 * <p>
	 * A builder agent without an initialize handler advertises {@code logout} when it has a
	 * {@code logoutHandler}, and an annotated agent when it has a
	 * {@link com.agentclientprotocol.sdk.annotation.Logout @Logout} method. The client fails
	 * {@code logout} with an {@link com.agentclientprotocol.sdk.error.AcpCapabilityException},
	 * without sending it, when {@code logout} is {@code null}.
	 *
	 * @param logout {@code {}} if the agent serves {@code logout}, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AgentAuthCapabilities(@JsonProperty("logout") @Nullable LogoutCapabilities logout,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates the auth capabilities without {@code _meta}.
		 * @param logout {@code {}} for {@code logout}, or {@code null}
		 */
		public AgentAuthCapabilities(@Nullable LogoutCapabilities logout) {
			this(logout, null);
		}

		/**
		 * Returns the auth capabilities of an agent that serves {@code logout}, written
		 * {@code {"logout":{}}}. Pass it to {@link AgentCapabilities.Builder#auth}.
		 * @return the auth capabilities
		 */
		public static AgentAuthCapabilities withLogout() {
			return new AgentAuthCapabilities(new LogoutCapabilities());
		}
	}

	/**
	 * The marker that an agent serves {@code logout}, as {@link AgentAuthCapabilities#logout()}. It
	 * carries only {@code _meta}: its presence, written {@code {}}, is the capability, and
	 * {@code null} in its place means the agent does not serve {@code logout}.
	 *
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LogoutCapabilities(@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/** Creates the marker without {@code _meta}, written {@code {}}. */
		public LogoutCapabilities() {
			this(null);
		}
	}

	/**
	 * Which optional session methods an agent serves besides {@code session/load}, as
	 * {@link AgentCapabilities#sessionCapabilities()}: {@code session/list}, {@code session/close},
	 * {@code session/resume}, {@code session/delete} and {@code session/fork}, and whether it
	 * accepts {@code additionalDirectories} on session requests. Every agent serves
	 * {@code session/new}, {@code session/prompt}, {@code session/cancel} and
	 * {@code session/update}; {@code session/load} has its own flag,
	 * {@link AgentCapabilities#loadSession()}.
	 *
	 * <p>
	 * Each component is an object on the wire: present, as {@code {}}, means supported, and
	 * {@code null} means not. Pass an empty map, {@code Map.of()}, or {@link Boolean#TRUE} for a
	 * supported method, and {@code null} or {@link Boolean#FALSE} for one that is not: the record
	 * holds {@code TRUE} as an empty map and {@code FALSE} as {@code null}. The components are
	 * typed {@link Object}, and other values are written as they are: a plain
	 * {@code new Object()} cannot be written at all. A record read from JSON holds a map, or
	 * {@code null}: a value other than an object, such as a peer's {@code false} or {@code true},
	 * reads as not advertised.
	 *
	 * <p>
	 * The client fails {@code session/list}, {@code session/close}, {@code session/resume},
	 * {@code session/delete} and {@code session/fork} calls with an
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException}, without sending them, when
	 * the matching component is {@code null}, and a session call that names additional directories
	 * when {@code additionalDirectories} is {@code null}. A builder agent without an initialize
	 * handler and an annotated agent advertise list, close, resume, delete and fork from their
	 * handlers; an annotated agent advertises {@code additionalDirectories} when
	 * {@code @AcpAgent(additionalDirectories = true)} declares it.
	 *
	 * @param list {@code {}} if the agent serves {@code session/list}, or {@code null}
	 * @param close {@code {}} if the agent serves {@code session/close}, or {@code null}
	 * @param resume {@code {}} if the agent serves {@code session/resume}, or {@code null}
	 * @param delete {@code {}} if the agent serves {@code session/delete}, or {@code null}
	 * @param additionalDirectories {@code {}} if the agent accepts {@code additionalDirectories} on
	 * session requests and may report them in {@link SessionInfo}, or {@code null}
	 * @param fork {@code {}} if the agent serves {@code session/fork}, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionCapabilities(@JsonProperty("list") @Nullable Object list, @JsonProperty("close") @Nullable Object close,
			@JsonProperty("resume") @Nullable Object resume, @JsonProperty("delete") @Nullable Object delete,
			@JsonProperty("additionalDirectories") @Nullable Object additionalDirectories,
			@UnstableAcpApi @JsonProperty("fork") @Nullable Object fork,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {

		/**
		 * Normalizes what a caller passes: {@link Boolean#TRUE} becomes {@code {}} (an empty map),
		 * and {@link Boolean#FALSE} becomes {@code null}, so neither writes a boolean the schema
		 * forbids.
		 */
		public SessionCapabilities {
			list = supported(list);
			close = supported(close);
			resume = supported(resume);
			delete = supported(delete);
			additionalDirectories = supported(additionalDirectories);
			fork = supported(fork);
		}

		/**
		 * Reads the capabilities a peer sent: only a JSON object counts as advertised, so a
		 * {@code false}, a {@code true} or another value the schema forbids reads as {@code null}.
		 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		@SuppressWarnings("unchecked")
		static SessionCapabilities read(Map<String, Object> json) {
			Object meta = json.get("_meta");
			return new SessionCapabilities(objectOnly(json.get("list")), objectOnly(json.get("close")),
					objectOnly(json.get("resume")), objectOnly(json.get("delete")),
					objectOnly(json.get("additionalDirectories")), objectOnly(json.get("fork")),
					(meta instanceof Map) ? (Map<String, Object>) meta : null);
		}

		private static @Nullable Object supported(@Nullable Object value) {
			if (Boolean.TRUE.equals(value)) {
				return Map.of();
			}
			return Boolean.FALSE.equals(value) ? null : value;
		}

		private static @Nullable Object objectOnly(@Nullable Object value) {
			return (value instanceof Map) ? value : null;
		}

		/**
		 * Creates session capabilities without {@code _meta}.
		 * @param list {@code {}} for {@code session/list}, or {@code null}
		 * @param close {@code {}} for {@code session/close}, or {@code null}
		 * @param resume {@code {}} for {@code session/resume}, or {@code null}
		 * @param delete {@code {}} for {@code session/delete}, or {@code null}
		 * @param additionalDirectories {@code {}} for {@code additionalDirectories}, or
		 * {@code null}
		 * @param fork {@code {}} for {@code session/fork}, or {@code null}
		 */
		@UnstableAcpApi
		public SessionCapabilities(@Nullable Object list, @Nullable Object close, @Nullable Object resume,
				@Nullable Object delete, @Nullable Object additionalDirectories, @Nullable Object fork) {
			this(list, close, resume, delete, additionalDirectories, fork, null);
		}

		/**
		 * Creates session capabilities for list, close and resume only, without delete, additional
		 * directories, fork or {@code _meta}.
		 * @param list {@code {}} for {@code session/list}, or {@code null}
		 * @param close {@code {}} for {@code session/close}, or {@code null}
		 * @param resume {@code {}} for {@code session/resume}, or {@code null}
		 */
		public SessionCapabilities(@Nullable Object list, @Nullable Object close, @Nullable Object resume) {
			this(list, close, resume, null, null, null);
		}

		/**
		 * Creates session capabilities for list, close, resume and fork only, without delete,
		 * additional directories or {@code _meta}.
		 * @param list {@code {}} for {@code session/list}, or {@code null}
		 * @param close {@code {}} for {@code session/close}, or {@code null}
		 * @param resume {@code {}} for {@code session/resume}, or {@code null}
		 * @param fork {@code {}} for {@code session/fork}, or {@code null}
		 */
		@UnstableAcpApi
		public SessionCapabilities(@Nullable Object list, @Nullable Object close, @Nullable Object resume,
				@Nullable Object fork) {
			this(list, close, resume, null, null, fork);
		}
	}

	/**
	 * Which MCP server transports beyond stdio an agent accepts, as
	 * {@link AgentCapabilities#mcpCapabilities()}: HTTP and SSE. Every agent accepts
	 * {@link McpServerStdio} servers; a client passes {@link McpServerHttp} or {@link McpServerSse}
	 * servers in {@code session/new}, {@code session/load} or {@code session/resume} only when the
	 * matching flag is {@code true}.
	 *
	 * <p>
	 * An annotated agent takes the flags from the {@code mcpHttp} and {@code mcpSse} attributes of
	 * {@link com.agentclientprotocol.sdk.annotation.AcpAgent @AcpAgent}; a builder agent without an
	 * initialize handler advertises both {@code false}. The SDK checks the MCP server types on
	 * neither side: a client checks {@code supportsMcpHttp()} and {@code supportsMcpSse()} on
	 * {@link com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities
	 * NegotiatedCapabilities} before it sends them.
	 *
	 * @param http whether the agent accepts MCP servers over HTTP, or {@code null}, read as
	 * {@code false}
	 * @param sse whether the agent accepts MCP servers over SSE, or {@code null}, read as
	 * {@code false}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record McpCapabilities(@JsonProperty("http") @Nullable Boolean http, @JsonProperty("sse") @Nullable Boolean sse,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates the MCP capabilities without {@code _meta}.
		 * @param http whether the agent accepts MCP servers over HTTP, or {@code null}
		 * @param sse whether the agent accepts MCP servers over SSE, or {@code null}
		 */
		public McpCapabilities(@Nullable Boolean http, @Nullable Boolean sse) {
			this(http, sse, null);
		}

		/**
		 * Creates the MCP capabilities of an agent that accepts only stdio MCP servers: both flags
		 * {@code false}.
		 */
		public McpCapabilities() {
			this(false, false);
		}
	}

	/**
	 * Which content beyond text and resource links an agent accepts in {@code session/prompt}, as
	 * {@link AgentCapabilities#promptCapabilities()}: images, audio and embedded resources. Every
	 * agent accepts {@link TextContent} and {@link ResourceLink} blocks; a client sends
	 * {@link ImageContent}, {@link AudioContent} or an embedded {@link Resource} only when the
	 * matching flag is {@code true}.
	 *
	 * <p>
	 * An annotated agent takes the flags from the {@code image}, {@code audio} and
	 * {@code embeddedContext} attributes of its
	 * {@link com.agentclientprotocol.sdk.annotation.Prompt @Prompt} method; a builder agent without
	 * an initialize handler advertises all three {@code false}. The SDK checks prompt content on
	 * neither side: a client checks {@code supportsImageContent()}, {@code supportsAudioContent()}
	 * and {@code supportsEmbeddedContext()} on
	 * {@link com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities
	 * NegotiatedCapabilities} before it sends such blocks. Note the component order: audio,
	 * embedded context, image.
	 *
	 * @param audio whether the agent accepts {@link AudioContent} blocks, or {@code null}, read as
	 * {@code false}
	 * @param embeddedContext whether the agent accepts {@link Resource} blocks, which embed a
	 * resource's contents in the prompt, or {@code null}, read as {@code false}
	 * @param image whether the agent accepts {@link ImageContent} blocks, or {@code null}, read as
	 * {@code false}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PromptCapabilities(@JsonProperty("audio") @Nullable Boolean audio,
			@JsonProperty("embeddedContext") @Nullable Boolean embeddedContext, @JsonProperty("image") @Nullable Boolean image,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates the prompt capabilities without {@code _meta}. The order is audio, embedded
		 * context, image.
		 * @param audio whether the agent accepts audio, or {@code null}
		 * @param embeddedContext whether the agent accepts embedded resources, or {@code null}
		 * @param image whether the agent accepts images, or {@code null}
		 */
		public PromptCapabilities(@Nullable Boolean audio, @Nullable Boolean embeddedContext, @Nullable Boolean image) {
			this(audio, embeddedContext, image, null);
		}

		/**
		 * Creates the prompt capabilities of an agent that accepts only text and resource links:
		 * all three flags {@code false}.
		 */
		public PromptCapabilities() {
			this(false, false, false);
		}
	}

	// ---------------------------
	// Session Types
	// ---------------------------

	/**
	 * One ACP session as {@code session/list} reports it: its id and working directory and, when
	 * the agent knows them, a title and the time of its last activity. The agent builds one per
	 * session in a {@link ListSessionsResponse}. A client shows them, for example as a session
	 * history, and reopens one by passing its {@link #sessionId()} in a {@link LoadSessionRequest}
	 * or {@link ResumeSessionRequest}.
	 *
	 * <p>
	 * The protocol makes {@code updatedAt} an ISO 8601 timestamp; the SDK keeps it as a string and
	 * does not check it. A present {@code additionalDirectories} is the session's complete ordered
	 * list of additional workspace roots; a missing and an empty list both mean none. While a
	 * session runs, the agent reports a new title or last activity with a {@link SessionInfoUpdate}
	 * session update.
	 *
	 * @param sessionId the session's id
	 * @param cwd the session's working directory, an absolute path
	 * @param title a human-readable title, or {@code null}
	 * @param updatedAt the time of the last activity as an ISO 8601 timestamp, or {@code null}
	 * @param additionalDirectories the session's additional workspace roots as absolute paths, or
	 * {@code null} for none
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionInfo(@JsonProperty("sessionId") String sessionId, @JsonProperty("cwd") String cwd,
			@JsonProperty("title") @Nullable String title, @JsonProperty("updatedAt") @Nullable String updatedAt,
			@JsonProperty("additionalDirectories") @Nullable List<String> additionalDirectories,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates an entry with only an id and a working directory: no title, last activity,
		 * additional directories or {@code _meta}.
		 * @param sessionId the session's id
		 * @param cwd the working directory, an absolute path
		 */
		public SessionInfo(String sessionId, String cwd) {
			this(sessionId, cwd, null, null, null, null);
		}
	}

	/**
	 * The modes an agent offers for an ACP session, and the one the session is in. An agent that
	 * has modes returns it as {@code modes} in its answer to {@code session/new}
	 * ({@link NewSessionResponse}), {@code session/load} ({@link LoadSessionResponse}) and
	 * {@code session/resume} ({@link ResumeSessionResponse}). A client shows the available modes
	 * and switches with a {@link SetSessionModeRequest}.
	 *
	 * <p>
	 * The state is a snapshot. When the agent changes the mode on its own, it sends a
	 * {@link CurrentModeUpdate} session update; the SDK does not keep the current mode for either
	 * side.
	 *
	 * @param currentModeId the {@link SessionMode#id()} of the mode the session is in
	 * @param availableModes the modes the agent offers for the session
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionModeState(@JsonProperty("currentModeId") String currentModeId,
			@JsonProperty("availableModes") List<SessionMode> availableModes,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a state without {@code _meta}.
		 * @param currentModeId the id of the current mode
		 * @param availableModes the modes offered
		 */
		public SessionModeState(String currentModeId, List<SessionMode> availableModes) {
			this(currentModeId, availableModes, null);
		}
	}

	/**
	 * One mode an agent offers for an ACP session, such as "ask" or "code": an id for the protocol,
	 * and a name and description for the user. A mode may change how the agent works on prompts and
	 * what it asks permission for. Modes appear in {@link SessionModeState#availableModes()}; a
	 * client switches to one by sending its {@link #id()} in a {@link SetSessionModeRequest}.
	 *
	 * @param id the mode's id, as {@link SetSessionModeRequest#modeId()} and
	 * {@link SessionModeState#currentModeId()} name it
	 * @param name the name to show the user
	 * @param description more detail to show with the name, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionMode(@JsonProperty("id") String id, @JsonProperty("name") String name,
			@JsonProperty("description") @Nullable String description,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a mode without {@code _meta}.
		 * @param id the mode's id
		 * @param name the name to show the user
		 * @param description more detail, or {@code null}
		 */
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
			 * Builds the option. Unlike the record's constructors, which also read other agents'
			 * options and so accept any value, the builder checks that the option can be
			 * offered: it has at least one choice, and {@code currentValue} is one of them.
			 * @return the option
			 * @throws IllegalStateException when {@code id}, {@code name},
			 * {@code currentValue} or the options were not set, when the options (across all
			 * groups) are empty, or when {@code currentValue} is not the value of one of them
			 */
			public SessionConfigSelect build() {
				String id = required(this.id, "id");
				String name = required(this.name, "name");
				String currentValue = required(this.currentValue, "currentValue");
				SessionConfigSelectOptions options = required(this.options, "options");
				List<String> values = options.allOptions().stream().map(SessionConfigSelectOption::value).toList();
				if (values.isEmpty()) {
					throw new IllegalStateException("Select config option '" + id + "' has no options to choose from");
				}
				if (!values.contains(currentValue)) {
					throw new IllegalStateException("Select config option '" + id + "' has currentValue '"
							+ currentValue + "', which is not one of its option values " + values);
				}
				return new SessionConfigSelect("select", id, name, this.description,
						this.category, currentValue, options, this.meta);
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
	 * One piece of what a user or an agent says: text, an image, audio, or a resource, linked or
	 * embedded. A user's message is a list of them, the {@link PromptRequest#prompt()} of
	 * {@code session/prompt}; an agent streams messages one block per session update
	 * ({@link AgentMessageChunk}, {@link AgentThoughtChunk}, {@link UserMessageChunk}), and a tool
	 * call shows its output in a {@link ToolCallContentBlock}. Create one of the variant records;
	 * when reading, check the variant with {@code instanceof}.
	 *
	 * <p>
	 * The variants: {@link TextContent}, plain text or Markdown, the usual block;
	 * {@link ImageContent} and {@link AudioContent}, base64-encoded media; {@link ResourceLink}, a
	 * reference to a resource the agent reads itself; {@link Resource}, a resource's contents
	 * embedded in the message. Each can carry {@link Annotations} for the client. The blocks have
	 * the same shape as the Model Context Protocol's, so an agent can pass on an MCP tool's content
	 * without converting it.
	 *
	 * <p>
	 * In a prompt, every agent accepts text and resource links. A client may send images, audio or
	 * embedded resources only when the agent advertises them in its {@link PromptCapabilities}, as
	 * {@code image}, {@code audio} and {@code embeddedContext}; an annotated agent declares them on
	 * its {@link com.agentclientprotocol.sdk.annotation.Prompt @Prompt} method. The SDK checks
	 * prompt content on neither side: a client checks
	 * {@link com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities
	 * NegotiatedCapabilities} before it sends such blocks.
	 *
	 * <p>
	 * On the wire the {@code type} member names the variant, and each variant record has it as its
	 * first component. A block of a kind this SDK does not know, or without {@code type}, reads as
	 * an {@link UnknownContentBlock}, so the message that carries it is still read. The interface
	 * is not sealed: end an {@code instanceof} chain with a branch for anything else
	 * (see {@link AcpSchema} on forward compatibility).
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
	 * A content block of a kind this SDK does not know, kept as received: the peer is on a newer
	 * protocol version or sent an extension. A receiver that does not understand it should ignore
	 * it; a proxy can forward it unchanged. The other blocks of the same prompt are still read.
	 *
	 * <p>
	 * It keeps the {@code type} discriminator ({@code null} when the block had none) and every
	 * other member in {@link #fields()}, an unmodifiable map in wire order, and writes them back
	 * unchanged. Names are case sensitive: {@code "TEXT"} is an unknown block, not a
	 * {@link TextContent}.
	 *
	 * @param type the discriminator as received, or {@code null}
	 * @param fields every other member, in wire order
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UnknownContentBlock(@JsonProperty("type") @Nullable String type,
			@JsonAnySetter @JsonAnyGetter Map<String, Object> fields) implements ContentBlock {
		/**
		 * Creates an unknown block. The fields are copied in their order, and {@code null} fields
		 * become an empty map.
		 * @param type the discriminator, or {@code null}
		 * @param fields every other member
		 */
		public UnknownContentBlock {
			fields = unknownFields(fields);
		}
	}

	/**
	 * Text in a message, plain or Markdown: the most common content block. A user's prompt is
	 * usually one {@code TextContent}, and an agent streams its reply as text blocks, one per
	 * {@link AgentMessageChunk}. Create one with {@code new TextContent("...")};
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#sendMessage(String)
	 * PromptContext.sendMessage} and {@code sendThought} wrap their text in one.
	 *
	 * <p>
	 * The protocol requires every agent to accept text blocks in a prompt, and asks clients to
	 * render the text as Markdown; the SDK passes the text on as it is.
	 *
	 * @param type the discriminator, {@code "text"}
	 * @param text the text, plain or Markdown
	 * @param annotations hints for the client on how to use or show the block, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TextContent(@JsonProperty("type") String type,
			@JsonProperty("text") String text, @JsonProperty("annotations") @Nullable Annotations annotations,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ContentBlock {
		/**
		 * Creates a text block with every component, as the JSON mapper does. Pass {@code null} for
		 * {@code type}, or use the shorter constructor, and it becomes {@code "text"}.
		 * @param type {@code null} or {@code "text"}
		 * @param text the text
		 * @param annotations the annotations, or {@code null}
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code type} is any other name
		 */
		public TextContent {
			type = discriminator(type, "text");
		}

		/**
		 * Creates a text block without annotations or metadata.
		 * @param text the text, plain or Markdown
		 */
		public TextContent(String text) {
			this("text", text, null, null);
		}
	}

	/**
	 * An image in a message, as base64-encoded data with its MIME type, such as a screenshot the
	 * user attaches to a prompt. A client sends one in a prompt only when the agent advertises
	 * {@code promptCapabilities.image} (see {@link ContentBlock}); an agent may also send images in
	 * its reply or in a tool call's content.
	 *
	 * <p>
	 * It differs from {@link TextContent} in what it carries: {@link #data()} is the image's bytes
	 * in base64, which the SDK neither encodes nor checks, and {@link #uri()} may name where the
	 * image came from. It has no shorter constructor: pass {@code null} for the type and for the
	 * optional components.
	 *
	 * @param type the discriminator, {@code "image"}
	 * @param data the image's bytes, base64-encoded
	 * @param mimeType the image's MIME type, such as {@code "image/png"}
	 * @param uri where the image came from, or {@code null}
	 * @param annotations hints for the client on how to use or show the block, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ImageContent(@JsonProperty("type") String type,
			@JsonProperty("data") String data, @JsonProperty("mimeType") String mimeType,
			@JsonProperty("uri") @Nullable String uri, @JsonProperty("annotations") @Nullable Annotations annotations,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ContentBlock {
		/**
		 * Creates an image block with every component, as the JSON mapper does. Pass {@code null}
		 * for {@code type} and it becomes {@code "image"}.
		 * @param type {@code null} or {@code "image"}
		 * @param data the image's bytes, base64-encoded
		 * @param mimeType the image's MIME type
		 * @param uri where the image came from, or {@code null}
		 * @param annotations the annotations, or {@code null}
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code type} is any other name
		 */
		public ImageContent {
			type = discriminator(type, "image");
		}
	}

	/**
	 * Audio in a message, as base64-encoded data with its MIME type, such as a voice recording for
	 * the agent to transcribe. A client sends one in a prompt only when the agent advertises
	 * {@code promptCapabilities.audio} (see {@link ContentBlock}).
	 *
	 * <p>
	 * Like {@link ImageContent}, it carries base64 data, which the SDK neither encodes nor checks,
	 * and a MIME type, but it has no {@code uri}. It has no shorter constructor: pass {@code null}
	 * for the type and for the optional components.
	 *
	 * @param type the discriminator, {@code "audio"}
	 * @param data the audio's bytes, base64-encoded
	 * @param mimeType the audio's MIME type, such as {@code "audio/wav"}
	 * @param annotations hints for the client on how to use or show the block, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AudioContent(@JsonProperty("type") String type,
			@JsonProperty("data") String data, @JsonProperty("mimeType") String mimeType,
			@JsonProperty("annotations") @Nullable Annotations annotations,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ContentBlock {
		/**
		 * Creates an audio block with every component, as the JSON mapper does. Pass {@code null}
		 * for {@code type} and it becomes {@code "audio"}.
		 * @param type {@code null} or {@code "audio"}
		 * @param data the audio's bytes, base64-encoded
		 * @param mimeType the audio's MIME type
		 * @param annotations the annotations, or {@code null}
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code type} is any other name
		 */
		public AudioContent {
			type = discriminator(type, "audio");
		}
	}

	/**
	 * A reference to a resource, such as a file, by its URI and a name, for the agent to read
	 * itself: the block carries no contents. A client puts one in a prompt to point the agent at a
	 * file the user mentioned; every agent must accept resource links in a prompt. A tool call's
	 * content can carry them too.
	 *
	 * <p>
	 * Unlike an embedded {@link Resource}, it leaves reading the resource to the agent, which must
	 * be able to reach it. The optional components describe the resource for display: a title, a
	 * description, its MIME type and its size. The SDK checks none of them and reads nothing from
	 * the URI. It has no shorter constructor: pass {@code null} for the type and for the optional
	 * components.
	 *
	 * @param type the discriminator, {@code "resource_link"}
	 * @param name a human-readable name for the resource, such as its file name
	 * @param uri the resource's URI, such as {@code "file:///home/user/document.pdf"}
	 * @param title a title to show the user, or {@code null}
	 * @param description a human-readable description of the resource, or {@code null}
	 * @param mimeType the resource's MIME type, or {@code null}
	 * @param size the resource's size in bytes, or {@code null} if unknown
	 * @param annotations hints for the client on how to use or show the block, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResourceLink(@JsonProperty("type") String type,
			@JsonProperty("name") String name, @JsonProperty("uri") String uri, @JsonProperty("title") @Nullable String title,
			@JsonProperty("description") @Nullable String description, @JsonProperty("mimeType") @Nullable String mimeType,
			@JsonProperty("size") @Nullable Long size, @JsonProperty("annotations") @Nullable Annotations annotations,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ContentBlock {
		/**
		 * Creates a resource link with every component, as the JSON mapper does. Pass {@code null}
		 * for {@code type} and it becomes {@code "resource_link"}.
		 * @param type {@code null} or {@code "resource_link"}
		 * @param name a human-readable name for the resource
		 * @param uri the resource's URI
		 * @param title a title to show the user, or {@code null}
		 * @param description a description of the resource, or {@code null}
		 * @param mimeType the resource's MIME type, or {@code null}
		 * @param size the resource's size in bytes, or {@code null}
		 * @param annotations the annotations, or {@code null}
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code type} is any other name
		 */
		public ResourceLink {
			type = discriminator(type, "resource_link");
		}
	}

	/**
	 * A resource's contents embedded in the message, such as the text of a file the user mentioned.
	 * It is the preferred way to give an agent context in a prompt: the agent need not read
	 * anything, and the client can include context the agent cannot reach. The schema calls this
	 * block {@code EmbeddedResource}; its {@code type} is {@code "resource"}.
	 *
	 * <p>
	 * A client sends one in a prompt only when the agent advertises
	 * {@code promptCapabilities.embeddedContext} (see {@link ContentBlock}). Unlike a
	 * {@link ResourceLink}, it carries the contents themselves: a {@link TextResourceContents} for
	 * text, or a {@link BlobResourceContents} for binary data. It has no shorter constructor: pass
	 * {@code null} for the type and for the optional components.
	 *
	 * @param type the discriminator, {@code "resource"}
	 * @param resource the resource's URI and contents
	 * @param annotations hints for the client on how to use or show the block, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Resource(@JsonProperty("type") String type,
			@JsonProperty("resource") EmbeddedResourceResource resource,
			@JsonProperty("annotations") @Nullable Annotations annotations,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ContentBlock {
		/**
		 * Creates an embedded resource with every component, as the JSON mapper does. Pass
		 * {@code null} for {@code type} and it becomes {@code "resource"}.
		 * @param type {@code null} or {@code "resource"}
		 * @param resource the resource's URI and contents
		 * @param annotations the annotations, or {@code null}
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code type} is any other name
		 */
		public Resource {
			type = discriminator(type, "resource");
		}
	}

	/**
	 * The contents of an embedded {@link Resource}: its URI and either text or binary data. Create
	 * a {@link TextResourceContents} or a {@link BlobResourceContents}; when reading, check which
	 * one with {@code instanceof}.
	 *
	 * <p>
	 * The wire form has no discriminator: contents with a {@code text} member read as
	 * {@code TextResourceContents}, and contents with a {@code blob} member as
	 * {@code BlobResourceContents}. There is no unknown variant. Contents with neither member
	 * cannot be read, so a prompt that carries them is answered with {@code -32602}
	 * (Invalid params) and a session update that carries them is skipped; contents with both read
	 * as text, and the blob is dropped.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextResourceContents.class),
			@JsonSubTypes.Type(value = BlobResourceContents.class) })
	public interface EmbeddedResourceResource {

	}

	/**
	 * The contents of a text resource, such as a source file, embedded in a {@link Resource}: its
	 * URI, its text and, optionally, its MIME type.
	 *
	 * <p>
	 * It differs from {@link BlobResourceContents} only in carrying text instead of base64 data.
	 *
	 * @param text the resource's text
	 * @param uri the resource's URI, such as {@code "file:///home/user/script.py"}
	 * @param mimeType the text's MIME type, such as {@code "text/x-python"}, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TextResourceContents(@JsonProperty("text") String text, @JsonProperty("uri") String uri,
			@JsonProperty("mimeType") @Nullable String mimeType,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements EmbeddedResourceResource {
		/**
		 * Creates text contents without {@code _meta}.
		 * @param text the resource's text
		 * @param uri the resource's URI
		 * @param mimeType the text's MIME type, or {@code null}
		 */
		public TextResourceContents(String text, String uri, @Nullable String mimeType) {
			this(text, uri, mimeType, null);
		}
	}

	/**
	 * The contents of a binary resource, such as an image or a PDF file, embedded in a
	 * {@link Resource}: its URI, its bytes in base64 and, optionally, its MIME type.
	 *
	 * <p>
	 * Like {@link TextResourceContents}, but {@link #blob()} holds the bytes, base64-encoded; the
	 * SDK neither encodes nor checks them.
	 *
	 * @param blob the resource's bytes, base64-encoded
	 * @param uri the resource's URI
	 * @param mimeType the resource's MIME type, such as {@code "application/pdf"}, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record BlobResourceContents(@JsonProperty("blob") String blob, @JsonProperty("uri") String uri,
			@JsonProperty("mimeType") @Nullable String mimeType,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements EmbeddedResourceResource {
		/**
		 * Creates binary contents without {@code _meta}.
		 * @param blob the resource's bytes, base64-encoded
		 * @param uri the resource's URI
		 * @param mimeType the resource's MIME type, or {@code null}
		 */
		public BlobResourceContents(String blob, String uri, @Nullable String mimeType) {
			this(blob, uri, mimeType, null);
		}
	}

	/**
	 * Optional hints on a content block for the client: whom the content is for, how important it
	 * is, and when the resource behind it last changed. Text, image, audio and resource blocks
	 * carry them as their {@code annotations} component, usually {@code null}; a client may use
	 * them to decide how to show or route the content.
	 *
	 * <p>
	 * {@link #audience()} lists the {@link Role}s the content is meant for, such as only the user;
	 * {@link #priority()} is its relative importance when the client chooses what to show;
	 * {@link #lastModified()} is a timestamp, which the SDK keeps as a string and does not parse.
	 * The SDK neither sets nor acts on any of them, and a role this SDK does not know is kept. A
	 * member of the wrong JSON type, such as a priority that is not a number, fails the message
	 * that carries it.
	 *
	 * @param audience the roles the content is meant for, or {@code null}
	 * @param priority the content's relative importance, or {@code null}
	 * @param lastModified when the resource behind the content last changed, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Annotations(@JsonProperty("audience") @Nullable List<Role> audience, @JsonProperty("priority") @Nullable Double priority,
			@JsonProperty("lastModified") @Nullable String lastModified,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates annotations without {@code _meta}.
		 * @param audience the roles the content is meant for, or {@code null}
		 * @param priority the content's relative importance, or {@code null}
		 * @param lastModified when the resource last changed, or {@code null}
		 */
		public Annotations(@Nullable List<Role> audience, @Nullable Double priority, @Nullable String lastModified) {
			this(audience, priority, lastModified, null);
		}
	}

	// ---------------------------
	// Session Updates
	// ---------------------------

	/**
	 * One update that an agent streams to the client in a {@link SessionNotification}: a piece of
	 * its reply or reasoning, a tool call or a change to one, its plan, or a change to the session.
	 * An agent creates one of the variant records and sends it with
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#sendUpdate PromptContext.sendUpdate};
	 * a client checks the variant with {@code instanceof} in its session-update consumer and shows
	 * what it understands.
	 *
	 * <p>
	 * The variants: {@link AgentMessageChunk}, {@link AgentThoughtChunk} and
	 * {@link UserMessageChunk} carry a message in pieces; {@link ToolCall} announces a tool call
	 * and {@link ToolCallUpdateNotification} changes it; {@link Plan} reports the agent's plan;
	 * {@link AvailableCommandsUpdate}, {@link CurrentModeUpdate}, {@link ConfigOptionUpdate} and
	 * {@link SessionInfoUpdate} report the session's slash commands, mode, config options and
	 * title; {@link UsageUpdate} reports how much of the context window the session uses, and its
	 * cost. A plan, a command list and a config option list are always complete: each update
	 * replaces the previous one.
	 *
	 * <p>
	 * On the wire the {@code sessionUpdate} member names the variant, and each variant record has
	 * it as its first component. An update of a kind this SDK does not know, or one without
	 * {@code sessionUpdate}, reads as an {@link UnknownSessionUpdate}, so the notification that
	 * carries it still reaches the consumers. The interface is not sealed: end an
	 * {@code instanceof} chain with a branch for anything else (see {@link AcpSchema} on forward
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
	 * A session update of a kind this SDK does not know, kept as received: the agent is on a newer
	 * protocol version or sent an extension. A client consumer that does not understand it should
	 * ignore it; a proxy can forward it unchanged.
	 *
	 * <p>
	 * It keeps the {@code sessionUpdate} discriminator ({@code null} when the update had none) and
	 * every other member in {@link #fields()}, an unmodifiable map in wire order, and writes them
	 * back unchanged. Names are case sensitive: {@code "PLAN"} is an unknown update, not a
	 * {@link Plan}.
	 *
	 * @param sessionUpdate the discriminator as received, or {@code null}
	 * @param fields every other member, in wire order
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UnknownSessionUpdate(@JsonProperty("sessionUpdate") @Nullable String sessionUpdate,
			@JsonAnySetter @JsonAnyGetter Map<String, Object> fields) implements SessionUpdate {
		/**
		 * Creates an unknown update. The fields are copied in their order, and {@code null} fields
		 * become an empty map.
		 * @param sessionUpdate the discriminator, or {@code null}
		 * @param fields every other member
		 */
		public UnknownSessionUpdate {
			fields = unknownFields(fields);
		}
	}

	/**
	 * A piece of a user's message, streamed as a session update. An agent sends these to replay the
	 * user's side of a conversation: when it answers {@code session/load}, it sends the whole
	 * conversation as user and agent message chunks before its answer. In a live prompt turn the
	 * client already has the user's message, the {@link PromptRequest}.
	 *
	 * <p>
	 * It differs from {@link AgentMessageChunk} only in whose message it carries: the content and
	 * the message id work the same way. The prompt context has no shortcut for it; send it with
	 * {@code PromptContext.sendUpdate} or {@code AcpSyncAgent.sendSessionUpdate}.
	 *
	 * @param sessionUpdate the discriminator, {@code "user_message_chunk"}
	 * @param content the piece of the message, one content block
	 * @param messageId the id of the message the piece belongs to, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UserMessageChunk(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("content") ContentBlock content, @JsonProperty("messageId") @Nullable String messageId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		/**
		 * Creates a chunk with every component, as the JSON mapper does. Pass {@code null} for
		 * {@code sessionUpdate}, or use a shorter constructor, and it becomes
		 * {@code "user_message_chunk"}.
		 * @param sessionUpdate {@code null} or {@code "user_message_chunk"}
		 * @param content the piece of the message
		 * @param messageId the message's id, or {@code null}
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code sessionUpdate} is any other name
		 */
		public UserMessageChunk {
			sessionUpdate = discriminator(sessionUpdate, "user_message_chunk");
		}

		/**
		 * Creates a chunk without a message id or {@code _meta}.
		 * @param content the piece of the message
		 */
		public UserMessageChunk(ContentBlock content) {
			this("user_message_chunk", content, null, null);
		}

		/**
		 * Creates a chunk without {@code _meta}.
		 * @param content the piece of the message
		 * @param messageId the message's id, or {@code null}
		 */
		public UserMessageChunk(ContentBlock content, @Nullable String messageId) {
			this("user_message_chunk", content, messageId, null);
		}
	}

	/**
	 * A piece of the agent's reply to the user, streamed as a session update. An agent sends its
	 * reply as a run of these, each with one {@link ContentBlock}, usually a {@link TextContent},
	 * and the client appends them to show the message as it grows.
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#sendMessage(String)
	 * PromptContext.sendMessage} sends one with a text block.
	 *
	 * <p>
	 * Chunks with the same {@link #messageId()} belong to one message, and a new id starts a new
	 * message. The id is optional and opaque; the SDK neither sets nor checks it, and
	 * {@code sendMessage(text, messageId)} sends one the agent chose. Together with
	 * {@link UserMessageChunk}s, an agent also sends these to replay a conversation before it
	 * answers {@code session/load}.
	 *
	 * @param sessionUpdate the discriminator, {@code "agent_message_chunk"}
	 * @param content the piece of the message, one content block
	 * @param messageId the id of the message the piece belongs to, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AgentMessageChunk(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("content") ContentBlock content, @JsonProperty("messageId") @Nullable String messageId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		/**
		 * Creates a chunk with every component, as the JSON mapper does. Pass {@code null} for
		 * {@code sessionUpdate}, or use a shorter constructor, and it becomes
		 * {@code "agent_message_chunk"}.
		 * @param sessionUpdate {@code null} or {@code "agent_message_chunk"}
		 * @param content the piece of the message
		 * @param messageId the message's id, or {@code null}
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code sessionUpdate} is any other name
		 */
		public AgentMessageChunk {
			sessionUpdate = discriminator(sessionUpdate, "agent_message_chunk");
		}

		/**
		 * Creates a chunk without a message id or {@code _meta}.
		 * @param content the piece of the message
		 */
		public AgentMessageChunk(ContentBlock content) {
			this("agent_message_chunk", content, null, null);
		}

		/**
		 * Creates a chunk without {@code _meta}.
		 * @param content the piece of the message
		 * @param messageId the message's id, or {@code null}
		 */
		public AgentMessageChunk(ContentBlock content, @Nullable String messageId) {
			this("agent_message_chunk", content, messageId, null);
		}
	}

	/**
	 * A piece of the agent's reasoning, streamed as a session update: like an
	 * {@link AgentMessageChunk}, but for what the agent thinks on the way to its reply, so a client
	 * can show it apart from the reply, for example folded away.
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#sendThought(String)
	 * PromptContext.sendThought} sends one with a text block.
	 *
	 * <p>
	 * It differs from {@link AgentMessageChunk} only in what it carries: the content and the
	 * message id work the same way, and {@code sendThought(text, messageId)} sends one with an id.
	 *
	 * @param sessionUpdate the discriminator, {@code "agent_thought_chunk"}
	 * @param content the piece of the message, one content block
	 * @param messageId the id of the message the piece belongs to, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AgentThoughtChunk(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("content") ContentBlock content, @JsonProperty("messageId") @Nullable String messageId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		/**
		 * Creates a chunk with every component, as the JSON mapper does. Pass {@code null} for
		 * {@code sessionUpdate}, or use a shorter constructor, and it becomes
		 * {@code "agent_thought_chunk"}.
		 * @param sessionUpdate {@code null} or {@code "agent_thought_chunk"}
		 * @param content the piece of the message
		 * @param messageId the message's id, or {@code null}
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code sessionUpdate} is any other name
		 */
		public AgentThoughtChunk {
			sessionUpdate = discriminator(sessionUpdate, "agent_thought_chunk");
		}

		/**
		 * Creates a chunk without a message id or {@code _meta}.
		 * @param content the piece of the message
		 */
		public AgentThoughtChunk(ContentBlock content) {
			this("agent_thought_chunk", content, null, null);
		}

		/**
		 * Creates a chunk without {@code _meta}.
		 * @param content the piece of the message
		 * @param messageId the message's id, or {@code null}
		 */
		public AgentThoughtChunk(ContentBlock content, @Nullable String messageId) {
			this("agent_thought_chunk", content, messageId, null);
		}
	}

	/**
	 * Announces a tool call, streamed as a session update: an action the agent takes for the
	 * language model, such as reading a file, running a command or fetching a page, so the client
	 * can show it. Later {@link ToolCallUpdateNotification}s with the same {@link #toolCallId()}
	 * report its progress and results.
	 *
	 * <p>
	 * Only {@code toolCallId}, unique within the session, and {@code title} are required. A tool
	 * call without a status is pending in the protocol; this record reads it as {@code null}.
	 * {@code name} is the tool's programmatic name, such as {@code read_file}: the protocol asks
	 * agents to send it in the first report when they have it and not to change it later, and it
	 * grants nothing. {@code kind} helps the client pick an icon, and a kind this SDK does not know
	 * reads as {@link ToolKind#OTHER}. {@code rawInput} and {@code rawOutput} are any JSON value,
	 * read as maps, lists, strings, numbers or booleans. There is no shorter constructor: pass
	 * {@code null} for what you leave out.
	 *
	 * <p>
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#askPermission(String)
	 * PromptContext.askPermission} and {@code askChoice} send one of these themselves, with a new
	 * random id and status pending, before they ask the user, and settle it with a
	 * {@link ToolCallUpdateNotification} when the request ends.
	 *
	 * @param sessionUpdate the discriminator, {@code "tool_call"}
	 * @param toolCallId the tool call's id, unique within the session
	 * @param title a human-readable description of what the tool is doing
	 * @param name the tool's programmatic name, or {@code null}
	 * @param kind the category of tool, or {@code null}
	 * @param status the execution status, or {@code null} (pending)
	 * @param content what the tool call produced, or {@code null}
	 * @param locations the files the tool call reads or changes, or {@code null}
	 * @param rawInput the raw input sent to the tool, or {@code null}
	 * @param rawOutput the raw output the tool returned, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
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
		/**
		 * Creates a tool call with every component, as the JSON mapper does. Pass {@code null} for
		 * {@code sessionUpdate}, or use a shorter constructor, and it becomes {@code "tool_call"}.
		 * @param sessionUpdate {@code null} or {@code "tool_call"}
		 * @param toolCallId the tool call's id
		 * @param title what the tool is doing
		 * @param name the tool's programmatic name, or {@code null}
		 * @param kind the category of tool, or {@code null}
		 * @param status the execution status, or {@code null}
		 * @param content what the tool call produced, or {@code null}
		 * @param locations the files the tool call affects, or {@code null}
		 * @param rawInput the raw input sent to the tool, or {@code null}
		 * @param rawOutput the raw output the tool returned, or {@code null}
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code sessionUpdate} is any other name
		 */
		public ToolCall {
			sessionUpdate = discriminator(sessionUpdate, "tool_call");
		}
	}

	/**
	 * The tool call a permission request asks about: the
	 * {@link RequestPermissionRequest#toolCall()} an agent sends with
	 * {@code session/request_permission}. Only {@link #toolCallId()} is required; the other fields
	 * describe the action, so the client can show the user what it is asked to allow. The
	 * four-argument constructor covers the usual case: id, title, kind and status.
	 *
	 * <p>
	 * The protocol defines it as an update to a tool call the agent announced with a
	 * {@link ToolCall} session update: the id names that tool call, a field left out, {@code null}
	 * here, is unchanged, and {@code content} and {@code locations}, when present, replace the
	 * whole list. It has the fields of a {@link ToolCallUpdateNotification} without the
	 * discriminator.
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#askPermission(String, ToolKind)
	 * PromptContext.askPermission} and {@code askChoice} announce a pending tool call first and
	 * send this with its id, title, kind and status.
	 *
	 * @param toolCallId the id of the tool call the request is about
	 * @param title the human-readable title, or {@code null}
	 * @param name the tool's programmatic name, or {@code null}
	 * @param kind the category of tool, or {@code null}
	 * @param status the execution status, or {@code null}
	 * @param content the content that replaces the tool call's content, or {@code null}
	 * @param locations the locations that replace the tool call's locations, or {@code null}
	 * @param rawInput the raw input sent to the tool, or {@code null}
	 * @param rawOutput the raw output the tool returned, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
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
		 * Creates a tool call update with an id, a title, a kind and a status, the usual form for a
		 * permission request, without a name, content, locations, raw input or output, or
		 * {@code _meta}.
		 * @param toolCallId the id of the tool call
		 * @param title the human-readable title, or {@code null}
		 * @param kind the category of tool, or {@code null}
		 * @param status the execution status, or {@code null}
		 */
		public ToolCallUpdate(String toolCallId, @Nullable String title, @Nullable ToolKind kind,
				@Nullable ToolCallStatus status) {
			this(toolCallId, title, null, kind, status, null, null, null, null, null);
		}
	}
	// CPD-ON

	/**
	 * Changes a tool call the agent announced with a {@link ToolCall}, streamed as a session
	 * update: its status, its results or any other field. Only {@link #toolCallId()} is required. A
	 * field left out, {@code null} here, is unchanged; {@code content} and {@code locations}, when
	 * present, replace the whole list.
	 *
	 * <p>
	 * A tool call typically goes from pending to in progress to completed or failed
	 * ({@link ToolCallStatus}), with an update at each step and the output in {@code content}. It
	 * has the same fields as {@link ToolCallUpdate}, the form a permission request carries, plus
	 * the discriminator. There is no shorter constructor: pass {@code null} for what does not
	 * change.
	 *
	 * @param sessionUpdate the discriminator, {@code "tool_call_update"}
	 * @param toolCallId the id of the tool call to change
	 * @param title a new human-readable title, or {@code null}
	 * @param name a new programmatic name, or {@code null}
	 * @param kind a new category, or {@code null}
	 * @param status a new execution status, or {@code null}
	 * @param content the content that replaces the tool call's content, or {@code null}
	 * @param locations the locations that replace the tool call's locations, or {@code null}
	 * @param rawInput a new raw input, or {@code null}
	 * @param rawOutput a new raw output, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
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
		/**
		 * Creates an update with every component, as the JSON mapper does. Pass {@code null} for
		 * {@code sessionUpdate}, or use a shorter constructor, and it becomes
		 * {@code "tool_call_update"}.
		 * @param sessionUpdate {@code null} or {@code "tool_call_update"}
		 * @param toolCallId the id of the tool call to change
		 * @param title a new title, or {@code null}
		 * @param name the tool's programmatic name, or {@code null}
		 * @param kind the category of tool, or {@code null}
		 * @param status the execution status, or {@code null}
		 * @param content what the tool call produced, or {@code null}
		 * @param locations the files the tool call affects, or {@code null}
		 * @param rawInput the raw input sent to the tool, or {@code null}
		 * @param rawOutput the raw output the tool returned, or {@code null}
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code sessionUpdate} is any other name
		 */
		public ToolCallUpdateNotification {
			sessionUpdate = discriminator(sessionUpdate, "tool_call_update");
		}
	}

	/**
	 * The agent's plan for the task at hand, streamed as a session update: a list of
	 * {@link PlanEntry} items, each with a priority and a status, so the client can show the steps
	 * and their progress. Every update is the whole plan: the agent sends all entries with their
	 * current status each time, and the client replaces the plan it shows.
	 *
	 * <p>
	 * The protocol asks agents to report a plan when the model makes one, and to send another as
	 * the work goes on. The SDK does not check the entries.
	 *
	 * @param sessionUpdate the discriminator, {@code "plan"}
	 * @param entries every entry of the plan, with its current status
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Plan(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("entries") List<PlanEntry> entries,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		/**
		 * Creates a plan with every component, as the JSON mapper does. Pass {@code null} for
		 * {@code sessionUpdate}, or use a shorter constructor, and it becomes {@code "plan"}.
		 * @param sessionUpdate {@code null} or {@code "plan"}
		 * @param entries every entry of the plan
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code sessionUpdate} is any other name
		 */
		public Plan {
			sessionUpdate = discriminator(sessionUpdate, "plan");
		}

		/**
		 * Creates a plan without {@code _meta}.
		 * @param entries every entry of the plan
		 */
		public Plan(List<PlanEntry> entries) {
			this("plan", entries, null);
		}
	}

	/**
	 * The slash commands the agent offers in an ACP session, such as {@code /web} or {@code /test},
	 * streamed as a session update so the client can offer them as the user types. The user runs
	 * one by sending it as text in a prompt. Each update is the full list and replaces the previous
	 * one: the agent can send it after creating the session and again whenever its commands change.
	 *
	 * @param sessionUpdate the discriminator, {@code "available_commands_update"}
	 * @param availableCommands every command the session offers now
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AvailableCommandsUpdate(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("availableCommands") List<AvailableCommand> availableCommands,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		/**
		 * Creates an update with every component, as the JSON mapper does. Pass {@code null} for
		 * {@code sessionUpdate}, or use a shorter constructor, and it becomes
		 * {@code "available_commands_update"}.
		 * @param sessionUpdate {@code null} or {@code "available_commands_update"}
		 * @param availableCommands every command the session offers
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code sessionUpdate} is any other name
		 */
		public AvailableCommandsUpdate {
			sessionUpdate = discriminator(sessionUpdate, "available_commands_update");
		}

		/**
		 * Creates an update without {@code _meta}.
		 * @param availableCommands every command the session offers
		 */
		public AvailableCommandsUpdate(List<AvailableCommand> availableCommands) {
			this("available_commands_update", availableCommands, null);
		}
	}

	/**
	 * Tells the client that the agent switched an ACP session to another mode on its own, streamed
	 * as a session update: for example from a planning mode to a coding mode when the model is
	 * ready to change code. {@link #currentModeId()} is the {@link SessionMode#id()} of one of the
	 * modes the agent offered in the session's {@link SessionModeState}.
	 *
	 * <p>
	 * A switch the client asked for with {@link SetSessionModeRequest} is confirmed by that
	 * request's answer. The SDK never sends this update by itself; the agent sends it.
	 *
	 * @param sessionUpdate the discriminator, {@code "current_mode_update"}
	 * @param currentModeId the id of the mode the session is in now
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CurrentModeUpdate(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("currentModeId") String currentModeId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		/**
		 * Creates an update with every component, as the JSON mapper does. Pass {@code null} for
		 * {@code sessionUpdate}, or use a shorter constructor, and it becomes
		 * {@code "current_mode_update"}.
		 * @param sessionUpdate {@code null} or {@code "current_mode_update"}
		 * @param currentModeId the id of the mode the session is in now
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code sessionUpdate} is any other name
		 */
		public CurrentModeUpdate {
			sessionUpdate = discriminator(sessionUpdate, "current_mode_update");
		}

		/**
		 * Creates an update without {@code _meta}.
		 * @param currentModeId the id of the mode the session is in now
		 */
		public CurrentModeUpdate(String currentModeId) {
			this("current_mode_update", currentModeId, null);
		}
	}

	/**
	 * New metadata for an ACP session, its title or the time of its last activity, streamed as a
	 * session update so a client can show a current session name without asking
	 * {@code session/list} again. Every field is optional: the agent sends only what changed, and a
	 * field left out is unchanged. These are fields of the {@link SessionInfo} a
	 * {@code session/list} answer carries; the session id is in the {@link SessionNotification}.
	 *
	 * <p>
	 * The protocol also lets a peer send {@code null} to clear a field. This record cannot tell an
	 * explicit {@code null} from a missing field (both read as {@code null}) and never writes
	 * {@code null}, so it can neither receive nor send a clear.
	 *
	 * @param sessionUpdate the discriminator, {@code "session_info_update"}
	 * @param title the session's new human-readable title, or {@code null} if unchanged
	 * @param updatedAt the time of the session's last activity, an ISO 8601 timestamp, or
	 * {@code null} if unchanged; the SDK does not check the format
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionInfoUpdate(@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("title") @Nullable String title, @JsonProperty("updatedAt") @Nullable String updatedAt,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		/**
		 * Creates an update with every component, as the JSON mapper does. Pass {@code null} for
		 * {@code sessionUpdate}, or use a shorter constructor, and it becomes
		 * {@code "session_info_update"}.
		 * @param sessionUpdate {@code null} or {@code "session_info_update"}
		 * @param title the new title, or {@code null}
		 * @param updatedAt the time of the last activity, or {@code null}
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code sessionUpdate} is any other name
		 */
		public SessionInfoUpdate {
			sessionUpdate = discriminator(sessionUpdate, "session_info_update");
		}

		/**
		 * Creates an update without {@code _meta}.
		 * @param title the new title, or {@code null}
		 * @param updatedAt the time of the last activity, or {@code null}
		 */
		public SessionInfoUpdate(@Nullable String title, @Nullable String updatedAt) {
			this("session_info_update", title, updatedAt, null);
		}
	}

	/**
	 * How much of the model's context window an ACP session uses, and what the session has cost so
	 * far, streamed as a session update so a client can show it. {@link #used()} is the number of
	 * tokens now in the context and {@link #size()} the window's total size; both are required, and
	 * {@link #cost()} is optional. The agent can send one whenever the numbers change.
	 *
	 * <p>
	 * The protocol defines both counts as non-negative; the SDK does not check them.
	 *
	 * @param sessionUpdate the discriminator, {@code "usage_update"}
	 * @param used the number of tokens now in the context window
	 * @param size the context window's total size, in tokens
	 * @param cost the session's total cost so far, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UsageUpdate(
			@JsonProperty("sessionUpdate") String sessionUpdate,
			@JsonProperty("used") Long used, @JsonProperty("size") Long size, @JsonProperty("cost") @Nullable Cost cost,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements SessionUpdate {
		/**
		 * Creates an update with every component, as the JSON mapper does. Pass {@code null} for
		 * {@code sessionUpdate}, or use a shorter constructor, and it becomes
		 * {@code "usage_update"}.
		 * @param sessionUpdate {@code null} or {@code "usage_update"}
		 * @param used the tokens now in the context window
		 * @param size the context window's size, in tokens
		 * @param cost the session's total cost, or {@code null}
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code sessionUpdate} is any other name
		 */
		public UsageUpdate {
			sessionUpdate = discriminator(sessionUpdate, "usage_update");
		}

		/**
		 * Creates an update without a cost or {@code _meta}.
		 * @param used the tokens now in the context window
		 * @param size the context window's size, in tokens
		 */
		public UsageUpdate(Long used, Long size) {
			this("usage_update", used, size, null, null);
		}
	}

	/**
	 * The total cost of an ACP session so far: the {@link UsageUpdate#cost()} an agent reports with
	 * its context-window usage. It is an amount and the currency it is in, both required.
	 *
	 * <p>
	 * The amount adds up over the whole session; it is not the cost of one prompt turn. The
	 * currency is an ISO 4217 code, such as {@code "USD"}; the SDK checks neither value.
	 *
	 * @param amount the session's total cost so far, in {@code currency}
	 * @param currency the ISO 4217 currency code, such as {@code "USD"} or {@code "EUR"}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Cost(@JsonProperty("amount") Double amount,
			@JsonProperty("currency") String currency,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a cost without {@code _meta}.
		 * @param amount the session's total cost so far
		 * @param currency the ISO 4217 currency code
		 */
		public Cost(Double amount, String currency) {
			this(amount, currency, null);
		}
	}

	// ---------------------------
	// Tool Call Types
	// ---------------------------

	/**
	 * Something a tool call produced, for the client to show with it: one item of the
	 * {@code content} list of a {@link ToolCall}, a {@link ToolCallUpdateNotification} or a
	 * {@link ToolCallUpdate}. An agent creates one of the variant records; a client checks the
	 * variant with {@code instanceof} and shows what it understands.
	 *
	 * <p>
	 * The variants: {@link ToolCallContentBlock} carries an ordinary {@link ContentBlock}, such as
	 * text or an image; {@link ToolCallDiff} shows a change to a file; {@link ToolCallTerminal}
	 * embeds the live output of a terminal the agent created.
	 *
	 * <p>
	 * On the wire the {@code type} member names the variant, and each variant record has it as its
	 * first component. Content of a kind this SDK does not know, or without {@code type}, reads as
	 * an {@link UnknownToolCallContent}, so the message that carries it is still read. The
	 * interface is not sealed: end an {@code instanceof} chain with a branch for anything else
	 * (see {@link AcpSchema} on forward compatibility).
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.EXISTING_PROPERTY,
			visible = true, defaultImpl = UnknownToolCallContent.class)
	@JsonSubTypes({ @JsonSubTypes.Type(value = ToolCallContentBlock.class, name = "content"),
			@JsonSubTypes.Type(value = ToolCallDiff.class, name = "diff"),
			@JsonSubTypes.Type(value = ToolCallTerminal.class, name = "terminal") })
	public interface ToolCallContent {

	}

	/**
	 * Tool call content of a kind this SDK does not know, kept as received: the peer is on a newer
	 * protocol version or sent an extension. A client that does not understand it should ignore it;
	 * a proxy can forward it unchanged.
	 *
	 * <p>
	 * It keeps the {@code type} discriminator ({@code null} when the content had none) and every
	 * other member in {@link #fields()}, an unmodifiable map in wire order, and writes them back
	 * unchanged. Names are case sensitive: {@code "DIFF"} is unknown content, not a
	 * {@link ToolCallDiff}.
	 *
	 * @param type the discriminator as received, or {@code null}
	 * @param fields every other member, in wire order
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record UnknownToolCallContent(@JsonProperty("type") @Nullable String type,
			@JsonAnySetter @JsonAnyGetter Map<String, Object> fields) implements ToolCallContent {
		/**
		 * Creates unknown content. The fields are copied in their order, and {@code null} fields
		 * become an empty map.
		 * @param type the discriminator, or {@code null}
		 * @param fields every other member
		 */
		public UnknownToolCallContent {
			fields = unknownFields(fields);
		}
	}

	/**
	 * Tool call content that is an ordinary {@link ContentBlock}, such as the text a command
	 * printed or an image it made, for the client to show with the tool call. It is the most common
	 * variant of {@link ToolCallContent}: an agent wraps the tool's output in it, usually a
	 * {@link TextContent}.
	 *
	 * <p>
	 * A content block of a kind this SDK does not know reads as an {@link UnknownContentBlock}
	 * inside it, so the tool call content itself is still a {@code ToolCallContentBlock}.
	 *
	 * @param type the discriminator, {@code "content"}
	 * @param content the content block
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallContentBlock(
			@JsonProperty("type") String type,
			@JsonProperty("content") ContentBlock content,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ToolCallContent {
		/**
		 * Creates content without {@code _meta}. Unlike the shorter constructors of the session
		 * updates, it still takes the discriminator: pass {@code null} or {@code "content"}.
		 * @param type {@code null} or {@code "content"}
		 * @param content the content block
		 * @throws IllegalArgumentException if {@code type} is any other name
		 */
		public ToolCallContentBlock(String type, ContentBlock content) {
			this(type, content, null);
		}

		/**
		 * Creates content with every component, as the JSON mapper does. Pass {@code null} for
		 * {@code type} and it becomes {@code "content"}.
		 * @param type {@code null} or {@code "content"}
		 * @param content the content block
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code type} is any other name
		 */
		public ToolCallContentBlock {
			type = discriminator(type, "content");
		}
	}

	/**
	 * Tool call content that shows a change to a file: its path and its text before and after, from
	 * which the client shows the user a diff. An agent adds one to the tool call that changes the
	 * file, typically of kind {@link ToolKind#EDIT}.
	 *
	 * <p>
	 * Like {@link ToolCallContentBlock}, it is one item of a tool call's content, but it carries a
	 * file change instead of a content block. The protocol asks for an absolute path, and
	 * {@code oldText} is {@code null} for a new file. The SDK checks neither and does not touch the
	 * file: the record only describes the change.
	 *
	 * @param type the discriminator, {@code "diff"}
	 * @param path the absolute path of the file
	 * @param oldText the file's text before the change, or {@code null} for a new file
	 * @param newText the file's text after the change
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallDiff(@JsonProperty("type") String type,
			@JsonProperty("path") String path, @JsonProperty("oldText") @Nullable String oldText,
			@JsonProperty("newText") String newText,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ToolCallContent {
		/**
		 * Creates a diff without {@code _meta}. Unlike the shorter constructors of the session
		 * updates, it still takes the discriminator: pass {@code null} or {@code "diff"}.
		 * @param type {@code null} or {@code "diff"}
		 * @param path the absolute path of the file
		 * @param oldText the text before the change, or {@code null} for a new file
		 * @param newText the text after the change
		 * @throws IllegalArgumentException if {@code type} is any other name
		 */
		public ToolCallDiff(String type, String path, @Nullable String oldText, String newText) {
			this(type, path, oldText, newText, null);
		}

		/**
		 * Creates a diff with every component, as the JSON mapper does. Pass {@code null} for
		 * {@code type} and it becomes {@code "diff"}.
		 * @param type {@code null} or {@code "diff"}
		 * @param path the absolute path of the file
		 * @param oldText the text before the change, or {@code null} for a new file
		 * @param newText the text after the change
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code type} is any other name
		 */
		public ToolCallDiff {
			type = discriminator(type, "diff");
		}
	}

	/**
	 * Tool call content that embeds a terminal, so the client shows a command's output live as part
	 * of the tool call. The terminal is one the agent created with {@code terminal/create}
	 * ({@link CreateTerminalRequest}), named by the {@link CreateTerminalResponse#terminalId()} the
	 * client answered with.
	 *
	 * <p>
	 * Like {@link ToolCallContentBlock}, it is one item of a tool call's content, but it carries a
	 * terminal id instead of a content block. The protocol requires the agent to add the terminal
	 * to a tool call before it releases the terminal with {@code terminal/release}; the client then
	 * keeps showing the output after the release. The SDK does not check the order.
	 *
	 * @param type the discriminator, {@code "terminal"}
	 * @param terminalId the id of the terminal, as the client returned it
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallTerminal(@JsonProperty("type") String type,
			@JsonProperty("terminalId") String terminalId,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ToolCallContent {
		/**
		 * Creates terminal content without {@code _meta}. Unlike the shorter constructors of the
		 * session updates, it still takes the discriminator: pass {@code null} or
		 * {@code "terminal"}.
		 * @param type {@code null} or {@code "terminal"}
		 * @param terminalId the id of the terminal
		 * @throws IllegalArgumentException if {@code type} is any other name
		 */
		public ToolCallTerminal(String type, String terminalId) {
			this(type, terminalId, null);
		}

		/**
		 * Creates terminal content with every component, as the JSON mapper does. Pass {@code null}
		 * for {@code type} and it becomes {@code "terminal"}.
		 * @param type {@code null} or {@code "terminal"}
		 * @param terminalId the id of the terminal
		 * @param meta the {@code _meta} map, or {@code null}
		 * @throws IllegalArgumentException if {@code type} is any other name
		 */
		public ToolCallTerminal {
			type = discriminator(type, "terminal");
		}
	}

	/**
	 * A file a tool call reads or changes, with an optional line, so a client can follow the agent:
	 * for example, open the file the agent works on as it works. Agents list these in the
	 * {@code locations} of a {@link ToolCall} or a {@link ToolCallUpdateNotification}.
	 *
	 * <p>
	 * The protocol asks for an absolute path and a line number from 0 up to an unsigned 32-bit
	 * maximum; the SDK checks neither. This record holds the line as an {@code Integer}, so a
	 * received line above {@link Integer#MAX_VALUE} cannot be read, and the message that carries it
	 * fails to read as a whole.
	 *
	 * @param path the absolute path of the file
	 * @param line the line in the file, or {@code null} for none in particular
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallLocation(@JsonProperty("path") String path, @JsonProperty("line") @Nullable Integer line,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a location without {@code _meta}.
		 * @param path the absolute path of the file
		 * @param line the line in the file, or {@code null}
		 */
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
	 * Where a tool call is in its run: pending, in progress, completed or failed. It is the
	 * {@code status} of a {@link ToolCall}, a {@link ToolCallUpdateNotification} and a
	 * {@link ToolCallUpdate}. Agents send one of the constants as the tool call goes on; clients
	 * compare the value they receive with {@code equals}, or switch on {@link #value()}.
	 *
	 * <p>
	 * A tool call usually starts pending, moves to in progress when it runs, and ends completed or
	 * failed, with a {@link ToolCallUpdateNotification} at each step. A tool call announced without
	 * a status is pending in the protocol, but its status reads as {@code null} here.
	 *
	 * <p>
	 * An open value: a value this SDK does not know, from a newer peer, is kept and written back
	 * unchanged, and its {@link #isKnown()} is {@code false}, so it never fails the message
	 * (see {@link AcpSchema} on forward compatibility). {@link #of} returns the constant for a
	 * known wire value, so a known value read from the wire is one of the constants.
	 *
	 * @param value the wire value, such as {@code "in_progress"}
	 */
	public record ToolCallStatus(@JsonValue String value) {

		/**
		 * {@code "pending"}: the tool call has not started, because its input is still streaming or
		 * it waits for the user's permission.
		 */
		public static final ToolCallStatus PENDING = new ToolCallStatus("pending");

		/** {@code "in_progress"}: the tool call is running. */
		public static final ToolCallStatus IN_PROGRESS = new ToolCallStatus("in_progress");

		/** {@code "completed"}: the tool call completed successfully. */
		public static final ToolCallStatus COMPLETED = new ToolCallStatus("completed");

		/** {@code "failed"}: the tool call failed with an error. */
		public static final ToolCallStatus FAILED = new ToolCallStatus("failed");

		private static final List<ToolCallStatus> KNOWN = List.of(PENDING, IN_PROGRESS, COMPLETED, FAILED);

		/**
		 * Creates a tool call status for a wire value. Prefer {@link #of}, which returns the
		 * constant for a known value; a value created here still equals that constant.
		 * @param value the wire value
		 * @throws NullPointerException if {@code value} is {@code null}
		 */
		public ToolCallStatus {
			Objects.requireNonNull(value, "value");
		}

		/**
		 * Returns a tool call status for a wire value: the constant when ACP v1 defines the value,
		 * and otherwise a new, unknown value.
		 * @param value the wire value
		 * @return the constant, or a new value for an unknown string
		 * @throws NullPointerException if {@code value} is {@code null}
		 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static ToolCallStatus of(String value) {
			return knownOrNew(KNOWN, ToolCallStatus::value, value, ToolCallStatus::new);
		}

		/**
		 * Returns the values ACP v1 defines, in schema order.
		 * @return the constants, in an unmodifiable list
		 */
		public static List<ToolCallStatus> known() {
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
		 * Returns the wire value, such as {@code "in_progress"}.
		 * @return the wire value
		 */
		@Override
		public String toString() {
			return value;
		}

	}

	/**
	 * The category of a tool call, such as reading a file, editing one or running a command, so a
	 * client can pick an icon and choose how to show the call's progress. It is the {@code kind} of
	 * a {@link ToolCall}, a {@link ToolCallUpdateNotification} and a {@link ToolCallUpdate}.
	 * {@link com.agentclientprotocol.sdk.agent.PromptContext#askPermission(String, ToolKind)
	 * PromptContext.askPermission} takes one for the tool call it announces, and uses
	 * {@link #OTHER} when given none.
	 *
	 * <p>
	 * The kinds: {@code read} reads files or data; {@code edit} changes files or content;
	 * {@code delete} removes files or data; {@code move} moves or renames files; {@code search}
	 * searches for information; {@code execute} runs commands or code; {@code think} is internal
	 * reasoning or planning; {@code fetch} gets data from outside, such as a web page;
	 * {@code switch_mode} switches the session's mode; {@code other} is any other tool, and the
	 * protocol's default.
	 *
	 * <p>
	 * Unlike {@link ToolCallStatus} and the other open values, it is a Java enum: compare with
	 * {@code ==} or switch on it. {@link #OTHER} also stands for every kind this SDK does not know:
	 * a newer peer's kind reads as {@code OTHER} and is written back as {@code "other"}, so the
	 * name it had is lost (see {@link AcpSchema} on forward compatibility). A tool call without a
	 * kind reads as {@code null}. Use {@link #value()} for the wire name: {@code toString()}
	 * returns the constant's Java name, such as {@code SWITCH_MODE}.
	 */
	public enum ToolKind {

		READ("read"), EDIT("edit"), DELETE("delete"), MOVE("move"), SEARCH("search"), EXECUTE("execute"),
		THINK("think"), FETCH("fetch"), SWITCH_MODE("switch_mode"), OTHER("other");

		private final String value;

		ToolKind(String value) {
			this.value = value;
		}

		/**
		 * Returns the wire value, such as {@code "switch_mode"}, which is not the constant's Java
		 * name.
		 * @return the kind's name in ACP
		 */
		@JsonValue
		public String value() {
			return value;
		}

		/**
		 * Returns the kind for a wire value, or {@link #OTHER} for a value this SDK does not know,
		 * {@code null} included. Names are case sensitive: {@code "READ"} is {@code OTHER}.
		 * @param value the wire value, or {@code null}
		 * @return the kind, never {@code null}
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
	 * A side of a conversation, the user or the assistant: an entry of
	 * {@link Annotations#audience()}, which says whom a piece of content is meant for. Senders use
	 * the constants; receivers compare the value they receive with {@code equals}, or switch on
	 * {@link #value()}.
	 *
	 * <p>
	 * An open value: a value this SDK does not know, from a newer peer, is kept and written back
	 * unchanged, and its {@link #isKnown()} is {@code false}, so it never fails the message
	 * (see {@link AcpSchema} on forward compatibility). {@link #of} returns the constant for a
	 * known wire value, so a known value read from the wire is one of the constants.
	 *
	 * @param value the wire value, such as {@code "user"}
	 */
	public record Role(@JsonValue String value) {

		/** {@code "assistant"}: the assistant side of a conversation. */
		public static final Role ASSISTANT = new Role("assistant");

		/** {@code "user"}: the user side of a conversation. */
		public static final Role USER = new Role("user");

		private static final List<Role> KNOWN = List.of(ASSISTANT, USER);

		/**
		 * Creates a role for a wire value. Prefer {@link #of}, which returns the constant for a
		 * known value; a value created here still equals that constant.
		 * @param value the wire value
		 * @throws NullPointerException if {@code value} is {@code null}
		 */
		public Role {
			Objects.requireNonNull(value, "value");
		}

		/**
		 * Returns a role for a wire value: the constant when ACP v1 defines the value, and
		 * otherwise a new, unknown value.
		 * @param value the wire value
		 * @return the constant, or a new value for an unknown string
		 * @throws NullPointerException if {@code value} is {@code null}
		 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static Role of(String value) {
			return knownOrNew(KNOWN, Role::value, value, Role::new);
		}

		/**
		 * Returns the values ACP v1 defines, in schema order.
		 * @return the constants, in an unmodifiable list
		 */
		public static List<Role> known() {
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
		 * Returns the wire value, such as {@code user}.
		 * @return the wire value
		 */
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
	 * Where a {@link PlanEntry} of the agent's plan stands: pending, in progress or completed.
	 * Agents send one of the constants on each entry of every {@link Plan}; clients compare the
	 * value they receive with {@code equals}, or switch on {@link #value()}.
	 *
	 * <p>
	 * There is no status for a failed or dropped step: the agent changes the plan instead, and
	 * leaves an entry out of the next plan to remove it.
	 *
	 * <p>
	 * An open value: a value this SDK does not know, from a newer peer, is kept and written back
	 * unchanged, and its {@link #isKnown()} is {@code false}, so it never fails the message
	 * (see {@link AcpSchema} on forward compatibility). {@link #of} returns the constant for a
	 * known wire value, so a known value read from the wire is one of the constants.
	 *
	 * @param value the wire value, such as {@code "in_progress"}
	 */
	public record PlanEntryStatus(@JsonValue String value) {

		/** {@code "pending"}: the task has not started yet. */
		public static final PlanEntryStatus PENDING = new PlanEntryStatus("pending");

		/** {@code "in_progress"}: the agent is working on the task. */
		public static final PlanEntryStatus IN_PROGRESS = new PlanEntryStatus("in_progress");

		/** {@code "completed"}: the task is done. */
		public static final PlanEntryStatus COMPLETED = new PlanEntryStatus("completed");

		private static final List<PlanEntryStatus> KNOWN = List.of(PENDING, IN_PROGRESS, COMPLETED);

		/**
		 * Creates a plan entry status for a wire value. Prefer {@link #of}, which returns the
		 * constant for a known value; a value created here still equals that constant.
		 * @param value the wire value
		 * @throws NullPointerException if {@code value} is {@code null}
		 */
		public PlanEntryStatus {
			Objects.requireNonNull(value, "value");
		}

		/**
		 * Returns a plan entry status for a wire value: the constant when ACP v1 defines the value,
		 * and otherwise a new, unknown value.
		 * @param value the wire value
		 * @return the constant, or a new value for an unknown string
		 * @throws NullPointerException if {@code value} is {@code null}
		 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static PlanEntryStatus of(String value) {
			return knownOrNew(KNOWN, PlanEntryStatus::value, value, PlanEntryStatus::new);
		}

		/**
		 * Returns the values ACP v1 defines, in schema order.
		 * @return the constants, in an unmodifiable list
		 */
		public static List<PlanEntryStatus> known() {
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
		 * Returns the wire value, such as {@code "in_progress"}.
		 * @return the wire value
		 */
		@Override
		public String toString() {
			return value;
		}

	}

	/**
	 * How much a {@link PlanEntry} matters to the task as a whole: high, medium or low, so a client
	 * can show which steps of the agent's plan are critical. Agents send one of the constants on
	 * each entry; clients compare the value they receive with {@code equals}, or switch on
	 * {@link #value()}.
	 *
	 * <p>
	 * An open value: a value this SDK does not know, from a newer peer, is kept and written back
	 * unchanged, and its {@link #isKnown()} is {@code false}, so it never fails the message
	 * (see {@link AcpSchema} on forward compatibility). {@link #of} returns the constant for a
	 * known wire value, so a known value read from the wire is one of the constants.
	 *
	 * @param value the wire value, such as {@code "high"}
	 */
	public record PlanEntryPriority(@JsonValue String value) {

		/** {@code "high"}: the task is critical to the overall goal. */
		public static final PlanEntryPriority HIGH = new PlanEntryPriority("high");

		/** {@code "medium"}: the task is important but not critical. */
		public static final PlanEntryPriority MEDIUM = new PlanEntryPriority("medium");

		/** {@code "low"}: the task is nice to have but not essential. */
		public static final PlanEntryPriority LOW = new PlanEntryPriority("low");

		private static final List<PlanEntryPriority> KNOWN = List.of(HIGH, MEDIUM, LOW);

		/**
		 * Creates a plan entry priority for a wire value. Prefer {@link #of}, which returns the
		 * constant for a known value; a value created here still equals that constant.
		 * @param value the wire value
		 * @throws NullPointerException if {@code value} is {@code null}
		 */
		public PlanEntryPriority {
			Objects.requireNonNull(value, "value");
		}

		/**
		 * Returns a plan entry priority for a wire value: the constant when ACP v1 defines the
		 * value, and otherwise a new, unknown value.
		 * @param value the wire value
		 * @return the constant, or a new value for an unknown string
		 * @throws NullPointerException if {@code value} is {@code null}
		 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static PlanEntryPriority of(String value) {
			return knownOrNew(KNOWN, PlanEntryPriority::value, value, PlanEntryPriority::new);
		}

		/**
		 * Returns the values ACP v1 defines, in schema order.
		 * @return the constants, in an unmodifiable list
		 */
		public static List<PlanEntryPriority> known() {
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
		 * Returns the wire value, such as {@code "high"}.
		 * @return the wire value
		 */
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
	 * An MCP server the client asks the agent to connect to for an ACP session, passed in the
	 * {@code mcpServers} of {@link NewSessionRequest}, {@link LoadSessionRequest},
	 * {@link ResumeSessionRequest} and {@link ForkSessionRequest}. The agent can then use the
	 * server's tools and context while it works on prompts. There are three transports:
	 * {@link McpServerStdio}, which every agent accepts, and {@link McpServerHttp} and
	 * {@link McpServerSse}, which an agent accepts only when it advertises them in
	 * {@link McpCapabilities}.
	 *
	 * <p>
	 * The SDK only carries these records: the agent's session handlers connect to the servers, and
	 * the protocol says they should connect to all of them. The SDK checks the transports against
	 * {@link McpCapabilities} on neither side; a client checks {@code supportsMcpHttp()} and
	 * {@code supportsMcpSse()} on
	 * {@link com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities
	 * NegotiatedCapabilities} before it sends HTTP or SSE servers.
	 *
	 * <p>
	 * On the wire the {@code type} member tells the transports apart: {@code "http"},
	 * {@code "sse"}, and none for stdio. A server without {@code type} reads as an
	 * {@link McpServerStdio}, and so does a server of a transport this SDK does not know: that
	 * record then has no command, and the members only that transport has are dropped.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.EXISTING_PROPERTY,
			defaultImpl = McpServerStdio.class)
	@JsonSubTypes({ @JsonSubTypes.Type(value = McpServerHttp.class, name = "http"),
			@JsonSubTypes.Type(value = McpServerSse.class, name = "sse") })
	public interface McpServer {

	}

	/**
	 * An MCP server the agent starts as a child process and talks to over standard input and
	 * output: it runs {@link #command()} with {@link #args()}, and sets {@link #env()} in the
	 * process's environment. Every agent must accept this transport, so a client can always send
	 * it. It is written without a {@code type} member.
	 *
	 * <p>
	 * The protocol requires an absolute path in {@code command}, and requires {@code args} and
	 * {@code env} on the wire; the SDK checks none of these. Pass empty lists, not {@code null}: a
	 * {@code null} list is left out of the JSON. A record read from a peer that left them out has
	 * {@code null} there.
	 *
	 * @param name a name for the server, shown to people
	 * @param command the absolute path of the server's executable
	 * @param args the command-line arguments, possibly empty
	 * @param env the environment variables to set for the server, possibly empty
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record McpServerStdio(@JsonProperty("name") String name, @JsonProperty("command") String command,
			@JsonProperty("args") List<String> args, @JsonProperty("env") List<EnvVariable> env,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements McpServer {
		/**
		 * Creates a server without {@code _meta}.
		 * @param name a name for the server
		 * @param command the absolute path of the executable
		 * @param args the command-line arguments, possibly empty
		 * @param env the environment variables, possibly empty
		 */
		public McpServerStdio(String name, String command, List<String> args, List<EnvVariable> env) {
			this(name, command, args, env, null);
		}
	}

	/**
	 * An MCP server the agent reaches over HTTP at {@link #url()}, sending {@link #headers()} with
	 * its requests. A client sends it only to an agent that advertises {@code mcpCapabilities.http}
	 * ({@link McpCapabilities}); the protocol recommends that new agents support it. It is written
	 * with {@code "type": "http"}.
	 *
	 * <p>
	 * The protocol requires {@code headers} on the wire: pass an empty list, not {@code null},
	 * which is left out of the JSON. A record read from a peer that left it out has {@code null}
	 * there. The SDK does not check the URL.
	 *
	 * @param name a name for the server, shown to people
	 * @param url the server's URL
	 * @param headers the HTTP headers to send to the server, possibly empty
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record McpServerHttp(@JsonProperty("name") String name, @JsonProperty("url") String url,
			@JsonProperty("headers") List<HttpHeader> headers,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements McpServer {
		/**
		 * Creates a server without {@code _meta}.
		 * @param name a name for the server
		 * @param url the server's URL
		 * @param headers the HTTP headers, possibly empty
		 */
		public McpServerHttp(String name, String url, List<HttpHeader> headers) {
			this(name, url, headers, null);
		}

		/**
		 * Returns the discriminator, {@code "http"}, which the JSON carries as {@code type}.
		 * @return {@code "http"}
		 */
		@JsonProperty("type")
		public String type() {
			return "http";
		}
	}

	/**
	 * An MCP server the agent reaches over HTTP with server-sent events (SSE) at {@link #url()},
	 * sending {@link #headers()} with its requests. A client sends it only to an agent that
	 * advertises {@code mcpCapabilities.sse} ({@link McpCapabilities}). It is written with
	 * {@code "type": "sse"}.
	 *
	 * <p>
	 * It differs from {@link McpServerHttp} only in the transport, which the MCP specification has
	 * deprecated: prefer {@link McpServerHttp} for an agent that accepts both. As there, pass an
	 * empty list of headers, not {@code null}, which is left out of the JSON.
	 *
	 * @param name a name for the server, shown to people
	 * @param url the server's URL
	 * @param headers the HTTP headers to send to the server, possibly empty
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record McpServerSse(@JsonProperty("name") String name, @JsonProperty("url") String url,
			@JsonProperty("headers") List<HttpHeader> headers,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements McpServer {
		/**
		 * Creates a server without {@code _meta}.
		 * @param name a name for the server
		 * @param url the server's URL
		 * @param headers the HTTP headers, possibly empty
		 */
		public McpServerSse(String name, String url, List<HttpHeader> headers) {
			this(name, url, headers, null);
		}

		/**
		 * Returns the discriminator, {@code "sse"}, which the JSON carries as {@code type}.
		 * @return {@code "sse"}
		 */
		@JsonProperty("type")
		public String type() {
			return "sse";
		}
	}

	/**
	 * One environment variable, a name and a value. It is an item of {@link McpServerStdio#env()},
	 * for an MCP server the agent starts, and of {@link CreateTerminalRequest#env()}, for a command
	 * the agent asks the client to run. Neither may be {@code null}: a {@code null} one is left out
	 * of the JSON, which the protocol does not allow.
	 *
	 * @param name the variable's name
	 * @param value the variable's value
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record EnvVariable(@JsonProperty("name") String name, @JsonProperty("value") String value,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a variable without {@code _meta}.
		 * @param name the variable's name
		 * @param value the variable's value
		 */
		public EnvVariable(String name, String value) {
			this(name, value, null);
		}
	}

	/**
	 * One HTTP header, a name and a value, that the agent sends with its requests to an MCP server,
	 * for example an {@code Authorization} header. It is an item of {@link McpServerHttp#headers()}
	 * and {@link McpServerSse#headers()}. Neither may be {@code null}: a {@code null} one is left
	 * out of the JSON, which the protocol does not allow.
	 *
	 * @param name the header's name
	 * @param value the header's value
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record HttpHeader(@JsonProperty("name") String name, @JsonProperty("value") String value,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a header without {@code _meta}.
		 * @param name the header's name
		 * @param value the header's value
		 */
		public HttpHeader(String name, String value) {
			this(name, value, null);
		}
	}

	/**
	 * How a terminal's command ended: its exit code, or the signal that ended it. It is the
	 * {@link TerminalOutputResponse#exitStatus()} of a {@code terminal/output} answer, present once
	 * the command has ended; {@code terminal/wait_for_exit} answers with the same two values in a
	 * {@link WaitForTerminalExitResponse}.
	 *
	 * <p>
	 * A process ends with an exit code or with a signal, so one of the two is normally
	 * {@code null}. The schema allows an exit code up to 4294967295 (an unsigned 32-bit number);
	 * one above {@link Integer#MAX_VALUE} cannot be read into this record, and the answer that
	 * carries it fails the agent's call with an {@link AcpError} of code {@code -32603}.
	 *
	 * @param exitCode the exit code, or {@code null} if a signal ended the process
	 * @param signal the signal that ended the process, or {@code null} if it exited
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TerminalExitStatus(@JsonProperty("exitCode") @Nullable Integer exitCode,
			@JsonProperty("signal") @Nullable String signal,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a status without {@code _meta}.
		 * @param exitCode the exit code, or {@code null}
		 * @param signal the signal that ended the process, or {@code null}
		 */
		public TerminalExitStatus(@Nullable Integer exitCode, @Nullable String signal) {
			this(exitCode, signal, null);
		}
	}

	/**
	 * One way a client can log in to an agent, as listed in
	 * {@link InitializeResponse#authMethods()}. It is an {@link AuthMethodAgent}, which the client
	 * passes to {@code authenticate} by its {@link #id()}, or an {@link AuthMethodTerminal}, which
	 * the client runs itself by starting the agent program again in an interactive terminal. Check
	 * the type with {@code instanceof} before acting on a method.
	 *
	 * <p>
	 * An annotated agent declares its methods with
	 * {@link com.agentclientprotocol.sdk.annotation.AuthMethod @AuthMethod} in the
	 * {@link com.agentclientprotocol.sdk.annotation.AcpAgent#authMethods() authMethods} of
	 * {@link com.agentclientprotocol.sdk.annotation.AcpAgent @AcpAgent}, and the SDK lists them in
	 * the {@code initialize} answer: the agent methods to every client, and the terminal methods
	 * only to a client that advertises {@code auth.terminal}. Methods that an
	 * {@link com.agentclientprotocol.sdk.annotation.Initialize @Initialize} method returns are
	 * added after the declared ones, replacing any with the same id, and are not filtered. A
	 * builder agent lists the methods its initialize handler returns, and none without one.
	 *
	 * <p>
	 * On the wire the {@code type} member tells the two apart. A method without {@code type} reads
	 * as an {@link AuthMethodAgent}, and so does a method of a type this SDK does not know,
	 * including {@code "agent"}, which some SDKs write; its {@code type} and the members only that
	 * type has are not kept (see {@link AcpSchema} on forward compatibility). Only
	 * {@code "terminal"} reads as an {@link AuthMethodTerminal}.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.EXISTING_PROPERTY,
			defaultImpl = AuthMethodAgent.class)
	@JsonSubTypes({ @JsonSubTypes.Type(value = AuthMethodTerminal.class, name = "terminal") })
	public interface AuthMethod {

		/**
		 * Returns the method's id, unique among the agent's methods. For an {@link AuthMethodAgent}
		 * the client passes it to {@code authenticate}.
		 * @return the method id
		 */
		String id();

		/**
		 * Returns the name a client shows the user for this method.
		 * @return the human-readable name
		 */
		String name();

		/**
		 * Returns a longer description a client may show, or {@code null}.
		 * @return the description, or {@code null} for none
		 */
		@Nullable String description();

	}

	/**
	 * An authentication method the agent runs itself: the client passes its {@link #id()} to
	 * {@code authenticate} in an {@link AuthenticateRequest}, and the agent's authenticate handler
	 * checks the login. It is the default {@link AuthMethod} type, written without a {@code type}
	 * member.
	 *
	 * <p>
	 * An annotated agent gets one for each
	 * {@link com.agentclientprotocol.sdk.annotation.AuthMethod @AuthMethod} of type {@code AGENT},
	 * the default, in {@code @AcpAgent(authMethods = ...)}, and lists it to every client. Such an
	 * agent needs an {@link com.agentclientprotocol.sdk.annotation.Authenticate @Authenticate}
	 * method, or building it throws an {@link IllegalStateException}. A builder agent returns these
	 * from its initialize handler and serves them with an {@code authenticateHandler}.
	 *
	 * <p>
	 * A method read with a {@code type} other than {@code "terminal"} also reads as this record
	 * (see {@link AuthMethod}).
	 *
	 * @param id the method id, which the client passes to {@code authenticate}
	 * @param name the name a client shows the user
	 * @param description a longer description, or {@code null}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AuthMethodAgent(@JsonProperty("id") String id, @JsonProperty("name") String name,
			@JsonProperty("description") @Nullable String description,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements AuthMethod {
		/**
		 * Creates a method without {@code _meta}.
		 * @param id the method id
		 * @param name the name a client shows
		 * @param description a longer description, or {@code null}
		 */
		public AuthMethodAgent(String id, String name, @Nullable String description) {
			this(id, name, description, null);
		}
	}

	/**
	 * An authentication method the client runs itself: it starts the agent program again as a
	 * separate, interactive process, with {@link #args()} added to its arguments and {@link #env()}
	 * to its environment, and the user logs in there. An exit status of zero means the login
	 * succeeded; any other end means it failed. The client then reconnects and sends
	 * {@code initialize} again. The client must not pass this method to {@code authenticate}: the
	 * terminal process is not the ACP connection.
	 *
	 * <p>
	 * The protocol lets an agent list it only to a client that advertises {@code auth.terminal}
	 * ({@link AuthCapabilities}). An annotated agent gets one for each
	 * {@link com.agentclientprotocol.sdk.annotation.AuthMethod @AuthMethod} of type
	 * {@code TERMINAL} in {@code @AcpAgent(authMethods = ...)}, with its {@code args} and its
	 * {@code env} entries ({@code NAME=value}), leaves it out of the {@code initialize} answer for
	 * a client without {@code auth.terminal}, and needs no {@code @Authenticate} method for it. A
	 * builder agent's initialize handler, or an {@code @Initialize} method that returns one, checks
	 * {@code supportsTerminalAuth()} on
	 * {@link com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities
	 * NegotiatedCapabilities} itself.
	 *
	 * <p>
	 * The client SDK neither runs the login process nor stops an {@code authenticate} call with
	 * this method's id; the application does both. The method carries no command: the client reuses
	 * the command it starts the agent with, and how the login hands its credentials to the agent is
	 * up to the agent.
	 *
	 * @param id the method id; the client does not pass it to {@code authenticate}
	 * @param name the name a client shows the user
	 * @param description a longer description, or {@code null}
	 * @param args arguments to add to the agent program's arguments, or {@code null} for none
	 * @param env environment variables to set for the agent program, overriding those of the same
	 * name in its launch configuration, or {@code null} for none
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AuthMethodTerminal(@JsonProperty("id") String id, @JsonProperty("name") String name,
			@JsonProperty("description") @Nullable String description,
			@JsonProperty("args") @Nullable List<String> args, @JsonProperty("env") @Nullable Map<String, String> env,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements AuthMethod {
		/**
		 * Creates a method without a description or {@code _meta}.
		 * @param id the method id
		 * @param name the name a client shows
		 * @param args extra arguments for the agent program, or {@code null}
		 * @param env extra environment variables for the agent program, or {@code null}
		 */
		public AuthMethodTerminal(String id, String name, @Nullable List<String> args,
				@Nullable Map<String, String> env) {
			this(id, name, null, args, env, null);
		}

		/**
		 * Returns the discriminator, {@code "terminal"}, which the JSON carries as {@code type}.
		 * @return {@code "terminal"}
		 */
		@JsonProperty("type")
		public String type() {
			return "terminal";
		}
	}

	/**
	 * The authentication method types a client handles beyond the default, as the {@code auth}
	 * component of {@link ClientCapabilities}. Today that is only {@code terminal}: when
	 * {@code true}, the agent may offer {@link AuthMethodTerminal} methods, which the client runs
	 * as an interactive process instead of passing them to {@code authenticate}.
	 *
	 * <p>
	 * Set {@code terminal} only when the client can run the agent's command again in an interactive
	 * terminal. The client SDK does not run terminal auth methods; the application does. An
	 * annotated agent leaves its terminal auth methods out of its {@code initialize} answer unless
	 * the client sets {@code terminal}; other agents check it with {@code supportsTerminalAuth()}
	 * on {@link com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities
	 * NegotiatedCapabilities}.
	 *
	 * @param terminal whether the client handles {@code terminal} auth methods, or {@code null},
	 * read as {@code false}
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AuthCapabilities(@JsonProperty("terminal") @Nullable Boolean terminal,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates the auth capabilities without {@code _meta}.
		 * @param terminal whether the client handles {@code terminal} auth methods, or {@code null}
		 */
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
	 * One step of the agent's {@link Plan}: a task it means to do for the user's request, with its
	 * priority and its status. The agent sends every entry again, with its current status, in each
	 * plan update, and the client shows the new list in place of the old one. Entries have no id.
	 *
	 * <p>
	 * All three components are required. The SDK does not check them when sending; a received plan
	 * with an entry that lacks one is skipped whole (see {@link SessionNotification}). A priority
	 * or status of a value this SDK does not know is kept.
	 *
	 * @param content a human-readable description of the task
	 * @param priority how much the task matters to the overall goal
	 * @param status where the task stands now
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PlanEntry(@JsonProperty("content") String content,
			@JsonProperty("priority") PlanEntryPriority priority, @JsonProperty("status") PlanEntryStatus status,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates an entry without {@code _meta}.
		 * @param content a human-readable description of the task
		 * @param priority how much the task matters
		 * @param status where the task stands now
		 */
		public PlanEntry(String content, PlanEntryPriority priority, PlanEntryStatus status) {
			this(content, priority, status, null);
		}
	}

	/**
	 * A slash command the agent offers in an ACP session: its name, what it does, and whether it
	 * takes input. The agent lists every command it offers in an {@link AvailableCommandsUpdate};
	 * the client shows them as the user types, and the user runs one by sending a slash and its
	 * name, such as {@code /web agent client protocol}, as text in a prompt. The agent recognizes
	 * the command in the prompt's text; the SDK does not route commands.
	 *
	 * <p>
	 * The name is written without the slash, such as {@code create_plan}. A command that takes text
	 * after its name has an {@link AvailableCommandInput}; one without takes no input. The SDK
	 * checks neither the name nor the description.
	 *
	 * @param name the command's name, without the leading slash
	 * @param description a human-readable description of what the command does
	 * @param input the input the command takes, or {@code null} if it takes none
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AvailableCommand(@JsonProperty("name") String name, @JsonProperty("description") String description,
			@JsonProperty("input") @Nullable AvailableCommandInput input,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates a command without {@code _meta}.
		 * @param name the command's name, without the leading slash
		 * @param description what the command does
		 * @param input the input the command takes, or {@code null}
		 */
		public AvailableCommand(String name, String description, @Nullable AvailableCommandInput input) {
			this(name, description, input, null);
		}
	}

	/**
	 * The input a slash command takes: free text the user types after the command's name, which
	 * reaches the agent as part of the prompt. {@link #hint()} is what the client shows in its
	 * place until the user types it, such as {@code "query to search for"}.
	 *
	 * <p>
	 * The protocol defines a command's input as one of several kinds; ACP v1 has one, unstructured
	 * text, which this record is. A received input without a hint lacks a required member, so the
	 * client skips the whole {@link AvailableCommandsUpdate} that carries it.
	 *
	 * @param hint the text shown until the user types the input
	 * @param meta the {@code _meta} map, reserved for extensions, or {@code null}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AvailableCommandInput(@JsonProperty("hint") String hint,
			@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
		/**
		 * Creates an input without {@code _meta}.
		 * @param hint the text shown until the user types the input
		 */
		public AvailableCommandInput(String hint) {
			this(hint, null);
		}
	}

}
