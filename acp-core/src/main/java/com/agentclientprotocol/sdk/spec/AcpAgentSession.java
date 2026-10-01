/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;


import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import com.agentclientprotocol.sdk.util.Assert;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Agent-side implementation of the ACP (Agent Client Protocol) session that manages
 * bidirectional JSON-RPC communication between agents and clients. This is the agent-side
 * counterpart to {@link AcpClientSession}.
 *
 * <p>
 * The session manages:
 * <ul>
 * <li>Request/response handling with unique message IDs</li>
 * <li>Notification processing</li>
 * <li>Message timeout management</li>
 * <li>Transport layer abstraction</li>
 * <li>Single-turn enforcement (only one prompt active at a time per session; a turn ends
 * with its prompt's response, also after {@code session/cancel})</li>
 * </ul>
 *
 * <p>
 * This is the agent-side session that receives requests from clients (initialize,
 * newSession, prompt, etc.) and sends requests to clients (readTextFile, writeTextFile,
 * requestPermission, etc.)
 * </p>
 *
 * @author Mark Pollack
 */
public class AcpAgentSession implements AcpSession {

	private static final Logger logger = LoggerFactory.getLogger(AcpAgentSession.class);

	/** Transport layer implementation for message exchange */
	private final AcpAgentTransport transport;

	/** Requests and notifications sent to the client, and the requests waiting for a response */
	private final OutboundMessages outbound;

	/** Map of request handlers keyed by method name */
	private final ConcurrentHashMap<String, RequestHandler<?>> requestHandlers = new ConcurrentHashMap<>();

	/** Map of notification handlers keyed by method name */
	private final ConcurrentHashMap<String, NotificationHandler> notificationHandlers = new ConcurrentHashMap<>();

	/** Single-turn enforcement: the active prompt of each logical ACP sessionId. */
	private final ActivePrompts activePrompts = new ActivePrompts();

	/** The cancel grace period and maximum prompt duration of each prompt. */
	private final PromptDeadlines promptDeadlines;

	/**
	 * Set when the transport's {@code start()} fails (already started, port in use, ...).
	 * Requests to the client are then failed immediately with the cause.
	 */
	private volatile @Nullable Throwable startFailure;

	/**
	 * Functional interface for handling incoming JSON-RPC requests. Implementations
	 * should process the request parameters and return a response.
	 *
	 * @param <T> Response type
	 */
	@FunctionalInterface
	public interface RequestHandler<T> {

		/**
		 * Handles an incoming request with the given parameters.
		 * @param params The request parameters; an omitted params arrives as an empty
		 * object
		 * @return A Mono containing the response object; it must not complete empty
		 */
		Mono<T> handle(Object params);

	}

	/**
	 * Functional interface for handling incoming JSON-RPC notifications. Implementations
	 * should process the notification parameters without returning a response.
	 */
	@FunctionalInterface
	public interface NotificationHandler {

		/**
		 * Handles an incoming notification with the given parameters.
		 * @param params The notification parameters; an omitted params arrives as an empty
		 * object
		 * @return A Mono that completes when the notification is processed
		 */
		Mono<Void> handle(Object params);

	}

	/**
	 * Creates a new AcpAgentSession with the specified configuration and handlers, and the
	 * default prompt timeouts ({@link PromptTimeouts#DEFAULTS}).
	 * @param requestTimeout Duration to wait for responses
	 * @param transport Transport implementation for message exchange
	 * @param requestHandlers Map of method names to request handlers
	 * @param notificationHandlers Map of method names to notification handlers
	 */
	public AcpAgentSession(Duration requestTimeout, AcpAgentTransport transport,
			Map<String, RequestHandler<?>> requestHandlers, Map<String, NotificationHandler> notificationHandlers) {
		this(requestTimeout, transport, requestHandlers, notificationHandlers, PromptTimeouts.DEFAULTS);
	}

	/**
	 * Creates a new AcpAgentSession with the specified configuration and handlers.
	 * @param requestTimeout Duration to wait for responses
	 * @param transport Transport implementation for message exchange
	 * @param requestHandlers Map of method names to request handlers
	 * @param notificationHandlers Map of method names to notification handlers
	 * @param promptTimeouts when the session answers a prompt its handler has not: the cancel
	 * grace period and the maximum prompt duration
	 */
	public AcpAgentSession(Duration requestTimeout, AcpAgentTransport transport,
			Map<String, RequestHandler<?>> requestHandlers, Map<String, NotificationHandler> notificationHandlers,
			PromptTimeouts promptTimeouts) {
		this(requestTimeout, transport, requestHandlers, notificationHandlers, promptTimeouts, AcpSchedulers::after);
	}

	/** As above, with the timer the prompt deadlines run on (a virtual-time one in tests). */
	AcpAgentSession(Duration requestTimeout, AcpAgentTransport transport,
			Map<String, RequestHandler<?>> requestHandlers, Map<String, NotificationHandler> notificationHandlers,
			PromptTimeouts promptTimeouts, Function<Duration, Mono<?>> timer) {

		Assert.notNull(requestTimeout, "The requestTimeout can not be null");
		Assert.notNull(promptTimeouts, "The promptTimeouts can not be null");
		Assert.notNull(transport, "The transport can not be null");
		Assert.notNull(requestHandlers, "The requestHandlers can not be null");
		Assert.notNull(notificationHandlers, "The notificationHandlers can not be null");

		this.transport = transport;
		this.promptDeadlines = new PromptDeadlines(promptTimeouts, timer);
		this.outbound = new OutboundMessages(transport, requestTimeout, () -> this.startFailure,
				AcpAgentSession::notStarted, "client");
		this.requestHandlers.putAll(requestHandlers);
		this.notificationHandlers.putAll(notificationHandlers);

		this.transport.start(mono -> mono.flatMap(this::handle)).subscribe(v -> {
		}, this::onStartFailure);

		// A transport that refuses synchronously (every transport does when asked to start
		// twice) fails construction rather than handing back a session that can never talk.
		Throwable failure = this.startFailure;
		if (failure != null) {
			throw notStarted(failure);
		}
	}

	private void onStartFailure(Throwable error) {
		this.startFailure = error;
		logger.error("ACP agent transport failed to start; every request on this session will fail: {}",
				error.getMessage());
		dismissPendingResponses(error);
	}

	private static IllegalStateException notStarted(Throwable cause) {
		return new IllegalStateException("ACP agent transport is not started: " + cause.getMessage(), cause);
	}

	private void dismissPendingResponses() {
		dismissPendingResponses(null);
	}

	private void dismissPendingResponses(@Nullable Throwable cause) {
		this.outbound.dismissPending(cause);
	}

	/**
	 * Handles an incoming JSON-RPC message and returns an optional response message.
	 * @param message The incoming message
	 * @return A Mono containing the response message, or empty for notifications
	 */
	private Mono<AcpSchema.JSONRPCMessage> handle(AcpSchema.JSONRPCMessage message) {
		if (message instanceof AcpSchema.JSONRPCResponse response) {
			this.outbound.complete(response);
			return Mono.empty();
		}
		if (message instanceof AcpSchema.JSONRPCRequest request) {
			logger.debug("Received request method={} id={}", request.method(), request.id());
			// Mono.from widens the response Mono to the message type without an operator.
			return Mono.from(handleIncomingRequest(request)
				.onErrorResume(error -> Mono.just(InboundMessages.error(request, error))));
		}
		if (message instanceof AcpSchema.JSONRPCNotification notification) {
			logger.debug("Received notification method={}", notification.method());
			return handleIncomingNotification(notification).then(Mono.empty());
		}
		logger.warn("Received unknown message type: {}", message.getClass().getName());
		return Mono.empty();
	}

	/**
	 * Handles an incoming JSON-RPC request by routing it to the appropriate handler.
	 * For session/prompt requests, enforces single-turn semantics.
	 * @param request The incoming JSON-RPC request
	 * @return A Mono containing the JSON-RPC response
	 */
	private Mono<AcpSchema.JSONRPCResponse> handleIncomingRequest(AcpSchema.JSONRPCRequest request) {
		return Mono.defer(() -> {
			var handler = this.requestHandlers.get(request.method());
			if (handler == null) {
				MethodNotFoundError error = getMethodNotFoundError(request.method());
				return Mono.just(InboundMessages.error(request, AcpErrorCodes.METHOD_NOT_FOUND, error.message(),
						error.data()));
			}

			// Single-turn enforcement for session/prompt requests
			if (AcpSchema.METHOD_SESSION_PROMPT.equals(request.method())) {
				String sessionId = extractSessionId(request.params());
				ActivePrompts.Turn turn = activePrompts.tryStart(sessionId, request.id());
				if (turn == null) {
					// -32600, not -32000: ACP v1 defines -32000 as "Authentication required".
					return Mono.just(InboundMessages.error(request, AcpErrorCodes.INVALID_REQUEST,
							"There is already an active prompt on session " + sessionId,
							Map.of("sessionId", sessionId)));
				}

				// The turn ends before the response is published (#14): see
				// ActivePrompts.endBeforePublishing. Mono.defer keeps a handler that throws
				// synchronously from holding the turn forever: the throw becomes an error
				// signal that passes through the release.
				// The deadlines may answer instead of the handler; either answer passes
				// through the release.
				return activePrompts.endBeforePublishing(turn, promptDeadlines.answer(turn, request,
						InboundMessages.requireResult(Mono.defer(() -> handler.handle(InboundMessages.paramsOrEmpty(request.params()))),
								request.method())
							.map(result -> InboundMessages.result(request, result))));
			}

			return InboundMessages.requireResult(Mono.defer(() -> handler.handle(InboundMessages.paramsOrEmpty(request.params()))),
						request.method())
				.map(result -> InboundMessages.result(request, result));
		});
	}

	/**
	 * Extracts the sessionId from request parameters, which arrive as a {@code Map} from
	 * the JSON transports and as the typed record from in-process transports.
	 */
	private String extractSessionId(@Nullable Object params) {
		// The typed records come from in-process callers, which no nullness checker may
		// have seen; a null key would fail the activePrompts map.
		if (params instanceof AcpSchema.PromptRequest promptRequest) {
			return promptRequest.sessionId() != null ? promptRequest.sessionId() : "unknown";
		}
		if (params instanceof AcpSchema.CancelNotification cancelNotification) {
			return cancelNotification.sessionId() != null ? cancelNotification.sessionId() : "unknown";
		}
		if (params instanceof Map<?, ?> map) {
			Object sessionId = map.get("sessionId");
			return sessionId != null ? sessionId.toString() : "unknown";
		}
		return "unknown";
	}

	record MethodNotFoundError(String method, String message, @Nullable Object data) {
	}

	private MethodNotFoundError getMethodNotFoundError(String method) {
		return new MethodNotFoundError(method, "Method not found: " + method, null);
	}

	/**
	 * Handles an incoming JSON-RPC notification by routing it to the appropriate handler.
	 * A session/cancel notification does not end the active prompt's turn: the cancelled
	 * prompt's response does (see ActivePrompts#cancel).
	 * @param notification The incoming JSON-RPC notification
	 * @return A Mono that completes when the notification is processed
	 */
	private Mono<Void> handleIncomingNotification(AcpSchema.JSONRPCNotification notification) {
		return Mono.defer(() -> {
			// session/cancel: the turn stays until the cancelled prompt answers
			if (AcpSchema.METHOD_SESSION_CANCEL.equals(notification.method())) {
				activePrompts.cancel(extractSessionId(notification.params()));
			}

			return InboundMessages.deliver(logger, notification, this.notificationHandlers, NotificationHandler::handle);
		});
	}

	/**
	 * Sends a JSON-RPC request to the client and expects a response of type T.
	 * This is used for agent→client requests like fs/read_text_file, terminal/*, etc.
	 * @param <T> the type of the expected response
	 * @param method the name of the method to call on the client
	 * @param requestParams the parameters to send with the request
	 * @param typeRef the TypeReference describing the expected response type
	 * @return a Mono that will emit the response when received; an error response fails
	 * it with {@link AcpError}
	 */
	@Override
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef) {
		return this.outbound.sendRequest(method, requestParams, typeRef);
	}

	/**
	 * Sends a JSON-RPC notification to the client.
	 * This is used for agent→client notifications like session/update.
	 * @param method the name of the notification method
	 * @param params the notification parameters
	 * @return a Mono that completes when the notification is sent
	 */
	@Override
	public Mono<Void> sendNotification(String method, @Nullable Object params) {
		return this.outbound.sendNotification(method, params);
	}

	/**
	 * Checks if there is an active prompt being processed.
	 * @return true if a prompt is currently active
	 */
	public boolean hasActivePrompt() {
		return !activePrompts.isEmpty();
	}

	/**
	 * Checks if there is an active prompt being processed for the specified logical
	 * ACP session.
	 * @param sessionId the logical ACP session ID
	 * @return true if a prompt is currently active for the session
	 */
	public boolean hasActivePrompt(String sessionId) {
		Assert.hasText(sessionId, "The sessionId can not be empty");
		return activePrompts.isActive(sessionId);
	}

	/**
	 * Gets one active prompt session ID, if any.
	 *
	 * <p>
	 * This is a legacy aggregate view. When multiple logical ACP sessions are active on
	 * the same transport connection, the returned session ID is arbitrary.
	 * </p>
	 * @return one active session ID or null if no prompt is active
	 */
	public @Nullable String getActivePromptSessionId() {
		return activePrompts.anySessionId();
	}

	/**
	 * Gets the logical ACP session IDs that currently have active prompts.
	 * @return an immutable snapshot of active prompt session IDs
	 */
	public Set<String> getActivePromptSessionIds() {
		return activePrompts.sessionIds();
	}

	/**
	 * Closes the session gracefully, allowing pending operations to complete.
	 * @return A Mono that completes when the session is closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			activePrompts.clear();
			dismissPendingResponses();
		}).then(this.transport.closeGracefully());
	}

	/**
	 * Closes the session immediately, potentially interrupting pending operations.
	 */
	@Override
	public void close() {
		activePrompts.clear();
		dismissPendingResponses();
		transport.close();
	}

}
