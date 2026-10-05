/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

/**
 * A request handler that also receives the agent it serves, so it can act back on the client
 * without capturing the built agent in a variable: send session updates (a
 * {@code session/load} replay, a {@link AcpSchema.ConfigOptionUpdate} after a config change),
 * or call the client. Every typed setter of {@link AcpAgent.AsyncAgentBuilder} except
 * {@code promptHandler} (whose {@link PromptContext} already reaches the client) has an
 * overload that takes one, for the same request and response types as its own handler
 * interface, and so do {@code extRequestHandler} and {@code extNotificationHandler} (an
 * {@code AgentAwareHandler<T, Void>}); a two-argument lambda picks it.
 *
 * <pre>{@code
 * AcpAsyncAgent agent = AcpAgent.async(transport)
 *     .setSessionConfigOptionHandler((request, self) -> self
 *         .sendSessionUpdate(request.sessionId(), new AcpSchema.ConfigOptionUpdate(options))
 *         .thenReturn(new AcpSchema.SetSessionConfigOptionResponse(options)))
 *     .build();
 * }</pre>
 *
 * <p>The second parameter is the agent being built, the same instance {@code build()} returns; a
 * lambda parameter must not reuse the name of a local variable in scope, so call it {@code self}
 * (or anything but the name the built agent is assigned to), as above.
 *
 * <p>Implementations follow the rules on {@link AcpAgent.PromptHandler}: they run on the transport's
 * thread and must not block, return a {@code Mono} and never {@code null}, and fail with an
 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} to answer with a chosen error.
 * @param <Q> the request type
 * @param <R> the response type
 * @author Mark Pollack
 * @see SyncAgentAwareHandler
 */
@FunctionalInterface
public interface AgentAwareHandler<Q, R> {

	/**
	 * Answers one request from the client.
	 * @param request the request, as the client sent it; never null
	 * @param agent the agent serving the request, the one {@code build()} returned
	 * @return a {@code Mono} that emits the answer
	 */
	Mono<R> handle(Q request, AcpAsyncAgent agent);

}
