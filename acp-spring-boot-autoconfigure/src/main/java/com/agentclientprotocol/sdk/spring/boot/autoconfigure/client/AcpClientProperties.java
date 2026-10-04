package com.agentclientprotocol.sdk.spring.boot.autoconfigure.client;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.spring.boot.autoconfigure.TransportType;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.acp.client")
public class AcpClientProperties {

	private Duration requestTimeout = Duration.ofSeconds(60);

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

		private @Nullable TransportType type;

		private WebSocketProperties websocket = new WebSocketProperties();

		private StdioProperties stdio = new StdioProperties();

		private HttpProperties http = new HttpProperties();

		public @Nullable TransportType getType() {
			return type;
		}

		public void setType(@Nullable TransportType type) {
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

		public HttpProperties getHttp() {
			return http;
		}

		public void setHttp(HttpProperties http) {
			this.http = http;
		}

	}

	/**
	 * Streamable HTTP client transport, for an agent served over HTTP.
	 */
	public static class HttpProperties {

		/**
		 * Endpoint of the agent (e.g. http://localhost:8080/acp).
		 */
		private @Nullable URI uri;

		public @Nullable URI getUri() {
			return uri;
		}

		public void setUri(@Nullable URI uri) {
			this.uri = uri;
		}

	}

	public static class WebSocketProperties {

		private @Nullable URI uri;

		private Duration connectTimeout = Duration.ofSeconds(10);

		public @Nullable URI getUri() {
			return uri;
		}

		public void setUri(@Nullable URI uri) {
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

		private @Nullable String command;

		private List<String> args = new ArrayList<>();

		private Map<String, String> env = new LinkedHashMap<>();

		public @Nullable String getCommand() {
			return command;
		}

		public void setCommand(@Nullable String command) {
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

		/**
		 * Advertise the fs/read_text_file capability. Off by default: the
		 * autoconfiguration registers no file handler, so enable it with one registered
		 * through an AcpClientCustomizer.
		 */
		private boolean readTextFile = false;

		/**
		 * Advertise the fs/write_text_file capability. Off by default: the
		 * autoconfiguration registers no file handler, so enable it with one registered
		 * through an AcpClientCustomizer.
		 */
		private boolean writeTextFile = false;

		/**
		 * Advertise the terminal capability. Enable it with terminal handlers registered
		 * through an AcpClientCustomizer.
		 */
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
