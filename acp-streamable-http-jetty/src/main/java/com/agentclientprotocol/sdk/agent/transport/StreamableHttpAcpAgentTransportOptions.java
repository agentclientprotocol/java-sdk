/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.time.Duration;

import com.agentclientprotocol.sdk.util.Assert;

/**
 * Limits and timings for {@link StreamableHttpAcpAgentTransport}. Every network-facing
 * allocation is bounded by one of these so a slow or hostile client cannot grow memory
 * without bound.
 *
 * @param maxPostBodyBytes largest accepted inbound message: a larger POST body gets 413, and a
 * larger WebSocket text message closes the connection (1009, message too big)
 * @param mailboxCapacity events retained per outbound stream while no subscriber is
 * attached; overflow closes the connection
 * @param maxPendingSseEvents events queued for one attached SSE subscriber before it is
 * closed as backpressured
 * @param maxWebSocketPendingFrames frames queued for one WebSocket connection before it is
 * closed as backpressured
 * @param maxProvisionalSessions session-scoped streams a connection may open before the
 * session is known (the {@code session/load} pre-open case)
 * @param keepAliveInterval interval between SSE keep-alive comments on attached streams;
 * {@link Duration#ZERO} disables them
 * @param maxConcurrentStreamsPerConnection HTTP/2 streams one client connection may hold open
 * at once; each attached SSE stream holds one for its lifetime
 * @param shutdownTimeout how long closing the endpoint ({@code closeGracefully()} of the
 * servlet or the listener, and the servlet's {@code destroy()}) waits for its connections
 * to close gracefully before it closes the rest at once; positive, default 5 seconds.
 * Closing never waits for a client: the SSE responses are completed, not drained
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

	public static StreamableHttpAcpAgentTransportOptions defaults() {
		return builder().build();
	}

	public static Builder builder() {
		return new Builder();
	}

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

		public Builder maxPostBodyBytes(long maxPostBodyBytes) {
			this.maxPostBodyBytes = maxPostBodyBytes;
			return this;
		}

		public Builder mailboxCapacity(int mailboxCapacity) {
			this.mailboxCapacity = mailboxCapacity;
			return this;
		}

		public Builder maxPendingSseEvents(int maxPendingSseEvents) {
			this.maxPendingSseEvents = maxPendingSseEvents;
			return this;
		}

		public Builder maxWebSocketPendingFrames(int maxWebSocketPendingFrames) {
			this.maxWebSocketPendingFrames = maxWebSocketPendingFrames;
			return this;
		}

		public Builder maxProvisionalSessions(int maxProvisionalSessions) {
			this.maxProvisionalSessions = maxProvisionalSessions;
			return this;
		}

		public Builder keepAliveInterval(Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval;
			return this;
		}

		public Builder maxConcurrentStreamsPerConnection(int maxConcurrentStreamsPerConnection) {
			this.maxConcurrentStreamsPerConnection = maxConcurrentStreamsPerConnection;
			return this;
		}

		public Builder shutdownTimeout(Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
			return this;
		}

		public StreamableHttpAcpAgentTransportOptions build() {
			return new StreamableHttpAcpAgentTransportOptions(maxPostBodyBytes, mailboxCapacity, maxPendingSseEvents,
					maxWebSocketPendingFrames, maxProvisionalSessions, keepAliveInterval,
					maxConcurrentStreamsPerConnection, shutdownTimeout);
		}

	}

}
