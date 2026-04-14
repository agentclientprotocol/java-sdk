package com.agentclientprotocol.autoconfigure.client;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.autoconfigure.TransportType;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.acp.client")
public class AcpClientProperties {

	private Duration requestTimeout = Duration.ofSeconds(30);

	private TransportProperties transport = new TransportProperties();

	private CapabilitiesProperties capabilities = new CapabilitiesProperties();

	public Duration getRequestTimeout() {
		return requestTimeout;
	}

	public void setRequestTimeout(Duration requestTimeout) {
		this.requestTimeout = requestTimeout;
	}

	public TransportProperties getTransport() {
		return transport;
	}

	public void setTransport(TransportProperties transport) {
		this.transport = transport;
	}

	public CapabilitiesProperties getCapabilities() {
		return capabilities;
	}

	public void setCapabilities(CapabilitiesProperties capabilities) {
		this.capabilities = capabilities;
	}

	public static class TransportProperties {

		private TransportType type;

		private WebSocketProperties websocket = new WebSocketProperties();

		private StdioProperties stdio = new StdioProperties();

		public TransportType getType() {
			return type;
		}

		public void setType(TransportType type) {
			this.type = type;
		}

		public WebSocketProperties getWebsocket() {
			return websocket;
		}

		public void setWebsocket(WebSocketProperties websocket) {
			this.websocket = websocket;
		}

		public StdioProperties getStdio() {
			return stdio;
		}

		public void setStdio(StdioProperties stdio) {
			this.stdio = stdio;
		}
	}

	public static class WebSocketProperties {

		private URI uri;

		private Duration connectTimeout = Duration.ofSeconds(10);

		public URI getUri() {
			return uri;
		}

		public void setUri(URI uri) {
			this.uri = uri;
		}

		public Duration getConnectTimeout() {
			return connectTimeout;
		}

		public void setConnectTimeout(Duration connectTimeout) {
			this.connectTimeout = connectTimeout;
		}
	}

	public static class StdioProperties {

		private String command;

		private List<String> args = new ArrayList<>();

		private Map<String, String> env = new LinkedHashMap<>();

		public String getCommand() {
			return command;
		}

		public void setCommand(String command) {
			this.command = command;
		}

		public List<String> getArgs() {
			return args;
		}

		public void setArgs(List<String> args) {
			this.args = args;
		}

		public Map<String, String> getEnv() {
			return env;
		}

		public void setEnv(Map<String, String> env) {
			this.env = env;
		}
	}

	public static class CapabilitiesProperties {

		private boolean readTextFile = true;

		private boolean writeTextFile = true;

		private boolean terminal = false;

		public boolean isReadTextFile() {
			return readTextFile;
		}

		public void setReadTextFile(boolean readTextFile) {
			this.readTextFile = readTextFile;
		}

		public boolean isWriteTextFile() {
			return writeTextFile;
		}

		public void setWriteTextFile(boolean writeTextFile) {
			this.writeTextFile = writeTextFile;
		}

		public boolean isTerminal() {
			return terminal;
		}

		public void setTerminal(boolean terminal) {
			this.terminal = terminal;
		}
	}
}
