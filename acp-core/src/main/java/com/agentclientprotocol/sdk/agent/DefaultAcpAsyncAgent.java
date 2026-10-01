/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.error.AcpCapabilityException;
import com.agentclientprotocol.sdk.spec.AcpAgentSession;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
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

	DefaultAcpAsyncAgent(AcpAgentTransport transport, Duration requestTimeout, AgentHandlers handlers) {
		this.transport = transport;
		this.requestTimeout = requestTimeout;
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
			this.session = new AcpAgentSession(requestTimeout, transport, requests, notifications);
			logger.info("ACP async agent started");
		});
	}

	/** Reads the params as the registered request type, then calls the registered handler. */
	private <T> AcpAgentSession.RequestHandler<Object> sessionHandler(AgentHandlers.Request<T> registration) {
		return params -> {
			T request = transport.unmarshalFrom(params, registration.requestType());
			recordClientCapabilities(request);
			return registration.handler().handle(request, this).cast(Object.class);
		};
	}

	private <T> AcpAgentSession.NotificationHandler sessionHandler(AgentHandlers.Notification<T> registration) {
		return params -> registration.handler()
			.apply(transport.unmarshalFrom(params, registration.notificationType()));
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
		return sendNotification(AcpSchema.METHOD_SESSION_UPDATE, new AcpSchema.SessionNotification(sessionId, update));
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
		});
	}

	@Override
	public Mono<AcpSchema.ReleaseTerminalResponse> releaseTerminal(AcpSchema.ReleaseTerminalRequest request) {
		return sendRequest(AcpSchema.METHOD_TERMINAL_RELEASE, request,
				new TypeRef<AcpSchema.ReleaseTerminalResponse>() {
				});
	}

	@Override
	public Mono<AcpSchema.WaitForTerminalExitResponse> waitForTerminalExit(
			AcpSchema.WaitForTerminalExitRequest request) {
		return sendRequest(AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT, request,
				new TypeRef<AcpSchema.WaitForTerminalExitResponse>() {
				});
	}

	@Override
	public Mono<AcpSchema.KillTerminalCommandResponse> killTerminal(AcpSchema.KillTerminalCommandRequest request) {
		return sendRequest(AcpSchema.METHOD_TERMINAL_KILL, request,
				new TypeRef<AcpSchema.KillTerminalCommandResponse>() {
				});
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
