package com.agentclientprotocol.sdk.spring.boot.autoconfigure.client;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.integration.AcpClientSettings;
import com.agentclientprotocol.sdk.integration.AcpTransportType;
import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The client's settings, bound from {@code spring.acp.client.*} onto {@link AcpClientSettings}.
 * A value left unset keeps the SDK's default.
 */
@ConfigurationProperties(prefix = "spring.acp.client")
public class AcpClientProperties {

	/**
	 * How long the client waits for the agent to answer a request. Unset keeps the SDK
	 * default.
	 */
	private @Nullable Duration requestTimeout;

	/**
	 * How long a prompt may wait for its answer, apart from the request timeout. Unset: none.
	 */
	private @Nullable Duration promptTimeout;

	private TransportProperties transport = new TransportProperties();

	private CapabilitiesProperties capabilities = new CapabilitiesProperties();

	public @Nullable Duration getRequestTimeout() {
		return requestTimeout;
	}

	public void setRequestTimeout(@Nullable Duration requestTimeout) {
		this.requestTimeout = requestTimeout;
	}

	public @Nullable Duration getPromptTimeout() {
		return promptTimeout;
	}

	public void setPromptTimeout(@Nullable Duration promptTimeout) {
		this.promptTimeout = promptTimeout;
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

	/**
	 * These properties as the SDK's framework-neutral settings.
	 * @return the settings
	 */
	public AcpClientSettings toSettings() {
		CapabilitiesProperties caps = capabilities;
		return AcpClientSettings.builder()
			.requestTimeout(requestTimeout)
			.promptTimeout(promptTimeout)
			.transport(transport.getType())
			.stdioCommand(transport.getStdio().getCommand())
			.stdioArgs(transport.getStdio().getArgs())
			.stdioEnv(transport.getStdio().getEnv())
			.websocketUri(transport.getWebsocket().getUri())
			.websocketConnectTimeout(transport.getWebsocket().getConnectTimeout())
			.httpUri(transport.getHttp().getUri())
			.capabilities(new AcpClientSettings.Capabilities(caps.isReadTextFile(), caps.isWriteTextFile(),
					caps.isTerminal(), caps.isElicitationForm(), caps.isElicitationUrl(),
					caps.isBooleanConfigOptions()))
			.build();
	}

	public static class TransportProperties {

		/**
		 * The transport: stdio, websocket or http. Unset, it is the one whose command or URI
		 * is set; with several set, it must be given.
		 */
		private @Nullable AcpTransportType type;

		private WebSocketProperties websocket = new WebSocketProperties();

		private StdioProperties stdio = new StdioProperties();

		private HttpProperties http = new HttpProperties();

		public @Nullable AcpTransportType getType() {
			return type;
		}

		public void setType(@Nullable AcpTransportType type) {
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

		private Duration connectTimeout = AcpClientSettings.DEFAULT_CONNECT_TIMEOUT;

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

		/**
		 * Advertise form-mode elicitation/create. Enable it with an elicitation handler
		 * registered through an AcpClientCustomizer.
		 */
		private boolean elicitationForm = false;

		/**
		 * Advertise URL-mode elicitation/create. Enable it with an elicitation handler
		 * registered through an AcpClientCustomizer.
		 */
		private boolean elicitationUrl = false;

		/**
		 * Advertise that the client accepts boolean session config options.
		 */
		private boolean booleanConfigOptions = false;

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

		public boolean isElicitationForm() {
			return elicitationForm;
		}

		public void setElicitationForm(boolean elicitationForm) {
			this.elicitationForm = elicitationForm;
		}

		public boolean isElicitationUrl() {
			return elicitationUrl;
		}

		public void setElicitationUrl(boolean elicitationUrl) {
			this.elicitationUrl = elicitationUrl;
		}

		public boolean isBooleanConfigOptions() {
			return booleanConfigOptions;
		}

		public void setBooleanConfigOptions(boolean booleanConfigOptions) {
			this.booleanConfigOptions = booleanConfigOptions;
		}

	}

}
