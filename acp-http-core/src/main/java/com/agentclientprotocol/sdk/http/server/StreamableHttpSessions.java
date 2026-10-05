/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.http.server.StreamableHttpRouting.SessionState;

/**
 * The sessions one Streamable HTTP connection knows and the SSE stream of each: known
 * sessions, and provisional ones a client opened a stream for, or sent {@code session/load}
 * for, before the agent confirmed them. Provisional sessions are bounded, so a client cannot
 * grow server state with arbitrary ids.
 *
 * <p>
 * Client requests (servlet threads), the agent's outbound messages and closing the connection
 * change the table concurrently, so every change runs under this object's lock: a check of
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

	/**
	 * Guards every change of the table. A lock, not a monitor: changes run on the host's threads,
	 * virtual ones included, and closing a stream may wait for that stream's own lock.
	 */
	private final ReentrantLock lock = new ReentrantLock();

	/** Guarded by {@code lock}. */
	private boolean closed;

	StreamableHttpSessions(String connectionId, StreamableHttpAcpAgentTransportOptions options) {
		this.connectionId = connectionId;
		this.options = options;
	}

	/**
	 * The stream of a session, created on first use. Once the connection has closed, the
	 * stream is closed too: a GET subscribing to it completes at once.
	 */
	SseOutboundStream stream(String sessionId) {
		lock.lock();
		try {
			SseOutboundStream stream = streams.computeIfAbsent(sessionId,
					ignored -> new SseOutboundStream(options.mailboxCapacity(), options.maxPendingSseEvents()));
			if (closed) {
				stream.close();
			}
			return stream;
		}
		finally {
			lock.unlock();
		}
	}

	/** The stream a session-scoped GET subscribes to; an unknown session becomes provisional. */
	SseOutboundStream openStream(String sessionId) {
		lock.lock();
		try {
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
		finally {
			lock.unlock();
		}
	}

	/**
	 * Admits a client message scoped to a session: a {@code session/load} may name a new
	 * session, which becomes provisional. Any other message is admitted whatever it names:
	 * the RFD's POST decision tree has no 404 for a session the connection does not know, so
	 * the agent decides (a {@code session/delete} of a session that never existed succeeds,
	 * session-delete.mdx).
	 * @return whether the session has a stream to answer on; otherwise its reply belongs on
	 * the connection stream, and no state is kept for an id the agent never confirmed
	 * @throws UnknownSessionException when a load would exceed the provisional bound
	 */
	boolean admitInbound(String sessionId, boolean isLoad) {
		lock.lock();
		try {
			if (isLoad && !sessions.containsKey(sessionId)) {
				addProvisional(sessionId);
				stream(sessionId);
			}
			return sessions.containsKey(sessionId);
		}
		finally {
			lock.unlock();
		}
	}

	void markKnown(String sessionId) {
		lock.lock();
		try {
			sessions.put(sessionId, SessionState.KNOWN);
			stream(sessionId);
		}
		finally {
			lock.unlock();
		}
	}

	/** A failed session/load leaves no provisional state behind. */
	void discardProvisional(String sessionId) {
		lock.lock();
		try {
			if (sessions.remove(sessionId, SessionState.PENDING_LOAD)) {
				SseOutboundStream stream = streams.remove(sessionId);
				if (stream != null) {
					stream.close();
				}
			}
		}
		finally {
			lock.unlock();
		}
	}

	void keepAlive() {
		streams.values().forEach(SseOutboundStream::keepAlive);
	}

	void close() {
		lock.lock();
		try {
			closed = true;
			streams.values().forEach(SseOutboundStream::close);
		}
		finally {
			lock.unlock();
		}
	}

	/** Closes every session stream with a closing comment: the endpoint is shutting down. */
	void closeForShutdown() {
		lock.lock();
		try {
			closed = true;
			streams.values().forEach(SseOutboundStream::closeForShutdown);
		}
		finally {
			lock.unlock();
		}
	}

	private void addProvisional(String sessionId) {
		lock.lock();
		try {
			long provisional = sessions.values().stream().filter(state -> state == SessionState.PENDING_LOAD).count();
			if (provisional >= options.maxProvisionalSessions()
					&& sessions.get(sessionId) != SessionState.PENDING_LOAD) {
				throw new UnknownSessionException("Too many provisional sessions on connection " + connectionId
						+ " (limit " + options.maxProvisionalSessions() + ")");
			}
			sessions.putIfAbsent(sessionId, SessionState.PENDING_LOAD);
		}
		finally {
			lock.unlock();
		}
	}

}
