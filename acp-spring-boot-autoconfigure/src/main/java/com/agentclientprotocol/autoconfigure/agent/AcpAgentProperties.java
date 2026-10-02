package com.agentclientprotocol.autoconfigure.agent;

import java.time.Duration;

import com.agentclientprotocol.autoconfigure.TransportType;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "spring.acp.agent")
public class AcpAgentProperties {

	private boolean enabled = true;

	private Duration requestTimeout = Duration.ofSeconds(60);

	private AgentTransportProperties transport = new AgentTransportProperties();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Duration getRequestTimeout() {
		return requestTimeout;
	}

	public void setRequestTimeout(Duration requestTimeout) {
		this.requestTimeout = requestTimeout;
	}

	public AgentTransportProperties getTransport() {
		return transport;
	}

	public void setTransport(AgentTransportProperties transport) {
		this.transport = transport;
	}

	public static class AgentTransportProperties {

		private TransportType type;

		private AgentHttpProperties http = new AgentHttpProperties();

		public TransportType getType() {
			return type;
		}

		public void setType(TransportType type) {
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
	 * {@code port}. The limits left unset keep the SDK defaults.
	 */
	public static class AgentHttpProperties {

		/**
		 * Port of the standalone listener. Ignored in a servlet web application, which
		 * uses its own server port.
		 */
		private int port = 8080;

		/**
		 * Endpoint path.
		 */
		private String path = "/acp";

		/**
		 * Largest accepted inbound message (POST body or WebSocket text message).
		 */
		private DataSize maxPostBodySize;

		/**
		 * Interval between SSE keep-alive comments; zero disables them.
		 */
		private Duration keepAliveInterval;

		/**
		 * Events retained per outbound stream while no subscriber is attached.
		 */
		private Integer mailboxCapacity;

		/**
		 * Events queued for one attached SSE subscriber before it is closed.
		 */
		private Integer maxPendingSseEvents;

		/**
		 * Frames queued for one WebSocket connection before it is closed.
		 */
		private Integer maxWebSocketPendingFrames;

		/**
		 * Session streams a connection may open before the session is known.
		 */
		private Integer maxProvisionalSessions;

		/**
		 * HTTP/2 streams one client connection may hold open. Standalone listener only.
		 */
		private Integer maxConcurrentStreamsPerConnection;

		/**
		 * How long closing the endpoint waits for its connections to close gracefully
		 * before closing the rest at once.
		 */
		private Duration shutdownTimeout;

		public int getPort() {
			return port;
		}

		public void setPort(int port) {
			this.port = port;
		}

		public String getPath() {
			return path;
		}

		public void setPath(String path) {
			this.path = path;
		}

		public DataSize getMaxPostBodySize() {
			return maxPostBodySize;
		}

		public void setMaxPostBodySize(DataSize maxPostBodySize) {
			this.maxPostBodySize = maxPostBodySize;
		}

		public Duration getKeepAliveInterval() {
			return keepAliveInterval;
		}

		public void setKeepAliveInterval(Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval;
		}

		public Integer getMailboxCapacity() {
			return mailboxCapacity;
		}

		public void setMailboxCapacity(Integer mailboxCapacity) {
			this.mailboxCapacity = mailboxCapacity;
		}

		public Integer getMaxPendingSseEvents() {
			return maxPendingSseEvents;
		}

		public void setMaxPendingSseEvents(Integer maxPendingSseEvents) {
			this.maxPendingSseEvents = maxPendingSseEvents;
		}

		public Integer getMaxWebSocketPendingFrames() {
			return maxWebSocketPendingFrames;
		}

		public void setMaxWebSocketPendingFrames(Integer maxWebSocketPendingFrames) {
			this.maxWebSocketPendingFrames = maxWebSocketPendingFrames;
		}

		public Integer getMaxProvisionalSessions() {
			return maxProvisionalSessions;
		}

		public void setMaxProvisionalSessions(Integer maxProvisionalSessions) {
			this.maxProvisionalSessions = maxProvisionalSessions;
		}

		public Integer getMaxConcurrentStreamsPerConnection() {
			return maxConcurrentStreamsPerConnection;
		}

		public void setMaxConcurrentStreamsPerConnection(Integer maxConcurrentStreamsPerConnection) {
			this.maxConcurrentStreamsPerConnection = maxConcurrentStreamsPerConnection;
		}

		public Duration getShutdownTimeout() {
			return shutdownTimeout;
		}

		public void setShutdownTimeout(Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
		}

	}

}
