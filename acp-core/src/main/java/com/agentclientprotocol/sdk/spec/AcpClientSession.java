/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import com.agentclientprotocol.sdk.util.Assert;
import com.agentclientprotocol.sdk.util.HandlerFailures;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import reactor.core.scheduler.Scheduler;

/**
 * Default implementation of the ACP (Agent Client Protocol) client session that manages
 * bidirectional JSON-RPC communication between clients and agents. This implementation
 * follows the ACP specification for message exchange and transport handling.
 *
 * <p>
 * The session manages:
 * <ul>
 * <li>Request/response handling with unique message IDs</li>
 * <li>Notification processing</li>
 * <li>Message timeout management</li>
 * <li>Transport layer abstraction</li>
 * </ul>
 *
 * <p>
 * This is the client-side session that sends requests to an agent (initialize,
 * newSession, prompt, etc.) and handles incoming requests from the agent (readTextFile,
 * writeTextFile, requestPermission, etc.)
 * </p>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
public class AcpClientSession implements AcpSession {

	private static final Logger logger = LoggerFactory.getLogger(AcpClientSession.class);

	/** Duration to wait for request responses before timing out */
	private final Duration requestTimeout;

	/**
	 * The JVM-wide daemon timer shared by every session (see AcpSchedulers); never disposed here.
	 */
	private final Scheduler timeoutScheduler;

	/** Transport layer implementation for message exchange */
	private final AcpClientTransport transport;

	/** Requests and notifications sent to the agent, and the requests waiting for a response */
	private final OutboundMessages outbound;

	/** Requests from the agent still being handled, which a $/cancel_request can cancel */
	private final InboundRequests inbound = new InboundRequests();

	/** Map of request handlers keyed by method name */
	private final ConcurrentHashMap<String, RequestHandler<?>> requestHandlers = new ConcurrentHashMap<>();

	/** Map of notification handlers keyed by method name */
	private final ConcurrentHashMap<String, NotificationHandler> notificationHandlers = new ConcurrentHashMap<>();

	/**
	 * Notifications waiting for in-order delivery, and responses waiting for the notifications
	 * before them to be handled; completed when the session closes
	 */
	private final InboundOrder<AcpSchema.JSONRPCNotification> notifications = new InboundOrder<>();

	/** Subscription draining the notifications in order */
	private final Disposable notificationSubscription;

	/** Completes when the notification drain terminates; awaited by {@link #closeGracefully()} */
	private final Sinks.Empty<Void> notificationDrainTerminated = Sinks.empty();

	/**
	 * Set when the transport's {@code connect()} fails. A transport that refuses to connect
	 * (already connected, process failed to start, socket unreachable) can never deliver a
	 * response, so every request is failed immediately with the cause instead of waiting
	 * out the request timeout.
	 */
	private volatile @Nullable Throwable connectFailure;

	/** Set once this session starts closing itself, so a transport termination it caused is not reported. */
	private volatile boolean closing;

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
	 * Creates a new AcpClientSession with the specified configuration and handlers.
	 * @param requestTimeout Duration to wait for responses
	 * @param transport Transport implementation for message exchange
	 * @param requestHandlers Map of method names to request handlers
	 * @param notificationHandlers Map of method names to notification handlers
	 * @param connectHook Hook that allows transforming the connection Publisher prior to
	 * subscribing
	 */
	public AcpClientSession(Duration requestTimeout, AcpClientTransport transport,
			Map<String, RequestHandler<?>> requestHandlers, Map<String, NotificationHandler> notificationHandlers,
			Function<? super Mono<Void>, ? extends Publisher<Void>> connectHook) {

		Assert.notNull(requestTimeout, "The requestTimeout can not be null");
		Assert.notNull(transport, "The transport can not be null");
		Assert.notNull(requestHandlers, "The requestHandlers can not be null");
		Assert.notNull(notificationHandlers, "The notificationHandlers can not be null");

		this.requestTimeout = requestTimeout;
		this.transport = transport;
		// A response completes its caller only once the notifications that arrived before it
		// have been handled: a prompt turn's updates come before its response.
		this.outbound = new OutboundMessages(transport, requestTimeout, () -> this.connectFailure,
				AcpClientSession::notConnected, "agent", this.notifications);
		this.requestHandlers.putAll(requestHandlers);
		this.notificationHandlers.putAll(notificationHandlers);

		logger.debug("AcpClientSession created with {} request handlers: {}",
				requestHandlers.size(), requestHandlers.keySet());
		logger.debug("AcpClientSession created with {} notification handlers: {}",
				notificationHandlers.size(), notificationHandlers.keySet());

		// One shared daemon timer for every session in the JVM (see AcpSchedulers).
		this.timeoutScheduler = AcpSchedulers.timeouts();

		// Serialize notification delivery: each notification's Mono completes before the next
		// one starts, preserving arrival order even when handlers do async work. A response
		// is released in its place among them (InboundOrder).
		this.notificationSubscription = this.notifications
			.drain(notification -> handleIncomingNotification(notification).onErrorComplete(t -> {
				logger.error("Error handling notification: {}", t.getMessage());
				return true;
			}))
			.doFinally(signal -> {
				this.notifications.releaseHeld();
				this.notificationDrainTerminated.tryEmitEmpty();
			})
			.subscribe(ignored -> {
			}, error -> logger.warn("Notification delivery ended with an error", error));

		this.transport.connect(mono -> mono.doOnNext(this::handle).then(Mono.empty()))
			.transform(connectHook)
			.subscribe(v -> {
			}, this::onConnectFailure);

		// A transport that refuses synchronously (stdio does, and every transport does when
		// asked to connect twice) fails construction rather than handing back a session
		// whose first request would time out.
		Throwable failure = this.connectFailure;
		if (failure != null) {
			this.notifications.complete();
			this.notificationSubscription.dispose();
			throw notConnected(failure);
		}

		// When the transport later terminates (peer gone, stream failed for good), pending
		// requests fail at once with the cause instead of waiting out the request timeout.
		this.transport.awaitTermination().subscribe(v -> {
		}, this::onTransportTerminated, () -> onTransportTerminated(null));
	}

	private void onTransportTerminated(@Nullable Throwable cause) {
		if (this.closing) {
			return;
		}
		Throwable failure = cause != null ? cause : new IllegalStateException("ACP client transport terminated");
		this.connectFailure = failure;
		if (cause != null) {
			logger.warn("ACP client transport terminated: {}", cause.getMessage());
		}
		else {
			logger.debug("ACP client transport terminated by the peer");
		}
		dismissPendingResponses(failure);
	}

	private void onConnectFailure(Throwable error) {
		this.connectFailure = error;
		logger.error("ACP client transport failed to connect; every request on this session will fail: {}",
				error.getMessage());
		dismissPendingResponses(error);
	}

	private static IllegalStateException notConnected(Throwable cause) {
		return new IllegalStateException("ACP client transport is not connected: " + cause.getMessage(), cause);
	}

	private void dismissPendingResponses() {
		dismissPendingResponses(null);
	}

	private void dismissPendingResponses(@Nullable Throwable cause) {
		this.outbound.dismissPending(cause);
	}

	private void handle(AcpSchema.JSONRPCMessage message) {
		if (message instanceof AcpSchema.JSONRPCResponse response) {
			this.outbound.complete(response);
		}
		else if (message instanceof AcpSchema.JSONRPCRequest request) {
			respondTo(request);
		}
		else if (message instanceof AcpSchema.JSONRPCNotification notification) {
			if (AcpSchema.METHOD_CANCEL_REQUEST.equals(notification.method())) {
				// Not queued behind the ordered notification drain: a slow session/update
				// consumer must not delay cancelling a pending request.
				this.inbound.cancel(notification);
			}
			else {
				enqueue(notification);
			}
		}
		else {
			logger.warn("Received unknown message type: {}", message.getClass().getName());
		}
	}

	/**
	 * Answers a request from the agent; a failed handler is answered with an error response,
	 * a request cancelled by $/cancel_request with -32800 unless it already answered.
	 */
	private void respondTo(AcpSchema.JSONRPCRequest request) {
		logger.debug("Received request method={} id={}", request.method(), request.id());
		logger.trace("Incoming request method='{}' id={}", request.method(), request.id());
		this.inbound.track(request, handleIncomingRequest(request))
			.onErrorResume(error -> Mono.just(InboundMessages.error(request, error)))
			.flatMap(this.transport::sendMessage)
			.onErrorComplete(t -> {
				logger.warn("Issue sending response to the agent, ", t);
				return true;
			})
			.subscribe();
	}

	/** Queues a notification for in-order delivery by the notification drain. */
	private void enqueue(AcpSchema.JSONRPCNotification notification) {
		logger.debug("Received notification method={}", notification.method());
		logger.trace("Incoming notification method='{}' params={}", notification.method(), notification.params());
		Sinks.EmitResult result = this.notifications.offer(notification);
		if (!result.isFailure()) {
			return;
		}
		if (result == Sinks.EmitResult.FAIL_TERMINATED || result == Sinks.EmitResult.FAIL_CANCELLED
				|| this.notifications.isCompleting()) {
			// The session is shutting down. Refusing newly arriving notifications
			// is the intended behaviour: a graceful shutdown stops accepting new
			// work and drains what is already queued, and JSON-RPC notifications
			// carry no delivery guarantee by design.
			logger.debug("Session is closing; dropping notification method='{}' ({})", notification.method(),
					result);
		}
		else {
			// Overflow, no subscriber, or concurrent (non-serialized) emission:
			// protocol traffic lost on a live session, which is never expected.
			logger.error("Dropped notification method='{}': sink emission failed with {}", notification.method(),
					result);
		}
	}

	/**
	 * Handles an incoming JSON-RPC request by routing it to the appropriate handler.
	 * @param request The incoming JSON-RPC request
	 * @return A Mono containing the JSON-RPC response
	 */
	private Mono<AcpSchema.JSONRPCResponse> handleIncomingRequest(AcpSchema.JSONRPCRequest request) {
		return Mono.defer(() -> {
			var handler = this.requestHandlers.get(request.method());
			if (handler == null) {
				MethodNotFoundError error = getMethodNotFoundError(request.method());
				logger.warn("No handler registered for request method '{}': {} - {}",
						request.method(), error.message(),
						error.data() != null ? error.data() : "register a handler to support this operation");
				logger.trace("Available handlers: {}", this.requestHandlers.keySet());
				return Mono.just(InboundMessages.error(request, AcpErrorCodes.METHOD_NOT_FOUND, error.message(),
						error.data()));
			}

			logger.debug("Invoking handler for method '{}'", request.method());
			logger.trace("Handler params for '{}': {}", request.method(), request.params());
			return InboundMessages.requireResult(HandlerFailures.invoke(() -> handler.handle(InboundMessages.paramsOrEmpty(request.params()))),
					request.method())
				.doOnSuccess(result -> logger.debug("Handler for '{}' completed successfully", request.method()))
				.doOnError(error -> logger.debug("Handler for '{}' threw error: {}", request.method(), error.getMessage()))
				.map(result -> InboundMessages.result(request, result));
		});
	}

	record MethodNotFoundError(String method, String message, @Nullable Object data) {
	}

	private MethodNotFoundError getMethodNotFoundError(String method) {
		// ACP-specific error messages for unsupported client methods
		switch (method) {
			case AcpSchema.METHOD_FS_READ_TEXT_FILE:
				return new MethodNotFoundError(method, "File system read not supported",
						Map.of("reason", "Client does not have fs.readTextFile capability"));
			case AcpSchema.METHOD_FS_WRITE_TEXT_FILE:
				return new MethodNotFoundError(method, "File system write not supported",
						Map.of("reason", "Client does not have fs.writeTextFile capability"));
			case AcpSchema.METHOD_SESSION_REQUEST_PERMISSION:
				return new MethodNotFoundError(method, "Permission request not supported",
						Map.of("reason", "No requestPermissionHandler registered - use --yolo flag or register a handler"));
			case AcpSchema.METHOD_TERMINAL_CREATE:
			case AcpSchema.METHOD_TERMINAL_OUTPUT:
			case AcpSchema.METHOD_TERMINAL_RELEASE:
			case AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT:
			case AcpSchema.METHOD_TERMINAL_KILL:
				return new MethodNotFoundError(method, "Terminal not supported",
						Map.of("reason", "Client does not have terminal capability"));
			default:
				return new MethodNotFoundError(method, "Method not found: " + method, null);
		}
	}

	/**
	 * Handles an incoming JSON-RPC notification by routing it to the appropriate handler.
	 * @param notification The incoming JSON-RPC notification
	 * @return A Mono that completes when the notification is processed
	 */
	private Mono<Void> handleIncomingNotification(AcpSchema.JSONRPCNotification notification) {
		return Mono.defer(() -> {
			return InboundMessages.deliver(logger, notification, this.notificationHandlers, NotificationHandler::handle);
		});
	}

	/**
	 * Sends a JSON-RPC request and returns the response.
	 * @param <T> The expected response type
	 * @param method The method name to call
	 * @param requestParams The request parameters
	 * @param typeRef Type reference for response deserialization
	 * @return A Mono containing the response; an error response fails it with {@link AcpError}
	 */
	@Override
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef) {
		return this.outbound.sendRequest(method, requestParams, typeRef);
	}

	/**
	 * Sends a JSON-RPC notification.
	 * @param method The method name for the notification
	 * @param params The notification parameters
	 * @return A Mono that completes when the notification is sent
	 */
	@Override
	public Mono<Void> sendNotification(String method, @Nullable Object params) {
		return this.outbound.sendNotification(method, params);
	}

	/**
	 * Closes the session gracefully, allowing pending operations to complete.
	 * @return A Mono that completes when the session is closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.<Void>fromRunnable(() -> {
			this.closing = true;
			this.inbound.cancelAll();
			dismissPendingResponses();
			// Never lost to a notification being delivered concurrently (NotificationQueue).
			this.notifications.complete();
		})
			// Wait for queued notifications to drain before tearing the session down;
			// disposing immediately would discard them. Bounded so a handler that never
			// completes cannot hang graceful close.
			.then(this.notificationDrainTerminated.asMono()
				.timeout(this.requestTimeout, Mono.empty(), this.timeoutScheduler))
			.doFinally(signal -> {
				notificationSubscription.dispose();
			});
	}

	/**
	 * Closes the session immediately, potentially interrupting pending operations.
	 */
	@Override
	public void close() {
		this.closing = true;
		this.inbound.cancelAll();
		dismissPendingResponses();
		this.notifications.complete();
		notificationSubscription.dispose();
		// A response held behind a notification still being handled completes now.
		this.notifications.releaseHeld();
	}

}
