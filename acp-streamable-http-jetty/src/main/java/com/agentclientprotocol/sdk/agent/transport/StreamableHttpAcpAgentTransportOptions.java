/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.time.Duration;

import com.agentclientprotocol.sdk.util.Assert;

/**
 * The limits and timings of an ACP Streamable HTTP and WebSocket endpoint, for
 * {@link StreamableHttpAcpAgentTransport} and {@link StreamableHttpAcpServlet} alike. Every
 * buffer the endpoint keeps for a client is bounded by one of these, so a slow or hostile
 * client cannot make the agent's memory grow without bound. Pass one to the listener's or the
 * servlet's constructor when a default does not fit; start from {@link #builder()}, or use
 * {@link #defaults()}:
 *
 * <pre>{@code
 * var options = StreamableHttpAcpAgentTransportOptions.builder()
 *     .maxPostBodyBytes(64L * 1024 * 1024)
 *     .shutdownTimeout(Duration.ofSeconds(10))
 *     .build();
 * }</pre>
 *
 * @param maxPostBodyBytes the largest message accepted from a client: a larger POST body is
 * answered 413, and a larger WebSocket text message closes the connection (1009, message too
 * big); default 16 MB
 * @param mailboxCapacity the events one SSE stream keeps unsent, as while no client is reading
 * it, to send when the client reconnects; one more closes the connection; default 1024
 * @param maxPendingSseEvents the events queued for a client that is reading an SSE stream;
 * one more detaches that client, keeping the events for its next GET; default 1024
 * @param maxWebSocketPendingFrames the frames queued for one WebSocket connection; one more
 * closes the connection; default 1024
 * @param maxProvisionalSessions the session streams a connection may open before the agent
 * knows the session, as a client does before {@code session/load}; a further one is refused;
 * default 64
 * @param keepAliveInterval the interval between keep-alive comments on open SSE streams, which
 * stop proxies from cutting idle streams; {@link Duration#ZERO} turns them off; default 15
 * seconds
 * @param maxConcurrentStreamsPerConnection the HTTP/2 streams one client connection may hold
 * open at once, each open SSE stream holding one; applied by the listener only, since a
 * servlet container configures its own HTTP/2; default 1024
 * @param shutdownTimeout how long closing the endpoint
 * ({@link StreamableHttpAcpServlet#closeGracefully()},
 * {@link StreamableHttpAcpAgentTransport#closeGracefully()} and
 * {@link StreamableHttpAcpServlet#destroy()}) waits for its connections'
 * agents to close before it closes the rest at once; closing never waits for a client, since
 * the SSE responses are completed, not drained; default 5 seconds
 * @author Mark Pollack
 */
public record StreamableHttpAcpAgentTransportOptions(long maxPostBodyBytes, int mailboxCapacity,
		int maxPendingSseEvents, int maxWebSocketPendingFrames, int maxProvisionalSessions,
		Duration keepAliveInterval, int maxConcurrentStreamsPerConnection, Duration shutdownTimeout) {

	private static final long DEFAULT_MAX_POST_BODY_BYTES = 16L * 1024 * 1024;

	private static final int DEFAULT_MAILBOX_CAPACITY = 1024;

	private static final int DEFAULT_MAX_PENDING_SSE_EVENTS = 1024;

	private static final int DEFAULT_MAX_WEBSOCKET_PENDING_FRAMES = 1024;

	private static final int DEFAULT_MAX_PROVISIONAL_SESSIONS = 64;

	private static final Duration DEFAULT_KEEP_ALIVE_INTERVAL = Duration.ofSeconds(15);

	private static final int DEFAULT_MAX_CONCURRENT_STREAMS_PER_CONNECTION = 1024;

	private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * Creates options with these values; prefer {@link #builder()}, which starts from the
	 * defaults.
	 * @param maxPostBodyBytes the largest message accepted from a client, in bytes
	 * @param mailboxCapacity the events kept for an SSE stream no client is reading
	 * @param maxPendingSseEvents the events queued for a client reading an SSE stream
	 * @param maxWebSocketPendingFrames the frames queued for one WebSocket connection
	 * @param maxProvisionalSessions the session streams opened before the session is known
	 * @param keepAliveInterval the interval between SSE keep-alive comments
	 * @param maxConcurrentStreamsPerConnection the HTTP/2 streams one connection may hold
	 * @param shutdownTimeout how long closing waits for the connections' agents
	 * @throws IllegalArgumentException if a count or {@code shutdownTimeout} is not positive,
	 * or {@code keepAliveInterval} is negative or null
	 */
	public StreamableHttpAcpAgentTransportOptions {
		Assert.isTrue(maxPostBodyBytes > 0, "maxPostBodyBytes must be positive");
		Assert.isTrue(mailboxCapacity > 0, "mailboxCapacity must be positive");
		Assert.isTrue(maxPendingSseEvents > 0, "maxPendingSseEvents must be positive");
		Assert.isTrue(maxWebSocketPendingFrames > 0, "maxWebSocketPendingFrames must be positive");
		Assert.isTrue(maxProvisionalSessions > 0, "maxProvisionalSessions must be positive");
		Assert.notNull(keepAliveInterval, "keepAliveInterval must not be null");
		Assert.isTrue(!keepAliveInterval.isNegative(), "keepAliveInterval must not be negative");
		Assert.isTrue(maxConcurrentStreamsPerConnection > 0, "maxConcurrentStreamsPerConnection must be positive");
		Assert.notNull(shutdownTimeout, "shutdownTimeout must not be null");
		Assert.isTrue(!shutdownTimeout.isNegative() && !shutdownTimeout.isZero(), "shutdownTimeout must be positive");
	}

	/**
	 * Returns the default limits and timings, as listed on the record's components.
	 * @return the default options
	 */
	public static StreamableHttpAcpAgentTransportOptions defaults() {
		return builder().build();
	}

	/**
	 * Returns a builder that starts from the defaults.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builds {@link StreamableHttpAcpAgentTransportOptions}, starting from the defaults; set
	 * only the values that should differ. Get one from
	 * {@link StreamableHttpAcpAgentTransportOptions#builder()}.
	 */
	public static final class Builder {

		private long maxPostBodyBytes = DEFAULT_MAX_POST_BODY_BYTES;

		private int mailboxCapacity = DEFAULT_MAILBOX_CAPACITY;

		private int maxPendingSseEvents = DEFAULT_MAX_PENDING_SSE_EVENTS;

		private int maxWebSocketPendingFrames = DEFAULT_MAX_WEBSOCKET_PENDING_FRAMES;

		private int maxProvisionalSessions = DEFAULT_MAX_PROVISIONAL_SESSIONS;

		private Duration keepAliveInterval = DEFAULT_KEEP_ALIVE_INTERVAL;

		private int maxConcurrentStreamsPerConnection = DEFAULT_MAX_CONCURRENT_STREAMS_PER_CONNECTION;

		private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;

		private Builder() {
		}

		/**
		 * Sets the largest message accepted from a client; default 16 MB.
		 * @param maxPostBodyBytes the limit in bytes; positive
		 * @return this builder
		 */
		public Builder maxPostBodyBytes(long maxPostBodyBytes) {
			this.maxPostBodyBytes = maxPostBodyBytes;
			return this;
		}

		/**
		 * Sets the events kept for an SSE stream that no client is reading; default 1024.
		 * @param mailboxCapacity the event count; positive
		 * @return this builder
		 */
		public Builder mailboxCapacity(int mailboxCapacity) {
			this.mailboxCapacity = mailboxCapacity;
			return this;
		}

		/**
		 * Sets the events queued for a client that is reading an SSE stream; default 1024.
		 * @param maxPendingSseEvents the event count; positive
		 * @return this builder
		 */
		public Builder maxPendingSseEvents(int maxPendingSseEvents) {
			this.maxPendingSseEvents = maxPendingSseEvents;
			return this;
		}

		/**
		 * Sets the frames queued for one WebSocket connection; default 1024.
		 * @param maxWebSocketPendingFrames the frame count; positive
		 * @return this builder
		 */
		public Builder maxWebSocketPendingFrames(int maxWebSocketPendingFrames) {
			this.maxWebSocketPendingFrames = maxWebSocketPendingFrames;
			return this;
		}

		/**
		 * Sets the session streams a connection may open before the agent knows the session;
		 * default 64.
		 * @param maxProvisionalSessions the stream count; positive
		 * @return this builder
		 */
		public Builder maxProvisionalSessions(int maxProvisionalSessions) {
			this.maxProvisionalSessions = maxProvisionalSessions;
			return this;
		}

		/**
		 * Sets the interval between keep-alive comments on open SSE streams; default 15
		 * seconds.
		 * @param keepAliveInterval the interval, or {@link Duration#ZERO} for none; not
		 * negative
		 * @return this builder
		 */
		public Builder keepAliveInterval(Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval;
			return this;
		}

		/**
		 * Sets the HTTP/2 streams one client connection may hold open at once; default 1024.
		 * Only the listener applies it.
		 * @param maxConcurrentStreamsPerConnection the stream count; positive
		 * @return this builder
		 */
		public Builder maxConcurrentStreamsPerConnection(int maxConcurrentStreamsPerConnection) {
			this.maxConcurrentStreamsPerConnection = maxConcurrentStreamsPerConnection;
			return this;
		}

		/**
		 * Sets how long closing the endpoint waits for its connections' agents before it
		 * closes the rest at once; default 5 seconds.
		 * @param shutdownTimeout the timeout; positive
		 * @return this builder
		 */
		public Builder shutdownTimeout(Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
			return this;
		}

		/**
		 * Returns the options.
		 * @return the options
		 * @throws IllegalArgumentException if a value is out of range
		 */
		public StreamableHttpAcpAgentTransportOptions build() {
			return new StreamableHttpAcpAgentTransportOptions(maxPostBodyBytes, mailboxCapacity, maxPendingSseEvents,
					maxWebSocketPendingFrames, maxProvisionalSessions, keepAliveInterval,
					maxConcurrentStreamsPerConnection, shutdownTimeout);
		}

	}

}
