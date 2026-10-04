/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.ExtensionMethods;
import com.agentclientprotocol.sdk.spec.PromptTimeouts;
import com.agentclientprotocol.sdk.util.Assert;
import com.agentclientprotocol.sdk.util.HandlerFailures;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The entry point for writing an ACP agent with builders: {@link #sync(AcpAgentTransport)} and
 * {@link #async(AcpAgentTransport)} start a builder on an agent-side transport, you register one
 * handler for each ACP method the agent serves, and {@code build()} returns the agent. Use
 * {@code sync} for handlers that return plain values and may block, and {@code async} for handlers
 * that return Reactor {@link Mono}s. To write the agent as an annotated class instead, use
 * {@code AcpAgentSupport} from the {@code acp-agent-support} module.
 *
 * <p>The interface is not implemented. It holds the two builders, the handler interfaces they take,
 * and a few shared constants. The built agent is an {@link AcpSyncAgent} or an
 * {@link AcpAsyncAgent} that serves one connection, for example over
 * {@link com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport}. A listener transport,
 * which accepts many connections, takes an {@link AcpAgentFactory} that runs the builder once per
 * connection instead.
 *
 * <pre>{@code
 * AcpSyncAgent agent = AcpAgent.sync(new StdioAcpAgentTransport())
 *     .initializeHandler(request -> AcpSchema.InitializeResponse.ok())
 *     .newSessionHandler(request -> new AcpSchema.NewSessionResponse(
 *         UUID.randomUUID().toString(), null, null))
 *     .promptHandler((request, context) -> {
 *         context.sendMessage("Working on it...");
 *         return AcpSchema.PromptResponse.endTurn();
 *     })
 *     .build();
 * agent.run();
 * }</pre>
 *
 * <h2>Handlers</h2>
 *
 * <p>A request for a method without a handler is answered with JSON-RPC error {@code -32601}
 * (method not found), so register at least {@code initialize}, {@code session/new} and
 * {@code session/prompt}. A handler that fails is answered with an error: an
 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} with its own code and message,
 * anything else with {@code -32603} (internal error). A notification without a handler, or whose
 * handler fails, is logged and dropped.
 *
 * <h2>Prompt turns and cancellation</h2>
 *
 * <p>Each ACP session, named by its {@code sessionId}, has at most one prompt turn at a time: a
 * second {@code session/prompt} for a busy session is answered {@code -32600} (invalid request). A
 * {@code session/cancel} does not end the turn; the prompt's answer does, normally with stop reason
 * {@code cancelled}. The builders' {@code cancelGracePeriod} (60 seconds by default) and
 * {@code maxPromptDuration} (off by default) bound how long that can take: the SDK then cancels the
 * handler and answers for it. Both limits are Java SDK policy, not protocol. A
 * {@code $/cancel_request} from the client cancels the handler of the request it names, which is
 * answered {@code -32800} (request cancelled), or {@code cancelled} for a prompt already under
 * {@code session/cancel}.
 *
 * <h2>Timeouts and threads</h2>
 *
 * <p>Requests the agent sends the client wait at most 60 seconds unless the builder's
 * {@code requestTimeout} says otherwise, the same default as the client builders'. Asynchronous
 * handlers are called on the transport's thread and must not block. Synchronous handlers run on the
 * executor given to {@link SyncAgentBuilder#handlerExecutor}, by default a pool of daemon threads
 * the SDK shares between all synchronous agents in the JVM.
 *
 * <p>Not to be confused with the class annotation
 * {@link com.agentclientprotocol.sdk.annotation.AcpAgent}, which marks an annotated agent. Where a
 * file uses both, import one and write the other fully qualified.
 *
 * @author Mark Pollack
 * @see AcpSyncAgent
 * @see AcpAsyncAgent
 * @see AcpAgentTransport
 */
public interface AcpAgent {

	/**
	 * Starts a builder for an agent whose handlers return plain values and may block. The handlers
	 * run on the builder's handler executor ({@link SyncAgentBuilder#handlerExecutor}).
	 * @param transport the agent-side transport the agent serves, for example a
	 * {@link com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport}
	 * @return a new builder
	 * @throws IllegalArgumentException if {@code transport} is null
	 */
	static SyncAgentBuilder sync(AcpAgentTransport transport) {
		return new SyncAgentBuilder(transport);
	}

	/**
	 * Starts a builder for an agent whose handlers return Reactor {@link Mono}s and must not block.
	 * @param transport the agent-side transport the agent serves, for example a
	 * {@link com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport}
	 * @return a new builder
	 * @throws IllegalArgumentException if {@code transport} is null
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
	 *         context.sendUpdate(new AgentThoughtChunk(...));
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
	 *         context.sendUpdate(new AgentThoughtChunk(...));
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
	 * Configures and builds an {@link AcpAsyncAgent}: one handler for each ACP method the agent
	 * serves, each returning a Reactor {@link Mono}, plus the request timeout and the prompt
	 * timeouts. Get one from {@link AcpAgent#async(AcpAgentTransport)}. Use it when the handlers do
	 * not block; for handler code that blocks, use {@link SyncAgentBuilder}.
	 *
	 * <p>Handlers are called on the transport's thread that delivered the message, so they must not
	 * block: a handler that waits there holds up the whole connection. Return a {@code Mono} that
	 * completes later instead.
	 *
	 * <p>Each setter registers the handler for one ACP method. A null handler fails with
	 * {@link IllegalArgumentException}, and registering a method a second time fails with
	 * {@link IllegalStateException} naming the setter. A request handler's {@code Mono} must emit
	 * the response: a failed {@code Mono} is sent to the client as an error answer (an
	 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} with its own code, anything
	 * else as {@code -32603}), and an empty one is answered {@code -32603}. A builder is not
	 * thread-safe; configure it on one thread.
	 */
	class AsyncAgentBuilder {

		private final AcpAgentTransport transport;

		/** How long the agent waits for the client's answers unless {@code requestTimeout} is set. */
		private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

		private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

		private PromptTimeouts promptTimeouts = PromptTimeouts.DEFAULTS;

		private final AgentHandlers handlers = new AgentHandlers();

		private AcpSchema.@Nullable Implementation agentInfo;

		AsyncAgentBuilder(AcpAgentTransport transport) {
			Assert.notNull(transport, "Transport must not be null");
			this.transport = transport;
		}

		/**
		 * Sets how long the agent waits for the client to answer a request the agent sent: a file
		 * read or write, a permission or elicitation request, a terminal call or an extension
		 * request. When it passes, the call fails with a
		 * {@link java.util.concurrent.TimeoutException} and the agent sends the client a
		 * {@code $/cancel_request}. Default: 60 seconds.
		 * It does not limit the agent's own handlers; for prompts, see {@code maxPromptDuration}.
		 * @param timeout the timeout
		 * @return this builder
		 * @throws IllegalArgumentException if {@code timeout} is null
		 */
		public AsyncAgentBuilder requestTimeout(Duration timeout) {
			Assert.notNull(timeout, "Timeout must not be null");
			this.requestTimeout = timeout;
			return this;
		}

		/**
		 * Sets how long a prompt handler has to answer after {@code session/cancel}. When it
		 * passes, the agent cancels the handler and answers the prompt itself with stop reason
		 * {@code cancelled}, as ACP requires of a cancelled prompt; that ends the turn, so the
		 * session accepts a new prompt. Updates the handler sent before that answer reach the
		 * client first. Default: 60 seconds ({@link PromptTimeouts#DEFAULT_CANCEL_GRACE_PERIOD});
		 * {@link Duration#ZERO} turns it off, and a handler that never answers then keeps its
		 * session busy. This limit is Java SDK policy; ACP defines none.
		 * @param gracePeriod the grace period; zero for none
		 * @return this builder
		 * @throws IllegalArgumentException if {@code gracePeriod} is null or negative
		 */
		public AsyncAgentBuilder cancelGracePeriod(Duration gracePeriod) {
			this.promptTimeouts = this.promptTimeouts.withCancelGracePeriod(gracePeriod);
			return this;
		}

		/**
		 * Sets the agent's name and version, sent as {@code agentInfo} in the {@code initialize}
		 * response the agent answers when no initialize handler is registered. An initialize
		 * handler sets {@code agentInfo} in its own response.
		 * @param agentInfo the agent's name, version and optional title
		 * @return this builder
		 */
		public AsyncAgentBuilder agentInfo(AcpSchema.Implementation agentInfo) {
			Assert.notNull(agentInfo, "agentInfo must not be null");
			this.agentInfo = agentInfo;
			return this;
		}

		/**
		 * Sets how long a prompt may run. When it passes, the agent cancels the handler and answers
		 * the prompt with JSON-RPC error {@code -32800} (request cancelled; ACP answers an
		 * internally cancelled request, an internal timeout included, with this code), or with stop
		 * reason {@code cancelled} if the client had cancelled the prompt, and the turn ends.
		 * Default: none ({@link Duration#ZERO}), since a prompt turn can legitimately run for a
		 * long time. This limit is Java SDK policy; ACP defines none.
		 * @param maxDuration the maximum prompt duration; zero for none
		 * @return this builder
		 * @throws IllegalArgumentException if {@code maxDuration} is null or negative
		 */
		public AsyncAgentBuilder maxPromptDuration(Duration maxDuration) {
			this.promptTimeouts = this.promptTimeouts.withMaxPromptDuration(maxDuration);
			return this;
		}

		/**
		 * Sets the handler for {@code initialize}, the client's first request: it carries the
		 * protocol version and the client's capabilities, and the answer carries the agent's.
		 * {@link AcpSchema.InitializeResponse#ok()} is the simplest answer. The client's
		 * capabilities are recorded before the handler runs, so {@code getClientCapabilities()} on
		 * the agent already returns them inside it.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public AsyncAgentBuilder initializeHandler(InitializeHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_INITIALIZE, new TypeRef<AcpSchema.InitializeRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code authenticate}: the client logs in with one of the
		 * authentication methods the initialize answer listed. To refuse, fail with an
		 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} carrying
		 * {@link com.agentclientprotocol.sdk.error.AcpErrorCodes#AUTHENTICATION_REQUIRED}.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public AsyncAgentBuilder authenticateHandler(AuthenticateHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_AUTHENTICATE, new TypeRef<AcpSchema.AuthenticateRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code logout}, which ends the client's authenticated state.
		 * Advertise it in the initialize answer ({@code AgentAuthCapabilities.withLogout()});
		 * clients check for it before sending {@code logout}.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public AsyncAgentBuilder logoutHandler(LogoutHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_LOGOUT, new TypeRef<AcpSchema.LogoutRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code session/new}, which creates an ACP session for a working
		 * directory. The answer carries the new {@code sessionId}, and optionally the session's
		 * modes and config options.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public AsyncAgentBuilder newSessionHandler(NewSessionHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_SESSION_NEW, new TypeRef<AcpSchema.NewSessionRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code session/load}, which reopens a session the agent kept: the
		 * agent replays the conversation to the client as session updates, then answers. Advertise
		 * it with the {@code loadSession} agent capability.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public AsyncAgentBuilder loadSessionHandler(LoadSessionHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_SESSION_LOAD, new TypeRef<AcpSchema.LoadSessionRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code session/prompt}: one prompt turn. The handler receives the
		 * request and a {@link PromptContext} for sending updates and calling the client, and
		 * answers with a {@link AcpSchema.PromptResponse} whose stop reason ends the turn. The SDK
		 * keeps one turn per session at a time (a second prompt for a busy session is answered
		 * {@code -32600}), and applies the cancel grace period and the maximum prompt duration.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public AsyncAgentBuilder promptHandler(PromptHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_SESSION_PROMPT, new TypeRef<AcpSchema.PromptRequest>() {
			}, (request, agent) -> handler.handle(request, new DefaultPromptContext(agent, request.sessionId())));
		}

		/**
		 * Sets the handler for {@code session/set_mode}, which switches a session to one of the
		 * modes the agent offered.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public AsyncAgentBuilder setSessionModeHandler(SetSessionModeHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_SESSION_SET_MODE, new TypeRef<AcpSchema.SetSessionModeRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code session/list}, which lists the sessions the agent knows,
		 * optionally only those of one working directory, a page at a time.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public AsyncAgentBuilder listSessionsHandler(ListSessionsHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_SESSION_LIST, new TypeRef<AcpSchema.ListSessionsRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code session/close}, which ends an active session and frees what
		 * it holds. The session first cancels its work as for {@code session/cancel} (the cancel
		 * handler is called and a running prompt answers {@code cancelled}), then calls this
		 * handler.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public AsyncAgentBuilder closeSessionHandler(CloseSessionHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_SESSION_CLOSE, new TypeRef<AcpSchema.CloseSessionRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code session/delete}, which removes a stored session so that it no
		 * longer appears in {@code session/list}.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public AsyncAgentBuilder deleteSessionHandler(DeleteSessionHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_SESSION_DELETE, new TypeRef<AcpSchema.DeleteSessionRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code session/resume}, which reopens a session without replaying
		 * its history to the client.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public AsyncAgentBuilder resumeSessionHandler(ResumeSessionHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_SESSION_RESUME, new TypeRef<AcpSchema.ResumeSessionRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code session/fork}, which creates a new session branched from an
		 * existing one.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		@UnstableAcpApi
		public AsyncAgentBuilder forkSessionHandler(ForkSessionHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_SESSION_FORK, new TypeRef<AcpSchema.ForkSessionRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code session/set_config_option}, which changes one of a session's
		 * config options. Answer with the full list of the session's options, not only the changed
		 * one. The request's value is a {@code String} for a select option and a {@code Boolean}
		 * for a boolean option, and the SDK does not check it against the options offered: answer
		 * an unknown option or value with an
		 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} carrying
		 * {@link com.agentclientprotocol.sdk.error.AcpErrorCodes#INVALID_PARAMS}.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public AsyncAgentBuilder setSessionConfigOptionHandler(SetSessionConfigOptionHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_SESSION_SET_CONFIG_OPTION, new TypeRef<AcpSchema.SetSessionConfigOptionRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code providers/list}, which lists the providers the agent can
		 * route to.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		@UnstableAcpApi
		public AsyncAgentBuilder listProvidersHandler(ListProvidersHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_PROVIDERS_LIST, new TypeRef<AcpSchema.ListProvidersRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code providers/set}, which configures how the agent reaches a
		 * provider (protocol, base URL, headers).
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		@UnstableAcpApi
		public AsyncAgentBuilder setProviderHandler(SetProviderHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_PROVIDERS_SET, new TypeRef<AcpSchema.SetProviderRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code providers/disable}, which disables a provider by ID.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		@UnstableAcpApi
		public AsyncAgentBuilder disableProviderHandler(DisableProviderHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			return request(AcpSchema.METHOD_PROVIDERS_DISABLE, new TypeRef<AcpSchema.DisableProviderRequest>() {
			}, (request, agent) -> handler.handle(request));
		}

		/**
		 * Sets the handler for {@code session/cancel}, the client's notification that it wants a
		 * session's prompt turn to stop. The SDK marks the turn as cancelling before the handler
		 * runs. The handler should make the prompt's work stop; the prompt handler then answers
		 * with stop reason {@code cancelled}, which ends the turn. A notification gets no answer,
		 * so a handler that fails is only logged.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public AsyncAgentBuilder cancelHandler(CancelHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			handlers.notification(AcpSchema.METHOD_SESSION_CANCEL, new TypeRef<AcpSchema.CancelNotification>() {
			}, handler::handle);
			return this;
		}

		/**
		 * Registers the handler for a custom extension request ({@code _}-prefixed method name, ACP
		 * v1 Extensibility) from the client, its params read as the given type. A request for an
		 * extension method without a handler is answered with "Method not found" ({@code -32601}).
		 * The handler's {@code Mono} emits the result, any value the JSON mapper can write; an
		 * empty {@code Mono} answers the request with an internal error ({@code -32603}). Answer
		 * with an empty map when there is nothing to return.
		 * @param <T> the params type
		 * @param method the method name, which must start with {@code _} (for example
		 * {@code _example.com/workspace/buffers})
		 * @param paramsType the type the params are read as; must not be null
		 * @param handler the handler; must not be null
		 * @return this builder
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
		 * Registers the handler for a custom extension request ({@code _}-prefixed method name, ACP
		 * v1 Extensibility) from the client, its params delivered as the raw JSON value (a
		 * {@code Map}, {@code List}, {@code String}, {@code Number} or {@code Boolean}).
		 * @param method the method name, which must start with {@code _}
		 * @param handler the handler; must not be null
		 * @return this builder
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 * @see #extRequestHandler(String, TypeRef, ExtRequestHandler)
		 */
		public AsyncAgentBuilder extRequestHandler(String method, ExtRequestHandler<Object> handler) {
			return extRequestHandler(method, AgentHandlers.RAW_PARAMS, handler);
		}

		/**
		 * Registers the handler for a custom extension notification ({@code _}-prefixed method
		 * name) from the client, its params read as the given type. An extension notification
		 * without a handler is ignored, as the protocol asks, and a handler that fails is only
		 * logged.
		 * @param <T> the params type
		 * @param method the method name, which must start with {@code _}
		 * @param paramsType the type the params are read as; must not be null
		 * @param handler the handler; must not be null
		 * @return this builder
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
		 * Registers the handler for a custom extension notification ({@code _}-prefixed method
		 * name) from the client, its params delivered as the raw JSON value.
		 * @param method the method name, which must start with {@code _}
		 * @param handler the handler; must not be null
		 * @return this builder
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
		 * Builds the agent on the builder's transport, with the handlers registered so far;
		 * handlers registered afterwards do not reach it. The agent does nothing until
		 * {@link AcpAsyncAgent#start()}. A transport serves one agent, so build once per transport.
		 * Without an initialize handler, the agent answers {@code initialize} with the protocol
		 * version negotiated with the client and the capabilities its handlers imply
		 * ({@code loadSessionHandler} advertises {@code loadSession}, {@code listSessionsHandler}
		 * {@code sessionCapabilities.list}, {@code logoutHandler} {@code auth.logout}, and so on).
		 * @return the new agent
		 * @throws IllegalStateException if no prompt handler is registered
		 */
		public AcpAsyncAgent build() {
			java.util.Set<String> methods = handlers.requestMethods();
			if (!methods.contains(AcpSchema.METHOD_SESSION_PROMPT)) {
				throw new IllegalStateException("An agent needs a prompt handler: call promptHandler(..) before build()");
			}
			AgentHandlers built = handlers.copy();
			if (!methods.contains(AcpSchema.METHOD_INITIALIZE)) {
				built.request(AcpSchema.METHOD_INITIALIZE, new TypeRef<AcpSchema.InitializeRequest>() {
				}, (request, agent) -> Mono.just(DefaultInitialize.respond(methods, agentInfo, request)));
			}
			return new DefaultAcpAsyncAgent(transport, requestTimeout, promptTimeouts, built);
		}

	}

	/**
	 * Configures and builds an {@link AcpSyncAgent}: one handler for each ACP method the agent
	 * serves, each returning a plain value, plus the request timeout and the prompt timeouts. Get
	 * one from {@link AcpAgent#sync(AcpAgentTransport)}. Use it when handler code blocks, for
	 * example on file or network I/O, or on {@link SyncPromptContext} calls to the client.
	 *
	 * <p>Every handler runs on the builder's handler executor ({@link #handlerExecutor}), by
	 * default a pool of daemon threads shared by the synchronous agents in the JVM, not on the
	 * transport's thread, so it may block. Handlers for different requests run at the same time on
	 * different threads, so state they share must be thread-safe. The builder turns each handler
	 * into its asynchronous counterpart on an {@link AsyncAgentBuilder} and builds the agent from
	 * it, so the rules described there apply: a null handler or a second handler for a method fails
	 * at the setter, and a handler that throws is answered with an error. A request handler that
	 * returns {@code null} is answered {@code -32603} (internal error). A builder is not
	 * thread-safe; configure it on one thread.
	 *
	 * <pre>{@code
	 * AcpSyncAgent agent = AcpAgent.sync(transport)
	 *     .initializeHandler(request -> AcpSchema.InitializeResponse.ok())
	 *     .newSessionHandler(request -> new AcpSchema.NewSessionResponse(
	 *         UUID.randomUUID().toString(), null, null))
	 *     .promptHandler((request, context) -> {
	 *         context.sendThought("Thinking...");   // blocks until handed to the transport
	 *         context.sendMessage("Done");
	 *         return AcpSchema.PromptResponse.endTurn();
	 *     })
	 *     .build();
	 * }</pre>
	 */
	class SyncAgentBuilder {

		private final AsyncAgentBuilder asyncBuilder;

		/** Where the handlers run; read when a handler is called. */
		private Scheduler handlerScheduler = SyncHandlerScheduler.DEFAULT;

		SyncAgentBuilder(AcpAgentTransport transport) {
			this.asyncBuilder = new AsyncAgentBuilder(transport);
		}

		/**
		 * Sets the executor the handlers run on, for example
		 * {@code Executors.newVirtualThreadPerTaskExecutor()} or a framework's worker pool. Without
		 * it the handlers run on a pool of daemon threads named {@code acp-agent-sync-handler},
		 * shared by every synchronous agent in the JVM, which has no size limit: each handler
		 * running at the same time takes a thread of its own. The executor must allow blocking.
		 * The SDK cancels a handler by interrupting its thread (cancelling the task submitted to
		 * the executor), and never shuts the executor down; that is the application's job.
		 * @param executor the executor the handlers run on
		 * @return this builder
		 * @throws IllegalArgumentException if {@code executor} is null
		 */
		public SyncAgentBuilder handlerExecutor(ExecutorService executor) {
			Assert.notNull(executor, "Executor must not be null");
			this.handlerScheduler = Schedulers.fromExecutorService(executor, "acp-agent-handlers");
			return this;
		}

		/**
		 * Sets how long the agent waits for the client to answer a request the agent sent: a file
		 * read or write, a permission or elicitation request, a terminal call or an extension
		 * request. When it passes, the blocking call throws an
		 * {@link com.agentclientprotocol.sdk.error.AcpTimeoutException}, whose cause is the
		 * {@link java.util.concurrent.TimeoutException}, and the agent sends the client a
		 * {@code $/cancel_request}. Default: 60 seconds.
		 * It does not limit the agent's own handlers; for prompts, see {@code maxPromptDuration}.
		 * @param timeout the timeout
		 * @return this builder
		 * @throws IllegalArgumentException if {@code timeout} is null
		 */
		public SyncAgentBuilder requestTimeout(Duration timeout) {
			asyncBuilder.requestTimeout(timeout);
			return this;
		}

		/**
		 * Sets how long a prompt handler has to answer after {@code session/cancel}. When it
		 * passes, the agent cancels the handler, which interrupts its thread if it is blocked (what
		 * it sends if it keeps running is its own), and answers the prompt itself with stop reason
		 * {@code cancelled}, as ACP requires of a cancelled prompt; that ends the turn, so the
		 * session accepts a new prompt. Updates the handler sent before that answer reach the
		 * client first. Default: 60 seconds ({@link PromptTimeouts#DEFAULT_CANCEL_GRACE_PERIOD});
		 * {@link Duration#ZERO} turns it off, and a handler that never answers then keeps its
		 * session busy. This limit is Java SDK policy; ACP defines none.
		 * @param gracePeriod the grace period; zero for none
		 * @return this builder
		 * @throws IllegalArgumentException if {@code gracePeriod} is null or negative
		 */
		public SyncAgentBuilder cancelGracePeriod(Duration gracePeriod) {
			asyncBuilder.cancelGracePeriod(gracePeriod);
			return this;
		}

		/**
		 * Sets the agent's name and version, sent as {@code agentInfo} in the {@code initialize}
		 * response the agent answers when no initialize handler is registered. An initialize
		 * handler sets {@code agentInfo} in its own response.
		 * @param agentInfo the agent's name, version and optional title
		 * @return this builder
		 */
		public SyncAgentBuilder agentInfo(AcpSchema.Implementation agentInfo) {
			asyncBuilder.agentInfo(agentInfo);
			return this;
		}

		/**
		 * Sets how long a prompt may run. When it passes, the agent cancels the handler and answers
		 * the prompt with JSON-RPC error {@code -32800} (request cancelled; ACP answers an
		 * internally cancelled request, an internal timeout included, with this code), or with stop
		 * reason {@code cancelled} if the client had cancelled the prompt, and the turn ends.
		 * Default: none ({@link Duration#ZERO}), since a prompt turn can legitimately run for a
		 * long time. This limit is Java SDK policy; ACP defines none.
		 * @param maxDuration the maximum prompt duration; zero for none
		 * @return this builder
		 * @throws IllegalArgumentException if {@code maxDuration} is null or negative
		 */
		public SyncAgentBuilder maxPromptDuration(Duration maxDuration) {
			asyncBuilder.maxPromptDuration(maxDuration);
			return this;
		}

		/**
		 * Sets the handler for {@code initialize}, the client's first request: it carries the
		 * protocol version and the client's capabilities, and the answer carries the agent's.
		 * {@link AcpSchema.InitializeResponse#ok()} is the simplest answer. The client's
		 * capabilities are recorded before the handler runs, so {@code getClientCapabilities()} on
		 * the agent already returns them inside it.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public SyncAgentBuilder initializeHandler(SyncInitializeHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.initializeHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code authenticate}: the client logs in with one of the
		 * authentication methods the initialize answer listed. To refuse, fail with an
		 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} carrying
		 * {@link com.agentclientprotocol.sdk.error.AcpErrorCodes#AUTHENTICATION_REQUIRED}.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public SyncAgentBuilder authenticateHandler(SyncAuthenticateHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.authenticateHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code logout}, which ends the client's authenticated state.
		 * Advertise it in the initialize answer ({@code AgentAuthCapabilities.withLogout()});
		 * clients check for it before sending {@code logout}.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public SyncAgentBuilder logoutHandler(SyncLogoutHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.logoutHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code session/new}, which creates an ACP session for a working
		 * directory. The answer carries the new {@code sessionId}, and optionally the session's
		 * modes and config options.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public SyncAgentBuilder newSessionHandler(SyncNewSessionHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.newSessionHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code session/load}, which reopens a session the agent kept: the
		 * agent replays the conversation to the client as session updates, then answers. Advertise
		 * it with the {@code loadSession} agent capability.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public SyncAgentBuilder loadSessionHandler(SyncLoadSessionHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.loadSessionHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code session/prompt}: one prompt turn. The handler receives the
		 * request and a {@link SyncPromptContext} for sending updates and calling the client, may
		 * block, and returns a {@link AcpSchema.PromptResponse} whose stop reason ends the turn.
		 * The SDK keeps one turn per session at a time (a second prompt for a busy session is
		 * answered {@code -32600}), and applies the cancel grace period and the maximum prompt
		 * duration.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public SyncAgentBuilder promptHandler(SyncPromptHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.promptHandler((request, context) -> onSyncHandlerThread(
					() -> handler.handle(request, new DefaultSyncPromptContext(context))));
			return this;
		}

		/**
		 * Sets the handler for {@code session/set_mode}, which switches a session to one of the
		 * modes the agent offered.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public SyncAgentBuilder setSessionModeHandler(SyncSetSessionModeHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.setSessionModeHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code session/list}, which lists the sessions the agent knows,
		 * optionally only those of one working directory, a page at a time.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public SyncAgentBuilder listSessionsHandler(SyncListSessionsHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.listSessionsHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code session/close}, which ends an active session and frees what
		 * it holds. The session first cancels its work as for {@code session/cancel} (the cancel
		 * handler is called and a running prompt answers {@code cancelled}), then calls this
		 * handler.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public SyncAgentBuilder closeSessionHandler(SyncCloseSessionHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.closeSessionHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code session/delete}, which removes a stored session so that it no
		 * longer appears in {@code session/list}.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public SyncAgentBuilder deleteSessionHandler(SyncDeleteSessionHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.deleteSessionHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code session/resume}, which reopens a session without replaying
		 * its history to the client.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public SyncAgentBuilder resumeSessionHandler(SyncResumeSessionHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.resumeSessionHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code session/fork}, which creates a new session branched from an
		 * existing one.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		@UnstableAcpApi
		public SyncAgentBuilder forkSessionHandler(SyncForkSessionHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.forkSessionHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code session/set_config_option}, which changes one of a session's
		 * config options. Answer with the full list of the session's options, not only the changed
		 * one. The request's value is a {@code String} for a select option and a {@code Boolean}
		 * for a boolean option, and the SDK does not check it against the options offered: answer
		 * an unknown option or value with an
		 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} carrying
		 * {@link com.agentclientprotocol.sdk.error.AcpErrorCodes#INVALID_PARAMS}.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public SyncAgentBuilder setSessionConfigOptionHandler(SyncSetSessionConfigOptionHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.setSessionConfigOptionHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code providers/list}, which lists the providers the agent can
		 * route to.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		@UnstableAcpApi
		public SyncAgentBuilder listProvidersHandler(SyncListProvidersHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.listProvidersHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code providers/set}, which configures how the agent reaches a
		 * provider (protocol, base URL, headers).
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		@UnstableAcpApi
		public SyncAgentBuilder setProviderHandler(SyncSetProviderHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.setProviderHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code providers/disable}, which disables a provider by ID.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		@UnstableAcpApi
		public SyncAgentBuilder disableProviderHandler(SyncDisableProviderHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.disableProviderHandler(request -> onSyncHandlerThread(() -> handler.handle(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code session/cancel}, the client's notification that it wants a
		 * session's prompt turn to stop. The SDK marks the turn as cancelling before the handler
		 * runs. The handler runs on its own thread while the prompt handler is still running, so
		 * tell the prompt handler to stop through something thread-safe, such as an
		 * {@code AtomicBoolean}; the prompt handler then returns stop reason {@code cancelled},
		 * which ends the turn. A handler that throws is only logged.
		 * @param handler the handler; must not be null
		 * @return this builder
		 */
		public SyncAgentBuilder cancelHandler(SyncCancelHandler handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.cancelHandler(notification -> Mono.<Void>fromRunnable(HandlerFailures.guard(() -> handler.handle(notification)))
				.subscribeOn(this.handlerScheduler));
			return this;
		}

		/**
		 * Registers the handler for a custom extension request ({@code _}-prefixed method name, ACP
		 * v1 Extensibility) from the client, its params read as the given type. A request for an
		 * extension method without a handler is answered with "Method not found" ({@code -32601}).
		 * The handler returns the result, any value the JSON mapper can write; returning
		 * {@code null} answers the request with an internal error ({@code -32603}). Return an empty
		 * map when there is nothing to return.
		 * @param <T> the params type
		 * @param method the method name, which must start with {@code _} (for example
		 * {@code _example.com/workspace/buffers})
		 * @param paramsType the type the params are read as; must not be null
		 * @param handler the handler; must not be null
		 * @return this builder
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
		 * Registers the handler for a custom extension request ({@code _}-prefixed method name, ACP
		 * v1 Extensibility) from the client, its params delivered as the raw JSON value (a
		 * {@code Map}, {@code List}, {@code String}, {@code Number} or {@code Boolean}).
		 * @param method the method name, which must start with {@code _}
		 * @param handler the handler; must not be null
		 * @return this builder
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 * @see #extRequestHandler(String, TypeRef, SyncExtRequestHandler)
		 */
		public SyncAgentBuilder extRequestHandler(String method, SyncExtRequestHandler<Object> handler) {
			return extRequestHandler(method, AgentHandlers.RAW_PARAMS, handler);
		}

		/**
		 * Registers the handler for a custom extension notification ({@code _}-prefixed method
		 * name) from the client, its params read as the given type. An extension notification
		 * without a handler is ignored, as the protocol asks, and a handler that fails is only
		 * logged.
		 * @param <T> the params type
		 * @param method the method name, which must start with {@code _}
		 * @param paramsType the type the params are read as; must not be null
		 * @param handler the handler; must not be null
		 * @return this builder
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 * @see AsyncAgentBuilder#extNotificationHandler(String, TypeRef, ExtNotificationHandler)
		 */
		public <T> SyncAgentBuilder extNotificationHandler(String method, TypeRef<T> paramsType,
				SyncExtNotificationHandler<T> handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncBuilder.extNotificationHandler(method, paramsType,
					params -> Mono.<Void>fromRunnable(HandlerFailures.guard(() -> handler.handle(params))).subscribeOn(this.handlerScheduler));
			return this;
		}

		/**
		 * Registers the handler for a custom extension notification ({@code _}-prefixed method
		 * name) from the client, its params delivered as the raw JSON value.
		 * @param method the method name, which must start with {@code _}
		 * @param handler the handler; must not be null
		 * @return this builder
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 * @see #extNotificationHandler(String, TypeRef, SyncExtNotificationHandler)
		 */
		public SyncAgentBuilder extNotificationHandler(String method, SyncExtNotificationHandler<Object> handler) {
			return extNotificationHandler(method, AgentHandlers.RAW_PARAMS, handler);
		}

		/**
		 * Builds the agent on the builder's transport, with the handlers registered so far;
		 * handlers registered afterwards do not reach it. The agent does nothing until
		 * {@link AcpSyncAgent#start()} or {@link AcpSyncAgent#run()}. A transport serves one agent,
		 * so build once per transport. Without an initialize handler, the agent answers
		 * {@code initialize} with the capabilities its handlers imply (see
		 * {@link AsyncAgentBuilder#build()}).
		 * @return the new agent
		 * @throws IllegalStateException if no prompt handler is registered
		 */
		public AcpSyncAgent build() {
			return new AcpSyncAgent(asyncBuilder.build());
		}

		/**
		 * Runs a sync handler on the handler executor, so it may block (on
		 * {@link SyncPromptContext} calls back to the client, for one) without stalling the
		 * transport.
		 */
		private <T> Mono<T> onSyncHandlerThread(Callable<T> handler) {
			return Mono.fromCallable(HandlerFailures.guard(handler)).subscribeOn(this.handlerScheduler);
		}

	}

}
