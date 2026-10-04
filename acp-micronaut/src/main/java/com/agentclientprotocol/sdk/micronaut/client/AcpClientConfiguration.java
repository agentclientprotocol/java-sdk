/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.client;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.integration.AcpClientCustomizer;
import com.agentclientprotocol.sdk.integration.AcpClientSettings;
import com.agentclientprotocol.sdk.integration.AcpTransportType;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.convert.format.MapFormat;
import io.micronaut.core.naming.conventions.StringConvention;
import org.jspecify.annotations.Nullable;

/**
 * The {@code acp.client.*} configuration properties: which agent the application's ACP client
 * connects to, and how. They choose the transport (start an agent process over stdio, or connect to
 * a WebSocket or Streamable HTTP endpoint), set the client's timeouts, and say which capabilities
 * the client advertises. {@link AcpClientBeans} builds the client from them.
 *
 * <p>Naming a transport is what creates the client beans: setting
 * {@code acp.client.transport.type}, {@code .stdio.command}, {@code .websocket.uri} or
 * {@code .http.uri}, as in Spring Boot and Quarkus. Another transport property alone, such as
 * {@code .websocket.connect-timeout}, creates none. Then {@code .stdio.command},
 * {@code .websocket.uri} or {@code .http.uri} must name the agent (with
 * {@code acp.client.transport.type} when more than one is set). The type binds in any case, such
 * as {@code websocket} or {@code WebSocket}.
 *
 * <pre>
 * acp.client.request-timeout                    60s
 * acp.client.prompt-timeout                     none
 * acp.client.transport.type                     stdio | websocket | http (else from what is set)
 * acp.client.transport.stdio.command, args, env the agent process to start
 * acp.client.transport.websocket.uri            ws://host:port/acp
 * acp.client.transport.websocket.connect-timeout 10s
 * acp.client.transport.http.uri                 http://host:port/acp
 * acp.client.capabilities.read-text-file        false
 * acp.client.capabilities.write-text-file       false
 * acp.client.capabilities.terminal              false
 * acp.client.capabilities.elicitation-form      false
 * acp.client.capabilities.elicitation-url       false
 * acp.client.capabilities.boolean-config-options false
 * </pre>
 *
 * <p>Durations take Micronaut's forms, such as {@code 30s} or {@code 500ms}. The properties map
 * onto {@link AcpClientSettings}, the framework-neutral settings the Spring Boot and Quarkus
 * integrations fill from their own keys, so each one means the same there; the timeouts end up on
 * the client's builder, {@link com.agentclientprotocol.sdk.client.AcpClient.AsyncSpec}. The
 * module's README walks through a client.
 */
@ConfigurationProperties(AcpClientConfiguration.PREFIX)
public class AcpClientConfiguration {

	/** The prefix of the client's settings. */
	public static final String PREFIX = "acp.client";

	private Duration requestTimeout = Duration.ofSeconds(60);

	private @Nullable Duration promptTimeout;

	private Transport transport = new Transport();

	private Capabilities capabilities = new Capabilities();

	/**
	 * Returns how long the client waits for the agent to answer a request other than a prompt
	 * ({@code acp.client.request-timeout}). Default 60 seconds, the SDK's default. Closing the
	 * client with the application context also waits at most this long plus 10 seconds before it
	 * closes at once. Maps to {@code AcpClient.AsyncSpec.requestTimeout}.
	 * @return the request timeout
	 */
	public Duration getRequestTimeout() {
		return requestTimeout;
	}

	/**
	 * Sets how long the client waits for the agent to answer a request; default 60 seconds.
	 * @param requestTimeout the request timeout
	 */
	public void setRequestTimeout(Duration requestTimeout) {
		this.requestTimeout = requestTimeout;
	}

	/**
	 * Returns how long a prompt turn ({@code session/prompt}) may take
	 * ({@code acp.client.prompt-timeout}). When it passes, {@code prompt} fails with a
	 * {@link java.util.concurrent.TimeoutException} and the client sends the agent a
	 * {@code $/cancel_request}. Default: unset, no limit; zero also means no limit. The request
	 * timeout never applies to prompts. Maps to {@code AcpClient.AsyncSpec.promptTimeout}.
	 * @return the prompt timeout, or {@code null} for none
	 */
	public @Nullable Duration getPromptTimeout() {
		return promptTimeout;
	}

	/**
	 * Sets how long a prompt turn may take before the client cancels it; unset (the default) for no
	 * limit.
	 * @param promptTimeout the longest a turn may take, zero or {@code null} for no limit; not
	 * negative
	 */
	public void setPromptTimeout(@Nullable Duration promptTimeout) {
		this.promptTimeout = promptTimeout;
	}

	/**
	 * Returns the transport properties, {@code acp.client.transport.*}.
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
	 * Returns the advertised capabilities, {@code acp.client.capabilities.*}.
	 * @return the capabilities
	 */
	public Capabilities getCapabilities() {
		return capabilities;
	}

	/**
	 * Replaces the advertised capabilities.
	 * @param capabilities the capabilities
	 */
	public void setCapabilities(Capabilities capabilities) {
		this.capabilities = capabilities;
	}

	/**
	 * Returns these properties as the SDK's framework-neutral {@link AcpClientSettings}, which
	 * {@link AcpClientBeans} passes to {@code acp-integration}. An unset transport type stays unset
	 * ({@code null}), so the SDK infers it. Each call builds new settings from the current values.
	 * @return the settings
	 */
	public AcpClientSettings toSettings() {
		Transport.Stdio stdio = transport.getStdio();
		return AcpClientSettings.builder()
			.requestTimeout(requestTimeout)
			.promptTimeout(promptTimeout)
			.transport(transport.getType())
			.stdioCommand(stdio.getCommand())
			.stdioArgs(stdio.getArgs())
			.stdioEnv(stdio.getEnv())
			.websocketUri(transport.getWebsocket().getUri())
			.websocketConnectTimeout(transport.getWebsocket().getConnectTimeout())
			.httpUri(transport.getHttp().getUri())
			.capabilities(new AcpClientSettings.Capabilities(capabilities.isReadTextFile(),
					capabilities.isWriteTextFile(), capabilities.isTerminal(), capabilities.isElicitationForm(),
					capabilities.isElicitationUrl(), capabilities.isBooleanConfigOptions()))
			.build();
	}

	/**
	 * The {@code acp.client.transport.*} properties: which transport the client uses, and each
	 * transport's own settings. Set the command or URI of one transport and it is chosen; set
	 * {@code type} as well only when more than one is set.
	 */
	@ConfigurationProperties("transport")
	public static class Transport {

		private @Nullable AcpTransportType type;

		private Stdio stdio = new Stdio();

		private WebSocket websocket = new WebSocket();

		private Http http = new Http();

		/**
		 * Returns the transport ({@code acp.client.transport.type}): {@code stdio},
		 * {@code websocket} or {@code http}, in any case. Maps to
		 * {@link AcpClientSettings#transport()}.
		 *
		 * <p>Unset (the default), the transport is the one whose {@code stdio.command},
		 * {@code websocket.uri} or {@code http.uri} is set; with more than one set, creating the
		 * client fails naming them. A type that is set wins, and creating the client fails when its
		 * own command or URI is missing, naming that property.
		 * @return the type, or {@code null} to infer it
		 */
		public @Nullable AcpTransportType getType() {
			return type;
		}

		/**
		 * Sets the transport: stdio, websocket or http; unset, the one whose command or URI is set.
		 * @param type the transport type, or {@code null} to infer it
		 */
		public void setType(@Nullable AcpTransportType type) {
			this.type = type;
		}

		/**
		 * Returns the stdio properties, {@code acp.client.transport.stdio.*}.
		 * @return the stdio properties
		 */
		public Stdio getStdio() {
			return stdio;
		}

		/**
		 * Replaces the stdio properties.
		 * @param stdio the stdio properties
		 */
		public void setStdio(Stdio stdio) {
			this.stdio = stdio;
		}

		/**
		 * Returns the WebSocket properties, {@code acp.client.transport.websocket.*}.
		 * @return the WebSocket properties
		 */
		public WebSocket getWebsocket() {
			return websocket;
		}

		/**
		 * Replaces the WebSocket properties.
		 * @param websocket the WebSocket properties
		 */
		public void setWebsocket(WebSocket websocket) {
			this.websocket = websocket;
		}

		/**
		 * Returns the Streamable HTTP properties, {@code acp.client.transport.http.*}.
		 * @return the HTTP properties
		 */
		public Http getHttp() {
			return http;
		}

		/**
		 * Replaces the Streamable HTTP properties.
		 * @param http the HTTP properties
		 */
		public void setHttp(Http http) {
			this.http = http;
		}

		/**
		 * The {@code acp.client.transport.stdio.*} properties: the agent process the stdio client
		 * transport starts, then talks to over the process's standard input and output.
		 */
		@ConfigurationProperties("stdio")
		public static class Stdio {

			private @Nullable String command;

			private List<String> args = new ArrayList<>();

			private Map<String, String> env = new LinkedHashMap<>();

			/**
			 * Returns the command that starts the agent process
			 * ({@code acp.client.transport.stdio.command}). Setting it selects the stdio transport,
			 * {@link com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport}, which
			 * starts the process when the client bean is created. No default. Maps to
			 * {@link AcpClientSettings.Stdio#command()}.
			 * @return the command, or {@code null} when unset
			 */
			public @Nullable String getCommand() {
				return command;
			}

			/**
			 * Sets the command that starts the agent process, such as an executable on the PATH.
			 * @param command the command
			 */
			public void setCommand(@Nullable String command) {
				this.command = command;
			}

			/**
			 * Returns the command's arguments ({@code acp.client.transport.stdio.args}), in order.
			 * Default none. Maps to {@link AcpClientSettings.Stdio#args()}.
			 * @return the arguments; the list the binder fills
			 */
			public List<String> getArgs() {
				return args;
			}

			/**
			 * Sets the arguments passed to the agent's command, in order.
			 * @param args the arguments
			 */
			public void setArgs(List<String> args) {
				this.args = args;
			}

			/**
			 * Returns the environment variables added to the agent process
			 * ({@code acp.client.transport.stdio.env.*}); their names keep the case they are
			 * written in. The process inherits the application's whole environment, and these add
			 * to it or replace a variable of the same name. Default none. Maps to
			 * {@link AcpClientSettings.Stdio#env()}.
			 * @return the variables, by name
			 */
			public Map<String, String> getEnv() {
				return env;
			}

			/**
			 * Sets environment variables added to the agent process's environment.
			 * @param env the variables, by name
			 */
			public void setEnv(@MapFormat(transformation = MapFormat.MapTransformation.FLAT,
					keyFormat = StringConvention.RAW) Map<String, String> env) {
				this.env = env;
			}

		}

		/**
		 * The {@code acp.client.transport.websocket.*} properties: the WebSocket client transport,
		 * for an agent that accepts WebSocket connections, such as the SDK's listener.
		 */
		@ConfigurationProperties("websocket")
		public static class WebSocket {

			private @Nullable URI uri;

			private Duration connectTimeout = AcpClientSettings.DEFAULT_CONNECT_TIMEOUT;

			/**
			 * Returns the agent's endpoint ({@code acp.client.transport.websocket.uri}), such as
			 * {@code ws://localhost:8080/acp}. Setting it selects the WebSocket transport,
			 * {@link com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport}. No
			 * default. Maps to {@link AcpClientSettings.WebSocket#uri()}.
			 * @return the URI, or {@code null} when unset
			 */
			public @Nullable URI getUri() {
				return uri;
			}

			/**
			 * Sets the agent's WebSocket endpoint, such as ws://localhost:8080/acp.
			 * @param uri the agent's endpoint
			 */
			public void setUri(@Nullable URI uri) {
				this.uri = uri;
			}

			/**
			 * Returns how long connecting to the agent, the WebSocket handshake, may take
			 * ({@code acp.client.transport.websocket.connect-timeout}). Default 10 seconds
			 * ({@link AcpClientSettings#DEFAULT_CONNECT_TIMEOUT}), which replaces the transport's
			 * own default. Maps to {@code WebSocketAcpClientTransport.connectTimeout}.
			 * @return the connect timeout
			 */
			public Duration getConnectTimeout() {
				return connectTimeout;
			}

			/**
			 * Sets how long connecting to the agent over WebSocket may take; default 10 seconds.
			 * @param connectTimeout the connect timeout; positive
			 */
			public void setConnectTimeout(Duration connectTimeout) {
				this.connectTimeout = connectTimeout;
			}

		}

		/**
		 * The {@code acp.client.transport.http.*} properties: the Streamable HTTP client transport,
		 * for an agent served over HTTP.
		 */
		@ConfigurationProperties("http")
		public static class Http {

			private @Nullable URI uri;

			/**
			 * Returns the agent's endpoint ({@code acp.client.transport.http.uri}), such as
			 * {@code http://localhost:8080/acp}. Setting it selects the Streamable HTTP transport,
			 * {@code StreamableHttpAcpClientTransport}. No default. Maps to
			 * {@link AcpClientSettings.Http#uri()}.
			 * @return the URI, or {@code null} when unset
			 */
			public @Nullable URI getUri() {
				return uri;
			}

			/**
			 * Sets the agent's Streamable HTTP endpoint, such as http://localhost:8080/acp.
			 * @param uri the agent's endpoint
			 */
			public void setUri(@Nullable URI uri) {
				this.uri = uri;
			}

		}

	}

	/**
	 * The {@code acp.client.capabilities.*} properties: the capabilities the client advertises to
	 * the agent in {@code initialize}. Each is off by default: enable one together with the handler
	 * for it, registered through an {@link AcpClientCustomizer} bean. A capability turned on
	 * without its handler makes creating the client fail, naming the property. Together they map to
	 * {@link AcpClientSettings.Capabilities}.
	 */
	@ConfigurationProperties("capabilities")
	public static class Capabilities {

		private boolean readTextFile;

		private boolean writeTextFile;

		private boolean terminal;

		private boolean elicitationForm;

		private boolean elicitationUrl;

		private boolean booleanConfigOptions;

		/**
		 * Returns whether the client advertises {@code fs/read_text_file}
		 * ({@code acp.client.capabilities.read-text-file}). Default {@code false}. Needs a
		 * read-file handler. Maps to {@link AcpClientSettings.Capabilities#readTextFile()}.
		 * @return whether advertised
		 */
		public boolean isReadTextFile() {
			return readTextFile;
		}

		/**
		 * Sets whether to advertise fs/read_text_file; default false.
		 * @param readTextFile whether to advertise {@code fs/read_text_file}
		 */
		public void setReadTextFile(boolean readTextFile) {
			this.readTextFile = readTextFile;
		}

		/**
		 * Returns whether the client advertises {@code fs/write_text_file}
		 * ({@code acp.client.capabilities.write-text-file}). Default {@code false}. Needs a
		 * write-file handler. Maps to {@link AcpClientSettings.Capabilities#writeTextFile()}.
		 * @return whether advertised
		 */
		public boolean isWriteTextFile() {
			return writeTextFile;
		}

		/**
		 * Sets whether to advertise fs/write_text_file; default false.
		 * @param writeTextFile whether to advertise {@code fs/write_text_file}
		 */
		public void setWriteTextFile(boolean writeTextFile) {
			this.writeTextFile = writeTextFile;
		}

		/**
		 * Returns whether the client advertises the {@code terminal/*} methods
		 * ({@code acp.client.capabilities.terminal}). Default {@code false}. Needs the terminal
		 * handlers. Maps to {@link AcpClientSettings.Capabilities#terminal()}.
		 * @return whether advertised
		 */
		public boolean isTerminal() {
			return terminal;
		}

		/**
		 * Sets whether to advertise the terminal methods; default false.
		 * @param terminal whether to advertise the terminal methods
		 */
		public void setTerminal(boolean terminal) {
			this.terminal = terminal;
		}

		/**
		 * Returns whether the client advertises form-mode {@code elicitation/create}
		 * ({@code acp.client.capabilities.elicitation-form}). Default {@code false}. Needs an
		 * elicitation handler. Maps to {@link AcpClientSettings.Capabilities#elicitationForm()}.
		 * @return whether advertised
		 */
		public boolean isElicitationForm() {
			return elicitationForm;
		}

		/**
		 * Sets whether to advertise form-mode elicitation; default false.
		 * @param elicitationForm whether to advertise {@code elicitation.form}
		 */
		public void setElicitationForm(boolean elicitationForm) {
			this.elicitationForm = elicitationForm;
		}

		/**
		 * Returns whether the client advertises URL-mode {@code elicitation/create}
		 * ({@code acp.client.capabilities.elicitation-url}). Default {@code false}. Needs an
		 * elicitation handler. Maps to {@link AcpClientSettings.Capabilities#elicitationUrl()}.
		 * @return whether advertised
		 */
		public boolean isElicitationUrl() {
			return elicitationUrl;
		}

		/**
		 * Sets whether to advertise URL-mode elicitation; default false.
		 * @param elicitationUrl whether to advertise {@code elicitation.url}
		 */
		public void setElicitationUrl(boolean elicitationUrl) {
			this.elicitationUrl = elicitationUrl;
		}

		/**
		 * Returns whether the client advertises that it accepts boolean session config options
		 * ({@code acp.client.capabilities.boolean-config-options}). Default {@code false}. Needs no
		 * handler. Maps to {@link AcpClientSettings.Capabilities#booleanConfigOptions()}.
		 * @return whether advertised
		 */
		public boolean isBooleanConfigOptions() {
			return booleanConfigOptions;
		}

		/**
		 * Sets whether to advertise that the client accepts boolean session config options; default
		 * false.
		 * @param booleanConfigOptions whether to advertise boolean session config options
		 */
		public void setBooleanConfigOptions(boolean booleanConfigOptions) {
			this.booleanConfigOptions = booleanConfigOptions;
		}

	}

}
