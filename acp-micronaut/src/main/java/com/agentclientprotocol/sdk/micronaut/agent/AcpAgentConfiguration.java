/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.time.Duration;

import com.agentclientprotocol.sdk.micronaut.TransportType;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.convert.format.ReadableBytes;
import org.jspecify.annotations.Nullable;

/**
 * The agent's settings, bound from {@code acp.agent.*}. Every setting has a default, so an
 * application that declares an {@code @AcpAgent} bean and nothing else serves it over stdio.
 *
 * <pre>
 * acp.agent.enabled                      true
 * acp.agent.request-timeout              60s     agent-to-client requests
 * acp.agent.cancel-grace-period          60s     after session/cancel, then answer "cancelled"
 * acp.agent.max-prompt-duration          0s      none; else answer -32800 after it
 * acp.agent.shutdown-on-transport-end    true    close the context when stdio input ends
 * acp.agent.transport.type               stdio   stdio | http | websocket
 * acp.agent.transport.http.port          8080    0 for an ephemeral port
 * acp.agent.transport.http.path          /acp
 * acp.agent.transport.http.max-post-body-size, keep-alive-interval, mailbox-capacity,
 *     max-pending-sse-events, max-web-socket-pending-frames, max-provisional-sessions,
 *     max-concurrent-streams-per-connection, shutdown-timeout   (unset: the SDK defaults)
 * </pre>
 */
@ConfigurationProperties(AcpAgentConfiguration.PREFIX)
public class AcpAgentConfiguration {

	/** The prefix of the agent's settings. */
	public static final String PREFIX = "acp.agent";

	private boolean enabled = true;

	private Duration requestTimeout = Duration.ofSeconds(60);

	private Duration cancelGracePeriod = Duration.ofSeconds(60);

	private Duration maxPromptDuration = Duration.ZERO;

	private boolean shutdownOnTransportEnd = true;

	private Transport transport = new Transport();

	/**
	 * Whether to serve the {@code @AcpAgent} bean. Default {@code true}.
	 * @return whether the agent is enabled
	 */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Sets whether to serve the {@code @AcpAgent} bean.
	 * @param enabled whether to serve the {@code @AcpAgent} bean
	 */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * How long a request the agent sends to the client may wait for its answer. Default 60
	 * seconds.
	 * @return the request timeout
	 */
	public Duration getRequestTimeout() {
		return requestTimeout;
	}

	/**
	 * Sets how long an agent-to-client request may wait for its answer.
	 * @param requestTimeout how long an agent-to-client request may wait for its answer
	 */
	public void setRequestTimeout(Duration requestTimeout) {
		this.requestTimeout = requestTimeout;
	}

	/**
	 * How long a {@code @Prompt} method has to return after {@code session/cancel} before the
	 * agent answers the prompt {@code cancelled} itself. Default 60 seconds; zero for none.
	 * @return the cancel grace period
	 */
	public Duration getCancelGracePeriod() {
		return cancelGracePeriod;
	}

	/**
	 * Sets the cancel grace period; zero for none.
	 * @param cancelGracePeriod the cancel grace period; zero for none
	 */
	public void setCancelGracePeriod(Duration cancelGracePeriod) {
		this.cancelGracePeriod = cancelGracePeriod;
	}

	/**
	 * How long a prompt may run before the agent answers it with error {@code -32800}.
	 * Default zero: no limit.
	 * @return the maximum prompt duration
	 */
	public Duration getMaxPromptDuration() {
		return maxPromptDuration;
	}

	/**
	 * Sets the maximum prompt duration; zero for none.
	 * @param maxPromptDuration the maximum prompt duration; zero for none
	 */
	public void setMaxPromptDuration(Duration maxPromptDuration) {
		this.maxPromptDuration = maxPromptDuration;
	}

	/**
	 * Whether to close the application context when the agent's transport ends on its own:
	 * for stdio, when the client closes the agent's standard input and every answer has been
	 * written. Default {@code true}, so the process exits with its client.
	 * @return whether the context closes at transport end
	 */
	public boolean isShutdownOnTransportEnd() {
		return shutdownOnTransportEnd;
	}

	/**
	 * Sets whether to close the context at transport end.
	 * @param shutdownOnTransportEnd whether to close the context at transport end
	 */
	public void setShutdownOnTransportEnd(boolean shutdownOnTransportEnd) {
		this.shutdownOnTransportEnd = shutdownOnTransportEnd;
	}

	/**
	 * The transport settings.
	 * @return the transport settings
	 */
	public Transport getTransport() {
		return transport;
	}

	/**
	 * Sets the transport settings.
	 * @param transport the transport settings
	 */
	public void setTransport(Transport transport) {
		this.transport = transport;
	}

	/** The agent transport, bound from {@code acp.agent.transport.*}. */
	@ConfigurationProperties("transport")
	public static class Transport {

		private TransportType type = TransportType.STDIO;

		private Http http = new Http();

		/**
		 * The transport: {@code stdio} (default), or {@code http} for the Streamable HTTP
		 * listener, which also accepts WebSocket upgrades ({@code websocket} means the same).
		 * An application bean of type {@code AcpAgentTransport} is used instead of stdio.
		 * @return the transport type
		 */
		public TransportType getType() {
			return type;
		}

		/**
		 * Sets the transport type.
		 * @param type the transport type
		 */
		public void setType(TransportType type) {
			this.type = type;
		}

		/**
		 * The Streamable HTTP listener settings.
		 * @return the HTTP settings
		 */
		public Http getHttp() {
			return http;
		}

		/**
		 * Sets the HTTP settings.
		 * @param http the HTTP settings
		 */
		public void setHttp(Http http) {
			this.http = http;
		}

		/**
		 * The Streamable HTTP listener ({@code type=http} or {@code websocket}), bound from
		 * {@code acp.agent.transport.http.*}. It is the SDK's own Jetty listener, on its own port,
		 * serving HTTP/1.1, cleartext HTTP/2 and WebSocket upgrades on {@code path}. A limit left
		 * unset keeps the SDK default.
		 */
		@ConfigurationProperties("http")
		public static class Http {

			private int port = 8080;

			private String path = "/acp";

			private @Nullable Long maxPostBodySize;

			private @Nullable Duration keepAliveInterval;

			private @Nullable Integer mailboxCapacity;

			private @Nullable Integer maxPendingSseEvents;

			private @Nullable Integer maxWebSocketPendingFrames;

			private @Nullable Integer maxProvisionalSessions;

			private @Nullable Integer maxConcurrentStreamsPerConnection;

			private @Nullable Duration shutdownTimeout;

			/**
			 * The listener's port. Default 8080; 0 for an ephemeral port.
			 * @return the port
			 */
			public int getPort() {
				return port;
			}

			/**
			 * Sets the listener's port; 0 for an ephemeral port.
			 * @param port the listener's port; 0 for an ephemeral port
			 */
			public void setPort(int port) {
				this.port = port;
			}

			/**
			 * The endpoint path. Default {@code /acp}.
			 * @return the path
			 */
			public String getPath() {
				return path;
			}

			/**
			 * Sets the endpoint path.
			 * @param path the endpoint path
			 */
			public void setPath(String path) {
				this.path = path;
			}

			/**
			 * The largest inbound message, a POST body or WebSocket text message, in bytes.
			 * @return the size, or null for the SDK default (16 MB)
			 */
			public @Nullable Long getMaxPostBodySize() {
				return maxPostBodySize;
			}

			/**
			 * Sets the largest inbound message, such as {@code 16MB}.
			 * @param maxPostBodySize the largest inbound message, such as {@code 16MB}
			 */
			public void setMaxPostBodySize(@ReadableBytes @Nullable Long maxPostBodySize) {
				this.maxPostBodySize = maxPostBodySize;
			}

			/**
			 * The interval between SSE keep-alive comments; zero disables them.
			 * @return the interval, or null for the SDK default
			 */
			public @Nullable Duration getKeepAliveInterval() {
				return keepAliveInterval;
			}

			/**
			 * Sets the interval between SSE keep-alive comments.
			 * @param keepAliveInterval the interval between SSE keep-alive comments
			 */
			public void setKeepAliveInterval(@Nullable Duration keepAliveInterval) {
				this.keepAliveInterval = keepAliveInterval;
			}

			/**
			 * The events retained per outbound stream while no subscriber is attached.
			 * @return the capacity, or null for the SDK default
			 */
			public @Nullable Integer getMailboxCapacity() {
				return mailboxCapacity;
			}

			/**
			 * Sets the events retained per unattached outbound stream.
			 * @param mailboxCapacity the events retained per unattached outbound stream
			 */
			public void setMailboxCapacity(@Nullable Integer mailboxCapacity) {
				this.mailboxCapacity = mailboxCapacity;
			}

			/**
			 * The events queued for one attached SSE subscriber before it is closed.
			 * @return the limit, or null for the SDK default
			 */
			public @Nullable Integer getMaxPendingSseEvents() {
				return maxPendingSseEvents;
			}

			/**
			 * Sets the events queued for one SSE subscriber.
			 * @param maxPendingSseEvents the events queued for one SSE subscriber
			 */
			public void setMaxPendingSseEvents(@Nullable Integer maxPendingSseEvents) {
				this.maxPendingSseEvents = maxPendingSseEvents;
			}

			/**
			 * The frames queued for one WebSocket connection before it is closed.
			 * @return the limit, or null for the SDK default
			 */
			public @Nullable Integer getMaxWebSocketPendingFrames() {
				return maxWebSocketPendingFrames;
			}

			/**
			 * Sets the frames queued for one WebSocket connection.
			 * @param maxWebSocketPendingFrames the frames queued for one WebSocket connection
			 */
			public void setMaxWebSocketPendingFrames(@Nullable Integer maxWebSocketPendingFrames) {
				this.maxWebSocketPendingFrames = maxWebSocketPendingFrames;
			}

			/**
			 * The session streams a connection may open before the session is known.
			 * @return the limit, or null for the SDK default
			 */
			public @Nullable Integer getMaxProvisionalSessions() {
				return maxProvisionalSessions;
			}

			/**
			 * Sets the provisional session streams per connection.
			 * @param maxProvisionalSessions the provisional session streams per connection
			 */
			public void setMaxProvisionalSessions(@Nullable Integer maxProvisionalSessions) {
				this.maxProvisionalSessions = maxProvisionalSessions;
			}

			/**
			 * The HTTP/2 streams one client connection may hold open.
			 * @return the limit, or null for the SDK default
			 */
			public @Nullable Integer getMaxConcurrentStreamsPerConnection() {
				return maxConcurrentStreamsPerConnection;
			}

			/**
			 * Sets the HTTP/2 streams per client connection.
			 * @param maxConcurrentStreamsPerConnection the HTTP/2 streams per client connection
			 */
			public void setMaxConcurrentStreamsPerConnection(@Nullable Integer maxConcurrentStreamsPerConnection) {
				this.maxConcurrentStreamsPerConnection = maxConcurrentStreamsPerConnection;
			}

			/**
			 * How long closing the listener waits for its connections to close gracefully before
			 * closing the rest at once.
			 * @return the timeout, or null for the SDK default
			 */
			public @Nullable Duration getShutdownTimeout() {
				return shutdownTimeout;
			}

			/**
			 * Sets how long closing waits for connections.
			 * @param shutdownTimeout how long closing waits for connections
			 */
			public void setShutdownTimeout(@Nullable Duration shutdownTimeout) {
				this.shutdownTimeout = shutdownTimeout;
			}

		}

	}

}
