/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import com.agentclientprotocol.sdk.util.Assert;
import com.agentclientprotocol.sdk.spec.SyncCalls;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * A connected ACP client with blocking calls: the calls of {@link AcpAsyncClient}, each waiting for
 * the agent's answer, while the handlers registered on {@link AcpClient.SyncSpec} answer the
 * agent's requests. Get one from {@code AcpClient.sync(transport)...build()}. Use it in plain Java
 * code; it is {@link AutoCloseable}, so try-with-resources closes the connection.
 *
 * <p>A client follows ACP's order: {@link #initialize()} first, {@link #authenticate} if the agent
 * requires it, then {@link #newSession}, {@link #loadSession} or {@link #resumeSession} for a
 * session ID, then {@link #prompt} as often as needed, one turn at a time per session.
 *
 * <pre>{@code
 * try (AcpSyncClient client = AcpClient.sync(transport).build()) {
 *     client.initialize();
 *     AcpSchema.NewSessionResponse session = client.newSession(
 *         new AcpSchema.NewSessionRequest("/workspace", List.of()));
 *     AcpSchema.PromptResponse response = client.prompt(new AcpSchema.PromptRequest(
 *         session.sessionId(), List.of(new AcpSchema.TextContent("Fix the bug"))));
 *     System.out.println("Stop reason: " + response.stopReason());
 * }
 * }</pre>
 *
 * <p>A call blocks until the answer arrives; it has no time limit of its own beyond the builder's
 * request timeout (30 seconds by default), or, for {@link #prompt}, the builder's prompt timeout
 * (none by default). Failures are thrown: {@link com.agentclientprotocol.sdk.spec.AcpError} for an
 * error answer, whose {@code getCode()} is the JSON-RPC error code;
 * {@link com.agentclientprotocol.sdk.error.AcpTimeoutException}, whose cause is the
 * {@link java.util.concurrent.TimeoutException}, when no answer came in time, after the client has
 * sent the agent a {@code $/cancel_request}; {@link java.util.concurrent.CancellationException}
 * when the waiting thread is interrupted (the request is cancelled and the interrupt flag stays
 * set); and {@link IllegalArgumentException} for a null argument. The client does not check the
 * agent's capabilities before a call; see {@link #getAgentCapabilities()}. Methods may be called
 * from several threads at once, but not from a thread that must not block, and not from a session
 * update consumer waiting for a prompt in flight (see {@link #prompt}).
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 * @see AcpClient
 * @see AcpAsyncClient
 */
public class AcpSyncClient implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(AcpSyncClient.class);

	private static final long DEFAULT_CLOSE_TIMEOUT_MS = 10_000L;

	private static final Duration CLOSE_TIMEOUT = Duration.ofMillis(DEFAULT_CLOSE_TIMEOUT_MS);

	private final AcpAsyncClient delegate;

	/**
	 * Creates a blocking view of an existing asynchronous client.
	 *
	 * <p>Both clients share the one session and the one transport connection behind
	 * {@code delegate}. Use this when an application needs both APIs: a transport carries exactly
	 * one client, and building a second client on a connected transport fails. Closing either
	 * client closes the shared session.
	 * @param delegate the asynchronous client to block on
	 * @throws IllegalArgumentException if {@code delegate} is null
	 */
	public AcpSyncClient(AcpAsyncClient delegate) {
		Assert.notNull(delegate, "Delegate must not be null");
		this.delegate = delegate;
	}

	// --------------------------
	// Lifecycle Management
	// --------------------------

	/**
	 * Closes the client gracefully, as {@link #closeGracefully()} does, and waits at most 10
	 * seconds. If that fails or takes longer, the failure is logged and nothing more is done.
	 */
	@Override
	public void close() {
		logger.debug("Closing ACP sync client");
		closeGracefully();
	}

	/**
	 * Closes the client as {@link AcpAsyncClient#closeGracefully()} does, and waits at most 10
	 * seconds: requests still waiting for an answer fail at once, session updates already received
	 * are still handed to the consumers, and then the transport closes gracefully.
	 * @return {@code true} if the client closed; {@code false} if closing failed or took longer
	 * than 10 seconds, which is logged
	 */
	public boolean closeGracefully() {
		try {
			logger.debug("Gracefully closing ACP sync client");
			this.delegate.closeGracefully()
				.timeout(CLOSE_TIMEOUT, AcpSchedulers.timeouts())
				.block();
		}
		catch (RuntimeException e) {
			Throwable cause = Exceptions.unwrap(e);
			if (cause instanceof TimeoutException) {
				logger.warn("Client didn't close within timeout of {} ms", DEFAULT_CLOSE_TIMEOUT_MS);
			}
			else {
				logger.warn("Client close failed: {}", cause.toString(), cause);
			}
			return false;
		}
		return true;
	}

	/**
	 * Returns the asynchronous client this client blocks on: the same session and transport
	 * connection, with calls that return {@code Mono}s, for code that needs both APIs, such as
	 * cancelling one request with {@link com.agentclientprotocol.sdk.spec.RequestCancellation}, or
	 * closing at once with {@link AcpAsyncClient#close()}.
	 * @return the asynchronous client
	 */
	public AcpAsyncClient async() {
		return this.delegate;
	}

	// --------------------------
	// Initialization
	// --------------------------

	/**
	 * Initializes the connection: the first request of the ACP lifecycle, sent once before any
	 * other. The client sends protocol version {@value AcpSchema#LATEST_PROTOCOL_VERSION} with the
	 * capabilities and client info set on the builder
	 * ({@link AcpClient.SyncSpec#clientCapabilities}, {@link AcpClient.SyncSpec#clientInfo}); the
	 * agent answers with its protocol version, capabilities and authentication methods, and
	 * {@link #getAgentCapabilities()} returns those capabilities from then on. The client does not
	 * check the protocol version the agent answers with.
	 *
	 * <p>The builder is the only place the client's capabilities are set, so what the client
	 * advertises is also what its handlers honour (an elicitation mode it did not advertise is
	 * refused). Without {@code clientCapabilities(...)} the client advertises
	 * {@code new ClientCapabilities()}: no file system and no terminal.
	 * @return the agent's answer
	 * @see AcpSchema#METHOD_INITIALIZE
	 * @see #initialize(int, Map)
	 */
	public AcpSchema.InitializeResponse initialize() {
		return awaitResponse(this.delegate.initialize());
	}

	/**
	 * Initializes the connection like {@link #initialize()}, with a chosen protocol version and
	 * {@code _meta}. The capabilities and client info still come from the builder; this overload
	 * exists for {@code _meta} and for testing version negotiation, not for advertising
	 * capabilities.
	 * @param protocolVersion the protocol version to announce; this SDK speaks
	 * {@value AcpSchema#LATEST_PROTOCOL_VERSION}
	 * @param meta the request's {@code _meta}, or {@code null}
	 * @return the agent's answer
	 * @see #initialize()
	 */
	public AcpSchema.InitializeResponse initialize(int protocolVersion, @Nullable Map<String, Object> meta) {
		return awaitResponse(this.delegate.initialize(protocolVersion, meta));
	}

	/**
	 * Returns the agent's capabilities from its {@code initialize} answer. Check them before calls
	 * that need them, for example {@code supportsLoadSession()} before {@link #loadSession} or
	 * {@code supportsLogout()} before {@link #logout}: the client sends every call without
	 * checking.
	 * @return the agent's capabilities, or {@code null} before an {@code initialize} answer arrived
	 */
	public com.agentclientprotocol.sdk.capabilities.@Nullable NegotiatedCapabilities getAgentCapabilities() {
		return this.delegate.getAgentCapabilities();
	}

	// --------------------------
	// Authentication
	// --------------------------

	/**
	 * Logs in with one of the authentication methods the agent listed in its {@code initialize}
	 * answer ({@code authenticate}). Needed only for an agent that requires it; such an agent
	 * answers other requests with {@code -32000} (authentication required) until then.
	 * @param authenticateRequest the ID of the chosen authentication method
	 * @return the agent's answer
	 * @see AcpSchema#METHOD_AUTHENTICATE
	 */
	public AcpSchema.AuthenticateResponse authenticate(AcpSchema.AuthenticateRequest authenticateRequest) {
		return awaitResponse(this.delegate.authenticate(authenticateRequest));
	}

	/**
	 * Logs out of the agent ({@code logout}), ending the authenticated state; afterwards the client
	 * must authenticate again where the agent requires it. Only an agent that advertises
	 * {@code auth.logout} supports it: check
	 * {@link com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities#supportsLogout()}
	 * first, since the client does not.
	 * @param logoutRequest the logout request
	 * @return the agent's answer
	 * @see AcpSchema#METHOD_LOGOUT
	 */
	public AcpSchema.LogoutResponse logout(AcpSchema.LogoutRequest logoutRequest) {
		return awaitResponse(this.delegate.logout(logoutRequest));
	}

	// --------------------------
	// Session Management
	// --------------------------

	/**
	 * Creates an ACP session ({@code session/new}) for a working directory, with the MCP servers
	 * the agent should connect to. The answer carries the session ID every later call for the
	 * session uses, and optionally the session's modes and config options.
	 * @param newSessionRequest the working directory, an absolute path, and the MCP servers
	 * @return the agent's answer, with the session ID
	 * @see AcpSchema#METHOD_SESSION_NEW
	 */
	public AcpSchema.NewSessionResponse newSession(AcpSchema.NewSessionRequest newSessionRequest) {
		return awaitResponse(this.delegate.newSession(newSessionRequest));
	}

	/**
	 * Reopens a session the agent kept ({@code session/load}). The agent replays the conversation
	 * as session updates, which reach the session update consumers before this call completes, then
	 * answers. Only an agent that advertises {@code loadSession} supports it; the client does not
	 * check.
	 * @param loadSessionRequest the session ID, the working directory and the MCP servers
	 * @return the agent's answer, once the history has been replayed
	 * @see AcpSchema#METHOD_SESSION_LOAD
	 */
	public AcpSchema.LoadSessionResponse loadSession(AcpSchema.LoadSessionRequest loadSessionRequest) {
		return awaitResponse(this.delegate.loadSession(loadSessionRequest));
	}

	/**
	 * Switches a session to one of the modes the agent offered ({@code session/set_mode}). Modes
	 * may change how the agent works on prompts and what it asks permission for.
	 * @param setModeRequest the session ID and the mode ID
	 * @return the agent's answer
	 * @see AcpSchema#METHOD_SESSION_SET_MODE
	 */
	public AcpSchema.SetSessionModeResponse setSessionMode(AcpSchema.SetSessionModeRequest setModeRequest) {
		return awaitResponse(this.delegate.setSessionMode(setModeRequest));
	}

	/**
	 * Lists the sessions the agent knows ({@code session/list}), optionally only those of one
	 * working directory. The answer may be one page: pass its {@code nextCursor} in the next
	 * request to get the next page.
	 * @param listSessionsRequest an optional working directory and an optional cursor
	 * @return a page of sessions
	 * @see AcpSchema#METHOD_SESSION_LIST
	 */
	public AcpSchema.ListSessionsResponse listSessions(AcpSchema.ListSessionsRequest listSessionsRequest) {
		return awaitResponse(this.delegate.listSessions(listSessionsRequest));
	}

	/**
	 * Closes an active session ({@code session/close}): the agent stops its work as for
	 * {@code session/cancel}, then frees what the session holds.
	 * @param closeSessionRequest the session ID
	 * @return the agent's answer
	 * @see AcpSchema#METHOD_SESSION_CLOSE
	 */
	public AcpSchema.CloseSessionResponse closeSession(AcpSchema.CloseSessionRequest closeSessionRequest) {
		return awaitResponse(this.delegate.closeSession(closeSessionRequest));
	}

	/**
	 * Deletes a stored session ({@code session/delete}). Unlike {@link #closeSession}, which frees
	 * an active session, it removes the session from the agent's storage, so that it no longer
	 * appears in {@code session/list}. Only an agent that advertises
	 * {@code sessionCapabilities.delete} supports it; the client does not check.
	 * @param deleteSessionRequest the session ID
	 * @return the agent's answer
	 * @see AcpSchema#METHOD_SESSION_DELETE
	 */
	public AcpSchema.DeleteSessionResponse deleteSession(AcpSchema.DeleteSessionRequest deleteSessionRequest) {
		return awaitResponse(this.delegate.deleteSession(deleteSessionRequest));
	}

	/**
	 * Reopens a session without replaying its history ({@code session/resume}), unlike
	 * {@link #loadSession}. Use it to reconnect to a session the agent still runs, or when the
	 * client keeps the history itself.
	 * @param resumeSessionRequest the session ID, the working directory and the MCP servers
	 * @return the agent's answer
	 * @see AcpSchema#METHOD_SESSION_RESUME
	 */
	public AcpSchema.ResumeSessionResponse resumeSession(AcpSchema.ResumeSessionRequest resumeSessionRequest) {
		return awaitResponse(this.delegate.resumeSession(resumeSessionRequest));
	}

	/**
	 * Creates a new session branched from an existing one ({@code session/fork}).
	 * @param forkSessionRequest the ID of the session to branch from and the working directory
	 * @return the agent's answer, with the new session's ID
	 * @see AcpSchema#METHOD_SESSION_FORK
	 */
	@UnstableAcpApi
	public AcpSchema.ForkSessionResponse forkSession(AcpSchema.ForkSessionRequest forkSessionRequest) {
		return awaitResponse(this.delegate.forkSession(forkSessionRequest));
	}

	/**
	 * Changes one of a session's config options ({@code session/set_config_option}); make the
	 * request with {@link AcpSchema.SetSessionConfigOptionRequest#select} or
	 * {@link AcpSchema.SetSessionConfigOptionRequest#bool}. The answer carries the full list of the
	 * session's options, which replaces the client's copy. Send a boolean option only to an agent
	 * that offered it.
	 * @param request the session ID, the option ID and the new value
	 * @return the agent's answer, with all of the session's options
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
	 * Lists the providers the agent can route to ({@code providers/list}). Only an agent that
	 * advertises {@code providers} supports it.
	 * @param request the list request
	 * @return the providers
	 * @see AcpSchema#METHOD_PROVIDERS_LIST
	 */
	@UnstableAcpApi
	public AcpSchema.ListProvidersResponse listProviders(AcpSchema.ListProvidersRequest request) {
		return awaitResponse(this.delegate.listProviders(request));
	}

	/**
	 * Configures how the agent reaches a provider: protocol, base URL and headers
	 * ({@code providers/set}).
	 * @param request the provider ID and its routing
	 * @return the agent's answer
	 * @see AcpSchema#METHOD_PROVIDERS_SET
	 */
	@UnstableAcpApi
	public AcpSchema.SetProviderResponse setProvider(AcpSchema.SetProviderRequest request) {
		return awaitResponse(this.delegate.setProvider(request));
	}

	/**
	 * Disables a provider by ID ({@code providers/disable}).
	 * @param request the provider ID
	 * @return the agent's answer
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
	 * Sends a prompt to a session ({@code session/prompt}) and returns the agent's answer at the
	 * end of the turn, with the stop reason. Meanwhile the agent streams the turn as
	 * {@code session/update} notifications to the session update consumers. A session takes one
	 * prompt at a time: a Java agent answers a second prompt sent before the first is answered with
	 * {@code -32600}.
	 *
	 * <p>The answer is delivered only once the session update consumers have finished with every
	 * notification the agent sent before it, so what they collected for the turn is complete when
	 * the stop reason arrives. A slow consumer delays the answer, and the wait counts against the
	 * prompt timeout, if one is set. The one exception: a consumer that was already running when the prompt was
	 * sent, and is still running when its answer arrives, is not waited for, since it may be the
	 * one waiting for the prompt. A consumer must therefore not wait for this prompt to complete.
	 *
	 * <p>A prompt is not bound by the builder's {@code requestTimeout}: by default it blocks until
	 * the end of the turn however long that takes. Set {@link AcpClient.SyncSpec#promptTimeout} to
	 * bound it; when that passes, the call fails as described above for a timeout and the client
	 * sends {@code $/cancel_request}, which makes a Java agent cancel the turn. To stop a turn from
	 * another thread and still receive its answer, call {@link #cancel}.
	 * @param promptRequest the session ID and the prompt's content blocks
	 * @return the agent's answer, with the stop reason
	 * @see AcpSchema#METHOD_SESSION_PROMPT
	 */
	public AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest promptRequest) {
		return awaitResponse(this.delegate.prompt(promptRequest));
	}

	/**
	 * Asks the agent to stop a session's prompt turn ({@code session/cancel}). It is a
	 * notification: the agent does not answer it.
	 *
	 * <p>The cancel does not end the turn. The agent may still send {@code session/update}s, which
	 * reach the session update consumers as usual, and then answers the pending prompt with stop
	 * reason {@code cancelled}. Send the next prompt on the session once that answer has arrived:
	 * until then the agent refuses it (ACP v1, prompt turn, Cancellation).
	 *
	 * <p>Returns once the notification has been handed to the transport, so it can be called from
	 * another thread while {@link #prompt} blocks.
	 * @param cancelNotification the session ID
	 * @see AcpSchema#METHOD_SESSION_CANCEL
	 */
	public void cancel(AcpSchema.CancelNotification cancelNotification) {
		SyncCalls.block(this.delegate.cancel(cancelNotification));
	}

	/**
	 * Sends a custom extension request ({@code _}-prefixed method name) to the agent and blocks for
	 * its result, read as the given type. An agent that does not handle the method answers "Method
	 * not found" ({@code -32601}), thrown as {@link com.agentclientprotocol.sdk.spec.AcpError}.
	 * @param <T> the result type
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @param resultType the type the result is read as
	 * @return the result, or {@code null} when the agent answers {@code "result": null}
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 * @see AcpAsyncClient#sendExtRequest(String, Object, TypeRef)
	 */
	public <T> @Nullable T sendExtRequest(String method, Object params, TypeRef<T> resultType) {
		return SyncCalls.block(this.delegate.sendExtRequest(method, params, resultType));
	}

	/**
	 * Sends a custom extension request to the agent and blocks for its result, as the raw JSON
	 * value: a {@code Map}, {@code List}, {@code String}, {@code Number} or {@code Boolean}.
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @return the result, or {@code null} when the agent answers {@code "result": null}
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 * @see AcpAsyncClient#sendExtRequest(String, Object)
	 */
	public @Nullable Object sendExtRequest(String method, Object params) {
		return SyncCalls.block(this.delegate.sendExtRequest(method, params));
	}

	/**
	 * Sends a custom extension notification ({@code _}-prefixed method name) to the agent and
	 * returns once it has been handed to the transport. An agent without a handler for it ignores
	 * it.
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 */
	public void sendExtNotification(String method, Object params) {
		SyncCalls.block(this.delegate.sendExtNotification(method, params));
	}

	/**
	 * Blocks for the response to a request. A request's Mono emits the response or fails:
	 * the session delivers every result through a Reactor sink, which cannot carry null, so
	 * it never completes empty. An empty completion would be a broken invariant, reported
	 * as such rather than returned as a null response.
	 */
	private static <T> T awaitResponse(Mono<T> response) {
		T value = SyncCalls.block(response);
		if (value == null) {
			throw new IllegalStateException("ACP request completed without a response");
		}
		return value;
	}

}
