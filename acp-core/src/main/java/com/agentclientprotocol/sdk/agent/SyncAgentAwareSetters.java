/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.concurrent.Callable;
import java.util.function.BiConsumer;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.util.Assert;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * The setters of {@link AcpAgent.SyncAgentBuilder} that take a {@link SyncAgentAwareHandler}: one
 * overload of each typed request setter but {@code promptHandler}, and of each extension handler
 * setter, registering a blocking handler that also receives the agent {@code build()} returned. Kept apart from the builder only to keep
 * that class small; it is part of the builder's API.
 */
abstract class SyncAgentAwareSetters<B> {

	/** Only {@link AcpAgent.SyncAgentBuilder} extends this class. */
	SyncAgentAwareSetters() {
	}

	/** The agent {@code build()} returned, handed to the agent-aware handlers. */
	private volatile @Nullable AcpSyncAgent built;

	/** The asynchronous builder the synchronous one registers its handlers on. */
	abstract AsyncAgentAwareSetters<?> asyncBuilder();

	/** Runs a handler on the builder's handler executor. */
	abstract <T> Mono<T> onSyncHandlerThread(Callable<T> handler);

	/** Runs a handler that returns nothing on the builder's handler executor. */
	abstract Mono<Void> runOnSyncHandlerThread(Runnable handler);

	/** Records the agent {@code build()} returns, for the agent-aware handlers. */
	AcpSyncAgent remember(AcpSyncAgent agent) {
		this.built = agent;
		return agent;
	}

	/** The synchronous agent this builder built around the given agent (the same instance). */
	@SuppressWarnings("ReferenceEquality")
	AcpSyncAgent syncView(AcpAsyncAgent agent) {
		AcpSyncAgent agentBuilt = this.built;
		return (agentBuilt != null && agentBuilt.async() == agent) ? agentBuilt : new AcpSyncAgent(agent);
	}

	@SuppressWarnings("unchecked")
	private B self() {
		return (B) this;
	}

	/**
	 * Sets the handler for {@code initialize}, as {@link AcpAgent.SyncAgentBuilder#initializeHandler(AcpAgent.SyncInitializeHandler)}
	 * does, for a
	 * handler that also needs the agent it serves, for example to send session updates
	 * ({@link AcpSyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B initializeHandler(
			SyncAgentAwareHandler<AcpSchema.InitializeRequest, AcpSchema.InitializeResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().initializeHandler((AcpSchema.InitializeRequest request, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(request, syncView(agent))));
		return self();
	}

	/**
	 * Sets the handler for {@code authenticate}, as {@link AcpAgent.SyncAgentBuilder#authenticateHandler(AcpAgent.SyncAuthenticateHandler)}
	 * does, for a
	 * handler that also needs the agent it serves, for example to send session updates
	 * ({@link AcpSyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B authenticateHandler(
			SyncAgentAwareHandler<AcpSchema.AuthenticateRequest, AcpSchema.AuthenticateResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().authenticateHandler((AcpSchema.AuthenticateRequest request, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(request, syncView(agent))));
		return self();
	}

	/**
	 * Sets the handler for {@code logout}, as {@link AcpAgent.SyncAgentBuilder#logoutHandler(AcpAgent.SyncLogoutHandler)}
	 * does, for a
	 * handler that also needs the agent it serves, for example to send session updates
	 * ({@link AcpSyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B logoutHandler(
			SyncAgentAwareHandler<AcpSchema.LogoutRequest, AcpSchema.LogoutResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().logoutHandler((AcpSchema.LogoutRequest request, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(request, syncView(agent))));
		return self();
	}

	/**
	 * Sets the handler for {@code session/new}, as {@link AcpAgent.SyncAgentBuilder#newSessionHandler(AcpAgent.SyncNewSessionHandler)}
	 * does, for a
	 * handler that also needs the agent it serves, for example to send session updates
	 * ({@link AcpSyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B newSessionHandler(
			SyncAgentAwareHandler<AcpSchema.NewSessionRequest, AcpSchema.NewSessionResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().newSessionHandler((AcpSchema.NewSessionRequest request, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(request, syncView(agent))));
		return self();
	}

	/**
	 * Sets the handler for {@code session/load}, as {@link AcpAgent.SyncAgentBuilder#loadSessionHandler(AcpAgent.SyncLoadSessionHandler)}
	 * does, for a
	 * handler that also needs the agent it serves, for example to send session updates
	 * ({@link AcpSyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B loadSessionHandler(
			SyncAgentAwareHandler<AcpSchema.LoadSessionRequest, AcpSchema.LoadSessionResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().loadSessionHandler((AcpSchema.LoadSessionRequest request, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(request, syncView(agent))));
		return self();
	}

	/**
	 * Sets the handler for {@code session/set_mode}, as {@link AcpAgent.SyncAgentBuilder#setSessionModeHandler(AcpAgent.SyncSetSessionModeHandler)}
	 * does, for a
	 * handler that also needs the agent it serves, for example to send session updates
	 * ({@link AcpSyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B setSessionModeHandler(
			SyncAgentAwareHandler<AcpSchema.SetSessionModeRequest, AcpSchema.SetSessionModeResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().setSessionModeHandler((AcpSchema.SetSessionModeRequest request, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(request, syncView(agent))));
		return self();
	}

	/**
	 * Sets the handler for {@code session/list}, as {@link AcpAgent.SyncAgentBuilder#listSessionsHandler(AcpAgent.SyncListSessionsHandler)}
	 * does, for a
	 * handler that also needs the agent it serves, for example to send session updates
	 * ({@link AcpSyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B listSessionsHandler(
			SyncAgentAwareHandler<AcpSchema.ListSessionsRequest, AcpSchema.ListSessionsResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().listSessionsHandler((AcpSchema.ListSessionsRequest request, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(request, syncView(agent))));
		return self();
	}

	/**
	 * Sets the handler for {@code session/close}, as {@link AcpAgent.SyncAgentBuilder#closeSessionHandler(AcpAgent.SyncCloseSessionHandler)}
	 * does, for a
	 * handler that also needs the agent it serves, for example to send session updates
	 * ({@link AcpSyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B closeSessionHandler(
			SyncAgentAwareHandler<AcpSchema.CloseSessionRequest, AcpSchema.CloseSessionResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().closeSessionHandler((AcpSchema.CloseSessionRequest request, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(request, syncView(agent))));
		return self();
	}

	/**
	 * Sets the handler for {@code session/delete}, as {@link AcpAgent.SyncAgentBuilder#deleteSessionHandler(AcpAgent.SyncDeleteSessionHandler)}
	 * does, for a
	 * handler that also needs the agent it serves, for example to send session updates
	 * ({@link AcpSyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B deleteSessionHandler(
			SyncAgentAwareHandler<AcpSchema.DeleteSessionRequest, AcpSchema.DeleteSessionResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().deleteSessionHandler((AcpSchema.DeleteSessionRequest request, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(request, syncView(agent))));
		return self();
	}

	/**
	 * Sets the handler for {@code session/resume}, as {@link AcpAgent.SyncAgentBuilder#resumeSessionHandler(AcpAgent.SyncResumeSessionHandler)}
	 * does, for a
	 * handler that also needs the agent it serves, for example to send session updates
	 * ({@link AcpSyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B resumeSessionHandler(
			SyncAgentAwareHandler<AcpSchema.ResumeSessionRequest, AcpSchema.ResumeSessionResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().resumeSessionHandler((AcpSchema.ResumeSessionRequest request, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(request, syncView(agent))));
		return self();
	}

	/**
	 * Sets the handler for {@code session/fork}, as {@link AcpAgent.SyncAgentBuilder#forkSessionHandler(AcpAgent.SyncForkSessionHandler)}
	 * does, for a
	 * handler that also needs the agent it serves, for example to send session updates
	 * ({@link AcpSyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	@UnstableAcpApi
	public B forkSessionHandler(
			SyncAgentAwareHandler<AcpSchema.ForkSessionRequest, AcpSchema.ForkSessionResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().forkSessionHandler((AcpSchema.ForkSessionRequest request, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(request, syncView(agent))));
		return self();
	}

	/**
	 * Sets the handler for {@code session/set_config_option}, as {@link AcpAgent.SyncAgentBuilder#setSessionConfigOptionHandler(AcpAgent.SyncSetSessionConfigOptionHandler)}
	 * does, for a
	 * handler that also needs the agent it serves, for example to send session updates
	 * ({@link AcpSyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	public B setSessionConfigOptionHandler(
			SyncAgentAwareHandler<AcpSchema.SetSessionConfigOptionRequest, AcpSchema.SetSessionConfigOptionResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().setSessionConfigOptionHandler((AcpSchema.SetSessionConfigOptionRequest request, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(request, syncView(agent))));
		return self();
	}

	/**
	 * Sets the handler for {@code providers/list}, as {@link AcpAgent.SyncAgentBuilder#listProvidersHandler(AcpAgent.SyncListProvidersHandler)}
	 * does, for a
	 * handler that also needs the agent it serves, for example to send session updates
	 * ({@link AcpSyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	@UnstableAcpApi
	public B listProvidersHandler(
			SyncAgentAwareHandler<AcpSchema.ListProvidersRequest, AcpSchema.ListProvidersResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().listProvidersHandler((AcpSchema.ListProvidersRequest request, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(request, syncView(agent))));
		return self();
	}

	/**
	 * Sets the handler for {@code providers/set}, as {@link AcpAgent.SyncAgentBuilder#setProviderHandler(AcpAgent.SyncSetProviderHandler)}
	 * does, for a
	 * handler that also needs the agent it serves, for example to send session updates
	 * ({@link AcpSyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	@UnstableAcpApi
	public B setProviderHandler(
			SyncAgentAwareHandler<AcpSchema.SetProviderRequest, AcpSchema.SetProviderResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().setProviderHandler((AcpSchema.SetProviderRequest request, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(request, syncView(agent))));
		return self();
	}

	/**
	 * Sets the handler for {@code providers/disable}, as {@link AcpAgent.SyncAgentBuilder#disableProviderHandler(AcpAgent.SyncDisableProviderHandler)}
	 * does, for a
	 * handler that also needs the agent it serves, for example to send session updates
	 * ({@link AcpSyncAgent#sendSessionUpdate}) or call the client.
	 * @param handler the handler; it receives the request and the agent; must not be null
	 * @return this builder
	 */
	@UnstableAcpApi
	public B disableProviderHandler(
			SyncAgentAwareHandler<AcpSchema.DisableProviderRequest, AcpSchema.DisableProviderResponse> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().disableProviderHandler((AcpSchema.DisableProviderRequest request, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(request, syncView(agent))));
		return self();
	}

	/**
	 * Registers the handler for a custom extension request ({@code _}-prefixed method name, ACP v1
	 * Extensibility) from the client, its params read as the given type, as
	 * {@link AcpAgent.SyncAgentBuilder#extRequestHandler(String, TypeRef, AcpAgent.SyncExtRequestHandler)}
	 * does, for a handler that also needs the agent it serves, for example to call the client back
	 * ({@link AcpSyncAgent#sendExtRequest(String, Object, TypeRef)}) or send session updates. Name
	 * the agent parameter {@code self}, not the name the built agent is assigned to.
	 * @param <T> the params type
	 * @param <R> the result type, any type the JSON mapper can write; returning {@code null}
	 * answers the request with an internal error ({@code -32603})
	 * @param method the method name, which must start with {@code _} (for example
	 * {@code _example.com/workspace/buffers})
	 * @param paramsType the type the params are read as; must not be null
	 * @param handler the handler; it receives the params and the agent; must not be null
	 * @return this builder
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 */
	public <T, R> B extRequestHandler(String method, TypeRef<T> paramsType, SyncAgentAwareHandler<T, R> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().extRequestHandler(method, paramsType, (T params, AcpAsyncAgent agent) -> onSyncHandlerThread(
				() -> handler.handle(params, syncView(agent))));
		return self();
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
	 * @see #extRequestHandler(String, TypeRef, SyncAgentAwareHandler)
	 */
	public <R> B extRequestHandler(String method, SyncAgentAwareHandler<Object, R> handler) {
		return extRequestHandler(method, AgentHandlers.RAW_PARAMS, handler);
	}

	/**
	 * Registers the handler for a custom extension notification ({@code _}-prefixed method name)
	 * from the client, its params read as the given type, as
	 * {@link AcpAgent.SyncAgentBuilder#extNotificationHandler(String, TypeRef, AcpAgent.SyncExtNotificationHandler)}
	 * does, for a handler that also needs the agent it serves, for example to answer with a
	 * notification of its own ({@link AcpSyncAgent#sendExtNotification(String, Object)}). It runs
	 * on the builder's handler executor and may block; a handler that throws is only logged.
	 * Name the agent parameter {@code self}, not the name the built agent is assigned to.
	 * @param <T> the params type
	 * @param method the method name, which must start with {@code _}
	 * @param paramsType the type the params are read as; must not be null
	 * @param handler the handler; it receives the params and the agent; must not be null
	 * @return this builder
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 */
	public <T> B extNotificationHandler(String method, TypeRef<T> paramsType, BiConsumer<T, AcpSyncAgent> handler) {
		Assert.notNull(handler, "Handler must not be null");
		asyncBuilder().extNotificationHandler(method, paramsType, (T params, AcpAsyncAgent agent) -> runOnSyncHandlerThread(
				() -> handler.accept(params, syncView(agent))));
		return self();
	}

	/**
	 * Registers the handler for a custom extension notification ({@code _}-prefixed method name)
	 * from the client, its params delivered as the raw JSON value, for a handler that also needs
	 * the agent it serves.
	 * @param method the method name, which must start with {@code _}
	 * @param handler the handler; it receives the params and the agent; must not be null
	 * @return this builder
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 * @see #extNotificationHandler(String, TypeRef, BiConsumer)
	 */
	public B extNotificationHandler(String method, BiConsumer<Object, AcpSyncAgent> handler) {
		return extNotificationHandler(method, AgentHandlers.RAW_PARAMS, handler);
	}

}
