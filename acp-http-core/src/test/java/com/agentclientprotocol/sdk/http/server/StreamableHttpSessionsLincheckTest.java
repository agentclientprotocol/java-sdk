/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jetbrains.lincheck.datastructures.BooleanGen;
import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.Validate;
import org.junit.jupiter.api.Test;

/**
 * Model checks the session table of one Streamable HTTP connection,
 * {@link StreamableHttpSessions}, with Lincheck: client requests (session GETs, session-scoped
 * POSTs) on servlet threads race the agent's outbound thread (which confirms or discards a
 * provisional session from a {@code session/load} reply, and pushes events to session
 * streams) and the connection closing.
 *
 * <p>
 * The sequential specification is a map from session to state (provisional or known) with
 * the provisional bound, here one. Checked after every interleaving: once the connection has
 * closed, every stream it handed out is closed.
 * </p>
 */
class StreamableHttpSessionsLincheckTest {

	/**
	 * Multiplies the number of scenarios explored. The default keeps this test to seconds in
	 * the build; CI's lincheck job raises it with -Dlincheck.scale. Scenarios and interleavings
	 * are generated from a fixed seed, so a run is repeatable.
	 */
	private static final int SCALE = Integer.getInteger("lincheck.scale", 1);

	@Test
	void sessionTableIsLinearizable() {
		new ModelCheckingOptions().iterations(30 * SCALE)
			.invocationsPerIteration(100)
			.threads(2)
			.actorsPerThread(3)
			.actorsBefore(1)
			.actorsAfter(1)
			.sequentialSpecification(SessionsSpec.class)
			.check(Sessions.class);
	}

	@Param(name = "session", gen = IntGen.class, conf = "0:1")
	public static class Sessions {

		private final StreamableHttpSessions sessions = new StreamableHttpSessions("connection",
				StreamableHttpAcpAgentTransportOptions.builder().maxProvisionalSessions(1).build());

		/** Every stream handed out, to a GET or to the agent's outbound thread. */
		private final List<SseOutboundStream> handedOut = new CopyOnWriteArrayList<>();

		private volatile boolean closed;

		/** A session-scoped GET. */
		@Operation
		public String openStream(@Param(name = "session") int session) {
			try {
				handedOut.add(sessions.openStream("s" + session));
				return "OPEN";
			}
			catch (UnknownSessionException e) {
				return "REJECTED";
			}
		}

		/** A session-scoped POST: a session/load, or any other request. */
		@Operation
		public String admit(@Param(name = "session") int session, @Param(gen = BooleanGen.class) boolean load) {
			try {
				return sessions.admitInbound("s" + session, load) ? "STREAM" : "CONNECTION";
			}
			catch (UnknownSessionException e) {
				return "UNKNOWN";
			}
		}

		/** The agent's reply to session/new or a successful session/load. */
		@Operation(nonParallelGroup = "agent")
		public void markKnown(@Param(name = "session") int session) {
			sessions.markKnown("s" + session);
		}

		/** The agent's error reply to session/load. */
		@Operation(nonParallelGroup = "agent")
		public void discardProvisional(@Param(name = "session") int session) {
			sessions.discardProvisional("s" + session);
		}

		/** The agent emits a message scoped to the session. */
		@Operation(nonParallelGroup = "agent")
		public void push(@Param(name = "session") int session) {
			handedOut.add(sessions.stream("s" + session));
		}

		@Operation
		public void close() {
			sessions.close();
			closed = true;
		}

		@Validate
		public void aClosedConnectionHasNoOpenStream() {
			if (closed) {
				for (SseOutboundStream stream : handedOut) {
					if (!stream.isClosed()) {
						throw new IllegalStateException("a stream handed out stayed open after the connection closed");
					}
				}
			}
		}

	}

	/** Sequential specification: session states and the provisional bound of one. */
	public static class SessionsSpec {

		private static final String PROVISIONAL = "PROVISIONAL";

		private static final String KNOWN = "KNOWN";

		private final Map<Integer, String> states = new HashMap<>();

		private boolean addProvisional(int session) {
			long provisional = states.values().stream().filter(PROVISIONAL::equals).count();
			if (provisional >= 1 && !PROVISIONAL.equals(states.get(session))) {
				return false;
			}
			states.putIfAbsent(session, PROVISIONAL);
			return true;
		}

		public String openStream(int session) {
			if (!states.containsKey(session) && !addProvisional(session)) {
				return "REJECTED";
			}
			return "OPEN";
		}

		/**
		 * A load may add a provisional session; nothing else changes the table, and no
		 * session id is refused (the RFD's POST tree has no 404 for an unknown session). The
		 * answer says whether the session has a stream to reply on.
		 */
		public String admit(int session, boolean load) {
			if (load && !states.containsKey(session) && !addProvisional(session)) {
				return "UNKNOWN";
			}
			return states.containsKey(session) ? "STREAM" : "CONNECTION";
		}

		public void markKnown(int session) {
			states.put(session, KNOWN);
		}

		public void discardProvisional(int session) {
			states.remove(session, PROVISIONAL);
		}

		public void push(int session) {
		}

		public void close() {
		}

	}

}
