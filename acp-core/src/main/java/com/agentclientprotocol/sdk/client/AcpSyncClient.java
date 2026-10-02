/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.Map;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.util.Assert;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * A synchronous client implementation for the Agent Client Protocol (ACP) that wraps an
 * {@link AcpAsyncClient} to provide blocking operations.
 *
 * <p>
 * This client implements the ACP specification by delegating to an asynchronous client
 * and blocking on the results. Key features include:
 * <ul>
 * <li>Synchronous, blocking API for simpler integration in non-reactive applications</li>
 * <li>Initialize handshake and capability negotiation</li>
 * <li>Session creation and management</li>
 * <li>Prompt submission with streaming updates</li>
 * <li>Authentication support for agents requiring it</li>
 * </ul>
 *
 * <p>
 * The client follows the same lifecycle as its async counterpart:
 * <ol>
 * <li>Initialization - Establishes connection and negotiates protocol version</li>
 * <li>Authentication - Optional authentication step</li>
 * <li>Session Creation - Creates a new agent session with working directory</li>
 * <li>Prompt Interaction - Sends prompts and receives responses</li>
 * <li>Graceful Shutdown - Ensures clean connection termination</li>
 * </ol>
 *
 * <p>
 * This implementation implements {@link AutoCloseable} for resource cleanup and provides
 * both immediate and graceful shutdown options. All operations block until completion or
 * timeout, making it suitable for traditional synchronous programming models.
 *
 * <p>
 * Example usage: <pre>{@code
 * try (AcpSyncClient client = AcpClient.sync(transport).build()) {
 *     // Initialize: sends the capabilities and client info set on the builder
 *     AcpSchema.InitializeResponse initResponse = client.initialize();
 *
 *     // Create session
 *     AcpSchema.NewSessionResponse sessionResponse = client.newSession(
 *         new AcpSchema.NewSessionRequest("/workspace", List.of()));
 *
 *     // Send prompt
 *     AcpSchema.PromptResponse response = client.prompt(
 *         new AcpSchema.PromptRequest(sessionResponse.sessionId(),
 *             List.of(new AcpSchema.TextContent("Fix the bug"))));
 *
 *     System.out.println("Stop reason: " + response.stopReason());
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 * @see AcpClient
 * @see AcpAsyncClient
 * @see AcpSchema
 */
public class AcpSyncClient implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(AcpSyncClient.class);

	private static final long DEFAULT_CLOSE_TIMEOUT_MS = 10_000L;

	private final AcpAsyncClient delegate;

	/**
	 * Creates a synchronous facade over an existing asynchronous client.
	 *
	 * <p>
	 * Both clients share the one session and the one transport connection behind
	 * {@code delegate}: use this when an application needs both APIs, because a transport
	 * instance carries exactly one session, and building a second client on an
	 * already-connected transport fails. Closing either client closes the shared session.
	 * </p>
	 * @param delegate the asynchronous client on top of which this synchronous client
	 * provides a blocking API
	 */
	public AcpSyncClient(AcpAsyncClient delegate) {
		Assert.notNull(delegate, "Delegate must not be null");
		this.delegate = delegate;
	}

	// --------------------------
	// Lifecycle Management
	// --------------------------

	/**
	 * Closes the client connection and waits for shutdown to complete.
	 *
	 * <p>
	 * This method blocks until the connection is closed or the timeout is reached.
	 * For synchronous clients, this ensures resources are fully released when
	 * try-with-resources completes.
	 * </p>
	 */
	@Override
	public void close() {
		logger.debug("Closing ACP sync client");
		closeGracefully();
	}

	/**
	 * Gracefully closes the client connection with a default timeout.
	 * @return true if the client closed gracefully, false if it timed out
	 */
	public boolean closeGracefully() {
		try {
			logger.debug("Gracefully closing ACP sync client");
			this.delegate.closeGracefully().block(Duration.ofMillis(DEFAULT_CLOSE_TIMEOUT_MS));
		}
		catch (RuntimeException e) {
			logger.warn("Client didn't close within timeout of {} ms", DEFAULT_CLOSE_TIMEOUT_MS, e);
			return false;
		}
		return true;
	}

	// --------------------------
	// Initialization
	// --------------------------

	/**
	 * Initializes the connection with the agent: the first step in the ACP lifecycle. The
	 * client sends protocol version {@value AcpSchema#LATEST_PROTOCOL_VERSION} with the
	 * capabilities and client info set on the builder
	 * ({@link AcpClient.SyncSpec#clientCapabilities}, {@link AcpClient.SyncSpec#clientInfo});
	 * the agent answers with its protocol version, capabilities and authentication methods.
	 *
	 * <p>
	 * The builder is the only place the client's capabilities are set, so what the client
	 * advertises is also what its handlers honour. Without {@code clientCapabilities(...)}
	 * the client advertises {@code new ClientCapabilities()}: no file system access and no
	 * terminal.
	 * </p>
	 * @return the initialization response with agent capabilities
	 * @see AcpSchema#METHOD_INITIALIZE
	 * @see #initialize(int, Map)
	 */
	public AcpSchema.InitializeResponse initialize() {
		return awaitResponse(this.delegate.initialize());
	}

	/**
	 * Initializes the connection with the agent, like {@link #initialize()}, with a chosen
	 * protocol version and {@code _meta}. The capabilities and client info still come
	 * from the builder; this overload exists for {@code _meta} and for testing version
	 * negotiation, not for advertising capabilities.
	 * @param protocolVersion the protocol version to announce; this SDK speaks
	 * {@value AcpSchema#LATEST_PROTOCOL_VERSION}
	 * @param meta the request's {@code _meta}, or {@code null}
	 * @return the initialization response with agent capabilities
	 * @see #initialize()
	 */
	public AcpSchema.InitializeResponse initialize(int protocolVersion, @Nullable Map<String, Object> meta) {
		return awaitResponse(this.delegate.initialize(protocolVersion, meta));
	}

	/**
	 * Returns the capabilities negotiated with the agent during initialization.
	 *
	 * <p>
	 * This method returns null if {@link #initialize} has not been called yet.
	 * </p>
	 * @return the negotiated agent capabilities, or null if not initialized
	 */
	public com.agentclientprotocol.sdk.capabilities.@Nullable NegotiatedCapabilities getAgentCapabilities() {
		return this.delegate.getAgentCapabilities();
	}

	// --------------------------
	// Authentication
	// --------------------------

	/**
	 * Authenticates with the agent using the specified authentication method.
	 *
	 * <p>
	 * Authentication is optional and depends on the agent's configuration. The
	 * authentication methods available are returned in the initialize response.
	 * </p>
	 * @param authenticateRequest the authentication request specifying the auth method
	 * and credentials
	 * @return the authentication response
	 * @see AcpSchema#METHOD_AUTHENTICATE
	 */
	public AcpSchema.AuthenticateResponse authenticate(AcpSchema.AuthenticateRequest authenticateRequest) {
		return awaitResponse(this.delegate.authenticate(authenticateRequest));
	}

	/**
	 * Logs out of the agent, clearing any stored credentials.
	 * @param logoutRequest the logout request
	 * @return the logout response
	 * @see AcpSchema#METHOD_LOGOUT
	 */
	public AcpSchema.LogoutResponse logout(AcpSchema.LogoutRequest logoutRequest) {
		return awaitResponse(this.delegate.logout(logoutRequest));
	}

	// --------------------------
	// Session Management
	// --------------------------

	/**
	 * Creates a new agent session with the specified working directory.
	 *
	 * <p>
	 * A session represents a conversation context with the agent. All prompts within a
	 * session share the same working directory and conversation history.
	 * </p>
	 * @param newSessionRequest the session creation request with working directory and
	 * initial context
	 * @return the session response containing the session ID
	 * @see AcpSchema#METHOD_SESSION_NEW
	 */
	public AcpSchema.NewSessionResponse newSession(AcpSchema.NewSessionRequest newSessionRequest) {
		return awaitResponse(this.delegate.newSession(newSessionRequest));
	}

	/**
	 * Loads an existing agent session by ID.
	 *
	 * <p>
	 * This allows resuming a previous conversation with the agent, maintaining the full
	 * history and context.
	 * </p>
	 * @param loadSessionRequest the session load request with session ID
	 * @return the load response confirming the session was loaded
	 * @see AcpSchema#METHOD_SESSION_LOAD
	 */
	public AcpSchema.LoadSessionResponse loadSession(AcpSchema.LoadSessionRequest loadSessionRequest) {
		return awaitResponse(this.delegate.loadSession(loadSessionRequest));
	}

	/**
	 * Sets the operational mode for a session (e.g., "code", "plan", "review").
	 *
	 * <p>
	 * Different modes may change how the agent processes prompts and what capabilities it
	 * exposes.
	 * </p>
	 * @param setModeRequest the set mode request with session ID and desired mode
	 * @return the response confirming the mode change
	 * @see AcpSchema#METHOD_SESSION_SET_MODE
	 */
	public AcpSchema.SetSessionModeResponse setSessionMode(AcpSchema.SetSessionModeRequest setModeRequest) {
		return awaitResponse(this.delegate.setSessionMode(setModeRequest));
	}

	/**
	 * Lists sessions known to the agent, optionally filtered by working directory.
	 * @param listSessionsRequest the list sessions request with optional cwd filter and cursor
	 * @return the list sessions response
	 * @see AcpSchema#METHOD_SESSION_LIST
	 */
	public AcpSchema.ListSessionsResponse listSessions(AcpSchema.ListSessionsRequest listSessionsRequest) {
		return awaitResponse(this.delegate.listSessions(listSessionsRequest));
	}

	/**
	 * Closes an active session, cancelling any in-flight work.
	 * @param closeSessionRequest the close session request with session ID
	 * @return the close session response
	 * @see AcpSchema#METHOD_SESSION_CLOSE
	 */
	public AcpSchema.CloseSessionResponse closeSession(AcpSchema.CloseSessionRequest closeSessionRequest) {
		return awaitResponse(this.delegate.closeSession(closeSessionRequest));
	}

	/**
	 * Permanently deletes a stored session.
	 * @param deleteSessionRequest the delete session request with session ID
	 * @return the delete session response
	 * @see AcpSchema#METHOD_SESSION_DELETE
	 */
	public AcpSchema.DeleteSessionResponse deleteSession(AcpSchema.DeleteSessionRequest deleteSessionRequest) {
		return awaitResponse(this.delegate.deleteSession(deleteSessionRequest));
	}

	/**
	 * Resumes an existing session without replaying conversation history.
	 * @param resumeSessionRequest the resume session request with session ID and cwd
	 * @return the resume session response
	 * @see AcpSchema#METHOD_SESSION_RESUME
	 */
	public AcpSchema.ResumeSessionResponse resumeSession(AcpSchema.ResumeSessionRequest resumeSessionRequest) {
		return awaitResponse(this.delegate.resumeSession(resumeSessionRequest));
	}

	/**
	 * Forks an existing session, creating a new session branched from it.
	 * @param forkSessionRequest the fork request with source session ID and cwd
	 * @return the fork response with the new session ID
	 * @see AcpSchema#METHOD_SESSION_FORK
	 */
	@UnstableAcpApi
	public AcpSchema.ForkSessionResponse forkSession(AcpSchema.ForkSessionRequest forkSessionRequest) {
		return awaitResponse(this.delegate.forkSession(forkSessionRequest));
	}

	/**
	 * Sets a configuration option for a session.
	 * @param request the config option request with session ID, config ID, and value
	 * @return the response with the full config state
	 * @see AcpSchema#METHOD_SESSION_SET_CONFIG_OPTION
	 */
	public AcpSchema.SetSessionConfigOptionResponse setSessionConfigOption(
			AcpSchema.SetSessionConfigOptionRequest request) {
		return awaitResponse(this.delegate.setSessionConfigOption(request));
	}

	// --------------------------
	// Provider Configuration (UNSTABLE)
	// --------------------------

	/**
	 * Lists the providers the agent can route to (UNSTABLE).
	 * @param request the list providers request
	 * @return the list of configurable providers
	 * @see AcpSchema#METHOD_PROVIDERS_LIST
	 */
	@UnstableAcpApi
	public AcpSchema.ListProvidersResponse listProviders(AcpSchema.ListProvidersRequest request) {
		return awaitResponse(this.delegate.listProviders(request));
	}

	/**
	 * Configures a provider's routing (UNSTABLE).
	 * @param request the set provider request
	 * @return the response
	 * @see AcpSchema#METHOD_PROVIDERS_SET
	 */
	@UnstableAcpApi
	public AcpSchema.SetProviderResponse setProvider(AcpSchema.SetProviderRequest request) {
		return awaitResponse(this.delegate.setProvider(request));
	}

	/**
	 * Disables a provider by id (UNSTABLE).
	 * @param request the disable provider request
	 * @return the response
	 * @see AcpSchema#METHOD_PROVIDERS_DISABLE
	 */
	@UnstableAcpApi
	public AcpSchema.DisableProviderResponse disableProvider(AcpSchema.DisableProviderRequest request) {
		return awaitResponse(this.delegate.disableProvider(request));
	}

	// --------------------------
	// Prompt Interaction
	// --------------------------

	/**
	 * Sends a prompt to the agent within a session.
	 *
	 * <p>
	 * The prompt can contain text, images, or other content types. The agent processes
	 * the prompt and may send streaming updates via session/update notifications before
	 * returning the final response.
	 * </p>
	 * @param promptRequest the prompt request with session ID and content
	 * @return the prompt response with stop reason
	 * @see AcpSchema#METHOD_SESSION_PROMPT
	 */
	public AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest promptRequest) {
		return awaitResponse(this.delegate.prompt(promptRequest));
	}

	/**
	 * Cancels ongoing operations for a session.
	 *
	 * <p>
	 * This sends a notification to the agent to stop any in-progress work for the
	 * specified session. Note that this is a notification (fire-and-forget), not a
	 * request.
	 * </p>
	 *
	 * <p>
	 * The cancel does not end the prompt turn. The agent may still send
	 * {@code session/update}s, which reach the session update consumers as usual, and then
	 * answers the pending {@code prompt} with stop reason {@code cancelled}. Send the next
	 * prompt on the session once that answer has arrived: until then the agent rejects it
	 * (ACP v1, prompt turn, Cancellation).
	 * </p>
	 * @param cancelNotification the cancel notification with session ID and optional
	 * reason
	 * @see AcpSchema#METHOD_SESSION_CANCEL
	 */
	public void cancel(AcpSchema.CancelNotification cancelNotification) {
		this.delegate.cancel(cancelNotification).block();
	}

	/**
	 * Sends a custom extension request ({@code _}-prefixed method name) to the agent and
	 * blocks for its result, read as the given type.
	 * @param <T> the result type
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @param resultType the type the result is read as
	 * @return the result, or null when the agent answers {@code "result": null}
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 * @see AcpAsyncClient#sendExtRequest(String, Object, TypeRef)
	 */
	public <T> @Nullable T sendExtRequest(String method, Object params, TypeRef<T> resultType) {
		return this.delegate.sendExtRequest(method, params, resultType).block();
	}

	/**
	 * Sends a custom extension request to the agent and blocks for its result, as the
	 * raw JSON value.
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @return the result, or null when the agent answers {@code "result": null}
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 * @see AcpAsyncClient#sendExtRequest(String, Object)
	 */
	public @Nullable Object sendExtRequest(String method, Object params) {
		return this.delegate.sendExtRequest(method, params).block();
	}

	/**
	 * Sends a custom extension notification ({@code _}-prefixed method name) to the
	 * agent.
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 */
	public void sendExtNotification(String method, Object params) {
		this.delegate.sendExtNotification(method, params).block();
	}

	/**
	 * Blocks for the response to a request. A request's Mono emits the response or fails:
	 * the session delivers every result through a Reactor sink, which cannot carry null, so
	 * it never completes empty. An empty completion would be a broken invariant, reported
	 * as such rather than returned as a null response.
	 */
	private static <T> T awaitResponse(Mono<T> response) {
		T value = response.block();
		if (value == null) {
			throw new IllegalStateException("ACP request completed without a response");
		}
		return value;
	}

}
