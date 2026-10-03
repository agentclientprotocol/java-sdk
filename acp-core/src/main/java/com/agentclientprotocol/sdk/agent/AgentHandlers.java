/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

/**
 * The JSON-RPC methods an agent serves, held as data: for each method, the type its params
 * are read as and the handler that answers them. The {@link AcpAgent} builders collect one
 * registration per handler they are given (a later one for the same method replaces an
 * earlier one); {@link DefaultAcpAsyncAgent} installs them in its session when it starts.
 *
 * @author Mark Pollack
 */
final class AgentHandlers {

	/** Reads params as the raw JSON value, for the untyped extension handlers. */
	static final TypeRef<Object> RAW_PARAMS = new TypeRef<>() {
	};

	/**
	 * Answers one inbound request, already read into its request type.
	 * @param <T> the request type
	 */
	@FunctionalInterface
	interface RequestHandler<T> {

		/**
		 * Answers the request.
		 * @param request the request
		 * @param agent the agent serving it, for handlers that act back on the client
		 * (a prompt handler's {@link PromptContext})
		 * @return the response
		 */
		Mono<?> handle(T request, AcpAsyncAgent agent);

	}

	/**
	 * One request method: its name, the type its params are read as, and its handler.
	 * @param <T> the request type
	 */
	record Request<T>(String method, TypeRef<T> requestType, RequestHandler<T> handler) {
	}

	/**
	 * One notification method: its name, the type its params are read as, and its handler.
	 * @param <T> the notification type
	 */
	record Notification<T>(String method, TypeRef<T> notificationType, Function<T, Mono<Void>> handler) {
	}

	private final Map<String, Request<?>> requests = new LinkedHashMap<>();

	private final Map<String, Notification<?>> notifications = new LinkedHashMap<>();

	<T> void request(String method, TypeRef<T> requestType, RequestHandler<T> handler) {
		if (requests.putIfAbsent(method, new Request<>(method, requestType, handler)) != null) {
			throw alreadyRegistered(method, "extRequestHandler");
		}
	}

	<T> void notification(String method, TypeRef<T> notificationType, Function<T, Mono<Void>> handler) {
		if (notifications.putIfAbsent(method, new Notification<>(method, notificationType, handler)) != null) {
			throw alreadyRegistered(method, "extNotificationHandler");
		}
	}

	/** A copy, to which a built agent's defaults are added without changing the builder's. */
	AgentHandlers copy() {
		AgentHandlers copy = new AgentHandlers();
		copy.requests.putAll(this.requests);
		copy.notifications.putAll(this.notifications);
		return copy;
	}

	/** The request methods with a registered handler. */
	java.util.Set<String> requestMethods() {
		return java.util.Set.copyOf(requests.keySet());
	}

	/** A second handler for a method: a mistake, which would silently replace the first. */
	private static IllegalStateException alreadyRegistered(String method, String extensionSetter) {
		String setter = SETTERS.getOrDefault(method, extensionSetter + "(\"" + method + "\", ...)");
		return new IllegalStateException("A handler for " + method + " is already registered; " + setter
				+ " was called twice on this builder");
	}

	/** The builder setter that registers each ACP method's handler, for error messages. */
	private static final Map<String, String> SETTERS = Map.ofEntries(
			Map.entry(AcpSchema.METHOD_INITIALIZE, "initializeHandler"),
			Map.entry(AcpSchema.METHOD_AUTHENTICATE, "authenticateHandler"),
			Map.entry(AcpSchema.METHOD_LOGOUT, "logoutHandler"),
			Map.entry(AcpSchema.METHOD_SESSION_NEW, "newSessionHandler"),
			Map.entry(AcpSchema.METHOD_SESSION_LOAD, "loadSessionHandler"),
			Map.entry(AcpSchema.METHOD_SESSION_PROMPT, "promptHandler"),
			Map.entry(AcpSchema.METHOD_SESSION_SET_MODE, "setSessionModeHandler"),
			Map.entry(AcpSchema.METHOD_SESSION_LIST, "listSessionsHandler"),
			Map.entry(AcpSchema.METHOD_SESSION_CLOSE, "closeSessionHandler"),
			Map.entry(AcpSchema.METHOD_SESSION_DELETE, "deleteSessionHandler"),
			Map.entry(AcpSchema.METHOD_SESSION_RESUME, "resumeSessionHandler"),
			Map.entry(AcpSchema.METHOD_SESSION_FORK, "forkSessionHandler"),
			Map.entry(AcpSchema.METHOD_SESSION_SET_CONFIG_OPTION, "setSessionConfigOptionHandler"),
			Map.entry(AcpSchema.METHOD_PROVIDERS_LIST, "listProvidersHandler"),
			Map.entry(AcpSchema.METHOD_PROVIDERS_SET, "setProviderHandler"),
			Map.entry(AcpSchema.METHOD_PROVIDERS_DISABLE, "disableProviderHandler"),
			Map.entry(AcpSchema.METHOD_SESSION_CANCEL, "cancelHandler"));

	/** A snapshot: registrations made after it do not reach an agent already built. */
	List<Request<?>> requests() {
		return List.copyOf(requests.values());
	}

	/** A snapshot: registrations made after it do not reach an agent already built. */
	List<Notification<?>> notifications() {
		return List.copyOf(notifications.values());
	}

}
