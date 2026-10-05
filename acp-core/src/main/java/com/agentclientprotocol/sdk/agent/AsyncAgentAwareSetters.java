/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.ExtensionMethods;
import com.agentclientprotocol.sdk.util.Assert;

/**
 * The setters of {@link AcpAgent.AsyncAgentBuilder} that take an {@link AgentAwareHandler}: one
 * overload of each typed request setter but {@code promptHandler}, and of each extension handler
 * setter, registering a handler that also receives the agent. Kept apart from the builder only to keep that class small; it is part of the
 * builder's API.
 */
abstract class AsyncAgentAwareSetters<B> {

	/** Only {@link AcpAgent.AsyncAgentBuilder} extends this class. */
	AsyncAgentAwareSetters() {
	}

	/** Registers the handler of one request method, as the typed setters do. */
	abstract <T> B request(String method, TypeRef<T> requestType,
			AgentHandlers.RequestHandler<T> handler);

	/** Registers the handler of one notification method. */
	abstract <T> B notification(String method, TypeRef<T> notificationType,
			AgentHandlers.NotificationHandler<T> handler);

	/**
	 * Sets the handler for {@code initialize}, as {@link AcpAgent.AsyncAgentBuilder#initializeHandler(AcpAgent.InitializeHandler)} does, for a handler
	 * that also needs the agent it serves, for example to send session updates
	 * ({@link AcpAsyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B initializeHandler(
			AgentAwareHandler<AcpSchema.InitializeRequest, AcpSchema.InitializeResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		return request(AcpSchema.METHOD_INITIALIZE, new TypeRef<AcpSchema.InitializeRequest>() {
		}, handler::handle);
	}

	/**
	 * Sets the handler for {@code authenticate}, as {@link AcpAgent.AsyncAgentBuilder#authenticateHandler(AcpAgent.AuthenticateHandler)} does, for a handler
	 * that also needs the agent it serves, for example to send session updates
	 * ({@link AcpAsyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B authenticateHandler(
			AgentAwareHandler<AcpSchema.AuthenticateRequest, AcpSchema.AuthenticateResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		return request(AcpSchema.METHOD_AUTHENTICATE, new TypeRef<AcpSchema.AuthenticateRequest>() {
		}, handler::handle);
	}

	/**
	 * Sets the handler for {@code logout}, as {@link AcpAgent.AsyncAgentBuilder#logoutHandler(AcpAgent.LogoutHandler)} does, for a handler
	 * that also needs the agent it serves, for example to send session updates
	 * ({@link AcpAsyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B logoutHandler(
			AgentAwareHandler<AcpSchema.LogoutRequest, AcpSchema.LogoutResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		return request(AcpSchema.METHOD_LOGOUT, new TypeRef<AcpSchema.LogoutRequest>() {
		}, handler::handle);
	}

	/**
	 * Sets the handler for {@code session/new}, as {@link AcpAgent.AsyncAgentBuilder#newSessionHandler(AcpAgent.NewSessionHandler)} does, for a handler
	 * that also needs the agent it serves, for example to send session updates
	 * ({@link AcpAsyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B newSessionHandler(
			AgentAwareHandler<AcpSchema.NewSessionRequest, AcpSchema.NewSessionResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		return request(AcpSchema.METHOD_SESSION_NEW, new TypeRef<AcpSchema.NewSessionRequest>() {
		}, handler::handle);
	}

	/**
	 * Sets the handler for {@code session/load}, as {@link AcpAgent.AsyncAgentBuilder#loadSessionHandler(AcpAgent.LoadSessionHandler)} does, for a handler
	 * that also needs the agent it serves, for example to send session updates
	 * ({@link AcpAsyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B loadSessionHandler(
			AgentAwareHandler<AcpSchema.LoadSessionRequest, AcpSchema.LoadSessionResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		return request(AcpSchema.METHOD_SESSION_LOAD, new TypeRef<AcpSchema.LoadSessionRequest>() {
		}, handler::handle);
	}

	/**
	 * Sets the handler for {@code session/set_mode}, as {@link AcpAgent.AsyncAgentBuilder#setSessionModeHandler(AcpAgent.SetSessionModeHandler)} does, for a handler
	 * that also needs the agent it serves, for example to send session updates
	 * ({@link AcpAsyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B setSessionModeHandler(
			AgentAwareHandler<AcpSchema.SetSessionModeRequest, AcpSchema.SetSessionModeResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		return request(AcpSchema.METHOD_SESSION_SET_MODE, new TypeRef<AcpSchema.SetSessionModeRequest>() {
		}, handler::handle);
	}

	/**
	 * Sets the handler for {@code session/list}, as {@link AcpAgent.AsyncAgentBuilder#listSessionsHandler(AcpAgent.ListSessionsHandler)} does, for a handler
	 * that also needs the agent it serves, for example to send session updates
	 * ({@link AcpAsyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B listSessionsHandler(
			AgentAwareHandler<AcpSchema.ListSessionsRequest, AcpSchema.ListSessionsResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		return request(AcpSchema.METHOD_SESSION_LIST, new TypeRef<AcpSchema.ListSessionsRequest>() {
		}, handler::handle);
	}

	/**
	 * Sets the handler for {@code session/close}, as {@link AcpAgent.AsyncAgentBuilder#closeSessionHandler(AcpAgent.CloseSessionHandler)} does, for a handler
	 * that also needs the agent it serves, for example to send session updates
	 * ({@link AcpAsyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B closeSessionHandler(
			AgentAwareHandler<AcpSchema.CloseSessionRequest, AcpSchema.CloseSessionResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		return request(AcpSchema.METHOD_SESSION_CLOSE, new TypeRef<AcpSchema.CloseSessionRequest>() {
		}, handler::handle);
	}

	/**
	 * Sets the handler for {@code session/delete}, as {@link AcpAgent.AsyncAgentBuilder#deleteSessionHandler(AcpAgent.DeleteSessionHandler)} does, for a handler
	 * that also needs the agent it serves, for example to send session updates
	 * ({@link AcpAsyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B deleteSessionHandler(
			AgentAwareHandler<AcpSchema.DeleteSessionRequest, AcpSchema.DeleteSessionResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		return request(AcpSchema.METHOD_SESSION_DELETE, new TypeRef<AcpSchema.DeleteSessionRequest>() {
		}, handler::handle);
	}

	/**
	 * Sets the handler for {@code session/resume}, as {@link AcpAgent.AsyncAgentBuilder#resumeSessionHandler(AcpAgent.ResumeSessionHandler)} does, for a handler
	 * that also needs the agent it serves, for example to send session updates
	 * ({@link AcpAsyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B resumeSessionHandler(
			AgentAwareHandler<AcpSchema.ResumeSessionRequest, AcpSchema.ResumeSessionResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		return request(AcpSchema.METHOD_SESSION_RESUME, new TypeRef<AcpSchema.ResumeSessionRequest>() {
		}, handler::handle);
	}

	/**
	 * Sets the handler for {@code session/fork}, as {@link AcpAgent.AsyncAgentBuilder#forkSessionHandler(AcpAgent.ForkSessionHandler)} does, for a handler
	 * that also needs the agent it serves, for example to send session updates
	 * ({@link AcpAsyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	@UnstableAcpApi
	public B forkSessionHandler(
			AgentAwareHandler<AcpSchema.ForkSessionRequest, AcpSchema.ForkSessionResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		return request(AcpSchema.METHOD_SESSION_FORK, new TypeRef<AcpSchema.ForkSessionRequest>() {
		}, handler::handle);
	}

	/**
	 * Sets the handler for {@code session/set_config_option}, as {@link AcpAgent.AsyncAgentBuilder#setSessionConfigOptionHandler(AcpAgent.SetSessionConfigOptionHandler)} does, for a handler
	 * that also needs the agent it serves, for example to send session updates
	 * ({@link AcpAsyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B setSessionConfigOptionHandler(
			AgentAwareHandler<AcpSchema.SetSessionConfigOptionRequest, AcpSchema.SetSessionConfigOptionResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		return request(AcpSchema.METHOD_SESSION_SET_CONFIG_OPTION, new TypeRef<AcpSchema.SetSessionConfigOptionRequest>() {
		}, handler::handle);
	}

	/**
	 * Sets the handler for {@code providers/list}, as {@link AcpAgent.AsyncAgentBuilder#listProvidersHandler(AcpAgent.ListProvidersHandler)} does, for a handler
	 * that also needs the agent it serves, for example to send session updates
	 * ({@link AcpAsyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	@UnstableAcpApi
	public B listProvidersHandler(
			AgentAwareHandler<AcpSchema.ListProvidersRequest, AcpSchema.ListProvidersResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		return request(AcpSchema.METHOD_PROVIDERS_LIST, new TypeRef<AcpSchema.ListProvidersRequest>() {
		}, handler::handle);
	}

	/**
	 * Sets the handler for {@code providers/set}, as {@link AcpAgent.AsyncAgentBuilder#setProviderHandler(AcpAgent.SetProviderHandler)} does, for a handler
	 * that also needs the agent it serves, for example to send session updates
	 * ({@link AcpAsyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	@UnstableAcpApi
	public B setProviderHandler(
			AgentAwareHandler<AcpSchema.SetProviderRequest, AcpSchema.SetProviderResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		return request(AcpSchema.METHOD_PROVIDERS_SET, new TypeRef<AcpSchema.SetProviderRequest>() {
		}, handler::handle);
	}

	/**
	 * Sets the handler for {@code providers/disable}, as {@link AcpAgent.AsyncAgentBuilder#disableProviderHandler(AcpAgent.DisableProviderHandler)} does, for a handler
	 * that also needs the agent it serves, for example to send session updates
	 * ({@link AcpAsyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	@UnstableAcpApi
	public B disableProviderHandler(
			AgentAwareHandler<AcpSchema.DisableProviderRequest, AcpSchema.DisableProviderResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		return request(AcpSchema.METHOD_PROVIDERS_DISABLE, new TypeRef<AcpSchema.DisableProviderRequest>() {
		}, handler::handle);
	}

	/**
	 * Registers the handler for a custom extension request ({@code _}-prefixed method name, ACP v1
	 * Extensibility) from the client, its params read as the given type, as
	 * {@link AcpAgent.AsyncAgentBuilder#extRequestHandler(String, TypeRef, AcpAgent.ExtRequestHandler)}
	 * does, for a handler that also needs the agent it serves, for example to call the client back
	 * ({@link AcpAsyncAgent#sendExtRequest(String, Object, TypeRef)}) or send session updates. Name
	 * the agent parameter {@code self}, not the name the built agent is assigned to.
	 * @param <T> the params type
	 * @param <R> the result type, any type the JSON mapper can write
	 * @param method the method name, which must start with {@code _} (for example
	 * {@code _example.com/workspace/buffers})
	 * @param paramsType the type the params are read as; must not be null
	 * @param handler the handler; it receives the params and the agent; must not be null
	 * @return this builder
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 */
	public <T, R> B extRequestHandler(String method, TypeRef<T> paramsType, AgentAwareHandler<T, R> handler) {
		ExtensionMethods.requireExtension(method);
		Assert.notNull(paramsType, "Params type must not be null");
		Assert.notNull(handler, "Handler must not be null");
		return request(method, paramsType, handler::handle);
	}

	/**
	 * Registers the handler for a custom extension request ({@code _}-prefixed method name) from
	 * the client, its params delivered as the raw JSON value (a {@code Map}, {@code List},
	 * {@code String}, {@code Number} or {@code Boolean}), for a handler that also needs the agent
	 * it serves.
	 * @param <R> the result type, any type the JSON mapper can write
	 * @param method the method name, which must start with {@code _}
	 * @param handler the handler; it receives the params and the agent; must not be null
	 * @return this builder
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 * @see #extRequestHandler(String, TypeRef, AgentAwareHandler)
	 */
	public <R> B extRequestHandler(String method, AgentAwareHandler<Object, R> handler) {
		return extRequestHandler(method, AgentHandlers.RAW_PARAMS, handler);
	}

	/**
	 * Registers the handler for a custom extension notification ({@code _}-prefixed method name)
	 * from the client, its params read as the given type, as
	 * {@link AcpAgent.AsyncAgentBuilder#extNotificationHandler(String, TypeRef, AcpAgent.ExtNotificationHandler)}
	 * does, for a handler that also needs the agent it serves, for example to answer with a
	 * notification of its own ({@link AcpAsyncAgent#sendExtNotification(String, Object)}). Its
	 * {@code Mono} completes when the notification is handled; a handler that fails is only
	 * logged.
	 * @param <T> the params type
	 * @param method the method name, which must start with {@code _}
	 * @param paramsType the type the params are read as; must not be null
	 * @param handler the handler; it receives the params and the agent; must not be null
	 * @return this builder
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 */
	public <T> B extNotificationHandler(String method, TypeRef<T> paramsType, AgentAwareHandler<T, Void> handler) {
		ExtensionMethods.requireExtension(method);
		Assert.notNull(paramsType, "Params type must not be null");
		Assert.notNull(handler, "Handler must not be null");
		return notification(method, paramsType, handler::handle);
	}

	/**
	 * Registers the handler for a custom extension notification ({@code _}-prefixed method name)
	 * from the client, its params delivered as the raw JSON value, for a handler that also needs
	 * the agent it serves.
	 * @param method the method name, which must start with {@code _}
	 * @param handler the handler; it receives the params and the agent; must not be null
	 * @return this builder
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 * @see #extNotificationHandler(String, TypeRef, AgentAwareHandler)
	 */
	public B extNotificationHandler(String method, AgentAwareHandler<Object, Void> handler) {
		return extNotificationHandler(method, AgentHandlers.RAW_PARAMS, handler);
	}

}
