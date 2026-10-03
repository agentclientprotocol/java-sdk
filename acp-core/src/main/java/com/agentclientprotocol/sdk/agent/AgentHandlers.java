/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.agentclientprotocol.sdk.json.TypeRef;
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
		requests.put(method, new Request<>(method, requestType, handler));
	}

	<T> void notification(String method, TypeRef<T> notificationType, Function<T, Mono<Void>> handler) {
		notifications.put(method, new Notification<>(method, notificationType, handler));
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

	/** A snapshot: registrations made after it do not reach an agent already built. */
	List<Request<?>> requests() {
		return List.copyOf(requests.values());
	}

	/** A snapshot: registrations made after it do not reach an agent already built. */
	List<Notification<?>> notifications() {
		return List.copyOf(notifications.values());
	}

}
