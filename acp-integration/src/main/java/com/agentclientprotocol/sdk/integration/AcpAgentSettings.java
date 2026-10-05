/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import org.jspecify.annotations.Nullable;

/**
 * How the application's {@code @AcpAgent} is served, in a form every framework shares: whether it
 * is served, its timeouts, what happens when stdio ends, the transport, and the HTTP endpoint's
 * path, port and limits. The framework binds its own configuration onto a {@link #builder()}
 * (Spring Boot and Micronaut {@code @ConfigurationProperties}, Quarkus {@code @ConfigMapping}), or
 * reads plain key/value configuration with {@link #from(SettingsSource, String)}, and hands the
 * result to {@link AcpAgents#builder} and {@link AcpListeners}.
 *
 * <p>A value left unset (null) keeps the SDK's default, so no default is decided twice: the
 * framework passes on only what the user wrote. The keys, under the framework's prefix (such as
 * {@code spring.acp.agent}), and their defaults:
 *
 * <pre>
 * enabled                                               true
 * request-timeout                                       SDK default, 60s (agent-to-client requests)
 * cancel-grace-period                                   SDK default, 60s
 * max-prompt-duration                                   SDK default (none)
 * shutdown-on-transport-end                             true
 * transport.type                                        stdio | http | websocket (same as http)
 * transport.http.path                                   /acp
 * transport.http.allowed-origins                        none (loopback origins only)
 * transport.http.max-post-body-size, keep-alive-interval, mailbox-capacity,
 *     max-pending-sse-events, max-web-socket-pending-frames, max-provisional-sessions,
 *     shutdown-timeout, web-socket-idle-timeout, initialize-timeout   SDK defaults
 * transport.http.listener.host                          loopback (127.0.0.1 and ::1)
 * transport.http.listener.port                          8080 (0: ephemeral)
 * transport.http.listener.max-concurrent-streams-per-connection   SDK default
 * </pre>
 *
 * <p>The framework, not this record, acts on {@code enabled} (no agent when false) and
 * {@code shutdownOnTransportEnd} (which {@code onTransportEnd} action it gives an
 * {@link AcpAgentHost}). The record checks only that its parts are present; the SDK builders
 * reject a negative grace period or prompt duration, and the HTTP options reject out-of-range
 * limits, when the agent or endpoint is built.
 *
 * @param enabled whether the {@code @AcpAgent} is served
 * @param requestTimeout how long a request the agent sends to the client waits for its answer;
 * null for the SDK default
 * @param cancelGracePeriod how long a prompt handler has after {@code session/cancel}; null for
 * the SDK default
 * @param maxPromptDuration how long a prompt turn may run; null for the SDK default (no limit)
 * @param shutdownOnTransportEnd whether the application stops when a single transport (stdio)
 * ends by itself
 * @param transport the transport; {@link AcpTransportType#WEBSOCKET} means the same as
 * {@link AcpTransportType#HTTP}
 * @param http the Streamable HTTP endpoint, used only when {@link #servesHttp()}
 */
public record AcpAgentSettings(boolean enabled, @Nullable Duration requestTimeout,
		@Nullable Duration cancelGracePeriod, @Nullable Duration maxPromptDuration, boolean shutdownOnTransportEnd,
		AcpTransportType transport, Http http) {

	/** The default endpoint path: {@value}. */
	public static final String DEFAULT_PATH = "/acp";

	/** The default port of the SDK's own listener: {@value}. */
	public static final int DEFAULT_LISTENER_PORT = 8080;

	/**
	 * Creates the settings, checking that the transport and endpoint are present.
	 * @throws NullPointerException if {@code transport} or {@code http} is null
	 */
	public AcpAgentSettings {
		Objects.requireNonNull(transport, "transport");
		Objects.requireNonNull(http, "http");
	}

	/**
	 * The agent's Streamable HTTP endpoint, wherever it is served: as the SDK's servlet in the
	 * framework's own server, or by the SDK's listener. The path and limits apply to both; the
	 * listener part only to the SDK's listener.
	 * @param path the endpoint path, such as {@value AcpAgentSettings#DEFAULT_PATH}
	 * @param limits the message and stream bounds
	 * @param listener the SDK listener's own settings
	 * @param allowedOrigins the browser origins accepted besides the loopback ones, such as
	 * {@code https://app.example.com}, or {@code *} for any; empty by default. A request from any
	 * other origin is answered 403, over HTTP and on the WebSocket handshake; see
	 * {@link StreamableHttpAcpAgentTransportOptions#allowedOrigins()}
	 */
	public record Http(String path, Limits limits, Listener listener, List<String> allowedOrigins) {

		/**
		 * Creates an endpoint, checking each part.
		 * @throws NullPointerException if a part is null
		 */
		public Http {
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(limits, "limits");
			Objects.requireNonNull(listener, "listener");
			allowedOrigins = List.copyOf(allowedOrigins);
		}

	}

	/**
	 * The message and stream bounds of the endpoint, for the servlet and the listener alike. Each
	 * null keeps the SDK default; {@link AcpAgentSettings#toOptions(boolean)} turns them into
	 * {@link StreamableHttpAcpAgentTransportOptions}, which documents each default and range.
	 * @param maxPostBodyBytes the largest inbound message in bytes (POST body or WebSocket text
	 * message)
	 * @param keepAliveInterval the interval between SSE keep-alive comments; zero disables them
	 * @param mailboxCapacity the events retained per outbound stream while none is attached
	 * @param maxPendingSseEvents the events queued for one SSE subscriber before it is closed
	 * @param maxWebSocketPendingFrames the frames queued for one WebSocket connection
	 * @param maxProvisionalSessions the session streams a connection may open before the session is
	 * known
	 * @param shutdownTimeout how long closing waits for the connections to close gracefully
	 * @param webSocketIdleTimeout how long a WebSocket connection may pass no frame before the
	 * endpoint closes it
	 * @param initializeTimeout how long a connection has to complete {@code initialize}
	 */
	public record Limits(@Nullable Long maxPostBodyBytes, @Nullable Duration keepAliveInterval,
			@Nullable Integer mailboxCapacity, @Nullable Integer maxPendingSseEvents,
			@Nullable Integer maxWebSocketPendingFrames, @Nullable Integer maxProvisionalSessions,
			@Nullable Duration shutdownTimeout, @Nullable Duration webSocketIdleTimeout,
			@Nullable Duration initializeTimeout) {
	}

	/**
	 * The settings of the SDK's own listener; a framework's own server ignores them.
	 * @param host the address the listener binds, or null (the default) for the loopback
	 * interface only; {@code 0.0.0.0} exposes the agent on every interface, an explicit opt-in
	 * (the endpoint has no authentication of its own)
	 * @param port the port, {@value AcpAgentSettings#DEFAULT_LISTENER_PORT} by default; 0 for an
	 * ephemeral one, which {@link AcpHost#port()} reports once started
	 * @param maxConcurrentStreamsPerConnection the HTTP/2 streams one client connection may hold
	 * open; null for the SDK default
	 */
	public record Listener(@Nullable String host, int port, @Nullable Integer maxConcurrentStreamsPerConnection) {
	}

	/**
	 * Returns whether the agent is served over Streamable HTTP (and WebSocket) rather than on one
	 * transport. A framework uses it to choose between an {@link AcpListenerHost} or servlet and
	 * an {@link AcpAgentHost}.
	 * @return true for {@code http} and {@code websocket}, false for {@code stdio}
	 */
	public boolean servesHttp() {
		return transport != AcpTransportType.STDIO;
	}

	/**
	 * Returns the SDK transport options for the endpoint's limits; an unset limit keeps the SDK
	 * default. {@link AcpListeners} calls it; a framework that creates the endpoint itself (as
	 * Quarkus does for its servlet and WebSocket route) calls it too. Needs
	 * {@code acp-streamable-http-jetty} on the classpath.
	 * @param listener true for the SDK's own listener, which also takes the listener's stream
	 * limit; false inside a framework's server
	 * @return the options
	 * @throws IllegalArgumentException if a limit is out of the range the options accept
	 */
	public StreamableHttpAcpAgentTransportOptions toOptions(boolean listener) {
		return toOptionsBuilder(listener).build();
	}

	/** The builder {@link #toOptions(boolean)} builds, for {@link AcpListeners} to add threads to. */
	StreamableHttpAcpAgentTransportOptions.Builder toOptionsBuilder(boolean listener) {
		Limits limits = http.limits();
		StreamableHttpAcpAgentTransportOptions.Builder options = StreamableHttpAcpAgentTransportOptions.builder();
		ifSet(limits.maxPostBodyBytes(), options::maxPostBodyBytes);
		ifSet(limits.keepAliveInterval(), options::keepAliveInterval);
		ifSet(limits.mailboxCapacity(), options::mailboxCapacity);
		ifSet(limits.maxPendingSseEvents(), options::maxPendingSseEvents);
		ifSet(limits.maxWebSocketPendingFrames(), options::maxWebSocketPendingFrames);
		ifSet(limits.maxProvisionalSessions(), options::maxProvisionalSessions);
		ifSet(limits.shutdownTimeout(), options::shutdownTimeout);
		ifSet(limits.webSocketIdleTimeout(), options::webSocketIdleTimeout);
		ifSet(limits.initializeTimeout(), options::initializeTimeout);
		options.allowedOrigins(http.allowedOrigins());
		if (listener) {
			ifSet(http.listener().maxConcurrentStreamsPerConnection(), options::maxConcurrentStreamsPerConnection);
			options.host(http.listener().host());
		}
		return options;
	}

	private static <T> void ifSet(@Nullable T value, Consumer<T> setter) {
		if (value != null) {
			setter.accept(value);
		}
	}

	/**
	 * Returns a builder holding every default: served, stdio, path
	 * {@value #DEFAULT_PATH}, listener port {@value #DEFAULT_LISTENER_PORT}, and the SDK's
	 * defaults for the rest.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Reads the settings under a prefix of key/value configuration, for a framework without typed
	 * binding. Keys are in kebab case, such as {@code acp.agent.request-timeout} and
	 * {@code acp.agent.transport.http.listener.port} for prefix {@code acp.agent}; an empty prefix
	 * reads the bare keys. A blank value counts as unset. Durations are ISO-8601
	 * ({@code PT30S}) or a whole amount with a unit {@code ms}, {@code s}, {@code m}, {@code h} or
	 * {@code d} ({@code 30s}, {@code 500ms}); a bare number is milliseconds. Sizes are bytes or an
	 * amount with {@code KB}, {@code MB} or {@code GB} (powers of 1024). Booleans are
	 * {@code true} or {@code false} in any case.
	 * @param source the configuration
	 * @param prefix the prefix of the agent's keys, such as {@code acp.agent}
	 * @return the settings
	 * @throws IllegalArgumentException if a value does not parse; the message names the full key
	 * and the value
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
			.webSocketIdleTimeout(read.duration("transport.http.web-socket-idle-timeout"))
			.initializeTimeout(read.duration("transport.http.initialize-timeout"))
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
		builder.listenerHost(read.string("transport.http.listener.host"));
		builder.allowedOrigins(read.list("transport.http.allowed-origins"));
		Integer port = read.integer("transport.http.listener.port");
		if (port != null) {
			builder.listenerPort(port);
		}
		return builder.build();
	}

	/**
	 * Builds {@link AcpAgentSettings}, flat: the endpoint's path, limits and listener settings
	 * are set here directly, and {@link #build()} groups them. Every setting has a default, so a
	 * framework sets only what the user configured. Not safe for use from several threads.
	 */
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

		private @Nullable Duration webSocketIdleTimeout;

		private @Nullable Duration initializeTimeout;

		private int listenerPort = DEFAULT_LISTENER_PORT;

		private @Nullable String listenerHost;

		private List<String> allowedOrigins = List.of();

		private @Nullable Integer maxConcurrentStreamsPerConnection;

		private Builder() {
		}

		/**
		 * Sets whether the agent is served. Default true; false serves no agent even when the
		 * application has an {@code @AcpAgent} bean.
		 * @param enabled whether the agent is served
		 * @return this builder
		 */
		public Builder enabled(boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		/**
		 * Sets how long a request the agent sends to the client (a permission prompt, a file
		 * read) waits for its answer.
		 * @param requestTimeout the timeout, or null for the SDK default (60 seconds)
		 * @return this builder
		 */
		public Builder requestTimeout(@Nullable Duration requestTimeout) {
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets how long a {@code @Prompt} method has to return after {@code session/cancel}
		 * before the SDK answers the turn {@code cancelled} itself and interrupts the method.
		 * @param cancelGracePeriod the grace period, zero for none, or null for the SDK default
		 * (60 seconds)
		 * @return this builder
		 */
		public Builder cancelGracePeriod(@Nullable Duration cancelGracePeriod) {
			this.cancelGracePeriod = cancelGracePeriod;
			return this;
		}

		/**
		 * Sets the longest a prompt turn may run before the SDK answers it with error
		 * {@code -32800} (request cancelled).
		 * @param maxPromptDuration the duration, zero for none, or null for the SDK default
		 * (none)
		 * @return this builder
		 */
		public Builder maxPromptDuration(@Nullable Duration maxPromptDuration) {
			this.maxPromptDuration = maxPromptDuration;
			return this;
		}

		/**
		 * Sets whether the application stops when a single transport (stdio) ends by itself,
		 * that is, when the client closed the agent's input. Default true. The framework acts on
		 * it through the action it gives {@link AcpAgentHost}.
		 * @param shutdownOnTransportEnd whether to stop at transport end
		 * @return this builder
		 */
		public Builder shutdownOnTransportEnd(boolean shutdownOnTransportEnd) {
			this.shutdownOnTransportEnd = shutdownOnTransportEnd;
			return this;
		}

		/**
		 * Sets the transport. Default {@link AcpTransportType#STDIO};
		 * {@link AcpTransportType#WEBSOCKET} means the same as {@link AcpTransportType#HTTP}.
		 * @param transport the transport
		 * @return this builder
		 */
		public Builder transport(AcpTransportType transport) {
			this.transport = Objects.requireNonNull(transport, "transport");
			return this;
		}

		/**
		 * Sets the HTTP endpoint's path, for the servlet and the listener alike. Default
		 * {@value AcpAgentSettings#DEFAULT_PATH}.
		 * @param path the path
		 * @return this builder
		 */
		public Builder path(String path) {
			this.path = Objects.requireNonNull(path, "path");
			return this;
		}

		/**
		 * Sets the largest message the endpoint accepts from a client: a larger POST body is
		 * answered 413, and a larger WebSocket text message closes the connection.
		 * @param maxPostBodyBytes the size in bytes, or null for the SDK default (16 MB)
		 * @return this builder
		 */
		public Builder maxPostBodyBytes(@Nullable Long maxPostBodyBytes) {
			this.maxPostBodyBytes = maxPostBodyBytes;
			return this;
		}

		/**
		 * Sets the interval between keep-alive comments on open SSE streams, which stop proxies
		 * from cutting idle streams.
		 * @param keepAliveInterval the interval, zero for none, or null for the SDK default (15
		 * seconds)
		 * @return this builder
		 */
		public Builder keepAliveInterval(@Nullable Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval;
			return this;
		}

		/**
		 * Sets how many unsent events one SSE stream keeps while no client is reading it, to
		 * send when the client reconnects; one more closes the connection.
		 * @param mailboxCapacity the capacity, or null for the SDK default (1024)
		 * @return this builder
		 */
		public Builder mailboxCapacity(@Nullable Integer mailboxCapacity) {
			this.mailboxCapacity = mailboxCapacity;
			return this;
		}

		/**
		 * Sets how many events may be queued for a client reading an SSE stream; one more
		 * detaches that client, keeping the events for its next GET.
		 * @param maxPendingSseEvents the limit, or null for the SDK default (1024)
		 * @return this builder
		 */
		public Builder maxPendingSseEvents(@Nullable Integer maxPendingSseEvents) {
			this.maxPendingSseEvents = maxPendingSseEvents;
			return this;
		}

		/**
		 * Sets how many frames may be queued for one WebSocket connection; one more closes the
		 * connection.
		 * @param maxWebSocketPendingFrames the limit, or null for the SDK default (1024)
		 * @return this builder
		 */
		public Builder maxWebSocketPendingFrames(@Nullable Integer maxWebSocketPendingFrames) {
			this.maxWebSocketPendingFrames = maxWebSocketPendingFrames;
			return this;
		}

		/**
		 * Sets how many session streams a connection may open before the agent knows the
		 * session, as a client does before {@code session/load}; a further one is refused.
		 * @param maxProvisionalSessions the limit, or null for the SDK default (64)
		 * @return this builder
		 */
		public Builder maxProvisionalSessions(@Nullable Integer maxProvisionalSessions) {
			this.maxProvisionalSessions = maxProvisionalSessions;
			return this;
		}

		/**
		 * Sets how long closing the endpoint waits for its connections' agents to close before
		 * it closes the rest at once. Closing never waits for a client.
		 * @param shutdownTimeout the timeout, or null for the SDK default (5 seconds)
		 * @return this builder
		 */
		public Builder shutdownTimeout(@Nullable Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
			return this;
		}

		/**
		 * Sets how long a WebSocket connection may pass no frame in either direction before the
		 * endpoint closes it (1001), on every host.
		 * @param webSocketIdleTimeout the timeout, or null for the SDK default (30 minutes)
		 * @return this builder
		 */
		public Builder webSocketIdleTimeout(@Nullable Duration webSocketIdleTimeout) {
			this.webSocketIdleTimeout = webSocketIdleTimeout;
			return this;
		}

		/**
		 * Sets how long a connection has to complete {@code initialize}: a WebSocket that has not
		 * sent it by then is closed (1008), and a POST {@code initialize} the agent has not
		 * answered by then is answered 500.
		 * @param initializeTimeout the timeout, or null for the SDK default (30 seconds)
		 * @return this builder
		 */
		public Builder initializeTimeout(@Nullable Duration initializeTimeout) {
			this.initializeTimeout = initializeTimeout;
			return this;
		}

		/**
		 * Sets the SDK listener's port; a framework's own server ignores it. Default
		 * {@value AcpAgentSettings#DEFAULT_LISTENER_PORT}.
		 * @param listenerPort the port; 0 for an ephemeral one
		 * @return this builder
		 */
		public Builder listenerPort(int listenerPort) {
			this.listenerPort = listenerPort;
			return this;
		}

		/**
		 * Sets the address the SDK listener binds; a framework's own server ignores it. Default
		 * (null) the loopback interface only; {@code 0.0.0.0} exposes the agent on every
		 * interface.
		 * @param listenerHost a host name or address, or null for loopback only
		 * @return this builder
		 */
		public Builder listenerHost(@Nullable String listenerHost) {
			this.listenerHost = listenerHost;
			return this;
		}

		/**
		 * Sets the browser origins the endpoint accepts besides the loopback ones; default none.
		 * @param allowedOrigins origins such as {@code https://app.example.com}, or {@code *}
		 * @return this builder
		 */
		public Builder allowedOrigins(List<String> allowedOrigins) {
			this.allowedOrigins = List.copyOf(allowedOrigins);
			return this;
		}

		/**
		 * Sets how many HTTP/2 streams one client connection to the SDK listener may hold open;
		 * a framework's own server ignores it.
		 * @param maxConcurrentStreamsPerConnection the limit, or null for the SDK default (1024)
		 * @return this builder
		 */
		public Builder maxConcurrentStreamsPerConnection(@Nullable Integer maxConcurrentStreamsPerConnection) {
			this.maxConcurrentStreamsPerConnection = maxConcurrentStreamsPerConnection;
			return this;
		}

		/**
		 * Builds the settings from the values set so far. The builder can be used again.
		 * @return the settings
		 */
		public AcpAgentSettings build() {
			Limits limits = new Limits(maxPostBodyBytes, keepAliveInterval, mailboxCapacity, maxPendingSseEvents,
					maxWebSocketPendingFrames, maxProvisionalSessions, shutdownTimeout, webSocketIdleTimeout,
					initializeTimeout);
			return new AcpAgentSettings(enabled, requestTimeout, cancelGracePeriod, maxPromptDuration,
					shutdownOnTransportEnd, transport,
					new Http(path, limits, new Listener(listenerHost, listenerPort, maxConcurrentStreamsPerConnection),
							allowedOrigins));
		}

	}

}
