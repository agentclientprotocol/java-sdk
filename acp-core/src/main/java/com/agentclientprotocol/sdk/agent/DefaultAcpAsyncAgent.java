/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.error.AcpCapabilityException;
import com.agentclientprotocol.sdk.spec.AcpAgentSession;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.ExtensionMethods;
import com.agentclientprotocol.sdk.spec.PromptTimeouts;
import com.agentclientprotocol.sdk.util.Assert;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Default implementation of {@link AcpAsyncAgent} that provides non-blocking
 * operations for handling client requests.
 *
 * <p>
 * This implementation creates an {@link AcpAgentSession} to manage the JSON-RPC
 * communication and installs in it the handlers its builder registered
 * ({@link AgentHandlers}).
 * </p>
 *
 * @author Mark Pollack
 */
class DefaultAcpAsyncAgent implements AcpAsyncAgent {

	private static final Logger logger = LoggerFactory.getLogger(DefaultAcpAsyncAgent.class);

	private final AcpAgentTransport transport;

	private final Duration requestTimeout;

	private final PromptTimeouts promptTimeouts;

	private final List<AgentHandlers.Request<?>> requestHandlers;

	private final List<AgentHandlers.Notification<?>> notificationHandlers;

	/**
	 * The session serving this agent; null until {@link #start()}, then never null again.
	 * Each method reads it once into a local, so its null check and its call see one value.
	 */
	private volatile @Nullable AcpAgentSession session;

	/**
	 * Capabilities negotiated with the client during initialization.
	 */
	private final AtomicReference<@Nullable NegotiatedCapabilities> clientCapabilities = new AtomicReference<>();

	/** The cancellation signals of the prompts running, which their contexts read. */
	private final PromptCancellations promptCancellations = new PromptCancellations();

	DefaultAcpAsyncAgent(AcpAgentTransport transport, Duration requestTimeout, PromptTimeouts promptTimeouts,
			AgentHandlers handlers) {
		this.transport = transport;
		this.requestTimeout = requestTimeout;
		this.promptTimeouts = promptTimeouts;
		this.requestHandlers = handlers.requests();
		this.notificationHandlers = handlers.notifications();
	}

	@Override
	public Mono<Void> start() {
		return Mono.fromRunnable(() -> {
			logger.info("Starting ACP async agent");
			Map<String, AcpAgentSession.RequestHandler<?>> requests = new HashMap<>();
			for (AgentHandlers.Request<?> registration : requestHandlers) {
				requests.put(registration.method(), sessionHandler(registration));
			}
			Map<String, AcpAgentSession.NotificationHandler> notifications = new HashMap<>();
			for (AgentHandlers.Notification<?> registration : notificationHandlers) {
				notifications.put(registration.method(), sessionHandler(registration));
			}
			notifications.put(AcpSchema.METHOD_SESSION_CANCEL,
					signalCancel(notifications.get(AcpSchema.METHOD_SESSION_CANCEL)));
			this.session = new AcpAgentSession(requestTimeout, transport, requests, notifications, promptTimeouts);
			logger.info("ACP async agent started");
		});
	}

	/** Reads the params as the registered request type, then calls the registered handler. */
	private <T> AcpAgentSession.RequestHandler<Object> sessionHandler(AgentHandlers.Request<T> registration) {
		return params -> {
			T request = transport.unmarshalParams(params, registration.requestType());
			recordClientCapabilities(request);
			if (request instanceof AcpSchema.PromptRequest prompt) {
				return handlePrompt(registration, request, prompt.sessionId());
			}
			return registration.handler()
				.handle(request, this)
				.cast(Object.class)
				.map(this::withoutBooleanOptionsUnlessAdvertised);
		};
	}

	/**
	 * ACP v1, session config options: "Agents MUST NOT include type: "boolean" options in
	 * configOptions payloads unless the Client advertised support." A boolean option the handler
	 * returned to any other client is omitted, with a warning naming it; the agent keeps using its
	 * default, which the protocol requires it to have.
	 */
	private Object withoutBooleanOptionsUnlessAdvertised(Object result) {
		NegotiatedCapabilities caps = clientCapabilities.get();
		if (caps != null && caps.supportsBooleanConfigOptions()) {
			return result;
		}
		Object gated = withoutBooleansInSessionAnswer(result);
		return (gated != null) ? gated : withoutBooleansInConfigAnswer(result);
	}

	/** The session lifecycle answers that carry config options, or null when {@code result} is none. */
	private static @Nullable Object withoutBooleansInSessionAnswer(Object result) {
		if (result instanceof AcpSchema.NewSessionResponse r) {
			return hasBoolean(r.configOptions()) ? new AcpSchema.NewSessionResponse(r.sessionId(), r.modes(),
					withoutBooleans(r.configOptions()), r.meta()) : r;
		}
		if (result instanceof AcpSchema.LoadSessionResponse r) {
			return hasBoolean(r.configOptions())
					? new AcpSchema.LoadSessionResponse(r.modes(), withoutBooleans(r.configOptions()), r.meta()) : r;
		}
		if (result instanceof AcpSchema.ResumeSessionResponse r) {
			return hasBoolean(r.configOptions())
					? new AcpSchema.ResumeSessionResponse(r.modes(), withoutBooleans(r.configOptions()), r.meta()) : r;
		}
		if (result instanceof AcpSchema.ForkSessionResponse r) {
			return hasBoolean(r.configOptions()) ? new AcpSchema.ForkSessionResponse(r.sessionId(), r.modes(),
					withoutBooleans(r.configOptions()), r.meta()) : r;
		}
		return null;
	}

	/** The config option answer and update, or {@code result} unchanged when it is neither. */
	private static Object withoutBooleansInConfigAnswer(Object result) {
		if (result instanceof AcpSchema.SetSessionConfigOptionResponse r && hasBoolean(r.configOptions())) {
			return new AcpSchema.SetSessionConfigOptionResponse(withoutBooleans(r.configOptions()), r.meta());
		}
		if (result instanceof AcpSchema.ConfigOptionUpdate u && hasBoolean(u.configOptions())) {
			return new AcpSchema.ConfigOptionUpdate(u.sessionUpdate(), withoutBooleans(u.configOptions()), u.meta());
		}
		return result;
	}

	private static boolean hasBoolean(@Nullable List<AcpSchema.SessionConfigOption> options) {
		return options != null && options.stream().anyMatch(AcpSchema.SessionConfigBoolean.class::isInstance);
	}

	private static List<AcpSchema.SessionConfigOption> withoutBooleans(
			@Nullable List<AcpSchema.SessionConfigOption> options) {
		if (options == null) {
			return List.of();
		}
		List<AcpSchema.SessionConfigOption> kept = new java.util.ArrayList<>(options.size());
		for (AcpSchema.SessionConfigOption option : options) {
			if (option instanceof AcpSchema.SessionConfigBoolean bool) {
				logger.warn("Omitting boolean session config option '{}': the client did not advertise "
						+ "session.configOptions.boolean (ACP v1, session config options)", bool.id());
			}
			else {
				kept.add(option);
			}
		}
		return List.copyOf(kept);
	}

	/**
	 * Calls a prompt handler with its cancellation signal started, so its context sees it,
	 * and signals it when the handler's subscription is cancelled: by {@code $/cancel_request},
	 * a prompt deadline, or the session closing. {@code session/cancel} signals it through
	 * {@link #signalCancel}.
	 */
	private <T> Mono<Object> handlePrompt(AgentHandlers.Request<T> registration, T request, String sessionId) {
		PromptCancellations.Signal signal = promptCancellations.start(sessionId);
		Mono<?> handled;
		try {
			handled = registration.handler().handle(request, this);
		}
		catch (RuntimeException | Error ex) {
			promptCancellations.end(sessionId, signal);
			throw ex;
		}
		return handled.cast(Object.class)
			.doOnCancel(signal::cancel)
			.doFinally(ignored -> promptCancellations.end(sessionId, signal));
	}

	/**
	 * Handles {@code session/cancel}: signals the session's running prompt, then calls the
	 * registered cancel handler, if any.
	 */
	private AcpAgentSession.NotificationHandler signalCancel(AcpAgentSession.@Nullable NotificationHandler registered) {
		return params -> {
			AcpSchema.CancelNotification cancel = transport.unmarshalParams(params,
					new TypeRef<AcpSchema.CancelNotification>() {
					});
			promptCancellations.cancel(cancel.sessionId());
			return (registered != null) ? registered.handle(params) : Mono.empty();
		};
	}

	/**
	 * The cancellation signal of the prompt running on {@code sessionId}; one that never
	 * fires when there is none (a context built outside a running prompt).
	 */
	PromptCancellations.Signal promptSignal(String sessionId) {
		PromptCancellations.Signal signal = promptCancellations.current(sessionId);
		return (signal != null) ? signal : new PromptCancellations.Signal();
	}

	/**
	 * Whether the prompt running on {@code sessionId} has been answered; always false when no
	 * prompt is running (a context built outside a running prompt).
	 */
	BooleanSupplier promptAnswered(String sessionId) {
		AcpAgentSession current = this.session;
		return (current != null) ? current.promptAnswered(sessionId) : () -> false;
	}

	private <T> AcpAgentSession.NotificationHandler sessionHandler(AgentHandlers.Notification<T> registration) {
		return params -> registration.handler()
			.handle(transport.unmarshalParams(params, registration.notificationType()), this);
	}

	/**
	 * Captures what the client offered in its initialize request, before the initialize
	 * handler runs, so the capability checks below see it.
	 */
	private void recordClientCapabilities(Object request) {
		if (request instanceof AcpSchema.InitializeRequest initialize) {
			NegotiatedCapabilities caps = NegotiatedCapabilities.fromClient(initialize.clientCapabilities());
			clientCapabilities.set(caps);
			logger.debug("Negotiated client capabilities: {}", caps);
		}
	}

	@Override
	public Mono<Void> awaitTermination() {
		return transport.awaitTermination();
	}

	@Override
	public @Nullable NegotiatedCapabilities getClientCapabilities() {
		return clientCapabilities.get();
	}

	@Override
	public Mono<Void> sendSessionUpdate(String sessionId, AcpSchema.SessionUpdate update) {
		AcpSchema.SessionUpdate sent = (AcpSchema.SessionUpdate) withoutBooleanOptionsUnlessAdvertised(update);
		return sendNotification(AcpSchema.METHOD_SESSION_UPDATE, new AcpSchema.SessionNotification(sessionId, sent));
	}

	@Override
	public Mono<AcpSchema.RequestPermissionResponse> requestPermission(AcpSchema.RequestPermissionRequest request) {
		return sendRequest(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, request,
				new TypeRef<AcpSchema.RequestPermissionResponse>() {
				});
	}

	@Override
	public Mono<AcpSchema.ReadTextFileResponse> readTextFile(AcpSchema.ReadTextFileRequest request) {
		return sendRequest(AcpSchema.METHOD_FS_READ_TEXT_FILE, request, new TypeRef<AcpSchema.ReadTextFileResponse>() {
		}, NegotiatedCapabilities::supportsReadTextFile, "fs.readTextFile");
	}

	@Override
	public Mono<AcpSchema.WriteTextFileResponse> writeTextFile(AcpSchema.WriteTextFileRequest request) {
		return sendRequest(AcpSchema.METHOD_FS_WRITE_TEXT_FILE, request,
				new TypeRef<AcpSchema.WriteTextFileResponse>() {
				}, NegotiatedCapabilities::supportsWriteTextFile, "fs.writeTextFile");
	}

	@Override
	public Mono<AcpSchema.CreateTerminalResponse> createTerminal(AcpSchema.CreateTerminalRequest request) {
		return sendRequest(AcpSchema.METHOD_TERMINAL_CREATE, request, new TypeRef<AcpSchema.CreateTerminalResponse>() {
		}, NegotiatedCapabilities::supportsTerminal, "terminal");
	}

	@Override
	public Mono<AcpSchema.TerminalOutputResponse> getTerminalOutput(AcpSchema.TerminalOutputRequest request) {
		return sendRequest(AcpSchema.METHOD_TERMINAL_OUTPUT, request, new TypeRef<AcpSchema.TerminalOutputResponse>() {
		}, NegotiatedCapabilities::supportsTerminal, "terminal");
	}

	@Override
	public Mono<AcpSchema.ReleaseTerminalResponse> releaseTerminal(AcpSchema.ReleaseTerminalRequest request) {
		return sendRequest(AcpSchema.METHOD_TERMINAL_RELEASE, request,
				new TypeRef<AcpSchema.ReleaseTerminalResponse>() {
				}, NegotiatedCapabilities::supportsTerminal, "terminal");
	}

	@Override
	public Mono<AcpSchema.WaitForTerminalExitResponse> waitForTerminalExit(
			AcpSchema.WaitForTerminalExitRequest request) {
		return sendRequest(AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT, request,
				new TypeRef<AcpSchema.WaitForTerminalExitResponse>() {
				}, NegotiatedCapabilities::supportsTerminal, "terminal");
	}

	@Override
	public Mono<AcpSchema.KillTerminalCommandResponse> killTerminal(AcpSchema.KillTerminalCommandRequest request) {
		return sendRequest(AcpSchema.METHOD_TERMINAL_KILL, request,
				new TypeRef<AcpSchema.KillTerminalCommandResponse>() {
				}, NegotiatedCapabilities::supportsTerminal, "terminal");
	}

	@Override
	public Mono<AcpSchema.CreateElicitationResponse> createElicitation(
			AcpSchema.CreateElicitationRequest request) {
		return switch (request.mode()) {
			case AcpSchema.CreateElicitationRequest.MODE_FORM -> sendRequest(AcpSchema.METHOD_ELICITATION_CREATE,
					request, ELICITATION_RESPONSE, NegotiatedCapabilities::supportsElicitationForm, "elicitation.form");
			case AcpSchema.CreateElicitationRequest.MODE_URL -> sendRequest(AcpSchema.METHOD_ELICITATION_CREATE,
					request, ELICITATION_RESPONSE, NegotiatedCapabilities::supportsElicitationUrl, "elicitation.url");
			// A mode this SDK does not know: the client can only have advertised it in a
			// capability this SDK does not model, so only elicitation itself is checked.
			default -> sendRequest(AcpSchema.METHOD_ELICITATION_CREATE, request, ELICITATION_RESPONSE,
					NegotiatedCapabilities::supportsElicitation, "elicitation");
		};
	}

	private static final TypeRef<AcpSchema.CreateElicitationResponse> ELICITATION_RESPONSE = new TypeRef<>() {
	};

	@Override
	public Mono<Void> completeElicitation(AcpSchema.CompleteElicitationNotification notification) {
		return sendNotification(AcpSchema.METHOD_ELICITATION_COMPLETE, notification);
	}

	@Override
	public <T> Mono<T> sendExtRequest(String method, Object params, TypeRef<T> resultType) {
		ExtensionMethods.requireExtension(method);
		Assert.notNull(params, "Params must not be null");
		Assert.notNull(resultType, "Result type must not be null");
		return sendRequest(method, params, resultType);
	}

	@Override
	public Mono<Object> sendExtRequest(String method, Object params) {
		return sendExtRequest(method, params, AgentHandlers.RAW_PARAMS);
	}

	@Override
	public Mono<Void> sendExtNotification(String method, Object params) {
		ExtensionMethods.requireExtension(method);
		Assert.notNull(params, "Params must not be null");
		return sendNotification(method, params);
	}

	private <T> Mono<T> sendRequest(String method, Object request, TypeRef<T> responseType) {
		return sendRequest(method, request, responseType, caps -> true, method);
	}

	/**
	 * Sends a request to the client, unless the agent is not started or the client said
	 * during initialization that it does not support the capability the request needs.
	 * Before initialization nothing is known, and the request is sent.
	 */
	private <T> Mono<T> sendRequest(String method, Object request, TypeRef<T> responseType,
			Predicate<NegotiatedCapabilities> clientSupports, String capability) {
		AcpAgentSession current = this.session;
		if (current == null) {
			return Mono.error(new IllegalStateException("Agent not started"));
		}
		NegotiatedCapabilities caps = clientCapabilities.get();
		if (caps != null && !clientSupports.test(caps)) {
			return Mono.error(new AcpCapabilityException(capability));
		}
		return current.sendRequest(method, request, responseType);
	}

	private Mono<Void> sendNotification(String method, Object notification) {
		AcpAgentSession current = this.session;
		if (current == null) {
			return Mono.error(new IllegalStateException("Agent not started"));
		}
		return current.sendNotification(method, notification);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			logger.info("Closing ACP async agent gracefully");
			AcpAgentSession current = this.session;
			if (current != null) {
				return current.closeGracefully();
			}
			return Mono.empty();
		});
	}

	@Override
	public void close() {
		logger.info("Closing ACP async agent");
		AcpAgentSession current = this.session;
		if (current != null) {
			current.close();
		}
	}

}
