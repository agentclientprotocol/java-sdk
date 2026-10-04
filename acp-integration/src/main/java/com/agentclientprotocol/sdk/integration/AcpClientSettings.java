/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.jspecify.annotations.Nullable;

/**
 * How the application's ACP client connects, whatever framework hosts it. A framework binds its
 * own configuration onto a {@link #builder()} (or calls {@link #from(SettingsSource, String)});
 * a value left unset keeps the SDK's default.
 *
 * <pre>
 * request-timeout                         SDK default
 * prompt-timeout                          none
 * transport.type                          stdio | websocket | http (else inferred, see AcpClientTransports)
 * transport.stdio.command, args, env      the agent process to start
 * transport.websocket.uri                 ws://host:port/acp
 * transport.websocket.connect-timeout     10s
 * transport.http.uri                      http://host:port/acp
 * capabilities.read-text-file, write-text-file, terminal, elicitation-form, elicitation-url,
 *     boolean-config-options              false
 * </pre>
 *
 * @param requestTimeout how long the client waits for the agent to answer a request; null for
 * the SDK default
 * @param promptTimeout how long a prompt may wait for its answer, apart from the request timeout;
 * null for none
 * @param transport the transport, or null to infer it from the one transport configured
 * @param stdio the agent process a stdio client starts
 * @param websocket the WebSocket endpoint
 * @param http the Streamable HTTP endpoint
 * @param capabilities the capabilities the client advertises
 */
public record AcpClientSettings(@Nullable Duration requestTimeout, @Nullable Duration promptTimeout,
		@Nullable AcpTransportType transport, Stdio stdio, WebSocket websocket, Http http, Capabilities capabilities) {

	/** The default WebSocket connect timeout. */
	public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Settings, each part checked.
	 * @throws NullPointerException if a part other than a timeout or the type is null
	 */
	public AcpClientSettings {
		Objects.requireNonNull(stdio, "stdio");
		Objects.requireNonNull(websocket, "websocket");
		Objects.requireNonNull(http, "http");
		Objects.requireNonNull(capabilities, "capabilities");
	}

	/**
	 * The agent process a stdio client starts.
	 * @param command the command, or null when unset
	 * @param args the command's arguments
	 * @param env variables added to the agent's environment
	 */
	public record Stdio(@Nullable String command, List<String> args, Map<String, String> env) {

		/**
		 * A process, its arguments and environment copied.
		 */
		public Stdio {
			args = List.copyOf(args);
			env = Map.copyOf(env);
		}

	}

	/**
	 * A WebSocket endpoint.
	 * @param uri the endpoint, or null when unset
	 * @param connectTimeout how long connecting may take
	 */
	public record WebSocket(@Nullable URI uri, Duration connectTimeout) {

		/**
		 * An endpoint, the connect timeout checked.
		 * @throws NullPointerException if {@code connectTimeout} is null
		 */
		public WebSocket {
			Objects.requireNonNull(connectTimeout, "connectTimeout");
		}

	}

	/**
	 * A Streamable HTTP endpoint.
	 * @param uri the endpoint, or null when unset
	 */
	public record Http(@Nullable URI uri) {
	}

	/**
	 * The capabilities the client advertises in {@code initialize}, all off by default. Advertise
	 * only what the application registers handlers for: turn a capability on together with the
	 * handler the application provides (through an {@link AcpClientCustomizer}).
	 * @param readTextFile {@code fs/read_text_file}
	 * @param writeTextFile {@code fs/write_text_file}
	 * @param terminal the {@code terminal/*} methods
	 * @param elicitationForm form-mode {@code elicitation/create}
	 * @param elicitationUrl URL-mode {@code elicitation/create}
	 * @param booleanConfigOptions boolean session config options
	 */
	public record Capabilities(boolean readTextFile, boolean writeTextFile, boolean terminal, boolean elicitationForm,
			boolean elicitationUrl, boolean booleanConfigOptions) {

		/** None advertised. */
		public static final Capabilities NONE = new Capabilities(false, false, false, false, false, false);

		/**
		 * The capabilities as the protocol writes them.
		 * @return the client capabilities
		 */
		public AcpSchema.ClientCapabilities toClientCapabilities() {
			AcpSchema.ElicitationCapabilities elicitation = null;
			if (elicitationForm || elicitationUrl) {
				elicitation = new AcpSchema.ElicitationCapabilities(
						elicitationForm ? new AcpSchema.ElicitationFormCapabilities() : null,
						elicitationUrl ? new AcpSchema.ElicitationUrlCapabilities() : null, null);
			}
			return new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(readTextFile, writeTextFile),
					terminal, booleanConfigOptions ? AcpSchema.ClientSessionCapabilities.withBooleanConfigOptions() : null,
					null, elicitation, null);
		}

	}

	/**
	 * Whether any transport setting is present: a type, a command or a URI. A framework creates
	 * no client when none is.
	 * @return true when a transport is configured
	 */
	public boolean hasTransport() {
		return transport != null || stdio.command() != null || websocket.uri() != null || http.uri() != null;
	}

	/**
	 * How long closing the client may wait: its request timeout (the SDK default, 30 seconds,
	 * when unset), which bounds the delivery of pending notifications, plus a margin.
	 * @return the close timeout
	 */
	public Duration closeTimeout() {
		return (requestTimeout != null ? requestTimeout : Duration.ofSeconds(30)).plusSeconds(10);
	}

	/**
	 * A builder with every default.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * The settings under a prefix of key/value configuration, keys in kebab case, such as
	 * {@code acp.client.transport.stdio.command} for prefix {@code acp.client}. Durations are
	 * ISO-8601 or an amount with a unit ({@code 30s}).
	 * @param source the configuration
	 * @param prefix the prefix of the client's keys, such as {@code acp.client}
	 * @return the settings
	 * @throws IllegalArgumentException naming the key, if a value does not parse
	 */
	public static AcpClientSettings from(SettingsSource source, String prefix) {
		SettingsReader read = new SettingsReader(source, prefix);
		Builder builder = builder().requestTimeout(read.duration("request-timeout"))
			.promptTimeout(read.duration("prompt-timeout"))
			.transport(read.transportType("transport.type"))
			.stdioCommand(read.string("transport.stdio.command"))
			.stdioArgs(read.list("transport.stdio.args"))
			.stdioEnv(read.map("transport.stdio.env"))
			.websocketUri(read.uri("transport.websocket.uri"))
			.httpUri(read.uri("transport.http.uri"))
			.capabilities(new Capabilities(read.bool("capabilities.read-text-file", false),
					read.bool("capabilities.write-text-file", false), read.bool("capabilities.terminal", false),
					read.bool("capabilities.elicitation-form", false), read.bool("capabilities.elicitation-url", false),
					read.bool("capabilities.boolean-config-options", false)));
		Duration connectTimeout = read.duration("transport.websocket.connect-timeout");
		if (connectTimeout != null) {
			builder.websocketConnectTimeout(connectTimeout);
		}
		return builder.build();
	}

	/** Builds {@link AcpClientSettings}; every setting has a default. */
	public static final class Builder {

		private @Nullable Duration requestTimeout;

		private @Nullable Duration promptTimeout;

		private @Nullable AcpTransportType transport;

		private @Nullable String stdioCommand;

		private List<String> stdioArgs = List.of();

		private Map<String, String> stdioEnv = Map.of();

		private @Nullable URI websocketUri;

		private Duration websocketConnectTimeout = DEFAULT_CONNECT_TIMEOUT;

		private @Nullable URI httpUri;

		private Capabilities capabilities = Capabilities.NONE;

		private Builder() {
		}

		/**
		 * Sets the client's request timeout.
		 * @param requestTimeout the timeout, or null for the SDK default
		 * @return this builder
		 */
		public Builder requestTimeout(@Nullable Duration requestTimeout) {
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets the prompt timeout.
		 * @param promptTimeout the timeout, or null for none
		 * @return this builder
		 */
		public Builder promptTimeout(@Nullable Duration promptTimeout) {
			this.promptTimeout = promptTimeout;
			return this;
		}

		/**
		 * Sets the transport.
		 * @param transport the transport, or null to infer it
		 * @return this builder
		 */
		public Builder transport(@Nullable AcpTransportType transport) {
			this.transport = transport;
			return this;
		}

		/**
		 * Sets the stdio agent's command.
		 * @param command the command, or null for none
		 * @return this builder
		 */
		public Builder stdioCommand(@Nullable String command) {
			this.stdioCommand = command;
			return this;
		}

		/**
		 * Sets the stdio agent's arguments.
		 * @param args the arguments
		 * @return this builder
		 */
		public Builder stdioArgs(List<String> args) {
			this.stdioArgs = List.copyOf(args);
			return this;
		}

		/**
		 * Sets variables added to the stdio agent's environment.
		 * @param env the variables
		 * @return this builder
		 */
		public Builder stdioEnv(Map<String, String> env) {
			this.stdioEnv = new LinkedHashMap<>(env);
			return this;
		}

		/**
		 * Sets the WebSocket endpoint.
		 * @param uri the endpoint, or null for none
		 * @return this builder
		 */
		public Builder websocketUri(@Nullable URI uri) {
			this.websocketUri = uri;
			return this;
		}

		/**
		 * Sets how long connecting over WebSocket may take. Default 10 seconds.
		 * @param connectTimeout the timeout
		 * @return this builder
		 */
		public Builder websocketConnectTimeout(Duration connectTimeout) {
			this.websocketConnectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
			return this;
		}

		/**
		 * Sets the Streamable HTTP endpoint.
		 * @param uri the endpoint, or null for none
		 * @return this builder
		 */
		public Builder httpUri(@Nullable URI uri) {
			this.httpUri = uri;
			return this;
		}

		/**
		 * Sets the advertised capabilities. Default none.
		 * @param capabilities the capabilities
		 * @return this builder
		 */
		public Builder capabilities(Capabilities capabilities) {
			this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
			return this;
		}

		/**
		 * The settings.
		 * @return the settings
		 */
		public AcpClientSettings build() {
			return new AcpClientSettings(requestTimeout, promptTimeout, transport,
					new Stdio(stdioCommand, stdioArgs, stdioEnv), new WebSocket(websocketUri, websocketConnectTimeout),
					new Http(httpUri), capabilities);
		}

	}

}
