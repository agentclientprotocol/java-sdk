/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.time.Duration;

import com.agentclientprotocol.sdk.integration.AcpAgentSettings;
import com.agentclientprotocol.sdk.integration.AcpTransportType;
import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * The agent's settings, bound from {@code spring.acp.agent.*} onto {@link AcpAgentSettings}. A
 * value left unset keeps the SDK's default.
 */
@ConfigurationProperties(prefix = "spring.acp.agent")
public class AcpAgentProperties {

	/**
	 * Whether to serve the application's @AcpAgent bean.
	 */
	private boolean enabled = true;

	/**
	 * How long a request the agent sends to the client waits for its answer. Unset keeps the
	 * SDK default.
	 */
	private @Nullable Duration requestTimeout;

	/**
	 * How long a @Prompt method has to return after session/cancel before the agent answers
	 * the prompt "cancelled" itself. Unset keeps the SDK default; zero for none.
	 */
	private @Nullable Duration cancelGracePeriod;

	/**
	 * How long a prompt may run before the agent answers it with error -32800. Unset keeps the
	 * SDK default (no limit).
	 */
	private @Nullable Duration maxPromptDuration;

	/**
	 * Close the application context when the agent's transport ends on its own, for stdio
	 * when the client closes the agent's input. Lets a keep-alive application exit.
	 */
	private boolean shutdownOnTransportEnd = true;

	/**
	 * The executor the agent's handler methods run on: the name of an Executor bean, or
	 * "none" for the SDK's own pool. Unset: the context's applicationTaskExecutor when
	 * spring.threads.virtual.enabled=true (virtual threads), else the SDK's own pool.
	 */
	private @Nullable String handlerExecutor;

	private AgentTransportProperties transport = new AgentTransportProperties();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public @Nullable Duration getRequestTimeout() {
		return requestTimeout;
	}

	public void setRequestTimeout(@Nullable Duration requestTimeout) {
		this.requestTimeout = requestTimeout;
	}

	public @Nullable Duration getCancelGracePeriod() {
		return cancelGracePeriod;
	}

	public void setCancelGracePeriod(@Nullable Duration cancelGracePeriod) {
		this.cancelGracePeriod = cancelGracePeriod;
	}

	public @Nullable Duration getMaxPromptDuration() {
		return maxPromptDuration;
	}

	public void setMaxPromptDuration(@Nullable Duration maxPromptDuration) {
		this.maxPromptDuration = maxPromptDuration;
	}

	public boolean isShutdownOnTransportEnd() {
		return shutdownOnTransportEnd;
	}

	public void setShutdownOnTransportEnd(boolean shutdownOnTransportEnd) {
		this.shutdownOnTransportEnd = shutdownOnTransportEnd;
	}

	public @Nullable String getHandlerExecutor() {
		return handlerExecutor;
	}

	public void setHandlerExecutor(@Nullable String handlerExecutor) {
		this.handlerExecutor = handlerExecutor;
	}

	public AgentTransportProperties getTransport() {
		return transport;
	}

	public void setTransport(AgentTransportProperties transport) {
		this.transport = transport;
	}

	/**
	 * These properties as the SDK's framework-neutral settings.
	 * @return the settings
	 */
	public AcpAgentSettings toSettings() {
		AgentHttpProperties http = transport.getHttp();
		DataSize maxPostBodySize = http.getMaxPostBodySize();
		AcpTransportType type = transport.getType();
		return AcpAgentSettings.builder()
			.enabled(enabled)
			.requestTimeout(requestTimeout)
			.cancelGracePeriod(cancelGracePeriod)
			.maxPromptDuration(maxPromptDuration)
			.shutdownOnTransportEnd(shutdownOnTransportEnd)
			.transport((type != null) ? type : AcpTransportType.STDIO)
			.path(http.getPath())
			.maxPostBodyBytes((maxPostBodySize != null) ? maxPostBodySize.toBytes() : null)
			.keepAliveInterval(http.getKeepAliveInterval())
			.mailboxCapacity(http.getMailboxCapacity())
			.maxPendingSseEvents(http.getMaxPendingSseEvents())
			.maxWebSocketPendingFrames(http.getMaxWebSocketPendingFrames())
			.maxProvisionalSessions(http.getMaxProvisionalSessions())
			.shutdownTimeout(http.getShutdownTimeout())
			.listenerPort(http.getListener().getPort())
			.maxConcurrentStreamsPerConnection(http.getListener().getMaxConcurrentStreamsPerConnection())
			.build();
	}

	public static class AgentTransportProperties {

		/**
		 * The transport: stdio (the default), or http for Streamable HTTP, which also takes
		 * WebSocket upgrades on its path (websocket means the same as http).
		 */
		private @Nullable AcpTransportType type;

		private AgentHttpProperties http = new AgentHttpProperties();

		public @Nullable AcpTransportType getType() {
			return type;
		}

		public void setType(@Nullable AcpTransportType type) {
			this.type = type;
		}

		public AgentHttpProperties getHttp() {
			return http;
		}

		public void setHttp(AgentHttpProperties http) {
			this.http = http;
		}

	}

	/**
	 * Streamable HTTP agent transport ({@code type=http}). In a servlet web application
	 * the endpoint is mounted on the application's own server at {@code path}; otherwise
	 * the SDK listener serves it, with WebSocket upgrades on the same path, on
	 * {@code listener.port}. The limits left unset keep the SDK defaults.
	 */
	public static class AgentHttpProperties {

		/**
		 * Endpoint path.
		 */
		private String path = AcpAgentSettings.DEFAULT_PATH;

		/**
		 * Largest accepted inbound message (POST body or WebSocket text message).
		 */
		private @Nullable DataSize maxPostBodySize;

		/**
		 * Interval between SSE keep-alive comments; zero disables them.
		 */
		private @Nullable Duration keepAliveInterval;

		/**
		 * Events retained per outbound stream while no subscriber is attached.
		 */
		private @Nullable Integer mailboxCapacity;

		/**
		 * Events queued for one attached SSE subscriber before it is closed.
		 */
		private @Nullable Integer maxPendingSseEvents;

		/**
		 * Frames queued for one WebSocket connection before it is closed.
		 */
		private @Nullable Integer maxWebSocketPendingFrames;

		/**
		 * Session streams a connection may open before the session is known.
		 */
		private @Nullable Integer maxProvisionalSessions;

		/**
		 * How long closing the endpoint waits for its connections to close gracefully
		 * before closing the rest at once.
		 */
		private @Nullable Duration shutdownTimeout;

		private ListenerProperties listener = new ListenerProperties();

		public String getPath() {
			return path;
		}

		public void setPath(String path) {
			this.path = path;
		}

		public @Nullable DataSize getMaxPostBodySize() {
			return maxPostBodySize;
		}

		public void setMaxPostBodySize(@Nullable DataSize maxPostBodySize) {
			this.maxPostBodySize = maxPostBodySize;
		}

		public @Nullable Duration getKeepAliveInterval() {
			return keepAliveInterval;
		}

		public void setKeepAliveInterval(@Nullable Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval;
		}

		public @Nullable Integer getMailboxCapacity() {
			return mailboxCapacity;
		}

		public void setMailboxCapacity(@Nullable Integer mailboxCapacity) {
			this.mailboxCapacity = mailboxCapacity;
		}

		public @Nullable Integer getMaxPendingSseEvents() {
			return maxPendingSseEvents;
		}

		public void setMaxPendingSseEvents(@Nullable Integer maxPendingSseEvents) {
			this.maxPendingSseEvents = maxPendingSseEvents;
		}

		public @Nullable Integer getMaxWebSocketPendingFrames() {
			return maxWebSocketPendingFrames;
		}

		public void setMaxWebSocketPendingFrames(@Nullable Integer maxWebSocketPendingFrames) {
			this.maxWebSocketPendingFrames = maxWebSocketPendingFrames;
		}

		public @Nullable Integer getMaxProvisionalSessions() {
			return maxProvisionalSessions;
		}

		public void setMaxProvisionalSessions(@Nullable Integer maxProvisionalSessions) {
			this.maxProvisionalSessions = maxProvisionalSessions;
		}

		public @Nullable Duration getShutdownTimeout() {
			return shutdownTimeout;
		}

		public void setShutdownTimeout(@Nullable Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
		}

		public ListenerProperties getListener() {
			return listener;
		}

		public void setListener(ListenerProperties listener) {
			this.listener = listener;
		}

	}

	/**
	 * The SDK's own listener, used outside a servlet web application. Ignored in a servlet
	 * web application, which serves the endpoint on its own server and port.
	 */
	public static class ListenerProperties {

		/**
		 * Port of the standalone listener; 0 for an ephemeral port.
		 */
		private int port = AcpAgentSettings.DEFAULT_LISTENER_PORT;

		/**
		 * HTTP/2 streams one client connection may hold open.
		 */
		private @Nullable Integer maxConcurrentStreamsPerConnection;

		public int getPort() {
			return port;
		}

		public void setPort(int port) {
			this.port = port;
		}

		public @Nullable Integer getMaxConcurrentStreamsPerConnection() {
			return maxConcurrentStreamsPerConnection;
		}

		public void setMaxConcurrentStreamsPerConnection(@Nullable Integer maxConcurrentStreamsPerConnection) {
			this.maxConcurrentStreamsPerConnection = maxConcurrentStreamsPerConnection;
		}

	}

}
