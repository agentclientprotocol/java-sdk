/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.SessionState;

/**
 * The sessions one Streamable HTTP connection knows and the SSE stream of each: known
 * sessions, and provisional ones a client opened a stream for, or sent {@code session/load}
 * for, before the agent confirmed them. Provisional sessions are bounded, so a client cannot
 * grow server state with arbitrary ids.
 *
 * <p>
 * Client requests (servlet threads), the agent's outbound messages and closing the connection
 * change the table concurrently, so every change runs under this object's monitor: a check of
 * a session's state and the change it decides stay together (a session GET racing a failed
 * {@code session/load} could otherwise recreate the discarded stream outside the table). A
 * stream first asked for after the connection closed used to stay open, holding its GET
 * forever (found by Lincheck, StreamableHttpSessionsLincheckTest); it is now closed.
 * </p>
 *
 * @author Kaiser Dandangi
 */
final class StreamableHttpSessions {

	private final String connectionId;

	private final StreamableHttpAcpAgentTransportOptions options;

	private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

	private final ConcurrentMap<String, SseOutboundStream> streams = new ConcurrentHashMap<>();

	/** Guarded by {@code this}. */
	private boolean closed;

	StreamableHttpSessions(String connectionId, StreamableHttpAcpAgentTransportOptions options) {
		this.connectionId = connectionId;
		this.options = options;
	}

	/**
	 * The stream of a session, created on first use. Once the connection has closed, the
	 * stream is closed too: a GET subscribing to it completes at once.
	 */
	synchronized SseOutboundStream stream(String sessionId) {
		SseOutboundStream stream = streams.computeIfAbsent(sessionId,
				ignored -> new SseOutboundStream(options.mailboxCapacity(), options.maxPendingSseEvents()));
		if (closed) {
			stream.close();
		}
		return stream;
	}

	/** The stream a session-scoped GET subscribes to; an unknown session becomes provisional. */
	synchronized SseOutboundStream openStream(String sessionId) {
		if (!sessions.containsKey(sessionId)) {
			/*
			 * RFD gap:
			 * The current text says unknown session-scoped GET requests return 404,
			 * but its resume flow also asks clients to open a session stream before
			 * sending session/load. Keep a provisional stream so practical resume can work.
			 */
			addProvisional(sessionId);
		}
		return stream(sessionId);
	}

	/**
	 * Admits a client message scoped to a session: a {@code session/load} may name a new
	 * session, which becomes provisional; anything else must name a known one.
	 * @throws UnknownSessionException otherwise, or when the provisional bound is reached
	 */
	synchronized void admitInbound(String sessionId, boolean isLoad) {
		SessionState current = sessions.get(sessionId);
		if (isLoad) {
			if (current == null) {
				addProvisional(sessionId);
				stream(sessionId);
			}
			return;
		}
		if (current != SessionState.KNOWN) {
			throw new UnknownSessionException("Unknown session " + sessionId);
		}
	}

	synchronized void markKnown(String sessionId) {
		sessions.put(sessionId, SessionState.KNOWN);
		stream(sessionId);
	}

	/** A failed session/load leaves no provisional state behind. */
	synchronized void discardProvisional(String sessionId) {
		if (sessions.remove(sessionId, SessionState.PENDING_LOAD)) {
			SseOutboundStream stream = streams.remove(sessionId);
			if (stream != null) {
				stream.close();
			}
		}
	}

	void keepAlive() {
		streams.values().forEach(SseOutboundStream::keepAlive);
	}

	synchronized void close() {
		closed = true;
		streams.values().forEach(SseOutboundStream::close);
	}

	private synchronized void addProvisional(String sessionId) {
		long provisional = sessions.values().stream().filter(state -> state == SessionState.PENDING_LOAD).count();
		if (provisional >= options.maxProvisionalSessions()
				&& sessions.get(sessionId) != SessionState.PENDING_LOAD) {
			throw new UnknownSessionException("Too many provisional sessions on connection " + connectionId
					+ " (limit " + options.maxProvisionalSessions() + ")");
		}
		sessions.putIfAbsent(sessionId, SessionState.PENDING_LOAD);
	}

}
