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
 * @author Kaiser Dandangi
 */
final class StreamableHttpSessions {

	private final String connectionId;

	private final StreamableHttpAcpAgentTransportOptions options;

	private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

	private final ConcurrentMap<String, SseOutboundStream> streams = new ConcurrentHashMap<>();

	StreamableHttpSessions(String connectionId, StreamableHttpAcpAgentTransportOptions options) {
		this.connectionId = connectionId;
		this.options = options;
	}

	/** The stream of a session, created on first use. */
	SseOutboundStream stream(String sessionId) {
		return streams.computeIfAbsent(sessionId,
				ignored -> new SseOutboundStream(options.mailboxCapacity(), options.maxPendingSseEvents()));
	}

	/** The stream a session-scoped GET subscribes to; an unknown session becomes provisional. */
	SseOutboundStream openStream(String sessionId) {
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
	void admitInbound(String sessionId, boolean isLoad) {
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

	void markKnown(String sessionId) {
		sessions.put(sessionId, SessionState.KNOWN);
		stream(sessionId);
	}

	/** A failed session/load leaves no provisional state behind. */
	void discardProvisional(String sessionId) {
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

	void close() {
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
