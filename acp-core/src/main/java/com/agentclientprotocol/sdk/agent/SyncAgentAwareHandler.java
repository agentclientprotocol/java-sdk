/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * The blocking counterpart of {@link AgentAwareHandler}: a request handler on the
 * {@link AcpAgent.SyncAgentBuilder} that also receives the agent it serves, the {@link AcpSyncAgent}
 * {@code build()} returned, so it can send session updates or call the client with blocking
 * calls. Every typed setter of the synchronous builder except {@code promptHandler} has an
 * overload that takes one, and so does {@code extRequestHandler} ({@code extNotificationHandler}
 * takes a {@link java.util.function.BiConsumer} of the params and the agent); a two-argument lambda
 * picks it.
 *
 * <pre>{@code
 * AcpSyncAgent agent = AcpAgent.sync(transport)
 *     .loadSessionHandler((request, self) -> {
 *         for (AcpSchema.SessionUpdate update : history.get(request.sessionId())) {
 *             self.sendSessionUpdate(request.sessionId(), update);   // the replay
 *         }
 *         return new AcpSchema.LoadSessionResponse(null, null);
 *     })
 *     .build();
 * }</pre>
 *
 * <p>The second parameter is the agent being built, the same instance {@code build()} returns; a
 * lambda parameter must not reuse the name of a local variable in scope, so call it {@code self}
 * (or anything but the name the built agent is assigned to), as above.
 *
 * <p>Implementations run on the builder's handler executor and may block.
 * @param <Q> the request type
 * @param <R> the response type
 * @author Mark Pollack
 * @see AgentAwareHandler
 */
@FunctionalInterface
public interface SyncAgentAwareHandler<Q, R> {

	/**
	 * Answers one request from the client. It may block.
	 * @param request the request, as the client sent it; never null
	 * @param agent the agent serving the request, the one {@code build()} returned
	 * @return the answer; {@code null} is answered {@code -32603}
	 */
	R handle(Q request, AcpSyncAgent agent);

}
