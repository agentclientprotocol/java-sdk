package com.agentclientprotocol.autoconfigure.agent;

import java.time.Duration;

import com.agentclientprotocol.autoconfigure.TransportType;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

		private AgentWebSocketProperties websocket = new AgentWebSocketProperties();

		public TransportType getType() {
			return type;
		}

		public void setType(TransportType type) {
			this.type = type;
		}

		public AgentWebSocketProperties getWebsocket() {
			return websocket;
		}

		public void setWebsocket(AgentWebSocketProperties websocket) {
			this.websocket = websocket;
		}
	}

	public static class AgentWebSocketProperties {

		private int port = 8080;

		private String path = "/acp";

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
	}
}
