/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.ExtensionMethods;
import com.agentclientprotocol.sdk.spec.PromptTimeouts;
import com.agentclientprotocol.sdk.util.Assert;
import com.agentclientprotocol.sdk.util.HandlerFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Factory class for creating Agent Client Protocol (ACP) agents. ACP agents
 * provide autonomous coding capabilities to clients (such as code editors)
 * through a standardized interface.
 *
 * <p>
 * This class serves as the main entry point for implementing ACP-compliant agents,
 * implementing the agent-side of the ACP specification. The protocol follows a
 * client-agent architecture where:
 * <ul>
 * <li>The agent (this implementation) responds to client requests and sends updates</li>
 * <li>The client connects to the agent and sends prompts</li>
 * <li>Communication occurs through a transport layer (e.g., stdio) using JSON-RPC 2.0</li>
 * </ul>
 *
 * <p>
 * The class provides factory methods to create either:
 * <ul>
 * <li>{@link AcpAsyncAgent} for non-blocking operations with Mono/Flux responses</li>
 * <li>{@link AcpSyncAgent} for blocking operations with direct responses</li>
 * </ul>
 *
 * <p>
 * Example of creating a basic asynchronous agent:
 *
 * <pre>{@code
 * AcpAsyncAgent agent = AcpAgent.async(transport)
 *     .requestTimeout(Duration.ofSeconds(30))
 *     .agentInfo(new AcpSchema.AgentCapabilities(true, null, null))
 *     .initializeHandler(request -> {
 *         return Mono.just(new AcpSchema.InitializeResponse(1,
 *             new AcpSchema.AgentCapabilities(), List.of()));
 *     })
 *     .newSessionHandler(request -> {
 *         return Mono.just(new AcpSchema.NewSessionResponse(
 *             "session-1", null, null));
 *     })
 *     .promptHandler((request, updater) -> {
 *         updater.sendUpdate(new AcpSchema.AgentMessageChunk(
 *             new AcpSchema.TextContent("Working on it...")));
 *         return Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
 *     })
 *     .build();
 *
 * agent.start().block();
 * }</pre>
 *
 * <p>
 * The agent supports:
 * <ul>
 * <li>Protocol version negotiation and capability exchange</li>
 * <li>Optional authentication with various methods</li>
 * <li>Session creation and management (including loadSession)</li>
 * <li>Prompt processing with streaming session updates</li>
 * <li>File system requests to client (read/write)</li>
 * <li>Permission requests for sensitive operations</li>
 * <li>Terminal operations for command execution</li>
 * <li>Custom extension requests and notifications ({@code _}-prefixed methods), both
 * directions</li>
 * </ul>
 *
 * @author Mark Pollack
 * @see AcpAsyncAgent
 * @see AcpAgentTransport
 */
public interface AcpAgent {

	Logger logger = LoggerFactory.getLogger(AcpAgent.class);

	/**
	 * Default request timeout duration.
	 */
	Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

	/**
	 * Library-owned scheduler for executing synchronous handlers.
	 * Uses daemon threads with descriptive names to prevent JVM hang on exit.
	 * This follows the best practice of never using global Schedulers.boundedElastic().
	 */
	Scheduler SYNC_HANDLER_SCHEDULER = Schedulers.fromExecutorService(
			Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "acp-agent-sync-handler");
				t.setDaemon(true);
				return t;
			}), "acp-agent-sync-handler");

	/**
	 * Start building a synchronous ACP agent with the specified transport layer.
	 * The synchronous agent provides blocking operations for simpler implementations.
	 * @param transport The transport layer to use for communication
	 * @return A builder for configuring the synchronous agent
	 */
	static SyncAgentBuilder sync(AcpAgentTransport transport) {
		return new SyncAgentBuilder(transport);
	}

	/**
	 * Start building an asynchronous ACP agent with the specified transport layer.
	 * The asynchronous agent provides non-blocking operations with Mono/Flux responses.
	 * @param transport The transport layer to use for communication
	 * @return A builder for configuring the asynchronous agent
	 */
	static AsyncAgentBuilder async(AcpAgentTransport transport) {
		return new AsyncAgentBuilder(transport);
	}

	/**
	 * Functional interface for handling initialize requests.
	 */
	@FunctionalInterface
	interface InitializeHandler {

		Mono<AcpSchema.InitializeResponse> handle(AcpSchema.InitializeRequest request);

	}

	/**
	 * Functional interface for handling authenticate requests.
	 */
	@FunctionalInterface
	interface AuthenticateHandler {

		Mono<AcpSchema.AuthenticateResponse> handle(AcpSchema.AuthenticateRequest request);

	}

	/**
	 * Functional interface for handling logout requests.
	 */
	@FunctionalInterface
	interface LogoutHandler {

		Mono<AcpSchema.LogoutResponse> handle(AcpSchema.LogoutRequest request);

	}

	/**
	 * Functional interface for handling new session requests.
	 */
	@FunctionalInterface
	interface NewSessionHandler {

		Mono<AcpSchema.NewSessionResponse> handle(AcpSchema.NewSessionRequest request);

	}

	/**
	 * Functional interface for handling load session requests.
	 */
	@FunctionalInterface
	interface LoadSessionHandler {

		Mono<AcpSchema.LoadSessionResponse> handle(AcpSchema.LoadSessionRequest request);

	}

	/**
	 * Functional interface for handling prompt requests with full agent context.
	 *
	 * <p>
	 * The handler receives a {@link PromptContext} that provides access to all agent
	 * capabilities including file operations, permission requests, terminal operations,
	 * and session updates.
	 *
	 * <p>Example usage:
	 * <pre>{@code
	 * AcpAgent.async(transport)
	 *     .promptHandler((request, context) -> {
	 *         // Read a file
	 *         var file = context.readTextFile(new ReadTextFileRequest(...)).block();
	 *
	 *         // Send progress update
	 *         context.sendUpdate(sessionId, new AgentThoughtChunk(...));
	 *
	 *         return Mono.just(new PromptResponse(StopReason.END_TURN));
	 *     })
	 *     .build();
	 * }</pre>
	 */
	@FunctionalInterface
	interface PromptHandler {

		/**
		 * Handles a prompt request with full access to agent capabilities.
		 * @param request The prompt request
		 * @param context Context providing all agent capabilities (file ops, permissions, updates, etc.)
		 * @return A Mono containing the prompt response
		 */
		Mono<AcpSchema.PromptResponse> handle(AcpSchema.PromptRequest request, PromptContext context);

	}

	/**
	 * Functional interface for handling set session mode requests.
	 */
	@FunctionalInterface
	interface SetSessionModeHandler {

		Mono<AcpSchema.SetSessionModeResponse> handle(AcpSchema.SetSessionModeRequest request);

	}

	/**
	 * Functional interface for handling list sessions requests.
	 */
	@FunctionalInterface
	interface ListSessionsHandler {

		Mono<AcpSchema.ListSessionsResponse> handle(AcpSchema.ListSessionsRequest request);

	}

	/**
	 * Functional interface for handling close session requests.
	 */
	@FunctionalInterface
	interface CloseSessionHandler {

		Mono<AcpSchema.CloseSessionResponse> handle(AcpSchema.CloseSessionRequest request);

	}

	/**
	 * Functional interface for handling delete session requests.
	 */
	@FunctionalInterface
	interface DeleteSessionHandler {

		Mono<AcpSchema.DeleteSessionResponse> handle(AcpSchema.DeleteSessionRequest request);

	}

	/**
	 * Functional interface for handling resume session requests.
	 */
	@FunctionalInterface
	interface ResumeSessionHandler {

		Mono<AcpSchema.ResumeSessionResponse> handle(AcpSchema.ResumeSessionRequest request);

	}

	/**
	 * Functional interface for handling fork session requests.
	 */
	@UnstableAcpApi
	@FunctionalInterface
	interface ForkSessionHandler {

		Mono<AcpSchema.ForkSessionResponse> handle(AcpSchema.ForkSessionRequest request);

	}

	/**
	 * Functional interface for handling set config option requests.
	 */
	@FunctionalInterface
	interface SetSessionConfigOptionHandler {

		Mono<AcpSchema.SetSessionConfigOptionResponse> handle(AcpSchema.SetSessionConfigOptionRequest request);

	}

	/**
	 * Functional interface for handling {@code providers/list} requests (UNSTABLE).
	 */
	@UnstableAcpApi
	@FunctionalInterface
	interface ListProvidersHandler {

		Mono<AcpSchema.ListProvidersResponse> handle(AcpSchema.ListProvidersRequest request);

	}

	/**
	 * Functional interface for handling {@code providers/set} requests (UNSTABLE).
	 */
	@UnstableAcpApi
	@FunctionalInterface
	interface SetProviderHandler {

		Mono<AcpSchema.SetProviderResponse> handle(AcpSchema.SetProviderRequest request);

	}

	/**
	 * Functional interface for handling {@code providers/disable} requests (UNSTABLE).
	 */
	@UnstableAcpApi
	@FunctionalInterface
	interface DisableProviderHandler {

		Mono<AcpSchema.DisableProviderResponse> handle(AcpSchema.DisableProviderRequest request);

	}

	/**
	 * Functional interface for handling cancel notifications.
	 *
	 * <p>
	 * A cancel does not end the prompt turn: stop the prompt's work, send any last
	 * updates, and answer the prompt with stop reason {@code cancelled}. The session stays
	 * busy, rejecting a new prompt, until that answer (or an error) is sent (ACP v1, prompt
	 * turn, Cancellation). If the prompt handler has not answered within the cancel grace
	 * period (60 seconds unless set with {@code cancelGracePeriod}), the agent cancels it
	 * and answers {@code cancelled} itself.
	 * </p>
	 */
	@FunctionalInterface
	interface CancelHandler {

		Mono<Void> handle(AcpSchema.CancelNotification notification);

	}

	/**
	 * Handles a custom extension request ({@code _}-prefixed method name, ACP v1
	 * Extensibility) from the client.
	 * @param <T> the type the params are read as; {@code Object} for the raw JSON value
	 * (a {@code Map}, {@code List}, {@code String}, {@code Number} or {@code Boolean})
	 */
	@FunctionalInterface
	interface ExtRequestHandler<T> {

		/**
		 * Answers the request.
		 * @param params the request's params; an omitted params arrives as an empty object
		 * @return the result, any value the JSON mapper can write. The Mono must not
		 * complete empty: the request is then answered with an internal error. Answer with
		 * an empty map when there is nothing to return.
		 */
		Mono<?> handle(T params);

	}

	/**
	 * Handles a custom extension notification ({@code _}-prefixed method name, ACP v1
	 * Extensibility) from the client.
	 * @param <T> the type the params are read as; {@code Object} for the raw JSON value
	 */
	@FunctionalInterface
	interface ExtNotificationHandler<T> {

		/**
		 * Handles the notification.
		 * @param params the notification's params; an omitted params arrives as an empty
		 * object
		 * @return a Mono that completes when the notification is handled
		 */
		Mono<Void> handle(T params);

	}

	// ========================================================================
	// Synchronous Handler Interfaces (for SyncAgentBuilder)
	// ========================================================================

	/**
	 * Synchronous functional interface for handling initialize requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncInitializeHandler {

		AcpSchema.InitializeResponse handle(AcpSchema.InitializeRequest request);

	}

	/**
	 * Synchronous functional interface for handling authenticate requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncAuthenticateHandler {

		AcpSchema.AuthenticateResponse handle(AcpSchema.AuthenticateRequest request);

	}

	/**
	 * Synchronous functional interface for handling logout requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncLogoutHandler {

		AcpSchema.LogoutResponse handle(AcpSchema.LogoutRequest request);

	}

	/**
	 * Synchronous functional interface for handling new session requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncNewSessionHandler {

		AcpSchema.NewSessionResponse handle(AcpSchema.NewSessionRequest request);

	}

	/**
	 * Synchronous functional interface for handling load session requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncLoadSessionHandler {

		AcpSchema.LoadSessionResponse handle(AcpSchema.LoadSessionRequest request);

	}

	/**
	 * Synchronous functional interface for handling prompt requests with full agent context.
	 *
	 * <p>
	 * The handler receives a {@link SyncPromptContext} that provides blocking access to all
	 * agent capabilities including file operations, permission requests, terminal operations,
	 * and session updates.
	 *
	 * <p>Example usage:
	 * <pre>{@code
	 * AcpAgent.sync(transport)
	 *     .promptHandler((request, context) -> {
	 *         // Read a file (blocks)
	 *         var file = context.readTextFile(new ReadTextFileRequest(...));
	 *
	 *         // Send progress update (blocks)
	 *         context.sendUpdate(sessionId, new AgentThoughtChunk(...));
	 *
	 *         return new PromptResponse(StopReason.END_TURN);
	 *     })
	 *     .build();
	 * }</pre>
	 */
	@FunctionalInterface
	interface SyncPromptHandler {

		/**
		 * Handles a prompt request with full access to agent capabilities.
		 * @param request The prompt request
		 * @param context Context providing blocking access to all agent capabilities
		 * @return The prompt response
		 */
		AcpSchema.PromptResponse handle(AcpSchema.PromptRequest request, SyncPromptContext context);

	}

	/**
	 * Synchronous functional interface for handling set session mode requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncSetSessionModeHandler {

		AcpSchema.SetSessionModeResponse handle(AcpSchema.SetSessionModeRequest request);

	}

	/**
	 * Synchronous functional interface for handling list sessions requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncListSessionsHandler {

		AcpSchema.ListSessionsResponse handle(AcpSchema.ListSessionsRequest request);

	}

	/**
	 * Synchronous functional interface for handling close session requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncCloseSessionHandler {

		AcpSchema.CloseSessionResponse handle(AcpSchema.CloseSessionRequest request);

	}

	/**
	 * Synchronous functional interface for handling delete session requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncDeleteSessionHandler {

		AcpSchema.DeleteSessionResponse handle(AcpSchema.DeleteSessionRequest request);

	}

	/**
	 * Synchronous functional interface for handling resume session requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncResumeSessionHandler {

		AcpSchema.ResumeSessionResponse handle(AcpSchema.ResumeSessionRequest request);

	}

	/**
	 * Synchronous functional interface for handling fork session requests.
	 */
	@UnstableAcpApi
	@FunctionalInterface
	interface SyncForkSessionHandler {

		AcpSchema.ForkSessionResponse handle(AcpSchema.ForkSessionRequest request);

	}

	/**
	 * Synchronous functional interface for handling set config option requests.
	 */
	@FunctionalInterface
	interface SyncSetSessionConfigOptionHandler {

		AcpSchema.SetSessionConfigOptionResponse handle(AcpSchema.SetSessionConfigOptionRequest request);

	}

	/**
	 * Synchronous functional interface for handling {@code providers/list} requests (UNSTABLE).
	 */
	@UnstableAcpApi
	@FunctionalInterface
	interface SyncListProvidersHandler {

		AcpSchema.ListProvidersResponse handle(AcpSchema.ListProvidersRequest request);

	}

	/**
	 * Synchronous functional interface for handling {@code providers/set} requests (UNSTABLE).
	 */
	@UnstableAcpApi
	@FunctionalInterface
	interface SyncSetProviderHandler {

		AcpSchema.SetProviderResponse handle(AcpSchema.SetProviderRequest request);

	}

	/**
	 * Synchronous functional interface for handling {@code providers/disable} requests (UNSTABLE).
	 */
	@UnstableAcpApi
	@FunctionalInterface
	interface SyncDisableProviderHandler {

		AcpSchema.DisableProviderResponse handle(AcpSchema.DisableProviderRequest request);

	}

	/**
	 * Synchronous functional interface for handling cancel notifications.
	 * Returns void instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncCancelHandler {

		void handle(AcpSchema.CancelNotification notification);

	}

	/**
	 * Synchronous {@link ExtRequestHandler}: returns the result instead of a Mono.
	 * @param <T> the type the params are read as; {@code Object} for the raw JSON value
	 */
	@FunctionalInterface
	interface SyncExtRequestHandler<T> {

		/**
		 * Answers the request.
		 * @param params the request's params; an omitted params arrives as an empty object
		 * @return the result, any value the JSON mapper can write; returning null answers
		 * the request with an internal error, so return an empty map when there is nothing
		 * to return
		 */
		Object handle(T params);

	}

	/**
	 * Synchronous {@link ExtNotificationHandler}.
	 * @param <T> the type the params are read as; {@code Object} for the raw JSON value
	 */
	@FunctionalInterface
	interface SyncExtNotificationHandler<T> {

		/**
		 * Handles the notification.
		 * @param params the notification's params; an omitted params arrives as an empty
		 * object
		 */
		void handle(T params);

	}

	/**
	 * Builder for creating asynchronous ACP agents.
	 */
	class AsyncAgentBuilder {

		private final AcpAgentTransport transport;

		private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

		private PromptTimeouts promptTimeouts = PromptTimeouts.DEFAULTS;

		private final AgentHandlers handlers = new AgentHandlers();

		AsyncAgentBuilder(AcpAgentTransport transport) {
			Assert.notNull(transport, "Transport must not be null");
			this.transport = transport;
		}

		/**
		 * Sets the timeout for requests sent to the client.
		 * @param timeout The request timeout duration
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder requestTimeout(Duration timeout) {
			Assert.notNull(timeout, "Timeout must not be null");
			this.requestTimeout = timeout;
			return this;
		}

		/**
		 * Sets how long a prompt handler has to answer after {@code session/cancel}. When it
		 * passes, the agent cancels the handler and answers the prompt itself with stop
		 * reason {@code cancelled}, as ACP requires of a cancelled prompt, which ends the
		 * turn so the session accepts a new prompt. Updates the handler sent before that
		 * answer reach the client first. Default: 60 seconds
		 * ({@link PromptTimeouts#DEFAULT_CANCEL_GRACE_PERIOD}); {@link Duration#ZERO} turns
		 * it off, and a handler that never answers then keeps its session busy.
		 * @param gracePeriod the grace period; zero for none, not negative
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder cancelGracePeriod(Duration gracePeriod) {
			this.promptTimeouts = this.promptTimeouts.withCancelGracePeriod(gracePeriod);
			return this;
		}

		/**
		 * Sets how long a prompt may run. When it passes, the agent cancels the handler and
		 * answers the prompt with JSON-RPC error {@code -32800} (request cancelled; ACP
		 * answers an internally cancelled request, an internal timeout included, with this
		 * code), or with stop reason {@code cancelled} if the client had cancelled the
		 * prompt, and the turn ends. Default: none ({@link Duration#ZERO}), since a prompt
		 * turn can legitimately run for a long time.
		 * @param maxDuration the maximum prompt duration; zero for none, not negative
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder maxPromptDuration(Duration maxDuration) {
			this.promptTimeouts = this.promptTimeouts.withMaxPromptDuration(maxDuration);
			return this;
		}

		/**
		 * Sets the handler for initialize requests.
		 * @param handler The initialize handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder initializeHandler(InitializeHandler handler) {
			return request(AcpSchema.METHOD_INITIALIZE, new TypeRef<AcpSchema.InitializeRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for authenticate requests.
		 * @param handler The authenticate handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder authenticateHandler(AuthenticateHandler handler) {
			return request(AcpSchema.METHOD_AUTHENTICATE, new TypeRef<AcpSchema.AuthenticateRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for logout requests.
		 * @param handler The logout handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder logoutHandler(LogoutHandler handler) {
			return request(AcpSchema.METHOD_LOGOUT, new TypeRef<AcpSchema.LogoutRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for new session requests.
		 * @param handler The new session handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder newSessionHandler(NewSessionHandler handler) {
			return request(AcpSchema.METHOD_SESSION_NEW, new TypeRef<AcpSchema.NewSessionRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for load session requests.
		 * @param handler The load session handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder loadSessionHandler(LoadSessionHandler handler) {
			return request(AcpSchema.METHOD_SESSION_LOAD, new TypeRef<AcpSchema.LoadSessionRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for prompt requests.
		 * @param handler The prompt handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder promptHandler(PromptHandler handler) {
			return request(AcpSchema.METHOD_SESSION_PROMPT, new TypeRef<AcpSchema.PromptRequest>() {
			}, (request, agent) -> handler.handle(request, new DefaultPromptContext(agent, request.sessionId())));
		}

		/**
		 * Sets the handler for set session mode requests.
		 * @param handler The set session mode handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder setSessionModeHandler(SetSessionModeHandler handler) {
			return request(AcpSchema.METHOD_SESSION_SET_MODE, new TypeRef<AcpSchema.SetSessionModeRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for list sessions requests.
		 * @param handler The list sessions handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder listSessionsHandler(ListSessionsHandler handler) {
			return request(AcpSchema.METHOD_SESSION_LIST, new TypeRef<AcpSchema.ListSessionsRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for close session requests.
		 * @param handler The close session handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder closeSessionHandler(CloseSessionHandler handler) {
			return request(AcpSchema.METHOD_SESSION_CLOSE, new TypeRef<AcpSchema.CloseSessionRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for delete session requests.
		 * @param handler The delete session handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder deleteSessionHandler(DeleteSessionHandler handler) {
			return request(AcpSchema.METHOD_SESSION_DELETE, new TypeRef<AcpSchema.DeleteSessionRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for resume session requests.
		 * @param handler The resume session handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder resumeSessionHandler(ResumeSessionHandler handler) {
			return request(AcpSchema.METHOD_SESSION_RESUME, new TypeRef<AcpSchema.ResumeSessionRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for fork session requests.
		 * @param handler The fork session handler
		 * @return This builder for chaining
		 */
		@UnstableAcpApi
		public AsyncAgentBuilder forkSessionHandler(ForkSessionHandler handler) {
			return request(AcpSchema.METHOD_SESSION_FORK, new TypeRef<AcpSchema.ForkSessionRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for set config option requests.
		 * @param handler The set config option handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder setSessionConfigOptionHandler(SetSessionConfigOptionHandler handler) {
			return request(AcpSchema.METHOD_SESSION_SET_CONFIG_OPTION, new TypeRef<AcpSchema.SetSessionConfigOptionRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code providers/list} requests (UNSTABLE).
		 * @param handler The list providers handler
		 * @return This builder for chaining
		 */
		@UnstableAcpApi
		public AsyncAgentBuilder listProvidersHandler(ListProvidersHandler handler) {
			return request(AcpSchema.METHOD_PROVIDERS_LIST, new TypeRef<AcpSchema.ListProvidersRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code providers/set} requests (UNSTABLE).
		 * @param handler The set provider handler
		 * @return This builder for chaining
		 */
		@UnstableAcpApi
		public AsyncAgentBuilder setProviderHandler(SetProviderHandler handler) {
			return request(AcpSchema.METHOD_PROVIDERS_SET, new TypeRef<AcpSchema.SetProviderRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code providers/disable} requests (UNSTABLE).
		 * @param handler The disable provider handler
		 * @return This builder for chaining
		 */
		@UnstableAcpApi
		public AsyncAgentBuilder disableProviderHandler(DisableProviderHandler handler) {
			return request(AcpSchema.METHOD_PROVIDERS_DISABLE, new TypeRef<AcpSchema.DisableProviderRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for cancel notifications.
		 * @param handler The cancel handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder cancelHandler(CancelHandler handler) {
			handlers.notification(AcpSchema.METHOD_SESSION_CANCEL, new TypeRef<AcpSchema.CancelNotification>() {
			}, handler::handle);
			return this;
		}

		/**
		 * Registers the handler for a custom extension request from the client, its params
		 * read as the given type. A request for an extension method without a handler is
		 * answered with "Method not found" (-32601).
		 * @param <T> the params type
		 * @param method the method name, which must start with {@code _}
		 * (e.g. {@code _example.com/workspace/buffers})
		 * @param paramsType the type the params are read as
		 * @param handler the handler
		 * @return This builder for chaining
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 */
		public <T> AsyncAgentBuilder extRequestHandler(String method, TypeRef<T> paramsType,
				ExtRequestHandler<T> handler) {
			ExtensionMethods.requireExtension(method);
			Assert.notNull(paramsType, "Params type must not be null");
			Assert.notNull(handler, "Handler must not be null");
			return request(method, paramsType, (params, agent) -> handler.handle(params));
		}

		/**
		 * Registers the handler for a custom extension request from the client, its params
		 * delivered as the raw JSON value.
		 * @param method the method name, which must start with {@code _}
		 * @param handler the handler
		 * @return This builder for chaining
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 * @see #extRequestHandler(String, TypeRef, ExtRequestHandler)
		 */
		public AsyncAgentBuilder extRequestHandler(String method, ExtRequestHandler<Object> handler) {
			return extRequestHandler(method, AgentHandlers.RAW_PARAMS, handler);
		}

		/**
		 * Registers the handler for a custom extension notification from the client, its
		 * params read as the given type. An extension notification without a handler is
		 * ignored, as the protocol asks.
		 * @param <T> the params type
		 * @param method the method name, which must start with {@code _}
		 * @param paramsType the type the params are read as
		 * @param handler the handler
		 * @return This builder for chaining
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 */
		public <T> AsyncAgentBuilder extNotificationHandler(String method, TypeRef<T> paramsType,
				ExtNotificationHandler<T> handler) {
			ExtensionMethods.requireExtension(method);
			Assert.notNull(paramsType, "Params type must not be null");
			Assert.notNull(handler, "Handler must not be null");
			handlers.notification(method, paramsType, handler::handle);
			return this;
		}

		/**
		 * Registers the handler for a custom extension notification from the client, its
		 * params delivered as the raw JSON value.
		 * @param method the method name, which must start with {@code _}
		 * @param handler the handler
		 * @return This builder for chaining
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 * @see #extNotificationHandler(String, TypeRef, ExtNotificationHandler)
		 */
		public AsyncAgentBuilder extNotificationHandler(String method, ExtNotificationHandler<Object> handler) {
			return extNotificationHandler(method, AgentHandlers.RAW_PARAMS, handler);
		}

		private <T> AsyncAgentBuilder request(String method, TypeRef<T> requestType,
				AgentHandlers.RequestHandler<T> handler) {
			handlers.request(method, requestType, handler);
			return this;
		}

		/**
		 * Builds the asynchronous ACP agent.
		 * @return A new AcpAsyncAgent instance
		 */
		public AcpAsyncAgent build() {
			return new DefaultAcpAsyncAgent(transport, requestTimeout, promptTimeouts, handlers);
		}

	}

	/**
	 * Builder for creating synchronous ACP agents.
	 * <p>
	 * This builder accepts synchronous handler interfaces that return plain values
	 * instead of Mono. Each is adapted to its async counterpart, which calls it with
	 * Mono.fromCallable on the library-owned {@link #SYNC_HANDLER_SCHEDULER}.
	 * </p>
	 *
	 * <p>Example usage:</p>
	 * <pre>{@code
	 * AcpSyncAgent agent = AcpAgent.sync(transport)
	 *     .initializeHandler(req -> new InitializeResponse(1, capabilities, List.of()))
	 *     .newSessionHandler(req -> new NewSessionResponse(sessionId, null, null))
	 *     .promptHandler((req, updater) -> {
	 *         updater.sendUpdate(sessionId, thought);  // blocks, void return
	 *         updater.sendUpdate(sessionId, message);  // blocks, void return
	 *         return new PromptResponse(StopReason.END_TURN);  // plain return
	 *     })
	 *     .build();
	 * }</pre>
	 */
	class SyncAgentBuilder {

		private final AsyncAgentBuilder asyncBuilder;

		SyncAgentBuilder(AcpAgentTransport transport) {
			this.asyncBuilder = new AsyncAgentBuilder(transport);
		}

		/**
		 * Sets the timeout for requests sent to the client.
		 * @param timeout The request timeout duration
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder requestTimeout(Duration timeout) {
			asyncBuilder.requestTimeout(timeout);
			return this;
		}

		/**
		 * Sets how long a prompt handler has to answer after {@code session/cancel}. When it
		 * passes, the agent cancels the handler (a sync handler blocked on its thread is interrupted; what it sends if it keeps running is its own) and answers the prompt itself with stop
		 * reason {@code cancelled}, as ACP requires of a cancelled prompt, which ends the
		 * turn so the session accepts a new prompt. Updates the handler sent before that
		 * answer reach the client first. Default: 60 seconds
		 * ({@link PromptTimeouts#DEFAULT_CANCEL_GRACE_PERIOD}); {@link Duration#ZERO} turns
		 * it off, and a handler that never answers then keeps its session busy.
		 * @param gracePeriod the grace period; zero for none, not negative
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder cancelGracePeriod(Duration gracePeriod) {
			asyncBuilder.cancelGracePeriod(gracePeriod);
			return this;
		}

		/**
		 * Sets how long a prompt may run. When it passes, the agent cancels the handler and
		 * answers the prompt with JSON-RPC error {@code -32800} (request cancelled; ACP
		 * answers an internally cancelled request, an internal timeout included, with this
		 * code), or with stop reason {@code cancelled} if the client had cancelled the
		 * prompt, and the turn ends. Default: none ({@link Duration#ZERO}), since a prompt
		 * turn can legitimately run for a long time.
		 * @param maxDuration the maximum prompt duration; zero for none, not negative
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder maxPromptDuration(Duration maxDuration) {
			asyncBuilder.maxPromptDuration(maxDuration);
			return this;
		}

		/**
		 * Sets the synchronous handler for initialize requests.
		 * @param handler The sync initialize handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder initializeHandler(SyncInitializeHandler handler) {
			asyncBuilder.initializeHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the synchronous handler for authenticate requests.
		 * @param handler The sync authenticate handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder authenticateHandler(SyncAuthenticateHandler handler) {
			asyncBuilder.authenticateHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the synchronous handler for logout requests.
		 * @param handler The sync logout handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder logoutHandler(SyncLogoutHandler handler) {
			asyncBuilder.logoutHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the synchronous handler for new session requests.
		 * @param handler The sync new session handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder newSessionHandler(SyncNewSessionHandler handler) {
			asyncBuilder.newSessionHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the synchronous handler for load session requests.
		 * @param handler The sync load session handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder loadSessionHandler(SyncLoadSessionHandler handler) {
			asyncBuilder.loadSessionHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the synchronous handler for prompt requests.
		 * @param handler The sync prompt handler (returns plain value, receives SyncPromptContext)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder promptHandler(SyncPromptHandler handler) {
			asyncBuilder.promptHandler((request, context) -> onSyncHandlerThread(
					() -> handler.handle(request, new DefaultSyncPromptContext(context))));
			return this;
		}

		/**
		 * Sets the synchronous handler for set session mode requests.
		 * @param handler The sync set session mode handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder setSessionModeHandler(SyncSetSessionModeHandler handler) {
			asyncBuilder.setSessionModeHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the synchronous handler for list sessions requests.
		 * @param handler The sync list sessions handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder listSessionsHandler(SyncListSessionsHandler handler) {
			asyncBuilder.listSessionsHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the synchronous handler for close session requests.
		 * @param handler The sync close session handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder closeSessionHandler(SyncCloseSessionHandler handler) {
			asyncBuilder.closeSessionHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the synchronous handler for delete session requests.
		 * @param handler The sync delete session handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder deleteSessionHandler(SyncDeleteSessionHandler handler) {
			asyncBuilder.deleteSessionHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the synchronous handler for resume session requests.
		 * @param handler The sync resume session handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder resumeSessionHandler(SyncResumeSessionHandler handler) {
			asyncBuilder.resumeSessionHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		@UnstableAcpApi
		public SyncAgentBuilder forkSessionHandler(SyncForkSessionHandler handler) {
			asyncBuilder.forkSessionHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		public SyncAgentBuilder setSessionConfigOptionHandler(SyncSetSessionConfigOptionHandler handler) {
			asyncBuilder.setSessionConfigOptionHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the synchronous handler for {@code providers/list} requests (UNSTABLE).
		 * @param handler The sync list providers handler
		 * @return This builder for chaining
		 */
		@UnstableAcpApi
		public SyncAgentBuilder listProvidersHandler(SyncListProvidersHandler handler) {
			asyncBuilder.listProvidersHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the synchronous handler for {@code providers/set} requests (UNSTABLE).
		 * @param handler The sync set provider handler
		 * @return This builder for chaining
		 */
		@UnstableAcpApi
		public SyncAgentBuilder setProviderHandler(SyncSetProviderHandler handler) {
			asyncBuilder.setProviderHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the synchronous handler for {@code providers/disable} requests (UNSTABLE).
		 * @param handler The sync disable provider handler
		 * @return This builder for chaining
		 */
		@UnstableAcpApi
		public SyncAgentBuilder disableProviderHandler(SyncDisableProviderHandler handler) {
			asyncBuilder.disableProviderHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the synchronous handler for cancel notifications.
		 * @param handler The sync cancel handler (returns void)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder cancelHandler(SyncCancelHandler handler) {
			asyncBuilder.cancelHandler(notification -> Mono.<Void>fromRunnable(HandlerFailures.guard(() -> handler.handle(notification)))
				.subscribeOn(SYNC_HANDLER_SCHEDULER));
			return this;
		}

		/**
		 * Registers the synchronous handler for a custom extension request from the
		 * client, its params read as the given type.
		 * @param <T> the params type
		 * @param method the method name, which must start with {@code _}
		 * @param paramsType the type the params are read as
		 * @param handler the handler
		 * @return This builder for chaining
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 * @see AsyncAgentBuilder#extRequestHandler(String, TypeRef, ExtRequestHandler)
		 */
		public <T> SyncAgentBuilder extRequestHandler(String method, TypeRef<T> paramsType,
				SyncExtRequestHandler<T> handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.extRequestHandler(method, paramsType,
					params -> onSyncHandlerThread(() -> handler.handle(params)));
			return this;
		}

		/**
		 * Registers the synchronous handler for a custom extension request from the
		 * client, its params delivered as the raw JSON value.
		 * @param method the method name, which must start with {@code _}
		 * @param handler the handler
		 * @return This builder for chaining
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 */
		public SyncAgentBuilder extRequestHandler(String method, SyncExtRequestHandler<Object> handler) {
			return extRequestHandler(method, AgentHandlers.RAW_PARAMS, handler);
		}

		/**
		 * Registers the synchronous handler for a custom extension notification from the
		 * client, its params read as the given type.
		 * @param <T> the params type
		 * @param method the method name, which must start with {@code _}
		 * @param paramsType the type the params are read as
		 * @param handler the handler
		 * @return This builder for chaining
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 * @see AsyncAgentBuilder#extNotificationHandler(String, TypeRef, ExtNotificationHandler)
		 */
		public <T> SyncAgentBuilder extNotificationHandler(String method, TypeRef<T> paramsType,
				SyncExtNotificationHandler<T> handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.extNotificationHandler(method, paramsType,
					params -> Mono.<Void>fromRunnable(HandlerFailures.guard(() -> handler.handle(params))).subscribeOn(SYNC_HANDLER_SCHEDULER));
			return this;
		}

		/**
		 * Registers the synchronous handler for a custom extension notification from the
		 * client, its params delivered as the raw JSON value.
		 * @param method the method name, which must start with {@code _}
		 * @param handler the handler
		 * @return This builder for chaining
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 */
		public SyncAgentBuilder extNotificationHandler(String method, SyncExtNotificationHandler<Object> handler) {
			return extNotificationHandler(method, AgentHandlers.RAW_PARAMS, handler);
		}

		/**
		 * Builds the synchronous ACP agent.
		 * @return A new AcpSyncAgent instance
		 */
		public AcpSyncAgent build() {
			return new AcpSyncAgent(asyncBuilder.build());
		}

		/**
		 * Runs a sync handler on the library-owned daemon scheduler, so it may block (on
		 * {@link SyncPromptContext} calls back to the client, for one) without stalling the
		 * transport.
		 */
		private static <T> Mono<T> onSyncHandlerThread(Callable<T> handler) {
			return Mono.fromCallable(HandlerFailures.guard(handler)).subscribeOn(SYNC_HANDLER_SCHEDULER);
		}

	}

}
