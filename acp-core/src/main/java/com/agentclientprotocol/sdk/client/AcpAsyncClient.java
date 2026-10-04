/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSession;
import com.agentclientprotocol.sdk.spec.ExtensionMethods;
import com.agentclientprotocol.sdk.util.Assert;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * A connected ACP client with a Reactor API: it sends the agent requests (initialize, sessions,
 * prompts, config options, extension methods) and returns each answer as a {@code Mono}, while the
 * handlers registered on {@link AcpClient.AsyncSpec} answer the agent's requests. Get one from
 * {@code AcpClient.async(transport)...build()}. Use {@link AcpSyncClient} for the same calls as
 * blocking methods.
 *
 * <p>A client follows ACP's order: {@link #initialize()} first, {@link #authenticate} if the agent
 * requires it, then {@link #newSession}, {@link #loadSession} or {@link #resumeSession} for a
 * session ID, then {@link #prompt} as often as needed, one turn at a time per session. Finish with
 * {@link #closeGracefully()}.
 *
 * <pre>{@code
 * AcpAsyncClient client = AcpClient.async(transport)
 *     .requestTimeout(Duration.ofMinutes(5))
 *     .sessionUpdateConsumer(notification -> Mono.fromRunnable(
 *         () -> System.out.println(notification.update())))
 *     .build();
 *
 * client.initialize()
 *     .then(client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())))
 *     .flatMap(session -> client.prompt(new AcpSchema.PromptRequest(session.sessionId(),
 *         List.of(new AcpSchema.TextContent("Fix the failing test")))))
 *     .doOnNext(response -> System.out.println(response.stopReason()))
 *     .then(client.closeGracefully())
 *     .block();
 * }</pre>
 *
 * <p>Calls send nothing until their {@code Mono} is subscribed. An error answer fails the
 * {@code Mono} with {@link com.agentclientprotocol.sdk.spec.AcpError}, whose {@code getCode()} is
 * the JSON-RPC error code. If the agent does not answer within the builder's request timeout (60
 * seconds by default), the {@code Mono} fails with a {@link java.util.concurrent.TimeoutException};
 * then, or when the caller disposes the {@code Mono} first, the client sends the agent a
 * {@code $/cancel_request}. To send one and still wait for the answer, put
 * {@link com.agentclientprotocol.sdk.spec.RequestCancellation#cancelWhen} in the request's context.
 * The client does not check the agent's capabilities before a call; see
 * {@link #getAgentCapabilities()}. Methods may be called from several threads at once, and a null
 * argument fails with {@link IllegalArgumentException}.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 * @see AcpClient
 * @see AcpSyncClient
 */
public class AcpAsyncClient {

	private static final Logger logger = LoggerFactory.getLogger(AcpAsyncClient.class);

	private static final TypeRef<AcpSchema.InitializeResponse> INITIALIZE_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<AcpSchema.AuthenticateResponse> AUTHENTICATE_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<AcpSchema.NewSessionResponse> NEW_SESSION_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<AcpSchema.LoadSessionResponse> LOAD_SESSION_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<AcpSchema.SetSessionModeResponse> SET_SESSION_MODE_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<AcpSchema.ListSessionsResponse> LIST_SESSIONS_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<AcpSchema.CloseSessionResponse> CLOSE_SESSION_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<AcpSchema.DeleteSessionResponse> DELETE_SESSION_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<AcpSchema.LogoutResponse> LOGOUT_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<AcpSchema.ResumeSessionResponse> RESUME_SESSION_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<AcpSchema.ForkSessionResponse> FORK_SESSION_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<AcpSchema.SetSessionConfigOptionResponse> SET_SESSION_CONFIG_OPTION_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<AcpSchema.ListProvidersResponse> LIST_PROVIDERS_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<AcpSchema.SetProviderResponse> SET_PROVIDER_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<AcpSchema.DisableProviderResponse> DISABLE_PROVIDER_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<Object> RAW_RESULT_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<AcpSchema.PromptResponse> PROMPT_RESPONSE_TYPE_REF = new TypeRef<>() {
	};

	/**
	 * The underlying ACP session that handles request/response communication.
	 */
	private final AcpSession session;

	/**
	 * The transport layer for this client.
	 */
	private final AcpClientTransport transport;

	/**
	 * Client capabilities configured via the builder: the ones every initialize request
	 * advertises.
	 */
	private final AcpSchema.ClientCapabilities clientCapabilities;

	/**
	 * Client info configured via the builder, sent with every initialize request.
	 */
	private final AcpSchema.@Nullable Implementation clientInfo;

	/**
	 * Capabilities negotiated with the agent during initialization.
	 */
	private final AtomicReference<@Nullable NegotiatedCapabilities> agentCapabilities = new AtomicReference<>();

	/**
	 * The capabilities the client advertised in its last initialize request.
	 */
	private final AtomicReference<AcpSchema.@Nullable ClientCapabilities> advertisedCapabilities;

	/**
	 * Creates a new AcpAsyncClient with the given session and transport. Uses default
	 * client capabilities.
	 * @param session the ACP session for communication
	 * @param transport the transport layer for this client
	 */
	AcpAsyncClient(AcpSession session, AcpClientTransport transport) {
		this(session, transport, null);
	}

	/**
	 * Creates a new AcpAsyncClient with the given session, transport, and client
	 * capabilities.
	 * @param session the ACP session for communication
	 * @param transport the transport layer for this client
	 * @param clientCapabilities the client capabilities to use during initialization (may
	 * be null for defaults)
	 */
	AcpAsyncClient(AcpSession session, AcpClientTransport transport,
			AcpSchema.@Nullable ClientCapabilities clientCapabilities) {
		this(session, transport, clientCapabilities, null, new AtomicReference<>());
	}

	/**
	 * Creates a new AcpAsyncClient that records the capabilities it advertises when it
	 * initializes, so its handlers can honour them.
	 * @param session the ACP session for communication
	 * @param transport the transport layer for this client
	 * @param clientCapabilities the client capabilities to use during initialization (may
	 * be null for defaults)
	 * @param clientInfo the client info to send during initialization (may be null)
	 * @param advertisedCapabilities set to the capabilities of each initialize request
	 * the client sends
	 */
	AcpAsyncClient(AcpSession session, AcpClientTransport transport,
			AcpSchema.@Nullable ClientCapabilities clientCapabilities, AcpSchema.@Nullable Implementation clientInfo,
			AtomicReference<AcpSchema.@Nullable ClientCapabilities> advertisedCapabilities) {
		Assert.notNull(session, "Session must not be null");
		Assert.notNull(transport, "Transport must not be null");
		this.session = session;
		this.transport = transport;
		this.clientCapabilities = clientCapabilities != null ? clientCapabilities : new AcpSchema.ClientCapabilities();
		this.clientInfo = clientInfo;
		this.advertisedCapabilities = advertisedCapabilities;
	}

	// --------------------------
	// Initialization
	// --------------------------

	/**
	 * Initializes the connection: the first request of the ACP lifecycle, sent once before any
	 * other. The client sends protocol version {@value AcpSchema#LATEST_PROTOCOL_VERSION} with the
	 * capabilities and client info set on the builder
	 * ({@link AcpClient.AsyncSpec#clientCapabilities}, {@link AcpClient.AsyncSpec#clientInfo}); the
	 * agent answers with its protocol version, capabilities and authentication methods, and
	 * {@link #getAgentCapabilities()} returns those capabilities from then on. The client does not
	 * check the protocol version the agent answers with.
	 *
	 * <p>The builder is the only place the client's capabilities are set, so what the client
	 * advertises is also what its handlers honour (an elicitation mode it did not advertise is
	 * refused). Without {@code clientCapabilities(...)} the client advertises
	 * {@code new ClientCapabilities()}: no file system and no terminal.
	 * @return a {@code Mono} emitting the agent's answer
	 * @see AcpSchema#METHOD_INITIALIZE
	 * @see #initialize(int, Map)
	 */
	public Mono<AcpSchema.InitializeResponse> initialize() {
		return initialize(AcpSchema.LATEST_PROTOCOL_VERSION, null);
	}

	/**
	 * Initializes the connection like {@link #initialize()}, with a chosen protocol version and
	 * {@code _meta}. The capabilities and client info still come from the builder; this overload
	 * exists for {@code _meta} and for testing version negotiation, not for advertising
	 * capabilities.
	 * @param protocolVersion the protocol version to announce; this SDK speaks
	 * {@value AcpSchema#LATEST_PROTOCOL_VERSION}
	 * @param meta the request's {@code _meta}, or {@code null}
	 * @return a {@code Mono} emitting the agent's answer
	 * @see #initialize()
	 */
	public Mono<AcpSchema.InitializeResponse> initialize(int protocolVersion, @Nullable Map<String, Object> meta) {
		AcpSchema.InitializeRequest initializeRequest = new AcpSchema.InitializeRequest(protocolVersion,
				this.clientCapabilities, this.clientInfo, meta);
		logger.debug("Initializing ACP client with protocol version: {}", protocolVersion);
		return Mono.fromRunnable(() -> advertisedCapabilities.set(this.clientCapabilities))
			.then(Mono.defer(
					() -> session.sendRequest(AcpSchema.METHOD_INITIALIZE, initializeRequest, INITIALIZE_RESPONSE_TYPE_REF)))
			.doOnNext(response -> {
				// Store the negotiated agent capabilities
				NegotiatedCapabilities caps = NegotiatedCapabilities.fromAgent(response.agentCapabilities());
				agentCapabilities.set(caps);
				logger.debug("Negotiated agent capabilities: {}", caps);
			});
	}

	/**
	 * Returns the agent's capabilities from its {@code initialize} answer. Check them before calls
	 * that need them, for example {@code supportsLoadSession()} before {@link #loadSession} or
	 * {@code supportsLogout()} before {@link #logout}: the client sends every call without
	 * checking.
	 * @return the agent's capabilities, or {@code null} before an {@code initialize} answer arrived
	 */
	public @Nullable NegotiatedCapabilities getAgentCapabilities() {
		return agentCapabilities.get();
	}

	// --------------------------
	// Authentication
	// --------------------------

	/**
	 * Logs in with one of the authentication methods the agent listed in its {@code initialize}
	 * answer ({@code authenticate}). Needed only for an agent that requires it; such an agent
	 * answers other requests with {@code -32000} (authentication required) until then.
	 * @param authenticateRequest the ID of the chosen authentication method
	 * @return a {@code Mono} emitting the agent's answer
	 * @see AcpSchema#METHOD_AUTHENTICATE
	 */
	public Mono<AcpSchema.AuthenticateResponse> authenticate(AcpSchema.AuthenticateRequest authenticateRequest) {
		Assert.notNull(authenticateRequest, "Authenticate request must not be null");
		logger.debug("Authenticating with method: {}", authenticateRequest.methodId());
		return session.sendRequest(AcpSchema.METHOD_AUTHENTICATE, authenticateRequest, AUTHENTICATE_RESPONSE_TYPE_REF);
	}

	/**
	 * Logs out of the agent ({@code logout}), ending the authenticated state; afterwards the client
	 * must authenticate again where the agent requires it. Only an agent that advertises
	 * {@code auth.logout} supports it: check
	 * {@link com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities#supportsLogout()}
	 * first, since the client does not.
	 * @param logoutRequest the logout request
	 * @return a {@code Mono} emitting the agent's answer
	 * @see AcpSchema#METHOD_LOGOUT
	 */
	public Mono<AcpSchema.LogoutResponse> logout(AcpSchema.LogoutRequest logoutRequest) {
		Assert.notNull(logoutRequest, "Logout request must not be null");
		logger.debug("Logging out");
		return session.sendRequest(AcpSchema.METHOD_LOGOUT, logoutRequest, LOGOUT_RESPONSE_TYPE_REF);
	}

	// --------------------------
	// Session Management
	// --------------------------

	/**
	 * Creates an ACP session ({@code session/new}) for a working directory, with the MCP servers
	 * the agent should connect to. The answer carries the session ID every later call for the
	 * session uses, and optionally the session's modes and config options.
	 * @param newSessionRequest the working directory, an absolute path, and the MCP servers
	 * @return a {@code Mono} emitting the agent's answer, with the session ID
	 * @see AcpSchema#METHOD_SESSION_NEW
	 */
	public Mono<AcpSchema.NewSessionResponse> newSession(AcpSchema.NewSessionRequest newSessionRequest) {
		Assert.notNull(newSessionRequest, "New session request must not be null");
		logger.debug("Creating new session with cwd: {}", newSessionRequest.cwd());
		return session.sendRequest(AcpSchema.METHOD_SESSION_NEW, newSessionRequest, NEW_SESSION_RESPONSE_TYPE_REF);
	}

	/**
	 * Reopens a session the agent kept ({@code session/load}). The agent replays the conversation
	 * as session updates, which reach the session update consumers before this call completes, then
	 * answers. Only an agent that advertises {@code loadSession} supports it; the client does not
	 * check.
	 * @param loadSessionRequest the session ID, the working directory and the MCP servers
	 * @return a {@code Mono} emitting the agent's answer once the history has been replayed
	 * @see AcpSchema#METHOD_SESSION_LOAD
	 */
	public Mono<AcpSchema.LoadSessionResponse> loadSession(AcpSchema.LoadSessionRequest loadSessionRequest) {
		Assert.notNull(loadSessionRequest, "Load session request must not be null");
		logger.debug("Loading session: {}", loadSessionRequest.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_LOAD, loadSessionRequest, LOAD_SESSION_RESPONSE_TYPE_REF);
	}

	/**
	 * Switches a session to one of the modes the agent offered ({@code session/set_mode}). Modes
	 * may change how the agent works on prompts and what it asks permission for.
	 * @param setModeRequest the session ID and the mode ID
	 * @return a {@code Mono} emitting the agent's answer
	 * @see AcpSchema#METHOD_SESSION_SET_MODE
	 */
	public Mono<AcpSchema.SetSessionModeResponse> setSessionMode(AcpSchema.SetSessionModeRequest setModeRequest) {
		Assert.notNull(setModeRequest, "Set session mode request must not be null");
		logger.debug("Setting session mode: {} for session: {}", setModeRequest.modeId(), setModeRequest.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_SET_MODE, setModeRequest,
				SET_SESSION_MODE_RESPONSE_TYPE_REF);
	}

	/**
	 * Lists the sessions the agent knows ({@code session/list}), optionally only those of one
	 * working directory. The answer may be one page: pass its {@code nextCursor} in the next
	 * request to get the next page.
	 * @param listSessionsRequest an optional working directory and an optional cursor
	 * @return a {@code Mono} emitting a page of sessions
	 * @see AcpSchema#METHOD_SESSION_LIST
	 */
	public Mono<AcpSchema.ListSessionsResponse> listSessions(AcpSchema.ListSessionsRequest listSessionsRequest) {
		Assert.notNull(listSessionsRequest, "List sessions request must not be null");
		logger.debug("Listing sessions");
		return session.sendRequest(AcpSchema.METHOD_SESSION_LIST, listSessionsRequest,
				LIST_SESSIONS_RESPONSE_TYPE_REF);
	}

	/**
	 * Closes an active session ({@code session/close}): the agent stops its work as for
	 * {@code session/cancel}, then frees what the session holds.
	 * @param closeSessionRequest the session ID
	 * @return a {@code Mono} emitting the agent's answer
	 * @see AcpSchema#METHOD_SESSION_CLOSE
	 */
	public Mono<AcpSchema.CloseSessionResponse> closeSession(AcpSchema.CloseSessionRequest closeSessionRequest) {
		Assert.notNull(closeSessionRequest, "Close session request must not be null");
		logger.debug("Closing session: {}", closeSessionRequest.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_CLOSE, closeSessionRequest,
				CLOSE_SESSION_RESPONSE_TYPE_REF);
	}

	/**
	 * Deletes a stored session ({@code session/delete}). Unlike {@link #closeSession}, which frees
	 * an active session, it removes the session from the agent's storage, so that it no longer
	 * appears in {@code session/list}. Only an agent that advertises
	 * {@code sessionCapabilities.delete} supports it; the client does not check.
	 * @param deleteSessionRequest the session ID
	 * @return a {@code Mono} emitting the agent's answer
	 * @see AcpSchema#METHOD_SESSION_DELETE
	 */
	public Mono<AcpSchema.DeleteSessionResponse> deleteSession(AcpSchema.DeleteSessionRequest deleteSessionRequest) {
		Assert.notNull(deleteSessionRequest, "Delete session request must not be null");
		logger.debug("Deleting session: {}", deleteSessionRequest.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_DELETE, deleteSessionRequest,
				DELETE_SESSION_RESPONSE_TYPE_REF);
	}

	/**
	 * Reopens a session without replaying its history ({@code session/resume}), unlike
	 * {@link #loadSession}. Use it to reconnect to a session the agent still runs, or when the
	 * client keeps the history itself.
	 * @param resumeSessionRequest the session ID, the working directory and the MCP servers
	 * @return a {@code Mono} emitting the agent's answer
	 * @see AcpSchema#METHOD_SESSION_RESUME
	 */
	public Mono<AcpSchema.ResumeSessionResponse> resumeSession(
			AcpSchema.ResumeSessionRequest resumeSessionRequest) {
		Assert.notNull(resumeSessionRequest, "Resume session request must not be null");
		logger.debug("Resuming session: {}", resumeSessionRequest.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_RESUME, resumeSessionRequest,
				RESUME_SESSION_RESPONSE_TYPE_REF);
	}

	/**
	 * Creates a new session branched from an existing one ({@code session/fork}).
	 * @param forkSessionRequest the ID of the session to branch from and the working directory
	 * @return a {@code Mono} emitting the agent's answer, with the new session's ID
	 * @see AcpSchema#METHOD_SESSION_FORK
	 */
	@UnstableAcpApi
	public Mono<AcpSchema.ForkSessionResponse> forkSession(AcpSchema.ForkSessionRequest forkSessionRequest) {
		Assert.notNull(forkSessionRequest, "Fork session request must not be null");
		logger.debug("Forking session: {}", forkSessionRequest.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_FORK, forkSessionRequest,
				FORK_SESSION_RESPONSE_TYPE_REF);
	}

	/**
	 * Changes one of a session's config options ({@code session/set_config_option}); make the
	 * request with {@link AcpSchema.SetSessionConfigOptionRequest#select} or
	 * {@link AcpSchema.SetSessionConfigOptionRequest#bool}. The answer carries the full list of the
	 * session's options, which replaces the client's copy. Send a boolean option only to an agent
	 * that offered it.
	 * @param request the session ID, the option ID and the new value
	 * @return a {@code Mono} emitting the agent's answer, with all of the session's options
	 * @see AcpSchema#METHOD_SESSION_SET_CONFIG_OPTION
	 */
	public Mono<AcpSchema.SetSessionConfigOptionResponse> setSessionConfigOption(
			AcpSchema.SetSessionConfigOptionRequest request) {
		Assert.notNull(request, "Set config option request must not be null");
		logger.debug("Setting config option {} for session: {}", request.configId(), request.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_SET_CONFIG_OPTION, request,
				SET_SESSION_CONFIG_OPTION_RESPONSE_TYPE_REF);
	}

	// --------------------------
	// Provider Configuration (UNSTABLE)
	// --------------------------

	/**
	 * Lists the providers the agent can route to ({@code providers/list}). Only an agent that
	 * advertises {@code providers} supports it.
	 * @param request the list request
	 * @return a {@code Mono} emitting the providers
	 * @see AcpSchema#METHOD_PROVIDERS_LIST
	 */
	@UnstableAcpApi
	public Mono<AcpSchema.ListProvidersResponse> listProviders(AcpSchema.ListProvidersRequest request) {
		Assert.notNull(request, "List providers request must not be null");
		logger.debug("Listing providers");
		return session.sendRequest(AcpSchema.METHOD_PROVIDERS_LIST, request, LIST_PROVIDERS_RESPONSE_TYPE_REF);
	}

	/**
	 * Configures how the agent reaches a provider: protocol, base URL and headers
	 * ({@code providers/set}).
	 * @param request the provider ID and its routing
	 * @return a {@code Mono} emitting the agent's answer
	 * @see AcpSchema#METHOD_PROVIDERS_SET
	 */
	@UnstableAcpApi
	public Mono<AcpSchema.SetProviderResponse> setProvider(AcpSchema.SetProviderRequest request) {
		Assert.notNull(request, "Set provider request must not be null");
		logger.debug("Setting provider: {}", request.providerId());
		return session.sendRequest(AcpSchema.METHOD_PROVIDERS_SET, request, SET_PROVIDER_RESPONSE_TYPE_REF);
	}

	/**
	 * Disables a provider by ID ({@code providers/disable}).
	 * @param request the provider ID
	 * @return a {@code Mono} emitting the agent's answer
	 * @see AcpSchema#METHOD_PROVIDERS_DISABLE
	 */
	@UnstableAcpApi
	public Mono<AcpSchema.DisableProviderResponse> disableProvider(AcpSchema.DisableProviderRequest request) {
		Assert.notNull(request, "Disable provider request must not be null");
		logger.debug("Disabling provider: {}", request.providerId());
		return session.sendRequest(AcpSchema.METHOD_PROVIDERS_DISABLE, request, DISABLE_PROVIDER_RESPONSE_TYPE_REF);
	}

	// --------------------------
	// Prompt Interaction
	// --------------------------

	/**
	 * Sends a prompt to a session ({@code session/prompt}) and returns the agent's answer at the
	 * end of the turn, with the stop reason. Meanwhile the agent streams the turn as
	 * {@code session/update} notifications to the session update consumers. A session takes one
	 * prompt at a time: a Java agent answers a second prompt sent before the first is answered with
	 * {@code -32600}.
	 *
	 * <p>The answer is delivered only once the session update consumers have finished with every
	 * notification the agent sent before it, so what they collected for the turn is complete when
	 * the stop reason arrives. A slow consumer delays the answer, and the wait counts against the
	 * request timeout. The one exception: a consumer that was already running when the prompt was
	 * sent, and is still running when its answer arrives, is not waited for, since it may be the
	 * one waiting for the prompt. A consumer must therefore not wait for this prompt to complete.
	 *
	 * <p>The whole turn must fit in the request timeout (60 seconds by default). When it passes,
	 * the client sends {@code $/cancel_request}, which makes a Java agent cancel the turn; raise
	 * {@code requestTimeout} on the builder for long turns.
	 * @param promptRequest the session ID and the prompt's content blocks
	 * @return a {@code Mono} emitting the agent's answer, with the stop reason
	 * @see AcpSchema#METHOD_SESSION_PROMPT
	 */
	public Mono<AcpSchema.PromptResponse> prompt(AcpSchema.PromptRequest promptRequest) {
		Assert.notNull(promptRequest, "Prompt request must not be null");
		logger.debug("Sending prompt to session: {}", promptRequest.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_PROMPT, promptRequest, PROMPT_RESPONSE_TYPE_REF);
	}

	/**
	 * Asks the agent to stop a session's prompt turn ({@code session/cancel}). It is a
	 * notification: the agent does not answer it.
	 *
	 * <p>The cancel does not end the turn. The agent may still send {@code session/update}s, which
	 * reach the session update consumers as usual, and then answers the pending prompt with stop
	 * reason {@code cancelled}. Send the next prompt on the session once that answer has arrived:
	 * until then the agent refuses it (ACP v1, prompt turn, Cancellation).
	 * @param cancelNotification the session ID
	 * @return a {@code Mono} that completes when the notification has been handed to the transport
	 * @see AcpSchema#METHOD_SESSION_CANCEL
	 */
	public Mono<Void> cancel(AcpSchema.CancelNotification cancelNotification) {
		Assert.notNull(cancelNotification, "Cancel notification must not be null");
		logger.debug("Canceling operations for session: {}", cancelNotification.sessionId());
		return session.sendNotification(AcpSchema.METHOD_SESSION_CANCEL, cancelNotification);
	}

	// --------------------------
	// Extension Methods
	// --------------------------

	/**
	 * Sends a custom extension request ({@code _}-prefixed method name, ACP v1 Extensibility) to
	 * the agent and reads its result as the given type. An agent that does not handle the method
	 * answers "Method not found" ({@code -32601}), which fails the {@code Mono} with
	 * {@link com.agentclientprotocol.sdk.spec.AcpError}. The SDK checks no capability for extension
	 * methods: agents advertise them in the {@code _meta} of their capabilities
	 * ({@link #getAgentCapabilities()}).
	 * @param <T> the result type
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @param resultType the type the result is read as
	 * @return a {@code Mono} emitting the result, or completing empty when the agent answers
	 * {@code "result": null}
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 */
	public <T> Mono<T> sendExtRequest(String method, Object params, TypeRef<T> resultType) {
		ExtensionMethods.requireExtension(method);
		Assert.notNull(params, "Params must not be null");
		Assert.notNull(resultType, "Result type must not be null");
		return session.sendRequest(method, params, resultType);
	}

	/**
	 * Sends a custom extension request to the agent and returns its result as the raw JSON value: a
	 * {@code Map}, {@code List}, {@code String}, {@code Number} or {@code Boolean}.
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @return a {@code Mono} emitting the result, or completing empty when the agent answers
	 * {@code "result": null}
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 * @see #sendExtRequest(String, Object, TypeRef)
	 */
	public Mono<Object> sendExtRequest(String method, Object params) {
		return sendExtRequest(method, params, RAW_RESULT_TYPE_REF);
	}

	/**
	 * Sends a custom extension notification ({@code _}-prefixed method name) to the agent. An agent
	 * without a handler for it ignores it.
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @return a {@code Mono} that completes when the notification has been handed to the transport
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 */
	public Mono<Void> sendExtNotification(String method, Object params) {
		ExtensionMethods.requireExtension(method);
		Assert.notNull(params, "Params must not be null");
		return session.sendNotification(method, params);
	}

	// --------------------------
	// Lifecycle Management
	// --------------------------

	/**
	 * Closes the client at once: requests waiting for an answer fail, the agent's requests being
	 * handled are cancelled, session updates not yet handled are dropped, and the transport is
	 * closed.
	 */
	public void close() {
		logger.debug("Closing ACP client");
		session.close();
		transport.close();
	}

	/**
	 * Closes the client gracefully. Requests still waiting for an answer, a prompt included, fail
	 * at once, and the agent's requests being handled are cancelled and answered {@code -32800}.
	 * Session updates already received are still handed to the consumers, waiting at most the
	 * request timeout, and then the transport closes gracefully. For a
	 * {@link com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport} that closes the
	 * agent's input first and gives the agent time to exit.
	 * @return a {@code Mono} that completes when the transport has closed
	 */
	public Mono<Void> closeGracefully() {
		logger.debug("Gracefully closing ACP client");
		return session.closeGracefully().then(transport.closeGracefully());
	}

}
