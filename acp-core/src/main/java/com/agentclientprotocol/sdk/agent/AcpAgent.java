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
 * an {@link com.agentclientprotocol.sdk.spec.AcpError} from a call to the client with the error it
 * carries, anything else with {@code -32603} (internal error, message "Internal error"). A notification without a handler, or whose
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
	 * Answers {@code initialize}, the client's first request on a connection: it carries the
	 * protocol version the client wants and the client's capabilities, and the answer carries the
	 * protocol version for the connection, the agent's capabilities, its authentication methods and
	 * its name. Register it with {@link AsyncAgentBuilder#initializeHandler} when the agent needs
	 * to choose that answer itself, for example to list authentication methods or advertise prompt
	 * capabilities.
	 *
	 * <p>The handler is optional. Without one, the built agent answers {@code initialize} itself,
	 * with the negotiated protocol version, the builder's {@code agentInfo} and the capabilities
	 * its other handlers imply ({@code session/load} advertises {@code loadSession},
	 * {@code session/list} advertises {@code sessionCapabilities.list}, {@code logout} advertises
	 * {@code auth.logout}, and so on). A handler's answer is sent as it is: nothing is derived from
	 * the other handlers, so it must advertise them itself, or clients will not call them. ACP asks
	 * the agent to answer with the client's protocol version if it supports it, and otherwise with
	 * the latest version it supports; the handler chooses the version, and the SDK does not check
	 * it. The client's capabilities are recorded before the handler runs
	 * ({@link AcpAsyncAgent#getClientCapabilities()}).
	 *
	 * <p>Implementations follow the rules on {@link PromptHandler}: they run on the transport's
	 * thread and must not block, return a {@code Mono} and never {@code null}, and fail with an
	 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} to answer with a chosen error.
	 */
	@FunctionalInterface
	interface InitializeHandler {

		/**
		 * Answers the client's {@code initialize} request.
		 * @param request the request, as the client sent it; never null
		 * @return a {@code Mono} that emits the agent's answer: protocol version, capabilities,
		 * authentication methods and agent info
		 */
		Mono<AcpSchema.InitializeResponse> handle(AcpSchema.InitializeRequest request);

	}

	/**
	 * Answers {@code authenticate}: the client logs in with one of the authentication methods the
	 * agent listed in its {@code initialize} answer, named by its ID. Register it with
	 * {@link AsyncAgentBuilder#authenticateHandler} when the agent lists authentication methods.
	 * Answer with an empty {@link AcpSchema.AuthenticateResponse} once the login has succeeded.
	 *
	 * <p>The SDK checks neither the method ID nor whether a client has logged in. The handler
	 * should reject an ID the agent did not list, and refuse a failed login by failing with an
	 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} carrying
	 * {@link com.agentclientprotocol.sdk.error.AcpErrorCodes#AUTHENTICATION_REQUIRED}. Handlers
	 * that need a login check for one themselves and fail the same way.
	 *
	 * <p>Implementations follow the rules on {@link PromptHandler}: they run on the transport's
	 * thread and must not block, return a {@code Mono} and never {@code null}, and fail with an
	 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} to answer with a chosen error.
	 */
	@FunctionalInterface
	interface AuthenticateHandler {

		/**
		 * Answers the client's {@code authenticate} request.
		 * @param request the request, as the client sent it; never null
		 * @return a {@code Mono} that emits the empty answer once the login has succeeded
		 */
		Mono<AcpSchema.AuthenticateResponse> handle(AcpSchema.AuthenticateRequest request);

	}

	/**
	 * Answers {@code logout}: the client ends its authenticated state with the agent. Register it
	 * with {@link AsyncAgentBuilder#logoutHandler}, and answer with an empty
	 * {@link AcpSchema.LogoutResponse} once the agent has dropped the login.
	 *
	 * <p>Clients send {@code logout} only to an agent that advertises {@code auth.logout}. The
	 * agent's default {@code initialize} answer advertises it whenever this handler is registered;
	 * an initialize handler must advertise it itself
	 * ({@link AcpSchema.AgentAuthCapabilities#withLogout()}).
	 *
	 * <p>Implementations follow the rules on {@link PromptHandler}: they run on the transport's
	 * thread and must not block, return a {@code Mono} and never {@code null}, and fail with an
	 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} to answer with a chosen error.
	 */
	@FunctionalInterface
	interface LogoutHandler {

		/**
		 * Answers the client's {@code logout} request.
		 * @param request the request, as the client sent it; never null
		 * @return a {@code Mono} that emits the empty answer once the login has been dropped
		 */
		Mono<AcpSchema.LogoutResponse> handle(AcpSchema.LogoutRequest request);

	}

	/**
	 * Answers {@code session/new}: the client opens an ACP session for a working directory (an
	 * absolute path) and names the MCP servers the agent should connect to. The answer carries the
	 * new session's ID, which every later request for the session names, and optionally the
	 * session's modes and config options. Register it with
	 * {@link AsyncAgentBuilder#newSessionHandler}.
	 *
	 * <p>ACP requires every agent to support {@code session/new}, but {@code build()} does not
	 * check for this handler: an agent built without one answers {@code session/new} with
	 * {@code -32601} (method not found), and no client can open a session. ACP requires the session
	 * ID to be unique.
	 *
	 * <p>Implementations follow the rules on {@link PromptHandler}: they run on the transport's
	 * thread and must not block, return a {@code Mono} and never {@code null}, and fail with an
	 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} to answer with a chosen error.
	 */
	@FunctionalInterface
	interface NewSessionHandler {

		/**
		 * Answers the client's {@code session/new} request.
		 * @param request the request, as the client sent it; never null
		 * @return a {@code Mono} that emits the new session's ID, and optionally its modes and
		 * config options
		 */
		Mono<AcpSchema.NewSessionResponse> handle(AcpSchema.NewSessionRequest request);

	}

	/**
	 * Answers {@code session/load}: the client reopens a session the agent kept, and the agent
	 * replays the whole conversation to the client before it answers. Register it with
	 * {@link AsyncAgentBuilder#loadSessionHandler}. To reopen a session without the replay, clients
	 * use {@code session/resume} instead ({@link ResumeSessionHandler}).
	 *
	 * <p>Unlike {@link PromptHandler}, it receives no context for sending updates. Send the replay
	 * as session updates through the built agent ({@link AcpAsyncAgent#sendSessionUpdate}), and
	 * complete the {@code Mono} only after they have been sent: ACP requires every update to
	 * precede the answer. Clients send {@code session/load} only to an agent that advertises
	 * {@code loadSession}; the default {@code initialize} answer does when this handler is
	 * registered.
	 *
	 * <p>Implementations follow the rules on {@link PromptHandler}: they run on the transport's
	 * thread and must not block, return a {@code Mono} and never {@code null}, and fail with an
	 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} to answer with a chosen error.
	 */
	@FunctionalInterface
	interface LoadSessionHandler {

		/**
		 * Answers the client's {@code session/load} request, after replaying the conversation.
		 * @param request the request, as the client sent it; never null
		 * @return a {@code Mono} that emits the answer, optionally with the session's modes and
		 * config options, once the replay has been sent
		 */
		Mono<AcpSchema.LoadSessionResponse> handle(AcpSchema.LoadSessionRequest request);

	}

	/**
	 * Answers {@code session/prompt}: one prompt turn of an ACP session. The handler receives the
	 * request (the session ID and the user's content blocks) and a {@link PromptContext} for
	 * sending session updates and calling the client during the turn, and returns a {@code Mono}
	 * that emits the {@link AcpSchema.PromptResponse} whose stop reason ends the turn. Register it
	 * with {@link AsyncAgentBuilder#promptHandler}; every agent needs one, and {@code build()}
	 * fails without it. For handler code that blocks, use {@link SyncPromptHandler} on the
	 * synchronous builder.
	 *
	 * <p>The SDK runs one turn per session at a time: a second prompt for a session whose turn has
	 * not ended is answered {@code -32600} (invalid request) without calling the handler. Send the
	 * turn's updates before the answer; once the prompt has been answered, its context drops
	 * further updates.
	 *
	 * <p>After {@code session/cancel} for the session, the context reports the cancel
	 * ({@link PromptContext#isCancelled()}, {@link PromptContext#whenCancelled()}): stop the work,
	 * send any last updates and answer {@link AcpSchema.PromptResponse#cancelled()}. If the handler
	 * has not answered within the builder's cancel grace period (60 seconds by default), the SDK
	 * disposes its {@code Mono} and answers {@code cancelled} itself; the builder's maximum prompt
	 * duration (off by default) bounds the whole turn the same way. While the prompt is cancelled,
	 * a failure that is a cancellation ({@link java.util.concurrent.CancellationException}, an
	 * interrupt, or an {@link com.agentclientprotocol.sdk.error.AcpProtocolException} with code
	 * {@code -32800}) is answered {@code cancelled}. Any other failure, such as the exception a
	 * model client throws when its call is aborted, is answered {@code -32603}; ACP requires
	 * {@code cancelled}, so catch it and answer {@code cancelled}.
	 *
	 * <pre>{@code
	 * AcpAgent.async(transport)
	 *     .promptHandler((request, context) -> model.stream(request.prompt())  // Flux<String>
	 *         .concatMap(context::sendMessage)
	 *         .takeUntilOther(context.whenCancelled())
	 *         .then(Mono.fromSupplier(() -> context.isCancelled()
	 *             ? AcpSchema.PromptResponse.cancelled()
	 *             : AcpSchema.PromptResponse.endTurn())))
	 *     .build();
	 * }</pre>
	 *
	 * <p>Implementations are called on the transport's thread that delivered the request and must
	 * not block it (no {@code block()} inside); return a {@code Mono} that completes later. One
	 * handler serves every session of the connection, and turns of different sessions can run at
	 * the same time, so state they share must be thread-safe. These rules hold for every handler
	 * interface of {@link AcpAgent}: the value the {@code Mono} emits is the result. A failure with
	 * an {@link com.agentclientprotocol.sdk.error.AcpProtocolException} is answered with its code,
	 * message and data, and one with the {@link com.agentclientprotocol.sdk.spec.AcpError} of a
	 * call to the client passes the client's error back unchanged. A
	 * {@link java.util.concurrent.CancellationException} is answered {@code -32800} (request
	 * cancelled). Any other failure, a throw or a {@code null} return included, is answered
	 * {@code -32603} with the message "Internal error" and logged at the agent. An empty
	 * {@code Mono} is answered {@code -32603} too.
	 */
	@FunctionalInterface
	interface PromptHandler {

		/**
		 * Runs one prompt turn and answers it.
		 * @param request the prompt: the session ID and the user's content blocks; never null
		 * @param context the turn's way back to the client: session updates, client requests and
		 * cancellation, for this turn
		 * @return a {@code Mono} that emits the response whose stop reason ends the turn, for
		 * example {@link AcpSchema.PromptResponse#endTurn()} or
		 * {@link AcpSchema.PromptResponse#cancelled()}
		 */
		Mono<AcpSchema.PromptResponse> handle(AcpSchema.PromptRequest request, PromptContext context);

	}

	/**
	 * Answers {@code session/set_mode}: the client switches a session to one of the modes the agent
	 * offered in the session's {@code modes} (for example "ask" or "code"). Register it with
	 * {@link AsyncAgentBuilder#setSessionModeHandler}, and answer with an empty
	 * {@link AcpSchema.SetSessionModeResponse} once the mode is in effect.
	 *
	 * <p>The SDK does not check the mode ID against the modes offered; answer an unknown one with
	 * an {@link com.agentclientprotocol.sdk.error.AcpProtocolException} carrying
	 * {@link com.agentclientprotocol.sdk.error.AcpErrorCodes#INVALID_PARAMS}. When the agent
	 * changes a session's mode on its own, it tells the client with a
	 * {@link AcpSchema.CurrentModeUpdate} session update instead.
	 *
	 * <p>Implementations follow the rules on {@link PromptHandler}: they run on the transport's
	 * thread and must not block, return a {@code Mono} and never {@code null}, and fail with an
	 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} to answer with a chosen error.
	 */
	@FunctionalInterface
	interface SetSessionModeHandler {

		/**
		 * Answers the client's {@code session/set_mode} request.
		 * @param request the request, as the client sent it; never null
		 * @return a {@code Mono} that emits the empty answer once the mode is in effect
		 */
		Mono<AcpSchema.SetSessionModeResponse> handle(AcpSchema.SetSessionModeRequest request);

	}

	/**
	 * Answers {@code session/list}: the client asks for the sessions the agent knows, optionally
	 * only those of one working directory, a page at a time. Register it with
	 * {@link AsyncAgentBuilder#listSessionsHandler}. Answer with a
	 * {@link AcpSchema.ListSessionsResponse} holding one page and, when more remain, an opaque
	 * cursor the client sends back for the next one.
	 *
	 * <p>Clients send {@code session/list} only to an agent that advertises
	 * {@code sessionCapabilities.list}; the default {@code initialize} answer does when this
	 * handler is registered. An agent with no sessions answers an empty list, and ACP asks for an
	 * error answer to a cursor the agent does not recognize.
	 *
	 * <p>Implementations follow the rules on {@link PromptHandler}: they run on the transport's
	 * thread and must not block, return a {@code Mono} and never {@code null}, and fail with an
	 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} to answer with a chosen error.
	 */
	@FunctionalInterface
	interface ListSessionsHandler {

		/**
		 * Answers the client's {@code session/list} request.
		 * @param request the request, as the client sent it; never null
		 * @return a {@code Mono} that emits one page of sessions and, when more remain, the next
		 * cursor
		 */
		Mono<AcpSchema.ListSessionsResponse> handle(AcpSchema.ListSessionsRequest request);

	}

	/**
	 * Answers {@code session/close}: the client ends an active session, and the agent frees what
	 * the session holds. Register it with {@link AsyncAgentBuilder#closeSessionHandler}, and answer
	 * with an empty {@link AcpSchema.CloseSessionResponse}.
	 *
	 * <p>The SDK first cancels the session's work, as ACP requires: it calls the
	 * {@link CancelHandler} if one is registered, cancels a running prompt (whose context reports
	 * the cancel), and waits for that prompt's turn to end. Only then does it call this handler.
	 * Clients send {@code session/close} only to an agent that advertises
	 * {@code sessionCapabilities.close}; the default {@code initialize} answer does when this
	 * handler is registered.
	 *
	 * <p>Implementations follow the rules on {@link PromptHandler}: they run on the transport's
	 * thread and must not block, return a {@code Mono} and never {@code null}, and fail with an
	 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} to answer with a chosen error.
	 */
	@FunctionalInterface
	interface CloseSessionHandler {

		/**
		 * Answers the client's {@code session/close} request, after the session's work has been
		 * cancelled.
		 * @param request the request, as the client sent it; never null
		 * @return a {@code Mono} that emits the empty answer once the session's resources are freed
		 */
		Mono<AcpSchema.CloseSessionResponse> handle(AcpSchema.CloseSessionRequest request);

	}

	/**
	 * Answers {@code session/delete}: the client removes a stored session for good, so that
	 * {@code session/list} no longer returns it. Register it with
	 * {@link AsyncAgentBuilder#deleteSessionHandler}, and answer with an empty
	 * {@link AcpSchema.DeleteSessionResponse}.
	 *
	 * <p>Deleting is not closing: unlike for {@link CloseSessionHandler}, the SDK does not cancel
	 * the session's work first. Clients send {@code session/delete} only to an agent that
	 * advertises {@code sessionCapabilities.delete}; the default {@code initialize} answer does
	 * when this handler is registered.
	 *
	 * <p>Implementations follow the rules on {@link PromptHandler}: they run on the transport's
	 * thread and must not block, return a {@code Mono} and never {@code null}, and fail with an
	 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} to answer with a chosen error.
	 */
	@FunctionalInterface
	interface DeleteSessionHandler {

		/**
		 * Answers the client's {@code session/delete} request.
		 * @param request the request, as the client sent it; never null
		 * @return a {@code Mono} that emits the empty answer once the session is deleted
		 */
		Mono<AcpSchema.DeleteSessionResponse> handle(AcpSchema.DeleteSessionRequest request);

	}

	/**
	 * Answers {@code session/resume}: the client reopens a session the agent kept, without a replay
	 * of its history. The agent restores the session, connects to the MCP servers the request
	 * names, and answers once the session can take prompts. Register it with
	 * {@link AsyncAgentBuilder#resumeSessionHandler}. To reopen a session with the replay, clients
	 * use {@code session/load} ({@link LoadSessionHandler}).
	 *
	 * <p>ACP forbids sending the history as session updates before the answer. Clients send
	 * {@code session/resume} only to an agent that advertises {@code sessionCapabilities.resume};
	 * the default {@code initialize} answer does when this handler is registered.
	 *
	 * <p>Implementations follow the rules on {@link PromptHandler}: they run on the transport's
	 * thread and must not block, return a {@code Mono} and never {@code null}, and fail with an
	 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} to answer with a chosen error.
	 */
	@FunctionalInterface
	interface ResumeSessionHandler {

		/**
		 * Answers the client's {@code session/resume} request.
		 * @param request the request, as the client sent it; never null
		 * @return a {@code Mono} that emits the answer, optionally with the session's modes and
		 * config options, once the session is ready
		 */
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
	 * Answers {@code session/set_config_option}: the client changes one of a session's config
	 * options (a select or a boolean the agent offered). Register it with
	 * {@link AsyncAgentBuilder#setSessionConfigOptionHandler}. Answer with a
	 * {@link AcpSchema.SetSessionConfigOptionResponse} that lists all of the session's config
	 * options with their current values, not only the changed one.
	 *
	 * <p>The request's value is a {@code String} for a select option and a {@code Boolean} for a
	 * boolean option, and the SDK does not check it against the options offered: answer an unknown
	 * option or value with an {@link com.agentclientprotocol.sdk.error.AcpProtocolException}
	 * carrying {@link com.agentclientprotocol.sdk.error.AcpErrorCodes#INVALID_PARAMS}. When the
	 * agent changes an option on its own, it tells the client with a
	 * {@link AcpSchema.ConfigOptionUpdate} session update instead.
	 *
	 * <p>Implementations follow the rules on {@link PromptHandler}: they run on the transport's
	 * thread and must not block, return a {@code Mono} and never {@code null}, and fail with an
	 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} to answer with a chosen error.
	 */
	@FunctionalInterface
	interface SetSessionConfigOptionHandler {

		/**
		 * Answers the client's {@code session/set_config_option} request.
		 * @param request the request, as the client sent it; never null
		 * @return a {@code Mono} that emits every config option of the session, with its current
		 * value
		 */
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
	 * Receives {@code session/cancel}: the client's notification that it wants a session's prompt
	 * turn to stop. Register it with {@link AsyncAgentBuilder#cancelHandler} when work outside the
	 * prompt handler's {@code Mono} must be stopped, for example a separate process. Most agents
	 * need none: the running prompt's {@link PromptContext} already reports the cancel.
	 *
	 * <p>Before the handler runs, the SDK marks the session's turn as cancelling and its prompt
	 * context as cancelled. A cancel does not end the turn: the prompt handler does, by answering
	 * with stop reason {@code cancelled}, and until then the session refuses a new prompt. If the
	 * prompt handler has not answered within the cancel grace period (60 seconds unless set with
	 * {@code cancelGracePeriod}), the SDK cancels it and answers {@code cancelled} itself. The
	 * handler is called for every {@code session/cancel}, also for a session with no prompt
	 * running, and when the client closes a session ({@code session/close}), before the
	 * {@link CloseSessionHandler}.
	 *
	 * <p>Implementations run on the transport's thread and must not block it. A notification gets
	 * no answer: a handler that fails is logged and the failure dropped.
	 */
	@FunctionalInterface
	interface CancelHandler {

		/**
		 * Handles a {@code session/cancel} for one session.
		 * @param notification the cancel, naming the session; never null
		 * @return a {@code Mono} that completes when the handler has done its part
		 */
		Mono<Void> handle(AcpSchema.CancelNotification notification);

	}

	/**
	 * Answers a custom extension request from the client: a method whose name starts with {@code _}
	 * (ACP v1 Extensibility), such as {@code _example.com/workspace/buffers}. Register it with
	 * {@link AsyncAgentBuilder#extRequestHandler(String, TypeRef, ExtRequestHandler)} to read the
	 * params as a type of your own, or with
	 * {@link AsyncAgentBuilder#extRequestHandler(String, ExtRequestHandler)} to get the raw JSON
	 * value. A request for an extension method without a handler is answered {@code -32601} (method
	 * not found).
	 *
	 * <p>Unlike the protocol handlers, it has no schema record: the params are converted to
	 * {@code T} but not checked, and the result can be any value the JSON mapper can write.
	 * Implementations follow the rules on {@link PromptHandler}: they run on the transport's thread
	 * and must not block, return a {@code Mono} and never {@code null}, and fail with an
	 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} to answer with a chosen error.
	 * @param <T> the type the params are read as; {@code Object} for the raw JSON value (a
	 * {@code Map}, {@code List}, {@code String}, {@code Number} or {@code Boolean})
	 */
	@FunctionalInterface
	interface ExtRequestHandler<T> {

		/**
		 * Answers the request.
		 * @param params the request's params; an omitted params arrives as an empty object
		 * @return a {@code Mono} that emits the result, any value the JSON mapper can write; an
		 * empty {@code Mono} is answered with an internal error ({@code -32603}), so emit an empty
		 * map when there is nothing to return
		 */
		Mono<?> handle(T params);

	}

	/**
	 * Receives a custom extension notification from the client: a method whose name starts with
	 * {@code _} (ACP v1 Extensibility). Register it with
	 * {@link AsyncAgentBuilder#extNotificationHandler(String, TypeRef, ExtNotificationHandler)} to
	 * read the params as a type of your own, or with
	 * {@link AsyncAgentBuilder#extNotificationHandler(String, ExtNotificationHandler)} to get the
	 * raw JSON value. An extension notification without a handler is ignored, as ACP asks.
	 *
	 * <p>Implementations run on the transport's thread and must not block it. A notification gets
	 * no answer: a handler that fails is logged and the failure dropped.
	 * @param <T> the type the params are read as; {@code Object} for the raw JSON value
	 */
	@FunctionalInterface
	interface ExtNotificationHandler<T> {

		/**
		 * Handles the notification.
		 * @param params the notification's params; an omitted params arrives as an empty object
		 * @return a {@code Mono} that completes when the notification is handled
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
		 * passes, the agent cancels the handler, which interrupts its thread if it is blocked (updates
		 * it sends through its prompt context if it keeps running are dropped), and answers the
		 * prompt itself with stop reason
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
