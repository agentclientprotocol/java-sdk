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
import com.agentclientprotocol.sdk.util.Assert;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Factory class for creating Agent Client Protocol (ACP) clients. ACP is a protocol that
 * enables applications to interact with autonomous coding agents through a standardized
 * interface.
 *
 * <p>
 * This class serves as the main entry point for establishing connections with ACP agents,
 * implementing the client-side of the ACP specification. The protocol follows a
 * client-agent architecture where:
 * <ul>
 * <li>The client (this implementation) initiates connections and sends prompts</li>
 * <li>The agent responds to prompts and can request client capabilities (file access,
 * etc.)</li>
 * <li>Communication occurs through a transport layer (e.g., stdio) using JSON-RPC
 * 2.0</li>
 * </ul>
 *
 * <p>
 * The class provides factory methods to create either:
 * <ul>
 * <li>{@link AcpAsyncClient} for non-blocking operations with Mono responses</li>
 * <li>{@link AcpSyncClient} for blocking operations with direct responses (future)</li>
 * </ul>
 *
 * <p>
 * Example of creating a basic asynchronous client:
 *
 * <pre>{@code
 * // Create transport
 * AgentParameters params = AgentParameters.builder("gemini")
 *     .arg("--experimental-acp")
 *     .build();
 * StdioAcpClientTransport transport = new StdioAcpClientTransport(params, AcpJsonMapper.createDefault());
 *
 * // Build client
 * AcpAsyncClient client = AcpClient.async(transport)
 *     .requestTimeout(Duration.ofSeconds(30))
 *     .sessionUpdateConsumer(notification -> {
 *         System.out.println("Session update: " + notification);
 *         return Mono.empty();
 *     })
 *     .build();
 *
 * // Initialize and use
 * client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()))
 *     .flatMap(initResponse -> client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())))
 *     .flatMap(sessionResponse -> client.prompt(new AcpSchema.PromptRequest(
 *         sessionResponse.sessionId(),
 *         List.of(new AcpSchema.TextContent("Fix the failing test")))))
 *     .doOnNext(response -> System.out.println("Response: " + response))
 *     .block();
 *
 * client.closeGracefully().block();
 * }</pre>
 *
 * <p>
 * The client supports:
 * <ul>
 * <li>Protocol version negotiation and capability exchange</li>
 * <li>Optional authentication with various methods</li>
 * <li>Session creation and management</li>
 * <li>Prompt submission with streaming updates</li>
 * <li>File system operations (read/write) through client handlers</li>
 * <li>Permission requests for sensitive operations</li>
 * <li>Terminal operations for command execution</li>
 * </ul>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 * @see AcpAsyncClient
 * @see AcpClientTransport
 */
public interface AcpClient {

	Logger logger = LoggerFactory.getLogger(AcpClient.class);

	// ====================================================================
	// Sync Handler Scheduler (library-owned, daemon threads)
	// ====================================================================

	/**
	 * Library-owned scheduler for executing synchronous handlers.
	 * Uses daemon threads with descriptive names to prevent JVM hang on exit.
	 * This follows the best practice of never using global Schedulers.boundedElastic().
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
	 * Functional interface for synchronous request handlers. Unlike
	 * {@link AcpClientSession.RequestHandler}, this interface returns the response
	 * directly without wrapping in Mono, making it natural for blocking I/O operations.
	 *
	 * <p>Use with {@link SyncSpec} builder methods to register handlers that don't
	 * require reactive programming patterns.
	 *
	 * @param <T> The response type
	 */
	@FunctionalInterface
	interface SyncRequestHandler<T> {
		/**
		 * Handles an incoming request with the given parameters.
		 * @param params The raw request parameters (requires unmarshalling)
		 * @return The response object
		 */
		T handle(Object params);
	}

	/**
	 * Start building a synchronous ACP client with the specified transport layer. The
	 * synchronous ACP client provides blocking operations. Synchronous clients wait for
	 * each operation to complete before returning, making them simpler to use but
	 * potentially less performant for concurrent operations.
	 * @param transport The transport layer implementation for ACP communication
	 * @return A new builder instance for configuring the client
	 * @throws IllegalArgumentException if transport is null
	 */
	static SyncSpec sync(AcpClientTransport transport) {
		return new SyncSpec(transport);
	}

	/**
	 * Start building an asynchronous ACP client with the specified transport layer. The
	 * asynchronous ACP client provides non-blocking operations using Project Reactor's
	 * Mono type. The transport layer handles the low-level communication between client
	 * and agent using protocols like stdio.
	 * @param transport The transport layer implementation for ACP communication. Common
	 * implementation is {@code StdioAcpClientTransport} for stdio-based communication.
	 * @return A new builder instance for configuring the client
	 * @throws IllegalArgumentException if transport is null
	 */
	static AsyncSpec async(AcpClientTransport transport) {
		return new AsyncSpec(transport);
	}

	/**
	 * Asynchronous client specification. This class follows the builder pattern to
	 * provide a fluent API for setting up clients with custom configurations.
	 *
	 * <p>
	 * The builder supports configuration of:
	 * <ul>
	 * <li>Transport layer for client-agent communication</li>
	 * <li>Request timeouts for operation boundaries</li>
	 * <li>Client capabilities for feature negotiation</li>
	 * <li>Request handlers for incoming agent requests (file operations, etc.)</li>
	 * <li>Notification handlers for streaming updates</li>
	 * </ul>
	 */
	class AsyncSpec {

		private final AcpClientTransport transport;

		private Duration requestTimeout = Duration.ofSeconds(30); // Default timeout

		private AcpSchema.@Nullable ClientCapabilities clientCapabilities;

		private final Map<String, AcpClientSession.RequestHandler<?>> requestHandlers = new HashMap<>();

		private final Map<String, AcpClientSession.NotificationHandler> notificationHandlers = new HashMap<>();

		private final List<Function<AcpSchema.SessionNotification, Mono<Void>>> sessionUpdateConsumers = new ArrayList<>();

		private @Nullable Function<AcpSchema.CreateElicitationRequest, Mono<AcpSchema.CreateElicitationResponse>> createElicitationHandler;

		private AsyncSpec(AcpClientTransport transport) {
			Assert.notNull(transport, "Transport must not be null");
			this.transport = transport;
		}

		/**
		 * Sets the duration to wait for agent responses before timing out requests. This
		 * timeout applies to all requests made through the client, including initialize,
		 * prompt, and session operations.
		 * @param requestTimeout The duration to wait before timing out requests. Must not
		 * be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if requestTimeout is null
		 */
		public AsyncSpec requestTimeout(Duration requestTimeout) {
			Assert.notNull(requestTimeout, "Request timeout must not be null");
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets the client capabilities that will be advertised to the agent during
		 * initialization. Capabilities define what features the client supports, such as
		 * file system operations, terminal access, and authentication methods.
		 * @param clientCapabilities The client capabilities configuration. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if clientCapabilities is null
		 */
		public AsyncSpec clientCapabilities(AcpSchema.ClientCapabilities clientCapabilities) {
			Assert.notNull(clientCapabilities, "Client capabilities must not be null");
			this.clientCapabilities = clientCapabilities;
			return this;
		}

		/**
		 * Adds a typed handler for file system read requests from the agent.
		 * This is the preferred method as it provides type-safe request handling
		 * without manual unmarshalling.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .readTextFileHandler(req ->
		 *     Mono.fromCallable(() -> Files.readString(Path.of(req.path())))
		 *         .map(ReadTextFileResponse::new))
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes read requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec readTextFileHandler(
				Function<AcpSchema.ReadTextFileRequest, Mono<AcpSchema.ReadTextFileResponse>> handler) {
			Assert.notNull(handler, "Read text file handler must not be null");
			return request(AcpSchema.METHOD_FS_READ_TEXT_FILE, new TypeRef<AcpSchema.ReadTextFileRequest>() {
			}, handler);
		}

		/**
		 * Adds a typed handler for file system write requests from the agent.
		 * This is the preferred method as it provides type-safe request handling
		 * without manual unmarshalling.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .writeTextFileHandler(req ->
		 *     Mono.fromRunnable(() -> Files.writeString(Path.of(req.path()), req.content()))
		 *         .then(Mono.just(new WriteTextFileResponse())))
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes write requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec writeTextFileHandler(
				Function<AcpSchema.WriteTextFileRequest, Mono<AcpSchema.WriteTextFileResponse>> handler) {
			Assert.notNull(handler, "Write text file handler must not be null");
			return request(AcpSchema.METHOD_FS_WRITE_TEXT_FILE, new TypeRef<AcpSchema.WriteTextFileRequest>() {
			}, handler);
		}

		/**
		 * Adds a typed handler for permission requests from the agent.
		 * This is the preferred method as it provides type-safe request handling
		 * without manual unmarshalling.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .requestPermissionHandler(req ->
		 *     Mono.just(new RequestPermissionResponse(
		 *         new RequestPermissionOutcome("approve", null))))
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes permission requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec requestPermissionHandler(
				Function<AcpSchema.RequestPermissionRequest, Mono<AcpSchema.RequestPermissionResponse>> handler) {
			Assert.notNull(handler, "Request permission handler must not be null");
			return request(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, new TypeRef<AcpSchema.RequestPermissionRequest>() {
			}, handler);
		}

		/**
		 * Adds a typed handler for terminal creation requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .createTerminalHandler(req -> {
		 *     String terminalId = UUID.randomUUID().toString();
		 *     // Start process with req.command(), req.args(), req.cwd()
		 *     return Mono.just(new CreateTerminalResponse(terminalId));
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal creation requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec createTerminalHandler(
				Function<AcpSchema.CreateTerminalRequest, Mono<AcpSchema.CreateTerminalResponse>> handler) {
			Assert.notNull(handler, "Create terminal handler must not be null");
			return request(AcpSchema.METHOD_TERMINAL_CREATE, new TypeRef<AcpSchema.CreateTerminalRequest>() {
			}, handler);
		}

		/**
		 * Adds a typed handler for terminal output requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .terminalOutputHandler(req -> {
		 *     String output = getTerminalOutput(req.terminalId());
		 *     return Mono.just(new TerminalOutputResponse(output, false, null));
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal output requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec terminalOutputHandler(
				Function<AcpSchema.TerminalOutputRequest, Mono<AcpSchema.TerminalOutputResponse>> handler) {
			Assert.notNull(handler, "Terminal output handler must not be null");
			return request(AcpSchema.METHOD_TERMINAL_OUTPUT, new TypeRef<AcpSchema.TerminalOutputRequest>() {
			}, handler);
		}

		/**
		 * Adds a typed handler for terminal release requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .releaseTerminalHandler(req -> {
		 *     releaseTerminal(req.terminalId());
		 *     return Mono.just(new ReleaseTerminalResponse());
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal release requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec releaseTerminalHandler(
				Function<AcpSchema.ReleaseTerminalRequest, Mono<AcpSchema.ReleaseTerminalResponse>> handler) {
			Assert.notNull(handler, "Release terminal handler must not be null");
			return request(AcpSchema.METHOD_TERMINAL_RELEASE, new TypeRef<AcpSchema.ReleaseTerminalRequest>() {
			}, handler);
		}

		/**
		 * Adds a typed handler for wait-for-terminal-exit requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .waitForTerminalExitHandler(req -> {
		 *     int exitCode = waitForExit(req.terminalId());
		 *     return Mono.just(new WaitForTerminalExitResponse(exitCode, null));
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes wait-for-exit requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec waitForTerminalExitHandler(
				Function<AcpSchema.WaitForTerminalExitRequest, Mono<AcpSchema.WaitForTerminalExitResponse>> handler) {
			Assert.notNull(handler, "Wait for terminal exit handler must not be null");
			return request(AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT, new TypeRef<AcpSchema.WaitForTerminalExitRequest>() {
			}, handler);
		}

		/**
		 * Adds a typed handler for terminal kill requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .killTerminalHandler(req -> {
		 *     killProcess(req.terminalId());
		 *     return Mono.just(new KillTerminalCommandResponse());
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal kill requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec killTerminalHandler(
				Function<AcpSchema.KillTerminalCommandRequest, Mono<AcpSchema.KillTerminalCommandResponse>> handler) {
			Assert.notNull(handler, "Kill terminal handler must not be null");
			return request(AcpSchema.METHOD_TERMINAL_KILL, new TypeRef<AcpSchema.KillTerminalCommandRequest>() {
			}, handler);
		}

		/**
		 * Adds a typed handler for {@code elicitation/create} requests from the agent,
		 * which ask the user for structured input through a form or a URL. The handler
		 * answers {@code accept}, {@code decline} or {@code cancel}.
		 *
		 * <p>
		 * The client answers a request whose mode ({@code form} or {@code url}) it did
		 * not advertise in {@code clientCapabilities.elicitation} at initialization with
		 * a JSON-RPC {@code -32602} (invalid params) error, without calling the handler.
		 * Advertise the modes the handler supports, for example with
		 * {@link AcpSchema.ElicitationCapabilities#formOnly()}.
		 *
		 * @param handler The typed handler function that processes elicitation requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec createElicitationHandler(
				Function<AcpSchema.CreateElicitationRequest, Mono<AcpSchema.CreateElicitationResponse>> handler) {
			Assert.notNull(handler, "Create elicitation handler must not be null");
			this.requestHandlers.remove(AcpSchema.METHOD_ELICITATION_CREATE);
			this.createElicitationHandler = handler;
			return this;
		}

		/**
		 * Adds a typed handler for {@code elicitation/complete} notifications: the agent
		 * reports that the external interaction of a URL-mode elicitation has finished.
		 * The spec requires clients to ignore unknown or already-completed elicitation
		 * IDs, so the handler should check the ID against the URL elicitations it
		 * accepted.
		 *
		 * @param handler The typed handler function that processes completion
		 * notifications
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
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
		 * Adds a consumer to be notified when session update notifications are received
		 * from the agent. Session updates include agent thoughts, message chunks, and
		 * other streaming content during prompt processing.
		 * @param sessionUpdateConsumer A consumer that receives session update
		 * notifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if sessionUpdateConsumer is null
		 */
		public AsyncSpec sessionUpdateConsumer(
				Function<AcpSchema.SessionNotification, Mono<Void>> sessionUpdateConsumer) {
			Assert.notNull(sessionUpdateConsumer, "Session update consumer must not be null");
			this.sessionUpdateConsumers.add(sessionUpdateConsumer);
			return this;
		}

		/**
		 * Adds a custom request handler for a specific method. This allows handling
		 * additional agent requests beyond the standard file system and permission
		 * operations.
		 * @param method The method name (e.g., "custom/operation")
		 * @param handler The handler function for this method
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if method or handler is null
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
		 * Adds a custom notification handler for a specific method. This allows handling
		 * additional agent notifications beyond session updates.
		 * @param method The method name (e.g., "custom/notification")
		 * @param handler The handler function for this method
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if method or handler is null
		 */
		public AsyncSpec notificationHandler(String method, AcpClientSession.NotificationHandler handler) {
			Assert.notNull(method, "Method must not be null");
			Assert.notNull(handler, "Handler must not be null");
			this.notificationHandlers.put(method, handler);
			return this;
		}

		/** Reads the params as the request type, then calls the handler. */
		private <Q, R> AsyncSpec request(String method, TypeRef<Q> requestType, Function<Q, Mono<R>> handler) {
			AcpClientSession.RequestHandler<R> rawHandler = params -> handler
				.apply(transport.unmarshalParams(params, requestType));
			this.requestHandlers.put(method, rawHandler);
			return this;
		}

		/**
		 * Creates an instance of {@link AcpAsyncClient} with the provided configurations
		 * or sensible defaults.
		 * @return a new instance of {@link AcpAsyncClient}
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

			return new AcpAsyncClient(session, transport, clientCapabilities, advertised);
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
	 * Synchronous client specification. This class follows the builder pattern to
	 * provide a fluent API for setting up synchronous clients with custom configurations.
	 *
	 * <p>
	 * The builder supports configuration of:
	 * <ul>
	 * <li>Transport layer for client-agent communication</li>
	 * <li>Request timeouts for operation boundaries</li>
	 * <li>Client capabilities for feature negotiation</li>
	 * <li>Request handlers for incoming agent requests (file operations, etc.)</li>
	 * <li>Notification handlers for streaming updates</li>
	 * </ul>
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
			return Mono.fromCallable(handler).subscribeOn(SYNC_HANDLER_SCHEDULER);
		}

		/**
		 * Sets the duration to wait for agent responses before timing out requests. This
		 * timeout applies to all requests made through the client, including initialize,
		 * prompt, and session operations.
		 * @param requestTimeout The duration to wait before timing out requests. Must not
		 * be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if requestTimeout is null
		 */
		public SyncSpec requestTimeout(Duration requestTimeout) {
			asyncSpec.requestTimeout(requestTimeout);
			return this;
		}

		/**
		 * Sets the client capabilities that will be advertised to the agent during
		 * initialization. Capabilities define what features the client supports, such as
		 * file system operations, terminal access, and authentication methods.
		 * @param clientCapabilities The client capabilities configuration. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if clientCapabilities is null
		 */
		public SyncSpec clientCapabilities(AcpSchema.ClientCapabilities clientCapabilities) {
			asyncSpec.clientCapabilities(clientCapabilities);
			return this;
		}

		/**
		 * Adds a typed handler for file system read requests from the agent.
		 * Provides type-safe request handling with automatic unmarshalling.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .readTextFileHandler(req ->
		 *     new ReadTextFileResponse(Files.readString(Path.of(req.path()))))
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes read requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec readTextFileHandler(
				Function<AcpSchema.ReadTextFileRequest, AcpSchema.ReadTextFileResponse> handler) {
			Assert.notNull(handler, "Read text file handler must not be null");
			asyncSpec.readTextFileHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Adds a typed handler for file system write requests from the agent.
		 * Provides type-safe request handling with automatic unmarshalling.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .writeTextFileHandler(req -> {
		 *     Files.writeString(Path.of(req.path()), req.content());
		 *     return new WriteTextFileResponse();
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes write requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec writeTextFileHandler(
				Function<AcpSchema.WriteTextFileRequest, AcpSchema.WriteTextFileResponse> handler) {
			Assert.notNull(handler, "Write text file handler must not be null");
			asyncSpec.writeTextFileHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Adds a typed handler for permission requests from the agent.
		 * Provides type-safe request handling with automatic unmarshalling.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .requestPermissionHandler(req -> {
		 *     System.out.println("Permission requested: " + req.toolCall().title());
		 *     // Show UI or auto-approve
		 *     return new RequestPermissionResponse(
		 *         new RequestPermissionOutcome("approve", null));
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes permission requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec requestPermissionHandler(
				Function<AcpSchema.RequestPermissionRequest, AcpSchema.RequestPermissionResponse> handler) {
			Assert.notNull(handler, "Request permission handler must not be null");
			asyncSpec.requestPermissionHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Adds a typed handler for terminal creation requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .createTerminalHandler(req -> {
		 *     String terminalId = UUID.randomUUID().toString();
		 *     // Start process with req.command(), req.args(), req.cwd()
		 *     return new CreateTerminalResponse(terminalId);
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal creation requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec createTerminalHandler(
				Function<AcpSchema.CreateTerminalRequest, AcpSchema.CreateTerminalResponse> handler) {
			Assert.notNull(handler, "Create terminal handler must not be null");
			asyncSpec.createTerminalHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Adds a typed handler for terminal output requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .terminalOutputHandler(req -> {
		 *     String output = getTerminalOutput(req.terminalId());
		 *     return new TerminalOutputResponse(output, false, null);
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal output requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec terminalOutputHandler(
				Function<AcpSchema.TerminalOutputRequest, AcpSchema.TerminalOutputResponse> handler) {
			Assert.notNull(handler, "Terminal output handler must not be null");
			asyncSpec.terminalOutputHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Adds a typed handler for terminal release requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .releaseTerminalHandler(req -> {
		 *     releaseTerminal(req.terminalId());
		 *     return new ReleaseTerminalResponse();
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal release requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec releaseTerminalHandler(
				Function<AcpSchema.ReleaseTerminalRequest, AcpSchema.ReleaseTerminalResponse> handler) {
			Assert.notNull(handler, "Release terminal handler must not be null");
			asyncSpec.releaseTerminalHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Adds a typed handler for wait-for-terminal-exit requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .waitForTerminalExitHandler(req -> {
		 *     int exitCode = waitForExit(req.terminalId());
		 *     return new WaitForTerminalExitResponse(exitCode, null);
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes wait-for-exit requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec waitForTerminalExitHandler(
				Function<AcpSchema.WaitForTerminalExitRequest, AcpSchema.WaitForTerminalExitResponse> handler) {
			Assert.notNull(handler, "Wait for terminal exit handler must not be null");
			asyncSpec.waitForTerminalExitHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Adds a typed handler for terminal kill requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .killTerminalHandler(req -> {
		 *     killProcess(req.terminalId());
		 *     return new KillTerminalCommandResponse();
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal kill requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec killTerminalHandler(
				Function<AcpSchema.KillTerminalCommandRequest, AcpSchema.KillTerminalCommandResponse> handler) {
			Assert.notNull(handler, "Kill terminal handler must not be null");
			asyncSpec.killTerminalHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Adds a typed handler for {@code elicitation/create} requests from the agent.
		 * See {@link AsyncSpec#createElicitationHandler(Function)}: a request for a mode
		 * the client did not advertise is answered with {@code -32602} without calling
		 * the handler.
		 *
		 * @param handler The typed handler function that processes elicitation requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec createElicitationHandler(
				Function<AcpSchema.CreateElicitationRequest, AcpSchema.CreateElicitationResponse> handler) {
			Assert.notNull(handler, "Create elicitation handler must not be null");
			asyncSpec.createElicitationHandler(request -> onSyncHandlerThread(() -> handler.apply(request)));
			return this;
		}

		/**
		 * Adds a synchronous handler for {@code elicitation/complete} notifications: the
		 * agent reports that the external interaction of a URL-mode elicitation has
		 * finished. Clients must ignore unknown or already-completed elicitation IDs.
		 *
		 * @param handler The handler that processes completion notifications
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec completeElicitationHandler(Consumer<AcpSchema.CompleteElicitationNotification> handler) {
			Assert.notNull(handler, "Complete elicitation handler must not be null");
			asyncSpec.completeElicitationHandler(notification -> Mono
				.fromRunnable(() -> handler.accept(notification))
				.subscribeOn(SYNC_HANDLER_SCHEDULER)
				.then());
			return this;
		}

		/**
		 * Adds a synchronous consumer to be notified when session update notifications
		 * are received from the agent. This is the preferred method for sync clients.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .sessionUpdateConsumer(notification -> {
		 *     if (notification.update() instanceof AgentMessageChunk msg) {
		 *         System.out.println(msg.content());
		 *     }
		 * })
		 * }</pre>
		 *
		 * @param sessionUpdateConsumer A consumer that receives session update
		 * notifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if sessionUpdateConsumer is null
		 */
		public SyncSpec sessionUpdateConsumer(Consumer<AcpSchema.SessionNotification> sessionUpdateConsumer) {
			Assert.notNull(sessionUpdateConsumer, "Session update consumer must not be null");
			// Convert sync consumer to async Function
			asyncSpec.sessionUpdateConsumer(notification -> Mono
				.fromRunnable(() -> sessionUpdateConsumer.accept(notification))
				.subscribeOn(SYNC_HANDLER_SCHEDULER)
				.then());
			return this;
		}

		/**
		 * Adds a synchronous custom request handler for a specific method.
		 * This is the preferred method for sync clients.
		 *
		 * @param <T> The response type
		 * @param method The method name (e.g., "custom/operation")
		 * @param handler The synchronous handler function for this method
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if method or handler is null
		 */
		public <T> SyncSpec requestHandler(String method, SyncRequestHandler<T> handler) {
			Assert.notNull(method, "Method must not be null");
			Assert.notNull(handler, "Handler must not be null");
			asyncSpec.requestHandler(method, fromSync(handler));
			return this;
		}

		/**
		 * Adds a custom notification handler for a specific method. This allows handling
		 * additional agent notifications beyond session updates.
		 * @param method The method name (e.g., "custom/notification")
		 * @param handler The handler function for this method
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if method or handler is null
		 */
		public SyncSpec notificationHandler(String method, AcpClientSession.NotificationHandler handler) {
			asyncSpec.notificationHandler(method, handler);
			return this;
		}

		/**
		 * Creates an instance of {@link AcpSyncClient} with the provided configurations
		 * or sensible defaults.
		 * @return a new instance of {@link AcpSyncClient}
		 */
		public AcpSyncClient build() {
			return new AcpSyncClient(asyncSpec.build());
		}

	}

}
