/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpClientSession;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSession;
import com.agentclientprotocol.sdk.spec.ExtensionMethods;
import com.agentclientprotocol.sdk.util.Assert;
import com.agentclientprotocol.sdk.util.HandlerFailures;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The entry point for an ACP client, the side that starts or connects to an agent and sends it
 * prompts: {@link #sync(AcpClientTransport)} and {@link #async(AcpClientTransport)} start a builder
 * on a client transport, you set the client's capabilities and the handlers for the agent's
 * requests, and {@code build()} returns the client. Use {@code sync} for blocking code and
 * {@link AcpSyncClient}; use {@code async} for Reactor code and {@link AcpAsyncClient}.
 *
 * <p>The interface is not implemented. It holds the two builders, {@link SyncRequestHandler}, and
 * the scheduler synchronous handlers run on. Building a client connects its transport, which for
 * {@link com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport} starts the agent
 * process; then call {@code initialize()}, create a session and send prompts. A transport carries
 * one client.
 *
 * <pre>{@code
 * AgentParameters params = AgentParameters.builder("my-agent").arg("--acp").build();
 * var transport = new StdioAcpClientTransport(params, AcpJsonMapper.createDefault());
 * try (AcpSyncClient client = AcpClient.sync(transport)
 *         .requestTimeout(Duration.ofMinutes(5))
 *         .sessionUpdateHandler(notification -> System.out.println(notification.update()))
 *         .build()) {
 *     client.initialize();
 *     String sessionId = client
 *         .newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()))
 *         .sessionId();
 *     AcpSchema.PromptResponse response = client.prompt(new AcpSchema.PromptRequest(sessionId,
 *         List.of(new AcpSchema.TextContent("Fix the failing test"))));
 *     System.out.println(response.stopReason());
 * }
 * }</pre>
 *
 * <h2>The agent's requests</h2>
 *
 * <p>The agent calls back into the client for files ({@code fs/*}), terminals ({@code terminal/*}),
 * permission and elicitation. Register a handler for each one you support, and advertise the
 * matching capabilities with {@code clientCapabilities(...)}: the builder does not derive them from
 * the handlers, but {@code build()} fails when a capability is advertised without its handlers and
 * warns about a handler whose capability is not advertised. A request without a handler is
 * answered {@code -32601} (method not found). A request reaches its handler once the session
 * updates the agent sent before it have been handled (see below); handlers do not wait for each
 * other. Asynchronous handlers are called on the thread that delivered the request or finished the
 * last of those updates, and must not block; synchronous handlers run on the executor given to
 * {@link SyncSpec#handlerExecutor}, by default a virtual thread per call on JDK 21 and later, and
 * before that a pool of daemon threads the SDK shares between all synchronous clients in the JVM. A handler that fails is answered with an error: an
 * {@link AcpProtocolException} with its own code, an {@code AcpError} from a call to the agent
 * with the error it carries, anything else with {@code -32603} (internal error, message
 * "Internal error").
 *
 * <h2>Session updates and ordering</h2>
 *
 * <p>{@code session/update} notifications go to the session update handlers one at a time, in the
 * order the agent sent them. A response completes its caller only after every notification received
 * before it has been handled, so when {@code prompt} returns, the turn's updates have all been
 * handled. In the same way, a request from the agent reaches its handler only after the updates the
 * agent sent before it, so a permission request comes after the {@code tool_call} update that
 * announced the tool call. A handler must therefore not wait for a prompt in flight to complete.
 * The one exception: a handler that is itself waiting for an answer to a request it sent does not
 * hold back the agent's requests or that answer.
 *
 * <h2>Timeouts and cancellation</h2>
 *
 * <p>Requests wait at most 60 seconds unless the builder's {@code requestTimeout} says otherwise.
 * A prompt is the exception: its answer comes only at the end of its turn, so it waits as long as
 * the turn takes unless the builder's {@code promptTimeout} sets a limit. When a timeout passes, or
 * the caller disposes a request's {@code Mono}, the client sends the agent a
 * {@code $/cancel_request} and the call fails; a Java agent then cancels the handler, which for a
 * prompt ends the turn. To stop a turn and still receive its answer, send {@code session/cancel}
 * with {@code cancel(...)}, or put
 * {@link com.agentclientprotocol.sdk.spec.RequestCancellation#cancelWhen} in the request's context.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 * @see AcpAsyncClient
 * @see AcpSyncClient
 * @see AcpClientTransport
 */
public interface AcpClient {

	// ====================================================================
	// Sync Handler Interfaces (for use with AcpClient.sync())
	// ====================================================================

	/**
	 * A blocking handler for one agent-to-client request method, registered with
	 * {@link SyncSpec#requestHandler(String, SyncRequestHandler)} for a method the typed setters do
	 * not cover. It receives the params as the transport read them and returns the result. Prefer
	 * the typed setters for ACP methods and {@code extRequestHandler} for extension methods, which
	 * read the params into a type for you.
	 * @param <T> the result type
	 */
	@FunctionalInterface
	interface SyncRequestHandler<T> {
		/**
		 * Answers one request from the agent. It runs on the sync builder's handler executor
		 * and may block.
		 * @param params the request's params as the transport read them, usually a {@code Map}; an
		 * omitted params arrives as an empty object
		 * @return the result, any value the JSON mapper can write; {@code null} answers the request
		 * with an internal error ({@code -32603})
		 */
		T handle(Object params);
	}

	/**
	 * Starts a builder for a client with blocking calls ({@link AcpSyncClient}) and blocking
	 * handlers, which run on the builder's handler executor.
	 * @param transport the client transport, for example a
	 * {@link com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport}, which starts
	 * the agent process
	 * @return a new builder
	 * @throws IllegalArgumentException if {@code transport} is null
	 */
	static SyncSpec sync(AcpClientTransport transport) {
		return new SyncSpec(transport);
	}

	/**
	 * Starts a builder for a client whose calls return Reactor {@code Mono}s
	 * ({@link AcpAsyncClient}) and whose handlers return {@code Mono}s and must not block.
	 * @param transport the client transport, for example a
	 * {@link com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport}, which starts
	 * the agent process
	 * @return a new builder
	 * @throws IllegalArgumentException if {@code transport} is null
	 */
	static AsyncSpec async(AcpClientTransport transport) {
		return new AsyncSpec(transport);
	}

	/**
	 * Configures and builds an {@link AcpAsyncClient}: the client's capabilities and info, the
	 * request timeout, the session update handlers, and the handlers for the agent's requests and
	 * notifications, each returning a Reactor {@code Mono}. Get one from
	 * {@link AcpClient#async(AcpClientTransport)}. For blocking handlers, use {@link SyncSpec}.
	 *
	 * <p>Handlers are called on the transport's thread and must not block: return a {@code Mono}
	 * that completes later instead. A method takes one handler: registering a second one, with the
	 * same setter or another, throws {@link IllegalStateException}, and a null handler throws
	 * {@link IllegalArgumentException}. A request
	 * handler's {@code Mono} must emit the answer: an empty one is answered {@code -32603}.
	 * {@link #build()} connects the transport. A builder is not thread-safe; configure it on one
	 * thread.
	 */
	class AsyncSpec {

		private static final Logger logger = LoggerFactory.getLogger(AcpClient.class);

		/** Reads params as the raw JSON value, for the untyped extension handlers. */
		private static final TypeRef<Object> RAW_PARAMS = new TypeRef<>() {
		};

		/** The typed setter of each agent-to-client request the SDK models. */
		private static final Map<String, String> TYPED_REQUEST_SETTERS = Map.of(
				AcpSchema.METHOD_FS_READ_TEXT_FILE, "readTextFileHandler",
				AcpSchema.METHOD_FS_WRITE_TEXT_FILE, "writeTextFileHandler",
				AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, "requestPermissionHandler",
				AcpSchema.METHOD_TERMINAL_CREATE, "createTerminalHandler",
				AcpSchema.METHOD_TERMINAL_OUTPUT, "terminalOutputHandler",
				AcpSchema.METHOD_TERMINAL_RELEASE, "releaseTerminalHandler",
				AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT, "waitForTerminalExitHandler",
				AcpSchema.METHOD_TERMINAL_KILL, "killTerminalHandler",
				AcpSchema.METHOD_ELICITATION_CREATE, "createElicitationHandler");

		/** The typed setter of each agent-to-client notification the SDK models. */
		private static final Map<String, String> TYPED_NOTIFICATION_SETTERS = Map.of(
				AcpSchema.METHOD_SESSION_UPDATE, "sessionUpdateHandler",
				AcpSchema.METHOD_ELICITATION_COMPLETE, "completeElicitationHandler");

		private final AcpClientTransport transport;

		/** The SDK's one default request timeout, the same as the agent builders'. */
		private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

		private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

		/** How long a prompt turn may take; null for no limit (the default). */
		private @Nullable Duration promptTimeout;

		private AcpSchema.@Nullable ClientCapabilities clientCapabilities;

		private AcpSchema.@Nullable Implementation clientInfo;

		private final Map<String, AcpClientSession.RequestHandler<?>> requestHandlers = new HashMap<>();

		private final Map<String, AcpClientSession.NotificationHandler> notificationHandlers = new HashMap<>();

		private final List<Function<AcpSchema.SessionNotification, Mono<Void>>> sessionUpdateHandlers = new ArrayList<>();

		/** Receives the session updates only while no handler is added. */
		private @Nullable Function<AcpSchema.SessionNotification, Mono<Void>> defaultSessionUpdateHandler;

		private @Nullable Function<AcpSchema.CreateElicitationRequest, Mono<AcpSchema.CreateElicitationResponse>> createElicitationHandler;

		private AsyncSpec(AcpClientTransport transport) {
			Assert.notNull(transport, "Transport must not be null");
			this.transport = transport;
		}

		/**
		 * Sets how long the client waits for the agent to answer a request: {@code initialize},
		 * session calls and extension requests. When it passes, the call fails with a
		 * {@link java.util.concurrent.TimeoutException} and the client sends the agent a
		 * {@code $/cancel_request}; a Java agent then cancels the handler. Default: 60 seconds, as for the agent builders.
		 * {@code session/prompt} is not bound by it, since its answer comes only at the end of the
		 * turn; see {@code promptTimeout}.
		 * @param requestTimeout the timeout
		 * @return this builder
		 * @throws IllegalArgumentException if {@code requestTimeout} is null
		 */
		public AsyncSpec requestTimeout(Duration requestTimeout) {
			Assert.notNull(requestTimeout, "Request timeout must not be null");
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets how long a prompt turn ({@code session/prompt}) may take before the client gives up
		 * on it. When it passes, {@code prompt} fails with a
		 * {@link java.util.concurrent.TimeoutException} and the client sends the agent a
		 * {@code $/cancel_request}, which makes a Java agent cancel the turn. Default: none, a
		 * prompt waits for the end of its turn however long it takes; the request timeout does not
		 * apply to it. {@link Duration#ZERO} also means none. To stop a turn early and still
		 * receive its answer, send {@code session/cancel} instead.
		 * @param promptTimeout the longest a turn may take, or {@link Duration#ZERO} for no limit
		 * @return this builder
		 * @throws IllegalArgumentException if {@code promptTimeout} is null or negative
		 */
		public AsyncSpec promptTimeout(Duration promptTimeout) {
			Assert.notNull(promptTimeout, "Prompt timeout must not be null");
			Assert.isTrue(!promptTimeout.isNegative(), "Prompt timeout must not be negative");
			this.promptTimeout = promptTimeout.isZero() ? null : promptTimeout;
			return this;
		}

		/**
		 * Sets the capabilities the client advertises to the agent in {@code initialize}: file
		 * reads and writes, terminals, boolean config options, authentication and elicitation. This
		 * is the only place they are set, and every initialize request carries them. Register the
		 * handlers that serve them as well: the builder does not derive the capabilities from the
		 * handlers, but {@code build()} throws when {@code fs.readTextFile},
		 * {@code fs.writeTextFile}, {@code terminal} or {@code elicitation} is advertised without
		 * its handlers, and logs a warning for a handler whose capability is not. Default:
		 * {@code new ClientCapabilities()}, no file system and no terminal. Build them with
		 * {@link AcpSchema.ClientCapabilities#builder()}.
		 * @param clientCapabilities the capabilities
		 * @return this builder
		 * @throws IllegalArgumentException if {@code clientCapabilities} is null
		 */
		public AsyncSpec clientCapabilities(AcpSchema.ClientCapabilities clientCapabilities) {
			Assert.notNull(clientCapabilities, "Client capabilities must not be null");
			this.clientCapabilities = clientCapabilities;
			return this;
		}

		/**
		 * Sets the client's name and version, sent to the agent in {@code initialize}. Optional.
		 * @param clientInfo the client's name and version
		 * @return this builder
		 * @throws IllegalArgumentException if {@code clientInfo} is null
		 */
		public AsyncSpec clientInfo(AcpSchema.Implementation clientInfo) {
			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			return this;
		}

		/**
		 * Sets the handler for {@code fs/read_text_file}: the agent asks for the content of a text
		 * file, which should include unsaved changes in the user's editor. Advertise
		 * {@code fs.readTextFile} in {@link #clientCapabilities} as well; the builder does not do
		 * it for you, and warns at {@code build()} if you do not.
		 *
		 * <pre>{@code
		 * .readTextFileHandler(request -> Mono
		 *     .fromCallable(() -> Files.readString(Path.of(request.path())))
		 *     .subscribeOn(Schedulers.boundedElastic())
		 *     .map(AcpSchema.ReadTextFileResponse::new))
		 * }</pre>
		 *
		 * @param handler the handler; it emits the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public AsyncSpec readTextFileHandler(
				Function<AcpSchema.ReadTextFileRequest, Mono<AcpSchema.ReadTextFileResponse>> handler) {
			Assert.notNull(handler, "Read text file handler must not be null");
			return request("readTextFileHandler", AcpSchema.METHOD_FS_READ_TEXT_FILE, new TypeRef<AcpSchema.ReadTextFileRequest>() {
			}, handler);
		}

		/**
		 * Sets the handler for {@code fs/write_text_file}: the agent asks to write a text file,
		 * which ACP requires the client to create if it does not exist. Advertise
		 * {@code fs.writeTextFile} in {@link #clientCapabilities} as well.
		 *
		 * <pre>{@code
		 * .writeTextFileHandler(request -> Mono
		 *     .fromCallable(() -> Files.writeString(Path.of(request.path()), request.content()))
		 *     .subscribeOn(Schedulers.boundedElastic())
		 *     .thenReturn(new AcpSchema.WriteTextFileResponse()))
		 * }</pre>
		 *
		 * @param handler the handler; it emits the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public AsyncSpec writeTextFileHandler(
				Function<AcpSchema.WriteTextFileRequest, Mono<AcpSchema.WriteTextFileResponse>> handler) {
			Assert.notNull(handler, "Write text file handler must not be null");
			return request("writeTextFileHandler", AcpSchema.METHOD_FS_WRITE_TEXT_FILE, new TypeRef<AcpSchema.WriteTextFileRequest>() {
			}, handler);
		}

		/**
		 * Sets the handler for {@code session/request_permission}: the agent asks the user to
		 * approve a tool call and offers the options to choose from. Answer with the selected
		 * option ({@link AcpSchema.PermissionSelected}), or with
		 * {@link AcpSchema.PermissionCancelled} once the prompt turn was cancelled, as ACP
		 * requires. Every client should register one: without it the request is answered
		 * {@code -32601} (method not found).
		 *
		 * <pre>{@code
		 * .requestPermissionHandler(request -> Mono.just(new AcpSchema.RequestPermissionResponse(
		 *     new AcpSchema.PermissionSelected(request.options().get(0).optionId()))))
		 * }</pre>
		 *
		 * @param handler the handler; it emits the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public AsyncSpec requestPermissionHandler(
				Function<AcpSchema.RequestPermissionRequest, Mono<AcpSchema.RequestPermissionResponse>> handler) {
			Assert.notNull(handler, "Request permission handler must not be null");
			return request("requestPermissionHandler", AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, new TypeRef<AcpSchema.RequestPermissionRequest>() {
			}, handler);
		}

		/**
		 * Sets the handler for {@code terminal/create}: the agent asks to start a command in a new
		 * terminal and gets back its ID. Advertise {@code terminal} in {@link #clientCapabilities}
		 * and register the other four terminal handlers as well.
		 *
		 * @param handler the handler; it emits the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public AsyncSpec createTerminalHandler(
				Function<AcpSchema.CreateTerminalRequest, Mono<AcpSchema.CreateTerminalResponse>> handler) {
			Assert.notNull(handler, "Create terminal handler must not be null");
			return request("createTerminalHandler", AcpSchema.METHOD_TERMINAL_CREATE, new TypeRef<AcpSchema.CreateTerminalRequest>() {
			}, handler);
		}

		/**
		 * Sets the handler for {@code terminal/output}: the agent asks for a terminal's output so
		 * far, whether it was truncated, and the exit status if the command has ended.
		 *
		 * @param handler the handler; it emits the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public AsyncSpec terminalOutputHandler(
				Function<AcpSchema.TerminalOutputRequest, Mono<AcpSchema.TerminalOutputResponse>> handler) {
			Assert.notNull(handler, "Terminal output handler must not be null");
			return request("terminalOutputHandler", AcpSchema.METHOD_TERMINAL_OUTPUT, new TypeRef<AcpSchema.TerminalOutputRequest>() {
			}, handler);
		}

		/**
		 * Sets the handler for {@code terminal/release}: the agent is done with a terminal. Kill
		 * its command if it is still running and free the terminal; its ID is invalid afterwards.
		 *
		 * @param handler the handler; it emits the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public AsyncSpec releaseTerminalHandler(
				Function<AcpSchema.ReleaseTerminalRequest, Mono<AcpSchema.ReleaseTerminalResponse>> handler) {
			Assert.notNull(handler, "Release terminal handler must not be null");
			return request("releaseTerminalHandler", AcpSchema.METHOD_TERMINAL_RELEASE, new TypeRef<AcpSchema.ReleaseTerminalRequest>() {
			}, handler);
		}

		/**
		 * Sets the handler for {@code terminal/wait_for_exit}: answer once the terminal's command
		 * has ended, with its exit code or the signal that ended it. The agent's request timeout
		 * bounds how long the agent waits for this answer.
		 *
		 * @param handler the handler; it emits the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public AsyncSpec waitForTerminalExitHandler(
				Function<AcpSchema.WaitForTerminalExitRequest, Mono<AcpSchema.WaitForTerminalExitResponse>> handler) {
			Assert.notNull(handler, "Wait for terminal exit handler must not be null");
			return request("waitForTerminalExitHandler", AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT, new TypeRef<AcpSchema.WaitForTerminalExitRequest>() {
			}, handler);
		}

		/**
		 * Sets the handler for {@code terminal/kill}: kill the terminal's command but keep the
		 * terminal, so its output and exit status can still be read until it is released.
		 *
		 * @param handler the handler; it emits the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public AsyncSpec killTerminalHandler(
				Function<AcpSchema.KillTerminalCommandRequest, Mono<AcpSchema.KillTerminalCommandResponse>> handler) {
			Assert.notNull(handler, "Kill terminal handler must not be null");
			return request("killTerminalHandler", AcpSchema.METHOD_TERMINAL_KILL, new TypeRef<AcpSchema.KillTerminalCommandRequest>() {
			}, handler);
		}

		/**
		 * Sets the handler for {@code elicitation/create}: the agent asks the user for structured
		 * input, with a form or by sending the user to a URL. The handler answers accept (with the
		 * form content), decline or cancel.
		 *
		 * <p>A request whose mode ({@code form} or {@code url}) the client did not advertise in its
		 * elicitation capabilities is answered with {@code -32602} (invalid params) without calling
		 * the handler. Advertise the modes the handler supports, for example with
		 * {@link AcpSchema.ElicitationCapabilities#formOnly()}.
		 * @param handler the handler; it emits the user's answer
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public AsyncSpec createElicitationHandler(
				Function<AcpSchema.CreateElicitationRequest, Mono<AcpSchema.CreateElicitationResponse>> handler) {
			Assert.notNull(handler, "Create elicitation handler must not be null");
			if (this.createElicitationHandler != null) {
				throw alreadyRegistered(AcpSchema.METHOD_ELICITATION_CREATE, "createElicitationHandler");
			}
			this.createElicitationHandler = handler;
			return this;
		}

		/**
		 * Sets the handler for {@code elicitation/complete} notifications: the agent reports that
		 * the outside interaction of a URL-mode elicitation has finished. ACP requires clients to
		 * ignore unknown or already completed elicitation IDs, so check the ID against the URL
		 * elicitations the user accepted.
		 * @param handler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public AsyncSpec completeElicitationHandler(
				Function<AcpSchema.CompleteElicitationNotification, Mono<Void>> handler) {
			Assert.notNull(handler, "Complete elicitation handler must not be null");
			return notification("completeElicitationHandler", AcpSchema.METHOD_ELICITATION_COMPLETE, params -> handler
				.apply(transport.unmarshalFrom(params, new TypeRef<AcpSchema.CompleteElicitationNotification>() {
				})));
		}

		/**
		 * Adds a handler for {@code session/update} notifications: the message and thought chunks,
		 * tool calls, plans and other updates the agent streams during a prompt turn, and the
		 * updates it sends between turns. The handler's {@code Mono} completes when it has
		 * finished with a notification.
		 *
		 * <p>Notifications are delivered one at a time, in the order the agent sent them, and each
		 * goes to every handler added: the next one waits until all handlers have finished with
		 * this one. A response from the agent, such as a prompt's, completes its caller only after
		 * the handlers have finished with every notification sent before it, so the updates of a
		 * turn are all handled when {@code prompt} returns. A slow handler therefore delays
		 * responses, and the wait counts against the request timeout. A handler must not wait for
		 * a prompt in flight to complete: that prompt waits for the handler. A handler that fails
		 * is logged, and the next notification follows. Handlers add up; the first one added
		 * replaces the {@link #defaultSessionUpdateHandler default handler}, if one is set.
		 * @param sessionUpdateHandler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if {@code sessionUpdateHandler} is null
		 */
		public AsyncSpec sessionUpdateHandler(
				Function<AcpSchema.SessionNotification, Mono<Void>> sessionUpdateHandler) {
			Assert.notNull(sessionUpdateHandler, "Session update handler must not be null");
			this.sessionUpdateHandlers.add(sessionUpdateHandler);
			return this;
		}

		/**
		 * Sets the handler that receives {@code session/update} notifications when no
		 * {@link #sessionUpdateHandler} is added: a default that the application's own handlers
		 * replace rather than run beside, whichever is registered first. It is meant for
		 * frameworks, which set a default (such as logging each update at DEBUG) before handing
		 * the builder to the application's customizers. It is delivered like a handler added
		 * with {@code sessionUpdateHandler}.
		 * @param handler the default handler
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 * @throws IllegalStateException if a default handler is already set
		 */
		public AsyncSpec defaultSessionUpdateHandler(Function<AcpSchema.SessionNotification, Mono<Void>> handler) {
			Assert.notNull(handler, "Default session update handler must not be null");
			if (this.defaultSessionUpdateHandler != null) {
				throw new IllegalStateException(
						"A default session update handler is already set; defaultSessionUpdateHandler was called twice");
			}
			this.defaultSessionUpdateHandler = handler;
			return this;
		}

		/**
		 * Registers a handler for an agent-to-client request method the SDK does not model, with
		 * the params as the transport read them: the escape hatch for a method newer than this
		 * SDK. A method with a typed setter ({@code fs/*}, {@code terminal/*},
		 * {@code session/request_permission}, {@code elicitation/create}) is refused, since the
		 * raw handler would bypass its typed params and checks; use the setter the exception
		 * names. For extension methods prefer
		 * {@link #extRequestHandler(String, TypeRef, Function)}.
		 * @param method the method name
		 * @param handler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if {@code method} or {@code handler} is null, or the
		 * method has a typed setter
		 * @throws IllegalStateException if the method already has a handler
		 */
		public AsyncSpec requestHandler(String method, AcpClientSession.RequestHandler<?> handler) {
			Assert.notNull(method, "Method must not be null");
			Assert.notNull(handler, "Handler must not be null");
			refuseTyped(method, TYPED_REQUEST_SETTERS, "requestHandler");
			if (this.requestHandlers.putIfAbsent(method, handler) != null) {
				throw alreadyRegistered(method, "requestHandler");
			}
			return this;
		}

		/**
		 * Registers a handler for an agent-to-client notification method the SDK does not model,
		 * with the params as the transport read them. It is delivered in order with the session
		 * updates, and it is called on the delivering thread, so it must not block. A method with
		 * a typed setter ({@code session/update}, {@code elicitation/complete}) is refused; use
		 * the setter the exception names. For extension methods prefer
		 * {@code extNotificationHandler}.
		 * @param method the method name
		 * @param handler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if {@code method} or {@code handler} is null, or the
		 * method has a typed setter
		 * @throws IllegalStateException if the method already has a handler
		 */
		public AsyncSpec notificationHandler(String method, AcpClientSession.NotificationHandler handler) {
			Assert.notNull(method, "Method must not be null");
			Assert.notNull(handler, "Handler must not be null");
			refuseTyped(method, TYPED_NOTIFICATION_SETTERS, "notificationHandler");
			return notification("notificationHandler", method, handler);
		}

		/**
		 * Registers the handler for a custom extension request ({@code _}-prefixed method name, ACP
		 * v1 Extensibility) from the agent, its params read as the given type. A request for an
		 * extension method without a handler is answered with "Method not found" ({@code -32601}).
		 *
		 * <pre>{@code
		 * .extRequestHandler("_example.com/workspace/buffers",
		 *     params -> Mono.just(Map.of("buffers", List.of())))
		 * }</pre>
		 *
		 * @param <T> the params type
		 * @param method the method name, which must start with {@code _}
		 * @param paramsType the type the params are read as
		 * @param handler the handler; its result is any value the JSON mapper can write, and it
		 * must not complete empty (the request is then answered with an internal error,
		 * {@code -32603}): answer with an empty map when there is nothing to return
		 * @return this builder
		 * @throws IllegalArgumentException if the method name does not start with {@code _}, or the
		 * type or handler is null
		 */
		public <T> AsyncSpec extRequestHandler(String method, TypeRef<T> paramsType,
				Function<T, ? extends Mono<?>> handler) {
			ExtensionMethods.requireExtension(method);
			Assert.notNull(paramsType, "Params type must not be null");
			Assert.notNull(handler, "Handler must not be null");
			return request("extRequestHandler", method, paramsType, params -> handler.apply(params).cast(Object.class));
		}

		/**
		 * Registers the handler for a custom extension request ({@code _}-prefixed method name, ACP
		 * v1 Extensibility) from the agent, its params delivered as the raw JSON value (a
		 * {@code Map}, {@code List}, {@code String}, {@code Number} or {@code Boolean}).
		 * @param method the method name, which must start with {@code _}
		 * @param handler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 * @see #extRequestHandler(String, TypeRef, Function)
		 */
		public AsyncSpec extRequestHandler(String method, Function<Object, ? extends Mono<?>> handler) {
			return extRequestHandler(method, RAW_PARAMS, handler);
		}

		/**
		 * Registers the handler for a custom extension notification ({@code _}-prefixed method
		 * name) from the agent, its params read as the given type. An extension notification
		 * without a handler is ignored, as the protocol asks. Extension notifications are delivered
		 * in order with the session updates.
		 * @param <T> the params type
		 * @param method the method name, which must start with {@code _}
		 * @param paramsType the type the params are read as
		 * @param handler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if the method name does not start with {@code _}, or the
		 * type or handler is null
		 */
		public <T> AsyncSpec extNotificationHandler(String method, TypeRef<T> paramsType,
				Function<T, Mono<Void>> handler) {
			ExtensionMethods.requireExtension(method);
			Assert.notNull(paramsType, "Params type must not be null");
			Assert.notNull(handler, "Handler must not be null");
			return notification("extNotificationHandler", method,
					params -> handler.apply(transport.unmarshalParams(params, paramsType)));
		}

		/**
		 * Registers the handler for a custom extension notification ({@code _}-prefixed method
		 * name) from the agent, its params delivered as the raw JSON value.
		 * @param method the method name, which must start with {@code _}
		 * @param handler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 * @see #extNotificationHandler(String, TypeRef, Function)
		 */
		public AsyncSpec extNotificationHandler(String method, Function<Object, Mono<Void>> handler) {
			return extNotificationHandler(method, RAW_PARAMS, handler);
		}

		/** Reads the params as the request type, then calls the handler. */
		private <Q, R> AsyncSpec request(String setter, String method, TypeRef<Q> requestType,
				Function<Q, Mono<R>> handler) {
			AcpClientSession.RequestHandler<R> rawHandler = params -> handler
				.apply(transport.unmarshalParams(params, requestType));
			if (this.requestHandlers.putIfAbsent(method, rawHandler) != null) {
				throw alreadyRegistered(method, setter);
			}
			return this;
		}

		/** Registers a notification handler, unless the method has one. */
		private AsyncSpec notification(String setter, String method, AcpClientSession.NotificationHandler handler) {
			if (this.notificationHandlers.putIfAbsent(method, handler) != null) {
				throw alreadyRegistered(method, setter);
			}
			return this;
		}

		/**
		 * Fails when the advertised capabilities claim a capability without the handlers that
		 * serve it, and warns once about handlers for capabilities not advertised.
		 */
		private void checkCapabilitiesHaveHandlers() {
			NegotiatedCapabilities advertised = NegotiatedCapabilities.fromClient(this.clientCapabilities);
			List<String> missing = new ArrayList<>();
			List<String> unadvertised = new ArrayList<>();
			checkCapability("fs.readTextFile", advertised.supportsReadTextFile(),
					List.of(AcpSchema.METHOD_FS_READ_TEXT_FILE), missing, unadvertised);
			checkCapability("fs.writeTextFile", advertised.supportsWriteTextFile(),
					List.of(AcpSchema.METHOD_FS_WRITE_TEXT_FILE), missing, unadvertised);
			checkCapability("terminal", advertised.supportsTerminal(),
					List.of(AcpSchema.METHOD_TERMINAL_CREATE, AcpSchema.METHOD_TERMINAL_OUTPUT,
							AcpSchema.METHOD_TERMINAL_RELEASE, AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT,
							AcpSchema.METHOD_TERMINAL_KILL),
					missing, unadvertised);
			// An elicitation capability without a mode advertises nothing the agent may ask for.
			checkCapability("elicitation", advertised.supportsElicitationForm() || advertised.supportsElicitationUrl(),
					List.of(AcpSchema.METHOD_ELICITATION_CREATE), missing, unadvertised);
			if (!missing.isEmpty()) {
				throw new IllegalStateException("The client advertises capabilities it has no handler for: "
						+ String.join("; ", missing)
						+ ". Register the handlers, or do not advertise the capabilities in clientCapabilities(..)");
			}
			if (!unadvertised.isEmpty()) {
				logger.warn("The client has handlers for capabilities it does not advertise, so an agent will not "
						+ "call them: {}. Advertise the capabilities in clientCapabilities(..)",
						String.join("; ", unadvertised));
			}
		}

		private void checkCapability(String capability, boolean advertised, List<String> methods,
				List<String> missing, List<String> unadvertised) {
			List<String> absent = new ArrayList<>();
			List<String> present = new ArrayList<>();
			for (String method : methods) {
				boolean handled = AcpSchema.METHOD_ELICITATION_CREATE.equals(method)
						? this.createElicitationHandler != null : this.requestHandlers.containsKey(method);
				(handled ? present : absent).add(TYPED_REQUEST_SETTERS.get(method));
			}
			if (advertised && !absent.isEmpty()) {
				missing.add(capability + " needs " + String.join(", ", absent));
			}
			else if (!advertised && !present.isEmpty()) {
				unadvertised.add(String.join(", ", present) + " (" + capability + ")");
			}
		}

		/** The raw setters are for methods the SDK does not model; a modelled one has its setter. */
		private static void refuseTyped(String method, Map<String, String> typedSetters, String rawSetter) {
			String setter = typedSetters.get(method);
			if (setter != null) {
				throw new IllegalArgumentException(method + " has a typed setter: use " + setter + " instead of "
						+ rawSetter + ", which is for methods the SDK does not model");
			}
		}

		/** A second handler for a method: a mistake, which would silently replace the first. */
		private static IllegalStateException alreadyRegistered(String method, String setter) {
			return new IllegalStateException("A handler for " + method + " is already registered on this builder; "
					+ setter + " cannot register a second one");
		}

		/**
		 * Builds the client and connects the transport: for a
		 * {@link com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport} that starts
		 * the agent process. Call {@link AcpAsyncClient#initialize()} next. A transport carries one
		 * client, so build once per transport.
		 * @return the client
		 * @throws IllegalStateException if the capabilities advertise {@code fs.readTextFile},
		 * {@code fs.writeTextFile}, {@code terminal} or {@code elicitation} without the handlers
		 * that serve them (the message names the missing setters); or if the transport refuses to
		 * connect at once, for example because it is already connected; a connection that fails
		 * later fails the client's requests instead
		 */
		public AcpAsyncClient build() {
			checkCapabilitiesHaveHandlers();

			// Set up session update notification handler: the application's handlers, or else the
			// default one
			List<Function<AcpSchema.SessionNotification, Mono<Void>>> updateHandlers = new ArrayList<>(
					sessionUpdateHandlers);
			Function<AcpSchema.SessionNotification, Mono<Void>> defaultHandler = this.defaultSessionUpdateHandler;
			if (updateHandlers.isEmpty() && defaultHandler != null) {
				updateHandlers.add(defaultHandler);
			}
			Map<String, AcpClientSession.NotificationHandler> notifications = new HashMap<>(notificationHandlers);
			if (!updateHandlers.isEmpty()) {
				notifications.put(AcpSchema.METHOD_SESSION_UPDATE, params -> {
					AcpSchema.SessionNotification notification = transport.unmarshalParams(params,
							new TypeRef<AcpSchema.SessionNotification>() {
							});
					logger.debug("Received session update for session: {}", notification.sessionId());

					// Call all registered handlers
					return Mono.when(updateHandlers.stream().map(handler -> handler.apply(notification)).toList());
				});
			}

			// The capabilities the client advertises when it initializes
			AtomicReference<AcpSchema.@Nullable ClientCapabilities> advertised = new AtomicReference<>();
			Map<String, AcpClientSession.RequestHandler<?>> handlers = new HashMap<>(requestHandlers);
			Function<AcpSchema.CreateElicitationRequest, Mono<AcpSchema.CreateElicitationResponse>> elicitation = this.createElicitationHandler;
			if (elicitation != null) {
				AcpClientSession.RequestHandler<AcpSchema.CreateElicitationResponse> rawHandler = params -> {
					AcpSchema.CreateElicitationRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.CreateElicitationRequest>() {
							});
					if (!advertisesMode(advertised.get(), request.mode())) {
						return Mono.error(new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS,
								"Elicitation mode '" + request.mode() + "' was not advertised by the client"));
					}
					return elicitation.apply(request);
				};
				handlers.put(AcpSchema.METHOD_ELICITATION_CREATE, rawHandler);
			}

			// Create session with request and notification handlers
			AcpSession session = new AcpClientSession(requestTimeout, transport, handlers, notifications,
					Function.identity());

			return new AcpAsyncClient(session, transport, clientCapabilities, clientInfo, advertised, promptTimeout);
		}

		/**
		 * Whether the advertised capabilities cover an elicitation mode. Before the
		 * client initializes nothing is known and every mode passes; a mode this SDK does
		 * not know passes too, since only the handler can judge it.
		 */
		private static boolean advertisesMode(AcpSchema.@Nullable ClientCapabilities advertised, String mode) {
			if (advertised == null) {
				return true;
			}
			AcpSchema.ElicitationCapabilities elicitation = advertised.elicitation();
			return switch (mode) {
				case AcpSchema.CreateElicitationRequest.MODE_FORM -> elicitation != null && elicitation.form() != null;
				case AcpSchema.CreateElicitationRequest.MODE_URL -> elicitation != null && elicitation.url() != null;
				default -> true;
			};
		}

	}

	/**
	 * Configures and builds an {@link AcpSyncClient}: the client's capabilities and info, the
	 * request timeout, the session update handlers, and the handlers for the agent's requests and
	 * notifications, each returning a plain value. Get one from
	 * {@link AcpClient#sync(AcpClientTransport)}.
	 *
	 * <p>Handlers and session update handlers run on the sync builder's handler executor, not
	 * on the transport's thread, so they may block. A request is handed to its handler once the
	 * session updates before it have been handled, without waiting for other handlers, so several
	 * handlers can run at the same time and state they share must be thread-safe. A request handler
	 * that returns {@code null} is answered {@code -32603}. The builder turns each handler into its
	 * asynchronous counterpart on an {@link AsyncSpec}, so the rules described there apply.
	 * {@link #build()} connects the transport. A builder is not thread-safe; configure it on one
	 * thread.
	 */
	class SyncSpec {

		private final AsyncSpec asyncSpec;

		/** Where the handlers run; read when one is called. */
		private Scheduler handlerScheduler = SyncHandlerScheduler.DEFAULT;

		private SyncSpec(AcpClientTransport transport) {
			this.asyncSpec = new AsyncSpec(transport);
		}

		/**
		 * Sets the executor the handlers run on, for example
		 * {@code Executors.newVirtualThreadPerTaskExecutor()} or a framework's worker pool. Without
		 * it each call runs on a virtual thread of its own on JDK 21 and later, and before that on
		 * a pool of daemon threads shared by every synchronous client in the JVM, which has no size
		 * limit: each handler running at the same time takes a thread of its own. Either way the
		 * threads are named {@code acp-sync-handler}. On JDK 21 to 23 a virtual thread that blocks
		 * inside a {@code synchronized} block pins its carrier thread (JDK 24 removed that, JEP
		 * 491). The executor must allow blocking. A handler the agent
		 * cancels is interrupted (its task is cancelled), and the SDK never shuts the executor
		 * down; that is the application's job.
		 * @param executor the executor the handlers run on
		 * @return this builder
		 * @throws IllegalArgumentException if {@code executor} is null
		 */
		public SyncSpec handlerExecutor(ExecutorService executor) {
			Assert.notNull(executor, "Executor must not be null");
			this.handlerScheduler = Schedulers.fromExecutorService(executor, "acp-client-handlers");
			return this;
		}

		/**
		 * Converts a sync request handler to an async request handler.
		 * Follows the MCP SDK pattern of wrapping sync handlers with Mono.fromCallable()
		 * and scheduling on the handler executor to prevent blocking the event loop.
		 *
		 * @param <T> The response type
		 * @param syncHandler The synchronous handler to convert
		 * @return An async handler that wraps the sync handler
		 */
		private <T> AcpClientSession.RequestHandler<T> fromSync(SyncRequestHandler<T> syncHandler) {
			return params -> onSyncHandlerThread(() -> syncHandler.handle(params));
		}

		/** Runs a sync handler on the handler executor, so it may block. */
		private <T> Mono<T> onSyncHandlerThread(Callable<T> handler) {
			return Mono.fromCallable(HandlerFailures.guard(handler)).subscribeOn(this.handlerScheduler);
		}

		/**
		 * Sets how long the client waits for the agent to answer a request: {@code initialize},
		 * session calls and extension requests. When it passes, the call fails with a
		 * {@link java.util.concurrent.TimeoutException} and the client sends the agent a
		 * {@code $/cancel_request}; a Java agent then cancels the handler. Default: 60 seconds, as for the agent builders.
		 * {@code session/prompt} is not bound by it, since its answer comes only at the end of the
		 * turn; see {@code promptTimeout}.
		 * @param requestTimeout the timeout
		 * @return this builder
		 * @throws IllegalArgumentException if {@code requestTimeout} is null
		 */
		public SyncSpec requestTimeout(Duration requestTimeout) {
			asyncSpec.requestTimeout(requestTimeout);
			return this;
		}

		/**
		 * Sets how long a prompt turn ({@code session/prompt}) may take before the client gives up
		 * on it. When it passes, {@code prompt} fails with a
		 * {@link java.util.concurrent.TimeoutException} and the client sends the agent a
		 * {@code $/cancel_request}, which makes a Java agent cancel the turn. Default: none, a
		 * prompt waits for the end of its turn however long it takes; the request timeout does not
		 * apply to it. {@link Duration#ZERO} also means none. To stop a turn early and still
		 * receive its answer, send {@code session/cancel} instead.
		 * @param promptTimeout the longest a turn may take, or {@link Duration#ZERO} for no limit
		 * @return this builder
		 * @throws IllegalArgumentException if {@code promptTimeout} is null or negative
		 */
		public SyncSpec promptTimeout(Duration promptTimeout) {
			asyncSpec.promptTimeout(promptTimeout);
			return this;
		}

		/**
		 * Sets the capabilities the client advertises to the agent in {@code initialize}: file
		 * reads and writes, terminals, boolean config options, authentication and elicitation. This
		 * is the only place they are set, and every initialize request carries them. Register the
		 * handlers that serve them as well: the builder does not derive the capabilities from the
		 * handlers, but {@code build()} throws when {@code fs.readTextFile},
		 * {@code fs.writeTextFile}, {@code terminal} or {@code elicitation} is advertised without
		 * its handlers, and logs a warning for a handler whose capability is not. Default:
		 * {@code new ClientCapabilities()}, no file system and no terminal. Build them with
		 * {@link AcpSchema.ClientCapabilities#builder()}.
		 * @param clientCapabilities the capabilities
		 * @return this builder
		 * @throws IllegalArgumentException if {@code clientCapabilities} is null
		 */
		public SyncSpec clientCapabilities(AcpSchema.ClientCapabilities clientCapabilities) {
			asyncSpec.clientCapabilities(clientCapabilities);
			return this;
		}

		/**
		 * Sets the client's name and version, sent to the agent in {@code initialize}. Optional.
		 * @param clientInfo the client's name and version
		 * @return this builder
		 * @throws IllegalArgumentException if {@code clientInfo} is null
		 */
		public SyncSpec clientInfo(AcpSchema.Implementation clientInfo) {
			asyncSpec.clientInfo(clientInfo);
			return this;
		}

		/**
		 * Sets the handler for {@code fs/read_text_file}: the agent asks for the content of a text
		 * file, which should include unsaved changes in the user's editor. Advertise
		 * {@code fs.readTextFile} in {@link #clientCapabilities} as well; the builder does not do
		 * it for you, and warns at {@code build()} if you do not.
		 *
		 * <pre>{@code
		 * .readTextFileHandler(request -> {
		 *     try {
		 *         String content = Files.readString(Path.of(request.path()));
		 *         return new AcpSchema.ReadTextFileResponse(content);
		 *     } catch (IOException e) {
		 *         throw new UncheckedIOException(e);
		 *     }
		 * })
		 * }</pre>
		 *
		 * @param handler the handler; it returns the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public SyncSpec readTextFileHandler(
				Function<AcpSchema.ReadTextFileRequest, AcpSchema.ReadTextFileResponse> handler) {
			Assert.notNull(handler, "Read text file handler must not be null");
			asyncSpec.readTextFileHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code fs/write_text_file}: the agent asks to write a text file,
		 * which ACP requires the client to create if it does not exist. Advertise
		 * {@code fs.writeTextFile} in {@link #clientCapabilities} as well.
		 *
		 * @param handler the handler; it returns the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public SyncSpec writeTextFileHandler(
				Function<AcpSchema.WriteTextFileRequest, AcpSchema.WriteTextFileResponse> handler) {
			Assert.notNull(handler, "Write text file handler must not be null");
			asyncSpec.writeTextFileHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code session/request_permission}: the agent asks the user to
		 * approve a tool call and offers the options to choose from. Answer with the selected
		 * option ({@link AcpSchema.PermissionSelected}), or with
		 * {@link AcpSchema.PermissionCancelled} once the prompt turn was cancelled, as ACP
		 * requires. Every client should register one: without it the request is answered
		 * {@code -32601} (method not found).
		 *
		 * <pre>{@code
		 * .requestPermissionHandler(request -> {
		 *     System.out.println("Permission requested: " + request.toolCall().title());
		 *     return new AcpSchema.RequestPermissionResponse(
		 *         new AcpSchema.PermissionSelected(request.options().get(0).optionId()));
		 * })
		 * }</pre>
		 *
		 * @param handler the handler; it returns the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public SyncSpec requestPermissionHandler(
				Function<AcpSchema.RequestPermissionRequest, AcpSchema.RequestPermissionResponse> handler) {
			Assert.notNull(handler, "Request permission handler must not be null");
			asyncSpec.requestPermissionHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code terminal/create}: the agent asks to start a command in a new
		 * terminal and gets back its ID. Advertise {@code terminal} in {@link #clientCapabilities}
		 * and register the other four terminal handlers as well.
		 *
		 * @param handler the handler; it returns the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public SyncSpec createTerminalHandler(
				Function<AcpSchema.CreateTerminalRequest, AcpSchema.CreateTerminalResponse> handler) {
			Assert.notNull(handler, "Create terminal handler must not be null");
			asyncSpec.createTerminalHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code terminal/output}: the agent asks for a terminal's output so
		 * far, whether it was truncated, and the exit status if the command has ended.
		 *
		 * @param handler the handler; it returns the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public SyncSpec terminalOutputHandler(
				Function<AcpSchema.TerminalOutputRequest, AcpSchema.TerminalOutputResponse> handler) {
			Assert.notNull(handler, "Terminal output handler must not be null");
			asyncSpec.terminalOutputHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code terminal/release}: the agent is done with a terminal. Kill
		 * its command if it is still running and free the terminal; its ID is invalid afterwards.
		 *
		 * @param handler the handler; it returns the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public SyncSpec releaseTerminalHandler(
				Function<AcpSchema.ReleaseTerminalRequest, AcpSchema.ReleaseTerminalResponse> handler) {
			Assert.notNull(handler, "Release terminal handler must not be null");
			asyncSpec.releaseTerminalHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code terminal/wait_for_exit}: answer once the terminal's command
		 * has ended, with its exit code or the signal that ended it. The agent's request timeout
		 * bounds how long the agent waits for this answer.
		 *
		 * @param handler the handler; it returns the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public SyncSpec waitForTerminalExitHandler(
				Function<AcpSchema.WaitForTerminalExitRequest, AcpSchema.WaitForTerminalExitResponse> handler) {
			Assert.notNull(handler, "Wait for terminal exit handler must not be null");
			asyncSpec.waitForTerminalExitHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code terminal/kill}: kill the terminal's command but keep the
		 * terminal, so its output and exit status can still be read until it is released.
		 *
		 * @param handler the handler; it returns the answer to the agent
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public SyncSpec killTerminalHandler(
				Function<AcpSchema.KillTerminalCommandRequest, AcpSchema.KillTerminalCommandResponse> handler) {
			Assert.notNull(handler, "Kill terminal handler must not be null");
			asyncSpec.killTerminalHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code elicitation/create}: the agent asks the user for structured
		 * input, with a form or by sending the user to a URL, and the handler returns accept,
		 * decline or cancel. As with {@link AsyncSpec#createElicitationHandler(Function)}, a
		 * request for a mode the client did not advertise is answered with {@code -32602} without
		 * calling the handler.
		 * @param handler the handler; it returns the user's answer
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public SyncSpec createElicitationHandler(
				Function<AcpSchema.CreateElicitationRequest, AcpSchema.CreateElicitationResponse> handler) {
			Assert.notNull(handler, "Create elicitation handler must not be null");
			asyncSpec.createElicitationHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Sets the handler for {@code elicitation/complete} notifications: the agent reports that
		 * the outside interaction of a URL-mode elicitation has finished. ACP requires clients to
		 * ignore unknown or already completed elicitation IDs, so check the ID against the URL
		 * elicitations the user accepted.
		 * @param handler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public SyncSpec completeElicitationHandler(Consumer<AcpSchema.CompleteElicitationNotification> handler) {
			Assert.notNull(handler, "Complete elicitation handler must not be null");
			asyncSpec.completeElicitationHandler(notification -> Mono
				.fromRunnable(HandlerFailures.guard(() -> handler.accept(notification)))
				.subscribeOn(this.handlerScheduler)
				.then());
			return this;
		}

		/**
		 * Adds a handler for {@code session/update} notifications: the message and thought chunks,
		 * tool calls, plans and other updates the agent streams during a prompt turn, and the
		 * updates it sends between turns. The handler runs on
		 * the sync builder's handler executor and has finished with a notification when it
		 * returns.
		 *
		 * <p>Notifications are delivered one at a time, in the order the agent sent them, and each
		 * goes to every handler added: the next one waits until all handlers have finished with
		 * this one. A response from the agent, such as a prompt's, completes its caller only after
		 * the handlers have finished with every notification sent before it, so the updates of a
		 * turn are all handled when {@code prompt} returns. A slow handler therefore delays
		 * responses, and the wait counts against the request timeout. A handler must not wait for
		 * a prompt in flight to complete: that prompt waits for the handler. A handler that fails
		 * is logged, and the next notification follows.
		 *
		 * <pre>{@code
		 * .sessionUpdateHandler(notification -> {
		 *     if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
		 *             && chunk.content() instanceof AcpSchema.TextContent text) {
		 *         System.out.print(text.text());
		 *     }
		 * })
		 * }</pre>
		 *
		 * @param sessionUpdateHandler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if {@code sessionUpdateHandler} is null
		 */
		public SyncSpec sessionUpdateHandler(Consumer<AcpSchema.SessionNotification> sessionUpdateHandler) {
			Assert.notNull(sessionUpdateHandler, "Session update handler must not be null");
			// Convert sync handler to async Function
			asyncSpec.sessionUpdateHandler(notification -> Mono
				.fromRunnable(HandlerFailures.guard(() -> sessionUpdateHandler.accept(notification)))
				.subscribeOn(this.handlerScheduler)
				.then());
			return this;
		}

		/**
		 * Sets the handler that receives {@code session/update} notifications when no
		 * {@link #sessionUpdateHandler} is added; the application's own handlers replace it,
		 * whichever is registered first. Meant for frameworks; see
		 * {@link AsyncSpec#defaultSessionUpdateHandler(Function)}. It runs on the handler
		 * executor.
		 * @param handler the default handler
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 * @throws IllegalStateException if a default handler is already set
		 */
		public SyncSpec defaultSessionUpdateHandler(Consumer<AcpSchema.SessionNotification> handler) {
			Assert.notNull(handler, "Default session update handler must not be null");
			asyncSpec.defaultSessionUpdateHandler(notification -> Mono
				.fromRunnable(HandlerFailures.guard(() -> handler.accept(notification)))
				.subscribeOn(this.handlerScheduler)
				.then());
			return this;
		}

		/**
		 * Registers a blocking handler for any agent-to-client request method, with the params as
		 * the transport read them. The method name is not checked: prefer the typed setters for ACP
		 * methods and {@link #extRequestHandler(String, TypeRef, Function)} for extension methods.
		 * A method already registered throws {@link IllegalStateException}.
		 * @param <T> the result type
		 * @param method the method name
		 * @param handler the handler, run on the sync builder's handler executor
		 * @return this builder
		 * @throws IllegalArgumentException if {@code method} or {@code handler} is null
		 */
		public <T> SyncSpec requestHandler(String method, SyncRequestHandler<T> handler) {
			Assert.notNull(method, "Method must not be null");
			Assert.notNull(handler, "Handler must not be null");
			asyncSpec.requestHandler(method, fromSync(handler));
			return this;
		}

		/**
		 * Registers a blocking handler for an agent-to-client notification method the SDK does
		 * not model, with the params as the transport read them. It runs on the handler executor,
		 * like the other sync handlers, and is delivered
		 * in order with the session updates. A method with a typed setter ({@code session/update},
		 * {@code elicitation/complete}) is refused; use the setter the exception names. For
		 * extension methods prefer {@code extNotificationHandler}.
		 * @param method the method name
		 * @param handler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if {@code method} or {@code handler} is null, or the
		 * method has a typed setter
		 * @throws IllegalStateException if the method already has a handler
		 */
		public SyncSpec notificationHandler(String method, Consumer<Object> handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncSpec.notificationHandler(method, params -> Mono
				.<Void>fromRunnable(HandlerFailures.guard(() -> handler.accept(params)))
				.subscribeOn(this.handlerScheduler));
			return this;
		}

		/**
		 * Registers the handler for a custom extension request ({@code _}-prefixed method name, ACP
		 * v1 Extensibility) from the agent, its params read as the given type. A request for an
		 * extension method without a handler is answered with "Method not found" ({@code -32601}).
		 * @param <T> the params type
		 * @param method the method name, which must start with {@code _}
		 * @param paramsType the type the params are read as
		 * @param handler the handler; it returns the result, any value the JSON mapper can write,
		 * and returning {@code null} answers the request with an internal error ({@code -32603})
		 * @return this builder
		 * @throws IllegalArgumentException if the method name does not start with {@code _}, or the
		 * type or handler is null
		 * @see AsyncSpec#extRequestHandler(String, TypeRef, Function)
		 */
		public <T> SyncSpec extRequestHandler(String method, TypeRef<T> paramsType, Function<T, ?> handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncSpec.extRequestHandler(method, paramsType,
					params -> onSyncHandlerThread(() -> handler.apply(params)));
			return this;
		}

		/**
		 * Registers the handler for a custom extension request ({@code _}-prefixed method name, ACP
		 * v1 Extensibility) from the agent, its params delivered as the raw JSON value (a
		 * {@code Map}, {@code List}, {@code String}, {@code Number} or {@code Boolean}).
		 * @param method the method name, which must start with {@code _}
		 * @param handler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 * @see #extRequestHandler(String, TypeRef, Function)
		 */
		public SyncSpec extRequestHandler(String method, Function<Object, ?> handler) {
			return extRequestHandler(method, AsyncSpec.RAW_PARAMS, handler);
		}

		/**
		 * Registers the handler for a custom extension notification ({@code _}-prefixed method
		 * name) from the agent, its params read as the given type. An extension notification
		 * without a handler is ignored, as the protocol asks. Extension notifications are delivered
		 * in order with the session updates.
		 * @param <T> the params type
		 * @param method the method name, which must start with {@code _}
		 * @param paramsType the type the params are read as
		 * @param handler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if the method name does not start with {@code _}, or the
		 * type or handler is null
		 * @see AsyncSpec#extNotificationHandler(String, TypeRef, Function)
		 */
		public <T> SyncSpec extNotificationHandler(String method, TypeRef<T> paramsType, Consumer<T> handler) {
			Assert.notNull(handler, "Handler must not be null");
			asyncSpec.extNotificationHandler(method, paramsType, params -> Mono
				.<Void>fromRunnable(HandlerFailures.guard(() -> handler.accept(params)))
				.subscribeOn(this.handlerScheduler));
			return this;
		}

		/**
		 * Registers the handler for a custom extension notification ({@code _}-prefixed method
		 * name) from the agent, its params delivered as the raw JSON value.
		 * @param method the method name, which must start with {@code _}
		 * @param handler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if the method name does not start with {@code _}
		 * @see #extNotificationHandler(String, TypeRef, Consumer)
		 */
		public SyncSpec extNotificationHandler(String method, Consumer<Object> handler) {
			return extNotificationHandler(method, AsyncSpec.RAW_PARAMS, handler);
		}

		/**
		 * Builds the client and connects the transport: for a
		 * {@link com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport} that starts
		 * the agent process. Call {@link AcpSyncClient#initialize()} next. A transport carries one
		 * client, so build once per transport.
		 * @return the client
		 * @throws IllegalStateException if the transport refuses to connect at once, for example
		 * because it is already connected; a connection that fails later fails the client's
		 * requests instead
		 */
		public AcpSyncClient build() {
			return new AcpSyncClient(asyncSpec.build());
		}

	}

}
