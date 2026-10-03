/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * The ACP settings read when the application starts: the agent's timeouts and HTTP
 * limits, and the client bean's transport and capabilities. An unset optional value
 * keeps the SDK's default.
 *
 * @author Mark Pollack
 */
@ConfigMapping(prefix = "quarkus.acp")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface AcpRuntimeConfig {

	/**
	 * The agent side.
	 * @return the agent settings
	 */
	Agent agent();

	/**
	 * The client side.
	 * @return the client settings
	 */
	Client client();

	/** Agent settings read at startup. */
	interface Agent {

		/**
		 * How long the agent waits for the client to answer a request it sent. Unset keeps
		 * the SDK default.
		 * @return the request timeout
		 */
		Optional<Duration> requestTimeout();

		/**
		 * How long a cancelled prompt may run before the SDK answers it {@code cancelled}
		 * itself. Unset keeps the SDK default.
		 * @return the cancel grace period
		 */
		Optional<Duration> cancelGracePeriod();

		/**
		 * The longest a prompt turn may run before it is answered with a request timeout.
		 * Unset keeps the SDK default (no limit).
		 * @return the maximum prompt duration
		 */
		Optional<Duration> maxPromptDuration();

		/**
		 * Whether the application exits when the stdio transport ends (the client closed
		 * standard input).
		 * @return whether to exit on transport end
		 */
		@WithDefault("true")
		boolean shutdownOnTransportEnd();

		/**
		 * The agent transport.
		 * @return the transport settings
		 */
		AgentTransport transport();

	}

	/** The agent transport read at startup. */
	interface AgentTransport {

		/**
		 * The Streamable HTTP endpoint's limits. The port is the Quarkus HTTP port
		 * ({@code quarkus.http.port}).
		 * @return the HTTP settings
		 */
		AgentHttp http();

	}

	/** The Streamable HTTP endpoint's limits; each unset value keeps the SDK default. */
	interface AgentHttp {

		/**
		 * The largest POST body and WebSocket message accepted. Also raise
		 * {@code quarkus.http.limits.max-body-size} when you raise it past that.
		 * @return the inbound size limit
		 */
		Optional<MemorySize> maxPostBodySize();

		/**
		 * How often an idle SSE stream gets a keep-alive comment.
		 * @return the keep-alive interval
		 */
		Optional<Duration> keepAliveInterval();

		/**
		 * How many inbound messages a connection queues before it pushes back.
		 * @return the mailbox capacity
		 */
		OptionalInt mailboxCapacity();

		/**
		 * How many SSE events a stream holds unconfirmed.
		 * @return the pending SSE event limit
		 */
		OptionalInt maxPendingSseEvents();

		/**
		 * How many WebSocket frames a connection queues for sending.
		 * @return the pending WebSocket frame limit
		 */
		OptionalInt maxWebSocketPendingFrames();

		/**
		 * How many provisional session streams the endpoint holds open.
		 * @return the provisional session limit
		 */
		OptionalInt maxProvisionalSessions();

		/**
		 * How long a graceful shutdown waits for the connections to close before it closes
		 * them at once.
		 * @return the shutdown timeout
		 */
		Optional<Duration> shutdownTimeout();

	}

	/** The ACP client bean. */
	interface Client {

		/**
		 * How long the client waits for the agent to answer a request. Unset keeps the SDK
		 * default.
		 * @return the request timeout
		 */
		Optional<Duration> requestTimeout();

		/**
		 * How long a prompt turn may take before the client cancels it. Unset (the default) for
		 * no limit: prompts are not bound by the request timeout.
		 * @return the prompt timeout
		 */
		Optional<Duration> promptTimeout();

		/**
		 * The client transport.
		 * @return the transport settings
		 */
		ClientTransport transport();

		/**
		 * The capabilities the client advertises.
		 * @return the capabilities
		 */
		Capabilities capabilities();

	}

	/** The client transport. */
	interface ClientTransport {

		/**
		 * The transport. Unset, it is inferred: WebSocket when {@code websocket.uri} is set,
		 * else HTTP when {@code http.uri} is set, else stdio when {@code stdio.command} is set.
		 * @return the transport type
		 */
		Optional<ClientTransportType> type();

		/**
		 * The agent process to launch.
		 * @return the stdio settings
		 */
		Stdio stdio();

		/**
		 * The WebSocket endpoint.
		 * @return the WebSocket settings
		 */
		WebSocket websocket();

		/**
		 * The Streamable HTTP endpoint.
		 * @return the HTTP settings
		 */
		Http http();

	}

	/** The agent process a stdio client launches. */
	interface Stdio {

		/**
		 * The command that starts the agent.
		 * @return the command
		 */
		Optional<String> command();

		/**
		 * The command's arguments.
		 * @return the arguments
		 */
		Optional<List<String>> args();

		/**
		 * Environment variables added to the agent process.
		 * @return the environment
		 */
		Map<String, String> env();

	}

	/** A WebSocket endpoint. */
	interface WebSocket {

		/**
		 * The endpoint, a {@code ws://} or {@code wss://} URI.
		 * @return the URI
		 */
		Optional<URI> uri();

		/**
		 * How long the client waits for the WebSocket to connect.
		 * @return the connect timeout
		 */
		@WithDefault("10s")
		Duration connectTimeout();

	}

	/** A Streamable HTTP endpoint. */
	interface Http {

		/**
		 * The endpoint, an {@code http://} or {@code https://} URI.
		 * @return the URI
		 */
		Optional<URI> uri();

	}

	/**
	 * The client capabilities advertised in {@code initialize}. Advertise only what the
	 * application registers handlers for, through an {@code AcpClientCustomizer}.
	 */
	interface Capabilities {

		/**
		 * Whether the client serves {@code fs/read_text_file}.
		 * @return the capability
		 */
		@WithDefault("false")
		boolean readTextFile();

		/**
		 * Whether the client serves {@code fs/write_text_file}.
		 * @return the capability
		 */
		@WithDefault("false")
		boolean writeTextFile();

		/**
		 * Whether the client serves the {@code terminal/*} methods.
		 * @return the capability
		 */
		@WithDefault("false")
		boolean terminal();

		/**
		 * Whether the client serves form-mode {@code elicitation/create}.
		 * @return the capability
		 */
		@WithDefault("false")
		boolean elicitationForm();

		/**
		 * Whether the client serves URL-mode {@code elicitation/create}.
		 * @return the capability
		 */
		@WithDefault("false")
		boolean elicitationUrl();

		/**
		 * Whether the client accepts boolean session config options.
		 * @return the capability
		 */
		@WithDefault("false")
		boolean booleanConfigOptions();

	}

}
