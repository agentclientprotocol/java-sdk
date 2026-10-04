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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

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
 *         .sessionUpdateConsumer(notification -> System.out.println(notification.update()))
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
 * the handlers. A request without a handler is answered {@code -32601} (method not found). Requests
 * are handled as they arrive, not queued behind session updates. Asynchronous handlers are called
 * on the transport's thread and must not block; synchronous handlers run on
 * {@link #SYNC_HANDLER_SCHEDULER}. A handler that fails is answered with an error: an
 * {@link AcpProtocolException} with its own code, anything else with {@code -32603} (internal
 * error).
 *
 * <h2>Session updates and ordering</h2>
 *
 * <p>{@code session/update} notifications go to the session update consumers one at a time, in the
 * order the agent sent them. A response completes its caller only after every notification received
 * before it has been handled, so when {@code prompt} returns, the turn's updates have all been
 * handled. A consumer must therefore not wait for a prompt in flight to complete.
 *
 * <h2>Timeouts and cancellation</h2>
 *
 * <p>Requests wait at most 60 seconds unless the builder's {@code requestTimeout} says otherwise,
 * and a prompt's answer comes only at the end of its turn, so raise it for real agents. When the
 * timeout passes, or the caller disposes a request's {@code Mono}, the client sends the agent a
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

	/**
	 * The logger the client builders log received session updates to, at DEBUG. It is public only
	 * because every field of an interface is; it is not meant for applications.
	 */
	Logger logger = LoggerFactory.getLogger(AcpClient.class);

	// ====================================================================
	// Sync Handler Scheduler (library-owned, daemon threads)
	// ====================================================================

	/**
	 * The scheduler the handlers and session update consumers of every synchronous client run on: a
	 * cached pool of daemon threads named {@code acp-sync-handler}, shared by all clients in the
	 * JVM. Its threads may block, so a handler can do file or process I/O without holding up the
	 * transport, and the daemon threads do not keep the JVM alive. The pool has no size limit:
	 * every handler running at the same time takes a thread of its own.
	 */
	Scheduler SYNC_HANDLER_SCHEDULER = Schedulers.fromExecutorService(
			Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "acp-sync-handler");
				t.setDaemon(true);
				return t;
			}), "acp-sync-handler");

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
		 * Answers one request from the agent. It runs on {@link AcpClient#SYNC_HANDLER_SCHEDULER}
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
	 * handlers, which run on {@link #SYNC_HANDLER_SCHEDULER}.
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
	 * request timeout, the session update consumers, and the handlers for the agent's requests and
	 * notifications, each returning a Reactor {@code Mono}. Get one from
	 * {@link AcpClient#async(AcpClientTransport)}. For blocking handlers, use {@link SyncSpec}.
	 *
	 * <p>Handlers are called on the transport's thread and must not block: return a {@code Mono}
	 * that completes later instead. Registering a method again replaces its handler, and a typed
	 * setter and {@link #requestHandler} for the same method replace each other. A request
	 * handler's {@code Mono} must emit the answer: an empty one is answered {@code -32603}.
	 * {@link #build()} connects the transport. A builder is not thread-safe; configure it on one
	 * thread.
	 */
	class AsyncSpec {

		/** Reads params as the raw JSON value, for the untyped extension handlers. */
		private static final TypeRef<Object> RAW_PARAMS = new TypeRef<>() {
		};

		private final AcpClientTransport transport;

		/** The SDK's one default request timeout, the same as the agent builders'. */
		private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

		private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

		private AcpSchema.@Nullable ClientCapabilities clientCapabilities;

		private AcpSchema.@Nullable Implementation clientInfo;

		private final Map<String, AcpClientSession.RequestHandler<?>> requestHandlers = new HashMap<>();

		private final Map<String, AcpClientSession.NotificationHandler> notificationHandlers = new HashMap<>();

		private final List<Function<AcpSchema.SessionNotification, Mono<Void>>> sessionUpdateConsumers = new ArrayList<>();

		private @Nullable Function<AcpSchema.CreateElicitationRequest, Mono<AcpSchema.CreateElicitationResponse>> createElicitationHandler;

		private AsyncSpec(AcpClientTransport transport) {
			Assert.notNull(transport, "Transport must not be null");
			this.transport = transport;
		}

		/**
		 * Sets how long the client waits for the agent to answer a request: {@code initialize},
		 * session calls, extension requests and {@code session/prompt}, whose answer comes only at
		 * the end of the turn. When it passes, the call fails with a
		 * {@link java.util.concurrent.TimeoutException} and the client sends the agent a
		 * {@code $/cancel_request}; a Java agent then cancels the handler, which for a prompt ends
		 * the turn. Default: 60 seconds, as for the agent builders. Raise it for agents whose
		 * turns run longer.
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
		 * Sets the capabilities the client advertises to the agent in {@code initialize}: file
		 * reads and writes, terminals, boolean config options, authentication and elicitation. This
		 * is the only place they are set, and every initialize request carries them. Register the
		 * handlers that serve them as well: the builder neither derives the capabilities from the
		 * handlers nor checks that they match, except for elicitation modes. Default:
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
		 * it for you.
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
			return request(AcpSchema.METHOD_FS_READ_TEXT_FILE, new TypeRef<AcpSchema.ReadTextFileRequest>() {
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
			return request(AcpSchema.METHOD_FS_WRITE_TEXT_FILE, new TypeRef<AcpSchema.WriteTextFileRequest>() {
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
			return request(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, new TypeRef<AcpSchema.RequestPermissionRequest>() {
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
			return request(AcpSchema.METHOD_TERMINAL_CREATE, new TypeRef<AcpSchema.CreateTerminalRequest>() {
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
			return request(AcpSchema.METHOD_TERMINAL_OUTPUT, new TypeRef<AcpSchema.TerminalOutputRequest>() {
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
			return request(AcpSchema.METHOD_TERMINAL_RELEASE, new TypeRef<AcpSchema.ReleaseTerminalRequest>() {
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
			return request(AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT, new TypeRef<AcpSchema.WaitForTerminalExitRequest>() {
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
			return request(AcpSchema.METHOD_TERMINAL_KILL, new TypeRef<AcpSchema.KillTerminalCommandRequest>() {
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
			this.requestHandlers.remove(AcpSchema.METHOD_ELICITATION_CREATE);
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
			this.notificationHandlers.put(AcpSchema.METHOD_ELICITATION_COMPLETE, params -> handler
				.apply(transport.unmarshalFrom(params, new TypeRef<AcpSchema.CompleteElicitationNotification>() {
				})));
			return this;
		}

		/**
		 * Adds a consumer for {@code session/update} notifications: the message and thought chunks,
		 * tool calls, plans and other updates the agent streams during a prompt turn, and the
		 * updates it sends between turns. The consumer's {@code Mono} completes when it has
		 * finished with a notification.
		 *
		 * <p>Notifications are delivered one at a time, in the order the agent sent them, and each
		 * goes to every consumer added: the next one waits until all consumers have finished with
		 * this one. A response from the agent, such as a prompt's, completes its caller only after
		 * the consumers have finished with every notification sent before it, so the updates of a
		 * turn are all handled when {@code prompt} returns. A slow consumer therefore delays
		 * responses, and the wait counts against the request timeout. A consumer must not wait for
		 * a prompt in flight to complete: that prompt waits for the consumer. A consumer that fails
		 * is logged, and the next notification follows.
		 * @param sessionUpdateConsumer the consumer
		 * @return this builder
		 * @throws IllegalArgumentException if {@code sessionUpdateConsumer} is null
		 */
		public AsyncSpec sessionUpdateConsumer(
				Function<AcpSchema.SessionNotification, Mono<Void>> sessionUpdateConsumer) {
			Assert.notNull(sessionUpdateConsumer, "Session update consumer must not be null");
			this.sessionUpdateConsumers.add(sessionUpdateConsumer);
			return this;
		}

		/**
		 * Registers a handler for any agent-to-client request method, with the params as the
		 * transport read them. It replaces a typed handler for the same method, and a typed setter
		 * called later replaces it. The method name is not checked: prefer the typed setters for
		 * ACP methods and {@link #extRequestHandler(String, TypeRef, Function)} for extension
		 * methods.
		 * @param method the method name
		 * @param handler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if {@code method} or {@code handler} is null
		 */
		public AsyncSpec requestHandler(String method, AcpClientSession.RequestHandler<?> handler) {
			Assert.notNull(method, "Method must not be null");
			Assert.notNull(handler, "Handler must not be null");
			if (AcpSchema.METHOD_ELICITATION_CREATE.equals(method)) {
				this.createElicitationHandler = null;
			}
			this.requestHandlers.put(method, handler);
			return this;
		}

		/**
		 * Registers a handler for any agent-to-client notification method, with the params as the
		 * transport read them. It is delivered in order with the session updates, and it is called
		 * on the delivering thread, so it must not block. If a session update consumer is added,
		 * {@code build()} replaces a handler registered here for {@code session/update}. The method
		 * name is not checked: prefer {@code extNotificationHandler} for extension methods.
		 * @param method the method name
		 * @param handler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if {@code method} or {@code handler} is null
		 */
		public AsyncSpec notificationHandler(String method, AcpClientSession.NotificationHandler handler) {
			Assert.notNull(method, "Method must not be null");
			Assert.notNull(handler, "Handler must not be null");
			this.notificationHandlers.put(method, handler);
			return this;
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
			return request(method, paramsType, params -> handler.apply(params).cast(Object.class));
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
			this.notificationHandlers.put(method, params -> handler.apply(transport.unmarshalParams(params, paramsType)));
			return this;
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
		private <Q, R> AsyncSpec request(String method, TypeRef<Q> requestType, Function<Q, Mono<R>> handler) {
			AcpClientSession.RequestHandler<R> rawHandler = params -> handler
				.apply(transport.unmarshalParams(params, requestType));
			this.requestHandlers.put(method, rawHandler);
			return this;
		}

		/**
		 * Builds the client and connects the transport: for a
		 * {@link com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport} that starts
		 * the agent process. Call {@link AcpAsyncClient#initialize()} next. A transport carries one
		 * client, so build once per transport.
		 * @return the client
		 * @throws IllegalStateException if the transport refuses to connect at once, for example
		 * because it is already connected; a connection that fails later fails the client's
		 * requests instead
		 */
		public AcpAsyncClient build() {
			// Set up session update notification handler
			if (!sessionUpdateConsumers.isEmpty()) {
				notificationHandlers.put(AcpSchema.METHOD_SESSION_UPDATE, params -> {
					AcpSchema.SessionNotification notification = transport.unmarshalParams(params,
							new TypeRef<AcpSchema.SessionNotification>() {
							});
					logger.debug("Received session update for session: {}", notification.sessionId());

					// Call all registered consumers
					return Mono
						.when(sessionUpdateConsumers.stream().map(consumer -> consumer.apply(notification)).toList());
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
			AcpSession session = new AcpClientSession(requestTimeout, transport, handlers,
					new HashMap<>(notificationHandlers), Function.identity());

			return new AcpAsyncClient(session, transport, clientCapabilities, clientInfo, advertised);
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
	 * request timeout, the session update consumers, and the handlers for the agent's requests and
	 * notifications, each returning a plain value. Get one from
	 * {@link AcpClient#sync(AcpClientTransport)}.
	 *
	 * <p>Handlers and session update consumers run on {@link AcpClient#SYNC_HANDLER_SCHEDULER}, not
	 * on the transport's thread, so they may block. Requests are handled as they arrive, so several
	 * handlers can run at the same time and state they share must be thread-safe. A request handler
	 * that returns {@code null} is answered {@code -32603}. The builder turns each handler into its
	 * asynchronous counterpart on an {@link AsyncSpec}, so the rules described there apply; the raw
	 * {@link #notificationHandler} is passed on as it is and must not block. {@link #build()}
	 * connects the transport. A builder is not thread-safe; configure it on one thread.
	 */
	class SyncSpec {

		private final AsyncSpec asyncSpec;

		private SyncSpec(AcpClientTransport transport) {
			this.asyncSpec = new AsyncSpec(transport);
		}

		/**
		 * Converts a sync request handler to an async request handler.
		 * Follows the MCP SDK pattern of wrapping sync handlers with Mono.fromCallable()
		 * and scheduling on a library-owned daemon scheduler to prevent blocking the event loop.
		 *
		 * @param <T> The response type
		 * @param syncHandler The synchronous handler to convert
		 * @return An async handler that wraps the sync handler
		 */
		private static <T> AcpClientSession.RequestHandler<T> fromSync(SyncRequestHandler<T> syncHandler) {
			return params -> onSyncHandlerThread(() -> syncHandler.handle(params));
		}

		/** Runs a sync handler on the library-owned daemon scheduler, so it may block. */
		private static <T> Mono<T> onSyncHandlerThread(Callable<T> handler) {
			return Mono.fromCallable(HandlerFailures.guard(handler)).subscribeOn(SYNC_HANDLER_SCHEDULER);
		}

		/**
		 * Sets how long the client waits for the agent to answer a request: {@code initialize},
		 * session calls, extension requests and {@code session/prompt}, whose answer comes only at
		 * the end of the turn. When it passes, the call fails with a
		 * {@link java.util.concurrent.TimeoutException} and the client sends the agent a
		 * {@code $/cancel_request}; a Java agent then cancels the handler, which for a prompt ends
		 * the turn. Default: 60 seconds, as for the agent builders. Raise it for agents whose
		 * turns run longer.
		 * @param requestTimeout the timeout
		 * @return this builder
		 * @throws IllegalArgumentException if {@code requestTimeout} is null
		 */
		public SyncSpec requestTimeout(Duration requestTimeout) {
			asyncSpec.requestTimeout(requestTimeout);
			return this;
		}

		/**
		 * Sets the capabilities the client advertises to the agent in {@code initialize}: file
		 * reads and writes, terminals, boolean config options, authentication and elicitation. This
		 * is the only place they are set, and every initialize request carries them. Register the
		 * handlers that serve them as well: the builder neither derives the capabilities from the
		 * handlers nor checks that they match, except for elicitation modes. Default:
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
		 * it for you.
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
				.subscribeOn(SYNC_HANDLER_SCHEDULER)
				.then());
			return this;
		}

		/**
		 * Adds a consumer for {@code session/update} notifications: the message and thought chunks,
		 * tool calls, plans and other updates the agent streams during a prompt turn, and the
		 * updates it sends between turns. The consumer runs on
		 * {@link AcpClient#SYNC_HANDLER_SCHEDULER} and has finished with a notification when it
		 * returns.
		 *
		 * <p>Notifications are delivered one at a time, in the order the agent sent them, and each
		 * goes to every consumer added: the next one waits until all consumers have finished with
		 * this one. A response from the agent, such as a prompt's, completes its caller only after
		 * the consumers have finished with every notification sent before it, so the updates of a
		 * turn are all handled when {@code prompt} returns. A slow consumer therefore delays
		 * responses, and the wait counts against the request timeout. A consumer must not wait for
		 * a prompt in flight to complete: that prompt waits for the consumer. A consumer that fails
		 * is logged, and the next notification follows.
		 *
		 * <pre>{@code
		 * .sessionUpdateConsumer(notification -> {
		 *     if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
		 *             && chunk.content() instanceof AcpSchema.TextContent text) {
		 *         System.out.print(text.text());
		 *     }
		 * })
		 * }</pre>
		 *
		 * @param sessionUpdateConsumer the consumer
		 * @return this builder
		 * @throws IllegalArgumentException if {@code sessionUpdateConsumer} is null
		 */
		public SyncSpec sessionUpdateConsumer(Consumer<AcpSchema.SessionNotification> sessionUpdateConsumer) {
			Assert.notNull(sessionUpdateConsumer, "Session update consumer must not be null");
			// Convert sync consumer to async Function
			asyncSpec.sessionUpdateConsumer(notification -> Mono
				.fromRunnable(HandlerFailures.guard(() -> sessionUpdateConsumer.accept(notification)))
				.subscribeOn(SYNC_HANDLER_SCHEDULER)
				.then());
			return this;
		}

		/**
		 * Registers a blocking handler for any agent-to-client request method, with the params as
		 * the transport read them. It replaces a typed handler for the same method, and a typed
		 * setter called later replaces it. The method name is not checked: prefer the typed setters
		 * for ACP methods and {@link #extRequestHandler(String, TypeRef, Function)} for extension
		 * methods.
		 * @param <T> the result type
		 * @param method the method name
		 * @param handler the handler, run on {@link AcpClient#SYNC_HANDLER_SCHEDULER}
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
		 * Registers a handler for any agent-to-client notification method, with the params as the
		 * transport read them. It is delivered in order with the session updates, and it is called
		 * on the delivering thread, so it must not block. If a session update consumer is added,
		 * {@code build()} replaces a handler registered here for {@code session/update}. The method
		 * name is not checked: prefer {@code extNotificationHandler} for extension methods.
		 * @param method the method name
		 * @param handler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if {@code method} or {@code handler} is null
		 */
		public SyncSpec notificationHandler(String method, AcpClientSession.NotificationHandler handler) {
			asyncSpec.notificationHandler(method, handler);
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
				.subscribeOn(SYNC_HANDLER_SCHEDULER));
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
