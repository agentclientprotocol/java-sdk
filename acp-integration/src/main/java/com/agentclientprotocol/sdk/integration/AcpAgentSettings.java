/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import org.jspecify.annotations.Nullable;

/**
 * How the application's {@code @AcpAgent} is served, whatever framework hosts it. A framework
 * binds its own configuration onto a {@link #builder()} (or, from plain key/value configuration,
 * calls {@link #from(SettingsSource, String)}); a value left unset keeps the SDK's default, so
 * no default is decided twice.
 *
 * <pre>
 * enabled                                               true
 * request-timeout                                       SDK default (agent-to-client requests)
 * cancel-grace-period                                   SDK default
 * max-prompt-duration                                   SDK default (none)
 * shutdown-on-transport-end                             true
 * transport.type                                        stdio | http | websocket (same as http)
 * transport.http.path                                   /acp
 * transport.http.max-post-body-size, keep-alive-interval, mailbox-capacity,
 *     max-pending-sse-events, max-web-socket-pending-frames, max-provisional-sessions,
 *     shutdown-timeout                                  SDK defaults
 * transport.http.listener.port                          8080 (0: ephemeral)
 * transport.http.listener.max-concurrent-streams-per-connection   SDK default
 * </pre>
 *
 * @param enabled whether the {@code @AcpAgent} is served
 * @param requestTimeout how long a request the agent sends to the client waits for its answer;
 * null for the SDK default
 * @param cancelGracePeriod how long a prompt handler has after {@code session/cancel}; null for
 * the SDK default
 * @param maxPromptDuration how long a prompt may run; null for the SDK default (no limit)
 * @param shutdownOnTransportEnd whether the application stops when a single transport (stdio)
 * ends by itself
 * @param transport the transport; {@link AcpTransportType#WEBSOCKET} means the same as
 * {@link AcpTransportType#HTTP}
 * @param http the Streamable HTTP endpoint
 */
public record AcpAgentSettings(boolean enabled, @Nullable Duration requestTimeout,
		@Nullable Duration cancelGracePeriod, @Nullable Duration maxPromptDuration, boolean shutdownOnTransportEnd,
		AcpTransportType transport, Http http) {

	/** The default endpoint path. */
	public static final String DEFAULT_PATH = "/acp";

	/** The default port of the SDK's own listener. */
	public static final int DEFAULT_LISTENER_PORT = 8080;

	/**
	 * Settings, each checked.
	 * @throws NullPointerException if {@code transport} or {@code http} is null
	 */
	public AcpAgentSettings {
		Objects.requireNonNull(transport, "transport");
		Objects.requireNonNull(http, "http");
	}

	/**
	 * The Streamable HTTP endpoint, wherever it is served: in the framework's own server, or by
	 * the SDK's listener.
	 * @param path the endpoint path
	 * @param limits the message and stream bounds
	 * @param listener the SDK listener's own settings
	 */
	public record Http(String path, Limits limits, Listener listener) {

		/**
		 * An endpoint, each part checked.
		 * @throws NullPointerException if a part is null
		 */
		public Http {
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(limits, "limits");
			Objects.requireNonNull(listener, "listener");
		}

	}

	/**
	 * Message and stream bounds of the endpoint; each null keeps the SDK default
	 * ({@link StreamableHttpAcpAgentTransportOptions}).
	 * @param maxPostBodyBytes the largest inbound message (POST body or WebSocket text message)
	 * @param keepAliveInterval the interval between SSE keep-alive comments; zero disables them
	 * @param mailboxCapacity the events retained per outbound stream while none is attached
	 * @param maxPendingSseEvents the events queued for one SSE subscriber before it is closed
	 * @param maxWebSocketPendingFrames the frames queued for one WebSocket connection
	 * @param maxProvisionalSessions the session streams a connection may open before the session is
	 * known
	 * @param shutdownTimeout how long closing waits for the connections to close gracefully
	 */
	public record Limits(@Nullable Long maxPostBodyBytes, @Nullable Duration keepAliveInterval,
			@Nullable Integer mailboxCapacity, @Nullable Integer maxPendingSseEvents,
			@Nullable Integer maxWebSocketPendingFrames, @Nullable Integer maxProvisionalSessions,
			@Nullable Duration shutdownTimeout) {
	}

	/**
	 * Settings of the SDK's own listener only; inside a framework's server they mean nothing.
	 * @param port the port; 0 for an ephemeral one
	 * @param maxConcurrentStreamsPerConnection the HTTP/2 streams one client connection may hold
	 * open; null for the SDK default
	 */
	public record Listener(int port, @Nullable Integer maxConcurrentStreamsPerConnection) {
	}

	/**
	 * Whether the agent is served over Streamable HTTP (and WebSocket) rather than one transport.
	 * @return true for {@code http} and {@code websocket}
	 */
	public boolean servesHttp() {
		return transport != AcpTransportType.STDIO;
	}

	/**
	 * The SDK transport options for the endpoint's limits; an unset limit keeps the SDK default.
	 * Needs {@code acp-streamable-http-jetty} on the classpath.
	 * @param listener true for the SDK's own listener, which also takes the listener's stream
	 * limit; false inside a framework's server
	 * @return the options
	 */
	public StreamableHttpAcpAgentTransportOptions toOptions(boolean listener) {
		Limits limits = http.limits();
		StreamableHttpAcpAgentTransportOptions.Builder options = StreamableHttpAcpAgentTransportOptions.builder();
		ifSet(limits.maxPostBodyBytes(), options::maxPostBodyBytes);
		ifSet(limits.keepAliveInterval(), options::keepAliveInterval);
		ifSet(limits.mailboxCapacity(), options::mailboxCapacity);
		ifSet(limits.maxPendingSseEvents(), options::maxPendingSseEvents);
		ifSet(limits.maxWebSocketPendingFrames(), options::maxWebSocketPendingFrames);
		ifSet(limits.maxProvisionalSessions(), options::maxProvisionalSessions);
		ifSet(limits.shutdownTimeout(), options::shutdownTimeout);
		if (listener) {
			ifSet(http.listener().maxConcurrentStreamsPerConnection(), options::maxConcurrentStreamsPerConnection);
		}
		return options.build();
	}

	private static <T> void ifSet(@Nullable T value, Consumer<T> setter) {
		if (value != null) {
			setter.accept(value);
		}
	}

	/**
	 * A builder with every default.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * The settings under a prefix of key/value configuration, keys in kebab case, such as
	 * {@code acp.agent.request-timeout} and {@code acp.agent.transport.http.listener.port} for
	 * prefix {@code acp.agent}. Durations are ISO-8601 ({@code PT30S}) or an amount with a unit
	 * ({@code 30s}, {@code 500ms}, {@code 2m}); sizes are bytes or an amount with {@code KB},
	 * {@code MB} or {@code GB}.
	 * @param source the configuration
	 * @param prefix the prefix of the agent's keys, such as {@code acp.agent}
	 * @return the settings
	 * @throws IllegalArgumentException naming the key, if a value does not parse
	 */
	public static AcpAgentSettings from(SettingsSource source, String prefix) {
		SettingsReader read = new SettingsReader(source, prefix);
		Builder builder = builder().enabled(read.bool("enabled", true))
			.requestTimeout(read.duration("request-timeout"))
			.cancelGracePeriod(read.duration("cancel-grace-period"))
			.maxPromptDuration(read.duration("max-prompt-duration"))
			.shutdownOnTransportEnd(read.bool("shutdown-on-transport-end", true))
			.maxPostBodyBytes(read.bytes("transport.http.max-post-body-size"))
			.keepAliveInterval(read.duration("transport.http.keep-alive-interval"))
			.mailboxCapacity(read.integer("transport.http.mailbox-capacity"))
			.maxPendingSseEvents(read.integer("transport.http.max-pending-sse-events"))
			.maxWebSocketPendingFrames(read.integer("transport.http.max-web-socket-pending-frames"))
			.maxProvisionalSessions(read.integer("transport.http.max-provisional-sessions"))
			.shutdownTimeout(read.duration("transport.http.shutdown-timeout"))
			.maxConcurrentStreamsPerConnection(
					read.integer("transport.http.listener.max-concurrent-streams-per-connection"));
		AcpTransportType type = read.transportType("transport.type");
		if (type != null) {
			builder.transport(type);
		}
		String path = read.string("transport.http.path");
		if (path != null) {
			builder.path(path);
		}
		Integer port = read.integer("transport.http.listener.port");
		if (port != null) {
			builder.listenerPort(port);
		}
		return builder.build();
	}

	/** Builds {@link AcpAgentSettings}; every setting has a default. */
	public static final class Builder {

		private boolean enabled = true;

		private @Nullable Duration requestTimeout;

		private @Nullable Duration cancelGracePeriod;

		private @Nullable Duration maxPromptDuration;

		private boolean shutdownOnTransportEnd = true;

		private AcpTransportType transport = AcpTransportType.STDIO;

		private String path = DEFAULT_PATH;

		private @Nullable Long maxPostBodyBytes;

		private @Nullable Duration keepAliveInterval;

		private @Nullable Integer mailboxCapacity;

		private @Nullable Integer maxPendingSseEvents;

		private @Nullable Integer maxWebSocketPendingFrames;

		private @Nullable Integer maxProvisionalSessions;

		private @Nullable Duration shutdownTimeout;

		private int listenerPort = DEFAULT_LISTENER_PORT;

		private @Nullable Integer maxConcurrentStreamsPerConnection;

		private Builder() {
		}

		/**
		 * Sets whether the agent is served. Default true.
		 * @param enabled whether the agent is served
		 * @return this builder
		 */
		public Builder enabled(boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		/**
		 * Sets the agent's request timeout.
		 * @param requestTimeout the timeout, or null for the SDK default
		 * @return this builder
		 */
		public Builder requestTimeout(@Nullable Duration requestTimeout) {
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets the cancel grace period.
		 * @param cancelGracePeriod the grace period, or null for the SDK default
		 * @return this builder
		 */
		public Builder cancelGracePeriod(@Nullable Duration cancelGracePeriod) {
			this.cancelGracePeriod = cancelGracePeriod;
			return this;
		}

		/**
		 * Sets the maximum prompt duration.
		 * @param maxPromptDuration the duration, or null for the SDK default (none)
		 * @return this builder
		 */
		public Builder maxPromptDuration(@Nullable Duration maxPromptDuration) {
			this.maxPromptDuration = maxPromptDuration;
			return this;
		}

		/**
		 * Sets whether the application stops when a single transport ends by itself. Default
		 * true.
		 * @param shutdownOnTransportEnd whether to stop at transport end
		 * @return this builder
		 */
		public Builder shutdownOnTransportEnd(boolean shutdownOnTransportEnd) {
			this.shutdownOnTransportEnd = shutdownOnTransportEnd;
			return this;
		}

		/**
		 * Sets the transport. Default {@link AcpTransportType#STDIO}.
		 * @param transport the transport
		 * @return this builder
		 */
		public Builder transport(AcpTransportType transport) {
			this.transport = Objects.requireNonNull(transport, "transport");
			return this;
		}

		/**
		 * Sets the endpoint path. Default {@value AcpAgentSettings#DEFAULT_PATH}.
		 * @param path the path
		 * @return this builder
		 */
		public Builder path(String path) {
			this.path = Objects.requireNonNull(path, "path");
			return this;
		}

		/**
		 * Sets the largest inbound message.
		 * @param maxPostBodyBytes the size in bytes, or null for the SDK default
		 * @return this builder
		 */
		public Builder maxPostBodyBytes(@Nullable Long maxPostBodyBytes) {
			this.maxPostBodyBytes = maxPostBodyBytes;
			return this;
		}

		/**
		 * Sets the interval between SSE keep-alive comments.
		 * @param keepAliveInterval the interval, zero for none, or null for the SDK default
		 * @return this builder
		 */
		public Builder keepAliveInterval(@Nullable Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval;
			return this;
		}

		/**
		 * Sets the events retained per unattached outbound stream.
		 * @param mailboxCapacity the capacity, or null for the SDK default
		 * @return this builder
		 */
		public Builder mailboxCapacity(@Nullable Integer mailboxCapacity) {
			this.mailboxCapacity = mailboxCapacity;
			return this;
		}

		/**
		 * Sets the events queued for one SSE subscriber.
		 * @param maxPendingSseEvents the limit, or null for the SDK default
		 * @return this builder
		 */
		public Builder maxPendingSseEvents(@Nullable Integer maxPendingSseEvents) {
			this.maxPendingSseEvents = maxPendingSseEvents;
			return this;
		}

		/**
		 * Sets the frames queued for one WebSocket connection.
		 * @param maxWebSocketPendingFrames the limit, or null for the SDK default
		 * @return this builder
		 */
		public Builder maxWebSocketPendingFrames(@Nullable Integer maxWebSocketPendingFrames) {
			this.maxWebSocketPendingFrames = maxWebSocketPendingFrames;
			return this;
		}

		/**
		 * Sets the provisional session streams per connection.
		 * @param maxProvisionalSessions the limit, or null for the SDK default
		 * @return this builder
		 */
		public Builder maxProvisionalSessions(@Nullable Integer maxProvisionalSessions) {
			this.maxProvisionalSessions = maxProvisionalSessions;
			return this;
		}

		/**
		 * Sets how long closing the endpoint waits for its connections.
		 * @param shutdownTimeout the timeout, or null for the SDK default
		 * @return this builder
		 */
		public Builder shutdownTimeout(@Nullable Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
			return this;
		}

		/**
		 * Sets the SDK listener's port. Default {@value AcpAgentSettings#DEFAULT_LISTENER_PORT}.
		 * @param listenerPort the port; 0 for an ephemeral one
		 * @return this builder
		 */
		public Builder listenerPort(int listenerPort) {
			this.listenerPort = listenerPort;
			return this;
		}

		/**
		 * Sets the HTTP/2 streams per client connection of the SDK listener.
		 * @param maxConcurrentStreamsPerConnection the limit, or null for the SDK default
		 * @return this builder
		 */
		public Builder maxConcurrentStreamsPerConnection(@Nullable Integer maxConcurrentStreamsPerConnection) {
			this.maxConcurrentStreamsPerConnection = maxConcurrentStreamsPerConnection;
			return this;
		}

		/**
		 * The settings.
		 * @return the settings
		 */
		public AcpAgentSettings build() {
			Limits limits = new Limits(maxPostBodyBytes, keepAliveInterval, mailboxCapacity, maxPendingSseEvents,
					maxWebSocketPendingFrames, maxProvisionalSessions, shutdownTimeout);
			return new AcpAgentSettings(enabled, requestTimeout, cancelGracePeriod, maxPromptDuration,
					shutdownOnTransportEnd, transport,
					new Http(path, limits, new Listener(listenerPort, maxConcurrentStreamsPerConnection)));
		}

	}

}
