/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.time.Duration;

import com.agentclientprotocol.sdk.integration.AcpAgentSettings;
import com.agentclientprotocol.sdk.integration.AcpTransportType;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.convert.format.ReadableBytes;
import org.jspecify.annotations.Nullable;

/**
 * The {@code acp.agent.*} configuration properties: how the application's {@code @AcpAgent} bean is
 * served. They choose the transport (stdio by default, or the SDK's Streamable HTTP listener), and
 * set the agent's timeouts and the listener's port, path and limits. {@link AcpAgentRuntime} reads
 * them when the application context starts. Every property has a default, so an application that
 * declares an {@code @AcpAgent} bean and nothing else serves it over stdio.
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
 *
 * <p>Durations take Micronaut's forms, such as {@code 30s} or {@code 500ms}; a size is a number of
 * bytes or an amount such as {@code 4MB}. A value the SDK refuses, such as a negative duration,
 * binds, then fails the startup when the agent is built. The properties map onto
 * {@link AcpAgentSettings}, the framework-neutral settings the Spring Boot and Quarkus integrations
 * fill from their own keys, so each one means the same there; the timeouts end up on the annotated
 * agent's builder, {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport.Builder}. The
 * module's README walks through an agent and its settings.
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
	 * Returns whether the application's {@code @AcpAgent} bean is served
	 * ({@code acp.agent.enabled}). Default {@code true}. With {@code false} there is no
	 * {@link AcpAgentRuntime} bean, so no agent is started. Maps to
	 * {@link AcpAgentSettings#enabled()}.
	 * @return whether the agent is served
	 */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Sets whether to serve the application's @AcpAgent bean; default true.
	 * @param enabled whether the agent is served
	 */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * Returns how long a request the agent sends to the client, such as a permission request or a
	 * file read, waits for its answer ({@code acp.agent.request-timeout}). Default 60 seconds, the
	 * SDK's default. Maps to {@code AcpAgentSupport.Builder.requestTimeout}.
	 * @return the request timeout
	 */
	public Duration getRequestTimeout() {
		return requestTimeout;
	}

	/**
	 * Sets how long a request the agent sends to the client waits for its answer; default 60
	 * seconds.
	 * @param requestTimeout the request timeout
	 */
	public void setRequestTimeout(Duration requestTimeout) {
		this.requestTimeout = requestTimeout;
	}

	/**
	 * Returns how long a {@code @Prompt} method has to return after the client sends
	 * {@code session/cancel} ({@code acp.agent.cancel-grace-period}). When it passes, the agent
	 * answers the prompt with stop reason {@code cancelled} itself and interrupts the method's
	 * thread. Default 60 seconds, the SDK's default; zero turns the limit off. Maps to
	 * {@code AcpAgentSupport.Builder.cancelGracePeriod}.
	 * @return the cancel grace period
	 */
	public Duration getCancelGracePeriod() {
		return cancelGracePeriod;
	}

	/**
	 * Sets how long a @Prompt method has to return after session/cancel; default 60 seconds, zero
	 * for no limit.
	 * @param cancelGracePeriod the cancel grace period; zero for none, not negative
	 */
	public void setCancelGracePeriod(Duration cancelGracePeriod) {
		this.cancelGracePeriod = cancelGracePeriod;
	}

	/**
	 * Returns how long a prompt turn may run before the agent answers it with error {@code -32800}
	 * (request cancelled) ({@code acp.agent.max-prompt-duration}). Default zero, the SDK's default:
	 * no limit. Maps to {@code AcpAgentSupport.Builder.maxPromptDuration}.
	 * @return the maximum prompt duration
	 */
	public Duration getMaxPromptDuration() {
		return maxPromptDuration;
	}

	/**
	 * Sets how long a prompt turn may run before the agent answers it with error -32800; default
	 * zero, no limit.
	 * @param maxPromptDuration the maximum prompt duration; zero for none, not negative
	 */
	public void setMaxPromptDuration(Duration maxPromptDuration) {
		this.maxPromptDuration = maxPromptDuration;
	}

	/**
	 * Returns whether the application context closes when the agent's transport ends on its own
	 * ({@code acp.agent.shutdown-on-transport-end}): for stdio, when the client closes the agent's
	 * standard input and every answer has been written. Default {@code true}, so the process exits
	 * with its client. It applies to a single transport (stdio, or an {@code AcpAgentTransport}
	 * bean of the application's own), not to the HTTP listener, which serves many clients. Maps to
	 * {@link AcpAgentSettings#shutdownOnTransportEnd()}.
	 * @return whether the context closes at transport end
	 */
	public boolean isShutdownOnTransportEnd() {
		return shutdownOnTransportEnd;
	}

	/**
	 * Sets whether to close the application context when the stdio transport ends on its own;
	 * default true.
	 * @param shutdownOnTransportEnd whether to close the context at transport end
	 */
	public void setShutdownOnTransportEnd(boolean shutdownOnTransportEnd) {
		this.shutdownOnTransportEnd = shutdownOnTransportEnd;
	}

	/**
	 * Returns the transport properties, {@code acp.agent.transport.*}.
	 * @return the transport properties
	 */
	public Transport getTransport() {
		return transport;
	}

	/**
	 * Replaces the transport properties.
	 * @param transport the transport properties
	 */
	public void setTransport(Transport transport) {
		this.transport = transport;
	}

	/**
	 * Returns these properties as the SDK's framework-neutral {@link AcpAgentSettings}, which
	 * {@link AcpAgentRuntime} passes to {@code acp-integration}. A listener limit left unset stays
	 * unset ({@code null}), so the SDK applies its default. Each call builds new settings from the
	 * current values.
	 * @return the settings
	 */
	public AcpAgentSettings toSettings() {
		Transport.Http http = transport.getHttp();
		return AcpAgentSettings.builder()
			.enabled(enabled)
			.requestTimeout(requestTimeout)
			.cancelGracePeriod(cancelGracePeriod)
			.maxPromptDuration(maxPromptDuration)
			.shutdownOnTransportEnd(shutdownOnTransportEnd)
			.transport(transport.getType())
			.path(http.getPath())
			.maxPostBodyBytes(http.getMaxPostBodySize())
			.keepAliveInterval(http.getKeepAliveInterval())
			.mailboxCapacity(http.getMailboxCapacity())
			.maxPendingSseEvents(http.getMaxPendingSseEvents())
			.maxWebSocketPendingFrames(http.getMaxWebSocketPendingFrames())
			.maxProvisionalSessions(http.getMaxProvisionalSessions())
			.shutdownTimeout(http.getShutdownTimeout())
			.listenerPort(http.getPort())
			.maxConcurrentStreamsPerConnection(http.getMaxConcurrentStreamsPerConnection())
			.build();
	}

	/**
	 * The {@code acp.agent.transport.*} properties: which transport serves the agent, and the
	 * Streamable HTTP listener's settings ({@link Http}).
	 */
	@ConfigurationProperties("transport")
	public static class Transport {

		private AcpTransportType type = AcpTransportType.STDIO;

		private Http http = new Http();

		/**
		 * Returns the transport ({@code acp.agent.transport.type}). Maps to
		 * {@link AcpAgentSettings#transport()}.
		 * <ul>
		 * <li>{@code stdio} (the default): the agent reads standard input and writes standard
		 * output. An application bean of type {@code AcpAgentTransport} is used instead of
		 * stdio.</li>
		 * <li>{@code http}: the SDK's Streamable HTTP listener, which also accepts WebSocket
		 * upgrades on its path. It needs {@code acp-streamable-http-jetty} on the classpath;
		 * without it the startup fails naming the module.</li>
		 * <li>{@code websocket}: the same listener as {@code http}.</li>
		 * </ul>
		 * The value is read in lower or upper case.
		 * @return the transport type
		 */
		public AcpTransportType getType() {
			return type;
		}

		/**
		 * Sets the transport: stdio (the default), http or websocket.
		 * @param type the transport type
		 */
		public void setType(AcpTransportType type) {
			this.type = type;
		}

		/**
		 * Returns the Streamable HTTP listener's properties, {@code acp.agent.transport.http.*}.
		 * @return the HTTP properties
		 */
		public Http getHttp() {
			return http;
		}

		/**
		 * Replaces the Streamable HTTP listener's properties.
		 * @param http the HTTP properties
		 */
		public void setHttp(Http http) {
			this.http = http;
		}

		/**
		 * The {@code acp.agent.transport.http.*} properties: the Streamable HTTP listener that
		 * serves the agent when {@code acp.agent.transport.type} is {@code http} or
		 * {@code websocket}. It is the SDK's own Jetty listener on its own port, beside Micronaut's
		 * HTTP server if the application has one, not inside it. It serves HTTP/1.1, cleartext
		 * HTTP/2 and WebSocket upgrades on {@code path}, with one agent per connection.
		 *
		 * <p>The limits bound every buffer the listener keeps for a client. Each one left unset
		 * keeps the SDK's default, which its getter names; together they map to
		 * {@code StreamableHttpAcpAgentTransportOptions}, the options of
		 * {@code acp-streamable-http-jetty}'s listener.
		 */
		@ConfigurationProperties("http")
		public static class Http {

			private int port = 8080;

			private String path = AcpAgentSettings.DEFAULT_PATH;

			private @Nullable Long maxPostBodySize;

			private @Nullable Duration keepAliveInterval;

			private @Nullable Integer mailboxCapacity;

			private @Nullable Integer maxPendingSseEvents;

			private @Nullable Integer maxWebSocketPendingFrames;

			private @Nullable Integer maxProvisionalSessions;

			private @Nullable Integer maxConcurrentStreamsPerConnection;

			private @Nullable Duration shutdownTimeout;

			/**
			 * Returns the listener's port ({@code acp.agent.transport.http.port}). Default 8080;
			 * {@code 0} picks a free port, which {@link AcpAgentRuntime#port()} then returns. Maps
			 * to {@link AcpAgentSettings.Listener#port()}.
			 * @return the port
			 */
			public int getPort() {
				return port;
			}

			/**
			 * Sets the listener's port; default 8080, 0 for an ephemeral port.
			 * @param port the listener's port; 0 for an ephemeral port
			 */
			public void setPort(int port) {
				this.port = port;
			}

			/**
			 * Returns the endpoint's path ({@code acp.agent.transport.http.path}). Default
			 * {@value AcpAgentSettings#DEFAULT_PATH}. Maps to {@link AcpAgentSettings.Http#path()}.
			 * @return the path
			 */
			public String getPath() {
				return path;
			}

			/**
			 * Sets the endpoint path; default /acp.
			 * @param path the endpoint path
			 */
			public void setPath(String path) {
				this.path = path;
			}

			/**
			 * Returns the largest message accepted from a client, in bytes
			 * ({@code acp.agent.transport.http.max-post-body-size}). A larger POST body is answered
			 * 413, and a larger WebSocket text message closes the connection. Default: unset, which
			 * keeps the SDK's default of 16 MB. Maps to the transport option
			 * {@code maxPostBodyBytes}.
			 * @return the size, or {@code null} for the SDK's default
			 */
			public @Nullable Long getMaxPostBodySize() {
				return maxPostBodySize;
			}

			/**
			 * Sets the largest inbound message, a POST body or WebSocket text message, such as 4MB;
			 * unset keeps the SDK default, 16MB.
			 * @param maxPostBodySize the largest inbound message, in bytes or as an amount such as
			 * {@code 4MB}; positive
			 */
			public void setMaxPostBodySize(@ReadableBytes @Nullable Long maxPostBodySize) {
				this.maxPostBodySize = maxPostBodySize;
			}

			/**
			 * Returns the interval between keep-alive comments on open SSE streams, which stop
			 * proxies from cutting idle streams
			 * ({@code acp.agent.transport.http.keep-alive-interval}). Zero turns them off. Default:
			 * unset, which keeps the SDK's default of 15 seconds. Maps to the transport option
			 * {@code keepAliveInterval}.
			 * @return the interval, or {@code null} for the SDK's default
			 */
			public @Nullable Duration getKeepAliveInterval() {
				return keepAliveInterval;
			}

			/**
			 * Sets the interval between SSE keep-alive comments; zero disables them, unset keeps
			 * the SDK default, 15 seconds.
			 * @param keepAliveInterval the interval; zero for none, not negative
			 */
			public void setKeepAliveInterval(@Nullable Duration keepAliveInterval) {
				this.keepAliveInterval = keepAliveInterval;
			}

			/**
			 * Returns how many events one SSE stream keeps unsent while no client is reading it, to
			 * send when the client reconnects ({@code acp.agent.transport.http.mailbox-capacity}).
			 * One more closes the connection. Default: unset, which keeps the SDK's default of
			 * 1024. Maps to the transport option {@code mailboxCapacity}.
			 * @return the capacity, or {@code null} for the SDK's default
			 */
			public @Nullable Integer getMailboxCapacity() {
				return mailboxCapacity;
			}

			/**
			 * Sets the events retained per outbound stream while no subscriber is attached; unset
			 * keeps the SDK default, 1024.
			 * @param mailboxCapacity the event count; positive
			 */
			public void setMailboxCapacity(@Nullable Integer mailboxCapacity) {
				this.mailboxCapacity = mailboxCapacity;
			}

			/**
			 * Returns how many events are queued for a client that is reading an SSE stream
			 * ({@code acp.agent.transport.http.max-pending-sse-events}). One more detaches that
			 * client and keeps the events for its next GET. Default: unset, which keeps the SDK's
			 * default of 1024. Maps to the transport option {@code maxPendingSseEvents}.
			 * @return the limit, or {@code null} for the SDK's default
			 */
			public @Nullable Integer getMaxPendingSseEvents() {
				return maxPendingSseEvents;
			}

			/**
			 * Sets the events queued for one attached SSE subscriber before it is detached; unset
			 * keeps the SDK default, 1024.
			 * @param maxPendingSseEvents the event count; positive
			 */
			public void setMaxPendingSseEvents(@Nullable Integer maxPendingSseEvents) {
				this.maxPendingSseEvents = maxPendingSseEvents;
			}

			/**
			 * Returns how many frames are queued for one WebSocket connection
			 * ({@code acp.agent.transport.http.max-web-socket-pending-frames}). One more closes the
			 * connection. Default: unset, which keeps the SDK's default of 1024. Maps to the
			 * transport option {@code maxWebSocketPendingFrames}.
			 * @return the limit, or {@code null} for the SDK's default
			 */
			public @Nullable Integer getMaxWebSocketPendingFrames() {
				return maxWebSocketPendingFrames;
			}

			/**
			 * Sets the frames queued for one WebSocket connection before it is closed; unset keeps
			 * the SDK default, 1024.
			 * @param maxWebSocketPendingFrames the frame count; positive
			 */
			public void setMaxWebSocketPendingFrames(@Nullable Integer maxWebSocketPendingFrames) {
				this.maxWebSocketPendingFrames = maxWebSocketPendingFrames;
			}

			/**
			 * Returns how many session streams a connection may open before the agent knows the
			 * session, as a client does before {@code session/load}
			 * ({@code acp.agent.transport.http.max-provisional-sessions}). A further one is
			 * refused. Default: unset, which keeps the SDK's default of 64. Maps to the transport
			 * option {@code maxProvisionalSessions}.
			 * @return the limit, or {@code null} for the SDK's default
			 */
			public @Nullable Integer getMaxProvisionalSessions() {
				return maxProvisionalSessions;
			}

			/**
			 * Sets the session streams a connection may open before the session is known; unset
			 * keeps the SDK default, 64.
			 * @param maxProvisionalSessions the stream count; positive
			 */
			public void setMaxProvisionalSessions(@Nullable Integer maxProvisionalSessions) {
				this.maxProvisionalSessions = maxProvisionalSessions;
			}

			/**
			 * Returns how many HTTP/2 streams one client connection may hold open at once, each
			 * open SSE stream holding one
			 * ({@code acp.agent.transport.http.max-concurrent-streams-per-connection}). Default:
			 * unset, which keeps the SDK's default of 1024. Maps to the transport option
			 * {@code maxConcurrentStreamsPerConnection}.
			 * @return the limit, or {@code null} for the SDK's default
			 */
			public @Nullable Integer getMaxConcurrentStreamsPerConnection() {
				return maxConcurrentStreamsPerConnection;
			}

			/**
			 * Sets the HTTP/2 streams one client connection may hold open; unset keeps the SDK
			 * default, 1024.
			 * @param maxConcurrentStreamsPerConnection the stream count; positive
			 */
			public void setMaxConcurrentStreamsPerConnection(@Nullable Integer maxConcurrentStreamsPerConnection) {
				this.maxConcurrentStreamsPerConnection = maxConcurrentStreamsPerConnection;
			}

			/**
			 * Returns how long closing the listener waits for its connections' agents to close
			 * before it closes the rest at once
			 * ({@code acp.agent.transport.http.shutdown-timeout}). Default: unset, which keeps the
			 * SDK's default of 5 seconds. Closing the application context waits for the agent at
			 * most this long plus 5 seconds. Maps to the transport option {@code shutdownTimeout}.
			 * @return the timeout, or {@code null} for the SDK's default
			 */
			public @Nullable Duration getShutdownTimeout() {
				return shutdownTimeout;
			}

			/**
			 * Sets how long closing the listener waits for its connections to close gracefully;
			 * unset keeps the SDK default, 5 seconds.
			 * @param shutdownTimeout the timeout; positive
			 */
			public void setShutdownTimeout(@Nullable Duration shutdownTimeout) {
				this.shutdownTimeout = shutdownTimeout;
			}

		}

	}

}
