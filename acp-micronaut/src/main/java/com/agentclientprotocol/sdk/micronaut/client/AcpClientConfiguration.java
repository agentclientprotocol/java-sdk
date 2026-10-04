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
 * The client's settings, bound from {@code acp.client.*}. A client is configured when a
 * transport is: {@code acp.client.transport.type}, or one of
 * {@code acp.client.transport.stdio.command}, {@code .websocket.uri} or {@code .http.uri}.
 *
 * <pre>
 * acp.client.request-timeout                    60s
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
	 * How long a request the client sends may wait for its answer. Default 60 seconds, the
	 * SDK's default.
	 * @return the request timeout
	 */
	public Duration getRequestTimeout() {
		return requestTimeout;
	}

	/**
	 * Sets how long a client request may wait for its answer.
	 * @param requestTimeout how long a client request may wait for its answer
	 */
	public void setRequestTimeout(Duration requestTimeout) {
		this.requestTimeout = requestTimeout;
	}

	/**
	 * How long a prompt turn may take before the client cancels it; unset (the default) for no
	 * limit. Prompts are not bound by the request timeout.
	 * @return the prompt timeout, or null for none
	 */
	public @Nullable Duration getPromptTimeout() {
		return promptTimeout;
	}

	/**
	 * Sets how long a prompt turn may take before the client cancels it.
	 * @param promptTimeout the longest a turn may take, or null for no limit
	 */
	public void setPromptTimeout(@Nullable Duration promptTimeout) {
		this.promptTimeout = promptTimeout;
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

	/**
	 * The capabilities the client advertises.
	 * @return the capabilities
	 */
	public Capabilities getCapabilities() {
		return capabilities;
	}

	/**
	 * Sets the capabilities the client advertises.
	 * @param capabilities the capabilities the client advertises
	 */
	public void setCapabilities(Capabilities capabilities) {
		this.capabilities = capabilities;
	}

	/**
	 * These settings as the SDK's framework-neutral settings.
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

	/** The client transport, bound from {@code acp.client.transport.*}. */
	@ConfigurationProperties("transport")
	public static class Transport {

		private @Nullable AcpTransportType type;

		private Stdio stdio = new Stdio();

		private WebSocket websocket = new WebSocket();

		private Http http = new Http();

		/**
		 * The transport type. When unset, the one whose command or URI is set; with more than
		 * one set, this must be given.
		 * @return the type, or null to infer it
		 */
		public @Nullable AcpTransportType getType() {
			return type;
		}

		/**
		 * Sets the transport type.
		 * @param type the transport type
		 */
		public void setType(@Nullable AcpTransportType type) {
			this.type = type;
		}

		/**
		 * The stdio transport settings.
		 * @return the stdio settings
		 */
		public Stdio getStdio() {
			return stdio;
		}

		/**
		 * Sets the stdio settings.
		 * @param stdio the stdio settings
		 */
		public void setStdio(Stdio stdio) {
			this.stdio = stdio;
		}

		/**
		 * The WebSocket transport settings.
		 * @return the WebSocket settings
		 */
		public WebSocket getWebsocket() {
			return websocket;
		}

		/**
		 * Sets the WebSocket settings.
		 * @param websocket the WebSocket settings
		 */
		public void setWebsocket(WebSocket websocket) {
			this.websocket = websocket;
		}

		/**
		 * The Streamable HTTP transport settings.
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

		/** Starts the agent as a process and speaks to it over its standard streams. */
		@ConfigurationProperties("stdio")
		public static class Stdio {

			private @Nullable String command;

			private List<String> args = new ArrayList<>();

			private Map<String, String> env = new LinkedHashMap<>();

			/**
			 * The agent's command.
			 * @return the command, or null when unset
			 */
			public @Nullable String getCommand() {
				return command;
			}

			/**
			 * Sets the agent's command.
			 * @param command the agent's command
			 */
			public void setCommand(@Nullable String command) {
				this.command = command;
			}

			/**
			 * The agent's arguments.
			 * @return the arguments
			 */
			public List<String> getArgs() {
				return args;
			}

			/**
			 * Sets the agent's arguments.
			 * @param args the agent's arguments
			 */
			public void setArgs(List<String> args) {
				this.args = args;
			}

			/**
			 * Environment variables added to the agent's environment.
			 * @return the variables
			 */
			public Map<String, String> getEnv() {
				return env;
			}

			/**
			 * Sets environment variables added to the agent's environment.
			 * @param env environment variables added to the agent's environment
			 */
			public void setEnv(@MapFormat(transformation = MapFormat.MapTransformation.FLAT,
					keyFormat = StringConvention.RAW) Map<String, String> env) {
				this.env = env;
			}

		}

		/** Connects to an agent over WebSocket. */
		@ConfigurationProperties("websocket")
		public static class WebSocket {

			private @Nullable URI uri;

			private Duration connectTimeout = AcpClientSettings.DEFAULT_CONNECT_TIMEOUT;

			/**
			 * The agent's endpoint, such as {@code ws://localhost:8080/acp}.
			 * @return the URI, or null when unset
			 */
			public @Nullable URI getUri() {
				return uri;
			}

			/**
			 * Sets the agent's endpoint.
			 * @param uri the agent's endpoint
			 */
			public void setUri(@Nullable URI uri) {
				this.uri = uri;
			}

			/**
			 * How long connecting may take. Default 10 seconds.
			 * @return the connect timeout
			 */
			public Duration getConnectTimeout() {
				return connectTimeout;
			}

			/**
			 * Sets how long connecting may take.
			 * @param connectTimeout how long connecting may take
			 */
			public void setConnectTimeout(Duration connectTimeout) {
				this.connectTimeout = connectTimeout;
			}

		}

		/** Connects to an agent over Streamable HTTP. */
		@ConfigurationProperties("http")
		public static class Http {

			private @Nullable URI uri;

			/**
			 * The agent's endpoint, such as {@code http://localhost:8080/acp}.
			 * @return the URI, or null when unset
			 */
			public @Nullable URI getUri() {
				return uri;
			}

			/**
			 * Sets the agent's endpoint.
			 * @param uri the agent's endpoint
			 */
			public void setUri(@Nullable URI uri) {
				this.uri = uri;
			}

		}

	}

	/**
	 * The capabilities the client advertises, bound from {@code acp.client.capabilities.*}.
	 * Each is off by default: enable one together with the handler for it, registered
	 * through an {@link AcpClientCustomizer}.
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
		 * Whether to advertise {@code fs/read_text_file}.
		 * @return whether advertised
		 */
		public boolean isReadTextFile() {
			return readTextFile;
		}

		/**
		 * Sets whether to advertise {@code fs/read_text_file}.
		 * @param readTextFile whether to advertise {@code fs/read_text_file}
		 */
		public void setReadTextFile(boolean readTextFile) {
			this.readTextFile = readTextFile;
		}

		/**
		 * Whether to advertise {@code fs/write_text_file}.
		 * @return whether advertised
		 */
		public boolean isWriteTextFile() {
			return writeTextFile;
		}

		/**
		 * Sets whether to advertise {@code fs/write_text_file}.
		 * @param writeTextFile whether to advertise {@code fs/write_text_file}
		 */
		public void setWriteTextFile(boolean writeTextFile) {
			this.writeTextFile = writeTextFile;
		}

		/**
		 * Whether to advertise the terminal methods.
		 * @return whether advertised
		 */
		public boolean isTerminal() {
			return terminal;
		}

		/**
		 * Sets whether to advertise the terminal methods.
		 * @param terminal whether to advertise the terminal methods
		 */
		public void setTerminal(boolean terminal) {
			this.terminal = terminal;
		}

		/**
		 * Whether to advertise form-mode elicitation ({@code elicitation.form}).
		 * @return whether advertised
		 */
		public boolean isElicitationForm() {
			return elicitationForm;
		}

		/**
		 * Sets whether to advertise form-mode elicitation.
		 * @param elicitationForm whether to advertise {@code elicitation.form}
		 */
		public void setElicitationForm(boolean elicitationForm) {
			this.elicitationForm = elicitationForm;
		}

		/**
		 * Whether to advertise URL-mode elicitation ({@code elicitation.url}).
		 * @return whether advertised
		 */
		public boolean isElicitationUrl() {
			return elicitationUrl;
		}

		/**
		 * Sets whether to advertise URL-mode elicitation.
		 * @param elicitationUrl whether to advertise {@code elicitation.url}
		 */
		public void setElicitationUrl(boolean elicitationUrl) {
			this.elicitationUrl = elicitationUrl;
		}

		/**
		 * Whether to advertise boolean session config options
		 * ({@code session.configOptions.boolean}).
		 * @return whether advertised
		 */
		public boolean isBooleanConfigOptions() {
			return booleanConfigOptions;
		}

		/**
		 * Sets whether to advertise boolean session config options.
		 * @param booleanConfigOptions whether to advertise boolean session config options
		 */
		public void setBooleanConfigOptions(boolean booleanConfigOptions) {
			this.booleanConfigOptions = booleanConfigOptions;
		}

	}

}
