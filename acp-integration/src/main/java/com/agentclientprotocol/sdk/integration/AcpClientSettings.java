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
 * How the application's ACP client connects and what it advertises, in a form every framework
 * shares: its timeouts, the transport (a stdio command, a WebSocket URI or a Streamable HTTP URI),
 * and the client capabilities. The framework binds its own configuration onto a
 * {@link #builder()}, or reads plain key/value configuration with
 * {@link #from(SettingsSource, String)}, then asks {@link #hasTransport()} whether to create a
 * client at all, and hands the settings to {@link AcpClientTransports}, {@link AcpClients} and
 * {@link AcpClientHost} (through {@link #closeTimeout()}).
 *
 * <p>A value left unset keeps the SDK's default. The keys, under the framework's prefix (such
 * as {@code spring.acp.client}), and their defaults:
 *
 * <pre>
 * request-timeout                         SDK default, 60s
 * prompt-timeout                          none
 * transport.type                          stdio | websocket | http (else inferred)
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

	/** The default WebSocket connect timeout: 10 seconds. */
	public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Creates the settings, checking that every part other than the timeouts and the type is
	 * present.
	 * @throws NullPointerException if a part other than a timeout or the type is null
	 */
	public AcpClientSettings {
		Objects.requireNonNull(stdio, "stdio");
		Objects.requireNonNull(websocket, "websocket");
		Objects.requireNonNull(http, "http");
		Objects.requireNonNull(capabilities, "capabilities");
	}

	/**
	 * The agent process a stdio client starts: the command, its arguments, and environment
	 * variables added to the environment the process inherits from the application. The
	 * arguments and environment are copied; no element, key or value may be null.
	 * @param command the command, or null when unset
	 * @param args the command's arguments, in order
	 * @param env variables added to the agent's environment, by name
	 */
	public record Stdio(@Nullable String command, List<String> args, Map<String, String> env) {

		/**
		 * Creates a process description, copying the arguments and environment, which must not
		 * be null or hold a null.
		 */
		public Stdio {
			args = List.copyOf(args);
			env = Map.copyOf(env);
		}

	}

	/**
	 * The WebSocket endpoint a WebSocket client connects to.
	 * @param uri the endpoint, such as {@code ws://host:port/acp}, or null when unset
	 * @param connectTimeout how long connecting may take
	 */
	public record WebSocket(@Nullable URI uri, Duration connectTimeout) {

		/**
		 * Creates an endpoint, checking the connect timeout.
		 * @throws NullPointerException if {@code connectTimeout} is null
		 */
		public WebSocket {
			Objects.requireNonNull(connectTimeout, "connectTimeout");
		}

	}

	/**
	 * The Streamable HTTP endpoint a Streamable HTTP client posts to.
	 * @param uri the endpoint, such as {@code http://host:port/acp}, or null when unset
	 */
	public record Http(@Nullable URI uri) {
	}

	/**
	 * The capabilities the client advertises in {@code initialize}, all off by default. Advertise
	 * only what the application handles: turn a capability on together with the handler the
	 * application registers through an {@link AcpClientCustomizer}. Building the client with
	 * {@link AcpClients#async} fails when a capability other than boolean config options has no
	 * handler, naming the setting.
	 * @param readTextFile {@code fs/read_text_file}
	 * @param writeTextFile {@code fs/write_text_file}
	 * @param terminal the {@code terminal/*} methods
	 * @param elicitationForm form-mode {@code elicitation/create}
	 * @param elicitationUrl URL-mode {@code elicitation/create}
	 * @param booleanConfigOptions boolean session config options
	 */
	public record Capabilities(boolean readTextFile, boolean writeTextFile, boolean terminal, boolean elicitationForm,
			boolean elicitationUrl, boolean booleanConfigOptions) {

		/** No capability advertised: the default. */
		public static final Capabilities NONE = new Capabilities(false, false, false, false, false, false);

		/**
		 * Returns the capabilities as the protocol writes them in {@code initialize}: the file
		 * system and terminal flags always, an elicitation object only when a mode is on, and
		 * the session capability for boolean config options only when that is on.
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
	 * Returns whether any transport setting is present: a type, a stdio command, or a URI. A
	 * framework creates no client when none is (an agent-only application). True does not mean
	 * the settings are complete; {@link AcpClientTransports#create} checks that.
	 * @return true when a transport is configured
	 */
	public boolean hasTransport() {
		return transport != null || stdio.command() != null || websocket.uri() != null || http.uri() != null;
	}

	/**
	 * Returns how long closing the client may wait, for {@link AcpClientHost#close(Duration)}:
	 * the request timeout (the SDK default, 60 seconds, when unset), which bounds the delivery of
	 * pending session updates, plus a margin of 10 seconds.
	 * @return the close timeout
	 */
	public Duration closeTimeout() {
		return (requestTimeout != null ? requestTimeout : Duration.ofSeconds(60)).plusSeconds(10);
	}

	/**
	 * Returns a builder holding every default: no transport, no capability, a 10-second
	 * WebSocket connect timeout, and the SDK's defaults for the rest.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Reads the settings under a prefix of key/value configuration, for a framework without typed
	 * binding. Keys are in kebab case, such as {@code acp.client.transport.stdio.command} for
	 * prefix {@code acp.client}; the arguments and environment are read with
	 * {@link SettingsSource#list} and {@link SettingsSource#map}. A blank value counts as unset.
	 * Durations and booleans are read as {@link AcpAgentSettings#from} reads them; URIs must
	 * parse as {@link URI}s.
	 * @param source the configuration
	 * @param prefix the prefix of the client's keys, such as {@code acp.client}
	 * @return the settings
	 * @throws IllegalArgumentException if a value does not parse; the message names the full key
	 * and the value
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

	/**
	 * Builds {@link AcpClientSettings}, flat: the transport's parts are set here directly, and
	 * {@link #build()} groups them. Every setting has a default, so a framework sets only what
	 * the user configured. Not safe for use from several threads.
	 */
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
		 * Sets how long the client waits for the agent to answer a request.
		 * @param requestTimeout the timeout, or null for the SDK default (60 seconds)
		 * @return this builder
		 */
		public Builder requestTimeout(@Nullable Duration requestTimeout) {
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets how long a prompt may wait for its answer; a prompt is not bound by the request
		 * timeout.
		 * @param promptTimeout the timeout, or null for none
		 * @return this builder
		 */
		public Builder promptTimeout(@Nullable Duration promptTimeout) {
			this.promptTimeout = promptTimeout;
			return this;
		}

		/**
		 * Sets the transport explicitly; it then needs its command or URI.
		 * @param transport the transport, or null to infer it from the one configured
		 * @return this builder
		 */
		public Builder transport(@Nullable AcpTransportType transport) {
			this.transport = transport;
			return this;
		}

		/**
		 * Sets the command that starts the agent process; with no explicit transport, setting it
		 * selects stdio.
		 * @param command the command, or null for none
		 * @return this builder
		 */
		public Builder stdioCommand(@Nullable String command) {
			this.stdioCommand = command;
			return this;
		}

		/**
		 * Sets the agent process's arguments, replacing any set before.
		 * @param args the arguments, in order; no element may be null
		 * @return this builder
		 */
		public Builder stdioArgs(List<String> args) {
			this.stdioArgs = List.copyOf(args);
			return this;
		}

		/**
		 * Sets the variables added to the environment the agent process inherits, replacing any
		 * set before.
		 * @param env the variables, by name; no key or value may be null
		 * @return this builder
		 */
		public Builder stdioEnv(Map<String, String> env) {
			this.stdioEnv = new LinkedHashMap<>(env);
			return this;
		}

		/**
		 * Sets the WebSocket endpoint; with no explicit transport, setting it selects WebSocket.
		 * @param uri the endpoint, or null for none
		 * @return this builder
		 */
		public Builder websocketUri(@Nullable URI uri) {
			this.websocketUri = uri;
			return this;
		}

		/**
		 * Sets how long connecting over WebSocket may take. Default 10 seconds.
		 * @param connectTimeout the timeout, not null
		 * @return this builder
		 */
		public Builder websocketConnectTimeout(Duration connectTimeout) {
			this.websocketConnectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
			return this;
		}

		/**
		 * Sets the Streamable HTTP endpoint; with no explicit transport, setting it selects
		 * Streamable HTTP.
		 * @param uri the endpoint, or null for none
		 * @return this builder
		 */
		public Builder httpUri(@Nullable URI uri) {
			this.httpUri = uri;
			return this;
		}

		/**
		 * Sets the capabilities the client advertises. Default {@link Capabilities#NONE}.
		 * @param capabilities the capabilities, not null
		 * @return this builder
		 */
		public Builder capabilities(Capabilities capabilities) {
			this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
			return this;
		}

		/**
		 * Builds the settings from the values set so far. The builder can be used again.
		 * @return the settings
		 */
		public AcpClientSettings build() {
			return new AcpClientSettings(requestTimeout, promptTimeout, transport,
					new Stdio(stdioCommand, stdioArgs, stdioEnv), new WebSocket(websocketUri, websocketConnectTimeout),
					new Http(httpUri), capabilities);
		}

	}

}
