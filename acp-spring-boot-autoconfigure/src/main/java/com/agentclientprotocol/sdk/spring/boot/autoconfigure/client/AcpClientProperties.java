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
 * The {@code spring.acp.client.*} configuration properties: which agent the application's ACP
 * client connects to, and how. They choose the transport (start an agent process over stdio, or
 * connect to a WebSocket or Streamable HTTP endpoint), set the client's timeouts, and say which
 * capabilities the client advertises. Set them in {@code application.properties} or
 * {@code application.yaml}; {@link AcpClientTransportAutoConfiguration} and
 * {@link AcpClientAutoConfiguration} read them through {@link #toSettings()}.
 *
 * <p>Setting a transport's command or URI is what creates the client: with no
 * {@code spring.acp.client.transport.*} property set, the application gets no client. A timeout
 * left unset keeps the SDK's default, which its getter names. Durations take Spring's forms
 * ({@code 30s}, {@code 500ms}, {@code PT1M}).
 *
 * <p>The properties map onto {@link AcpClientSettings}, the framework-neutral settings the
 * Micronaut and Quarkus integrations fill from their own keys, so each property means the same
 * there. The timeouts end up on the client's builder,
 * {@link com.agentclientprotocol.sdk.client.AcpClient.AsyncSpec}.
 */
@ConfigurationProperties(prefix = "spring.acp.client")
public class AcpClientProperties {

	/**
	 * How long the client waits for the agent to answer a request. Unset keeps the SDK default, 60
	 * seconds.
	 */
	private @Nullable Duration requestTimeout;

	/**
	 * How long a prompt turn may take before the client cancels it; unset (the default) for no
	 * limit. Prompts are not bound by the request timeout.
	 */
	private @Nullable Duration promptTimeout;

	private TransportProperties transport = new TransportProperties();

	private CapabilitiesProperties capabilities = new CapabilitiesProperties();

	/**
	 * Returns how long the client waits for the agent to answer a request other than a prompt
	 * ({@code spring.acp.client.request-timeout}). Default: unset, which keeps the SDK's default of
	 * 60 seconds. Maps to {@code AcpClient.AsyncSpec.requestTimeout}. Closing the client at context
	 * shutdown also waits at most this long plus 10 seconds before it closes at once.
	 * @return the timeout, or {@code null} when unset
	 */
	public @Nullable Duration getRequestTimeout() {
		return requestTimeout;
	}

	/**
	 * Sets {@code spring.acp.client.request-timeout}; see {@link #getRequestTimeout()}.
	 * @param requestTimeout the timeout, or {@code null} for the SDK's default
	 */
	public void setRequestTimeout(@Nullable Duration requestTimeout) {
		this.requestTimeout = requestTimeout;
	}

	/**
	 * Returns how long a prompt turn ({@code session/prompt}) may take
	 * ({@code spring.acp.client.prompt-timeout}). When it passes, {@code prompt} fails with a
	 * {@link java.util.concurrent.TimeoutException} and the client sends the agent a
	 * {@code $/cancel_request}. Default: unset, which keeps the SDK's default of no limit; zero
	 * also means no limit. The request timeout never applies to prompts. Maps to
	 * {@code AcpClient.AsyncSpec.promptTimeout}.
	 * @return the timeout, or {@code null} when unset
	 */
	public @Nullable Duration getPromptTimeout() {
		return promptTimeout;
	}

	/**
	 * Sets {@code spring.acp.client.prompt-timeout}; see {@link #getPromptTimeout()}.
	 * @param promptTimeout the timeout, zero for no limit, or {@code null} for the SDK's default;
	 * not negative
	 */
	public void setPromptTimeout(@Nullable Duration promptTimeout) {
		this.promptTimeout = promptTimeout;
	}

	/**
	 * Returns the transport properties, {@code spring.acp.client.transport.*}.
	 * @return the transport properties
	 */
	public TransportProperties getTransport() {
		return transport;
	}

	/**
	 * Replaces the transport properties, {@code spring.acp.client.transport.*}.
	 * @param transport the transport properties
	 */
	public void setTransport(TransportProperties transport) {
		this.transport = transport;
	}

	/**
	 * Returns the advertised capabilities, {@code spring.acp.client.capabilities.*}.
	 * @return the capability properties
	 */
	public CapabilitiesProperties getCapabilities() {
		return capabilities;
	}

	/**
	 * Replaces the advertised capabilities, {@code spring.acp.client.capabilities.*}.
	 * @param capabilities the capability properties
	 */
	public void setCapabilities(CapabilitiesProperties capabilities) {
		this.capabilities = capabilities;
	}

	/**
	 * Returns these properties as the SDK's framework-neutral {@link AcpClientSettings}, which the
	 * autoconfiguration passes to {@code acp-integration}. An unset timeout or transport type stays
	 * unset ({@code null}), so the SDK applies its default or infers the type. Each call builds new
	 * settings from the current values.
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

	/**
	 * The {@code spring.acp.client.transport.*} properties: which transport the client uses, and
	 * each transport's own settings. Set the command or URI of one transport and it is chosen; set
	 * {@code type} as well only when more than one is set.
	 */
	public static class TransportProperties {

		/**
		 * The transport: stdio, websocket or http. Unset, it is the one whose command or URI
		 * is set; with several set, it must be given.
		 */
		private @Nullable AcpTransportType type;

		private WebSocketProperties websocket = new WebSocketProperties();

		private StdioProperties stdio = new StdioProperties();

		private HttpProperties http = new HttpProperties();

		/**
		 * Returns the transport ({@code spring.acp.client.transport.type}): {@code stdio},
		 * {@code websocket} or {@code http}, read in any case. Maps to
		 * {@link AcpClientSettings#transport()}.
		 *
		 * <p>Unset (the default), the transport is the one whose {@code stdio.command},
		 * {@code websocket.uri} or {@code http.uri} is set; with more than one set, the application
		 * fails at startup naming them. A type that is set wins, and fails the startup when its own
		 * command or URI is missing, naming that property.
		 * @return the transport, or {@code null} when unset
		 */
		public @Nullable AcpTransportType getType() {
			return type;
		}

		/**
		 * Sets {@code spring.acp.client.transport.type}; see {@link #getType()}.
		 * @param type the transport, or {@code null} to infer it
		 */
		public void setType(@Nullable AcpTransportType type) {
			this.type = type;
		}

		/**
		 * Returns the WebSocket properties, {@code spring.acp.client.transport.websocket.*}.
		 * @return the WebSocket properties
		 */
		public WebSocketProperties getWebsocket() {
			return websocket;
		}

		/**
		 * Replaces the WebSocket properties, {@code spring.acp.client.transport.websocket.*}.
		 * @param websocket the WebSocket properties
		 */
		public void setWebsocket(WebSocketProperties websocket) {
			this.websocket = websocket;
		}

		/**
		 * Returns the stdio properties, {@code spring.acp.client.transport.stdio.*}.
		 * @return the stdio properties
		 */
		public StdioProperties getStdio() {
			return stdio;
		}

		/**
		 * Replaces the stdio properties, {@code spring.acp.client.transport.stdio.*}.
		 * @param stdio the stdio properties
		 */
		public void setStdio(StdioProperties stdio) {
			this.stdio = stdio;
		}

		/**
		 * Returns the Streamable HTTP properties, {@code spring.acp.client.transport.http.*}.
		 * @return the HTTP properties
		 */
		public HttpProperties getHttp() {
			return http;
		}

		/**
		 * Replaces the Streamable HTTP properties, {@code spring.acp.client.transport.http.*}.
		 * @param http the HTTP properties
		 */
		public void setHttp(HttpProperties http) {
			this.http = http;
		}

	}

	/**
	 * The {@code spring.acp.client.transport.http.*} properties: the Streamable HTTP client
	 * transport, for an agent served over HTTP, such as one served by
	 * {@code spring.acp.agent.transport.type=http}.
	 */
	public static class HttpProperties {

		/**
		 * Endpoint of the agent (e.g. http://localhost:8080/acp).
		 */
		private @Nullable URI uri;

		/**
		 * Returns the agent's endpoint ({@code spring.acp.client.transport.http.uri}), such as
		 * {@code http://localhost:8080/acp}. Setting it selects the Streamable HTTP transport,
		 * {@link com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport}. No
		 * default. Maps to {@link AcpClientSettings.Http#uri()}.
		 * @return the endpoint, or {@code null} when unset
		 */
		public @Nullable URI getUri() {
			return uri;
		}

		/**
		 * Sets {@code spring.acp.client.transport.http.uri}; see {@link #getUri()}.
		 * @param uri the endpoint, or {@code null} for none
		 */
		public void setUri(@Nullable URI uri) {
			this.uri = uri;
		}

	}

	/**
	 * The {@code spring.acp.client.transport.websocket.*} properties: the WebSocket client
	 * transport, for an agent that accepts WebSocket connections, such as the SDK's listener.
	 */
	public static class WebSocketProperties {

		/**
		 * Endpoint of the agent (e.g. ws://localhost:8080/acp).
		 */
		private @Nullable URI uri;

		/**
		 * How long connecting to the agent may take. Default 10 seconds.
		 */
		private Duration connectTimeout = AcpClientSettings.DEFAULT_CONNECT_TIMEOUT;

		/**
		 * Returns the agent's endpoint ({@code spring.acp.client.transport.websocket.uri}), such as
		 * {@code ws://localhost:8080/acp}. Setting it selects the WebSocket transport,
		 * {@link com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport}. No
		 * default. Maps to {@link AcpClientSettings.WebSocket#uri()}.
		 * @return the endpoint, or {@code null} when unset
		 */
		public @Nullable URI getUri() {
			return uri;
		}

		/**
		 * Sets {@code spring.acp.client.transport.websocket.uri}; see {@link #getUri()}.
		 * @param uri the endpoint, or {@code null} for none
		 */
		public void setUri(@Nullable URI uri) {
			this.uri = uri;
		}

		/**
		 * Returns how long connecting to the agent, the WebSocket handshake, may take
		 * ({@code spring.acp.client.transport.websocket.connect-timeout}). Default 10 seconds
		 * ({@link AcpClientSettings#DEFAULT_CONNECT_TIMEOUT}), which replaces the transport's own
		 * default. Maps to {@code WebSocketAcpClientTransport.connectTimeout}.
		 * @return the timeout
		 */
		public Duration getConnectTimeout() {
			return connectTimeout;
		}

		/**
		 * Sets {@code spring.acp.client.transport.websocket.connect-timeout}; see
		 * {@link #getConnectTimeout()}.
		 * @param connectTimeout the timeout; positive
		 */
		public void setConnectTimeout(Duration connectTimeout) {
			this.connectTimeout = connectTimeout;
		}

	}

	/**
	 * The {@code spring.acp.client.transport.stdio.*} properties: the agent process the stdio
	 * client transport starts, then talks to over the process's standard input and output.
	 */
	public static class StdioProperties {

		/**
		 * Command that starts the agent process, such as an executable on the PATH.
		 */
		private @Nullable String command;

		/**
		 * Arguments passed to the command, in order. Default none.
		 */
		private List<String> args = new ArrayList<>();

		/**
		 * Environment variables added to the agent process's environment. Default none.
		 */
		private Map<String, String> env = new LinkedHashMap<>();

		/**
		 * Returns the command that starts the agent process
		 * ({@code spring.acp.client.transport.stdio.command}). Setting it selects the stdio
		 * transport, {@link com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport},
		 * which starts the process when the client is created, at context startup. No default. Maps
		 * to {@link AcpClientSettings.Stdio#command()}.
		 * @return the command, or {@code null} when unset
		 */
		public @Nullable String getCommand() {
			return command;
		}

		/**
		 * Sets {@code spring.acp.client.transport.stdio.command}; see {@link #getCommand()}.
		 * @param command the command, or {@code null} for none
		 */
		public void setCommand(@Nullable String command) {
			this.command = command;
		}

		/**
		 * Returns the command's arguments ({@code spring.acp.client.transport.stdio.args}), in
		 * order. Default none. Maps to {@link AcpClientSettings.Stdio#args()}.
		 * @return the arguments; the list the binder fills
		 */
		public List<String> getArgs() {
			return args;
		}

		/**
		 * Sets {@code spring.acp.client.transport.stdio.args}; see {@link #getArgs()}.
		 * @param args the arguments
		 */
		public void setArgs(List<String> args) {
			this.args = args;
		}

		/**
		 * Returns the environment variables added to the agent process
		 * ({@code spring.acp.client.transport.stdio.env.*}). The process inherits the application's
		 * whole environment, and these add to it or replace a variable of the same name. Default
		 * none. Maps to {@link AcpClientSettings.Stdio#env()}.
		 * @return the variables, by name; the map the binder fills
		 */
		public Map<String, String> getEnv() {
			return env;
		}

		/**
		 * Sets {@code spring.acp.client.transport.stdio.env}; see {@link #getEnv()}.
		 * @param env the variables, by name
		 */
		public void setEnv(Map<String, String> env) {
			this.env = env;
		}

	}

	/**
	 * The {@code spring.acp.client.capabilities.*} properties: the capabilities the client
	 * advertises to the agent in {@code initialize}. All are off by default.
	 *
	 * <p>The autoconfiguration registers no handler for the methods a capability promises to serve.
	 * Turn a capability on together with its handler, registered through an
	 * {@link com.agentclientprotocol.sdk.integration.AcpClientCustomizer} bean; a capability turned
	 * on without its handler fails the application's startup, naming the property. Together they
	 * map to {@link AcpClientSettings.Capabilities}.
	 */
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
		 * Advertise the terminal capability. Off by default; enable it with terminal handlers
		 * registered through an AcpClientCustomizer.
		 */
		private boolean terminal = false;

		/**
		 * Advertise form-mode elicitation/create. Off by default; enable it with an elicitation
		 * handler registered through an AcpClientCustomizer.
		 */
		private boolean elicitationForm = false;

		/**
		 * Advertise URL-mode elicitation/create. Off by default; enable it with an elicitation
		 * handler registered through an AcpClientCustomizer.
		 */
		private boolean elicitationUrl = false;

		/**
		 * Advertise that the client accepts boolean session config options. Off by default.
		 */
		private boolean booleanConfigOptions = false;

		/**
		 * Returns whether the client advertises {@code fs/read_text_file}
		 * ({@code spring.acp.client.capabilities.read-text-file}). Default {@code false}. Needs a
		 * read-file handler. Maps to {@link AcpClientSettings.Capabilities#readTextFile()}.
		 * @return whether the capability is advertised
		 */
		public boolean isReadTextFile() {
			return readTextFile;
		}

		/**
		 * Sets {@code spring.acp.client.capabilities.read-text-file}; see
		 * {@link #isReadTextFile()}.
		 * @param readTextFile whether the capability is advertised
		 */
		public void setReadTextFile(boolean readTextFile) {
			this.readTextFile = readTextFile;
		}

		/**
		 * Returns whether the client advertises {@code fs/write_text_file}
		 * ({@code spring.acp.client.capabilities.write-text-file}). Default {@code false}. Needs a
		 * write-file handler. Maps to {@link AcpClientSettings.Capabilities#writeTextFile()}.
		 * @return whether the capability is advertised
		 */
		public boolean isWriteTextFile() {
			return writeTextFile;
		}

		/**
		 * Sets {@code spring.acp.client.capabilities.write-text-file}; see
		 * {@link #isWriteTextFile()}.
		 * @param writeTextFile whether the capability is advertised
		 */
		public void setWriteTextFile(boolean writeTextFile) {
			this.writeTextFile = writeTextFile;
		}

		/**
		 * Returns whether the client advertises the {@code terminal/*} methods
		 * ({@code spring.acp.client.capabilities.terminal}). Default {@code false}. Needs the
		 * terminal handlers. Maps to {@link AcpClientSettings.Capabilities#terminal()}.
		 * @return whether the capability is advertised
		 */
		public boolean isTerminal() {
			return terminal;
		}

		/**
		 * Sets {@code spring.acp.client.capabilities.terminal}; see {@link #isTerminal()}.
		 * @param terminal whether the capability is advertised
		 */
		public void setTerminal(boolean terminal) {
			this.terminal = terminal;
		}

		/**
		 * Returns whether the client advertises form-mode {@code elicitation/create}
		 * ({@code spring.acp.client.capabilities.elicitation-form}). Default {@code false}. Needs
		 * an elicitation handler. Maps to {@link AcpClientSettings.Capabilities#elicitationForm()}.
		 * @return whether the capability is advertised
		 */
		public boolean isElicitationForm() {
			return elicitationForm;
		}

		/**
		 * Sets {@code spring.acp.client.capabilities.elicitation-form}; see
		 * {@link #isElicitationForm()}.
		 * @param elicitationForm whether the capability is advertised
		 */
		public void setElicitationForm(boolean elicitationForm) {
			this.elicitationForm = elicitationForm;
		}

		/**
		 * Returns whether the client advertises URL-mode {@code elicitation/create}
		 * ({@code spring.acp.client.capabilities.elicitation-url}). Default {@code false}. Needs an
		 * elicitation handler. Maps to {@link AcpClientSettings.Capabilities#elicitationUrl()}.
		 * @return whether the capability is advertised
		 */
		public boolean isElicitationUrl() {
			return elicitationUrl;
		}

		/**
		 * Sets {@code spring.acp.client.capabilities.elicitation-url}; see
		 * {@link #isElicitationUrl()}.
		 * @param elicitationUrl whether the capability is advertised
		 */
		public void setElicitationUrl(boolean elicitationUrl) {
			this.elicitationUrl = elicitationUrl;
		}

		/**
		 * Returns whether the client advertises that it accepts boolean session config options
		 * ({@code spring.acp.client.capabilities.boolean-config-options}). Default {@code false}.
		 * Needs no handler. Maps to {@link AcpClientSettings.Capabilities#booleanConfigOptions()}.
		 * @return whether the capability is advertised
		 */
		public boolean isBooleanConfigOptions() {
			return booleanConfigOptions;
		}

		/**
		 * Sets {@code spring.acp.client.capabilities.boolean-config-options}; see
		 * {@link #isBooleanConfigOptions()}.
		 * @param booleanConfigOptions whether the capability is advertised
		 */
		public void setBooleanConfigOptions(boolean booleanConfigOptions) {
			this.booleanConfigOptions = booleanConfigOptions;
		}

	}

}
