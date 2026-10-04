/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.concurrent.Callable;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.util.Assert;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * The setters of {@link AcpAgent.SyncAgentBuilder} that take a {@link SyncAgentAwareHandler}: one
 * overload of each typed request setter but {@code promptHandler}, registering a blocking handler
 * that also receives the agent {@code build()} returned. Kept apart from the builder only to keep
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

}
