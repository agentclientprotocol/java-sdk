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

import com.agentclientprotocol.sdk.integration.AcpTransportType;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * The {@code quarkus.acp.*} configuration read when the application starts: the agent's timeouts
 * and HTTP endpoint limits, and the ACP client's transport, timeouts and capabilities. Set these in
 * {@code application.properties} or any Quarkus configuration source; they can change without a
 * rebuild. What is fixed at build time (whether the agent is served, its transport and path) is in
 * {@link AcpBuildTimeConfig}.
 *
 * <p>An unset optional value keeps the SDK's default, which each property names. Durations take
 * Quarkus' forms ({@code 30s}, {@code 500ms}, {@code PT1M}); sizes take {@code MemorySize} forms
 * ({@code 16M}). The properties map onto {@code AcpAgentSettings} and {@code AcpClientSettings} of
 * {@code acp-integration}, the framework-neutral settings the Spring Boot and Micronaut
 * integrations fill from their own keys, so each one means the same there. The module's README
 * lists every property.
 *
 * @author Mark Pollack
 */
@ConfigMapping(prefix = "quarkus.acp")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface AcpRuntimeConfig {

	/**
	 * Returns the agent's run-time properties, {@code quarkus.acp.agent.*}.
	 * @return the agent settings
	 */
	Agent agent();

	/**
	 * Returns the client's properties, {@code quarkus.acp.client.*}.
	 * @return the client settings
	 */
	Client client();

	/** The {@code quarkus.acp.agent.*} properties read at startup. */
	interface Agent {

		/**
		 * How long the agent waits for the client to answer a request it sent, such as a permission
		 * request or a file read. Unset keeps the SDK default, 60 seconds. Maps to
		 * {@code AcpAgentSupport.Builder.requestTimeout}.
		 * @return the request timeout
		 */
		Optional<Duration> requestTimeout();

		/**
		 * How long a cancelled prompt may run before the SDK answers it {@code cancelled} itself:
		 * the time a {@code @Prompt} method has to return after the client sends
		 * {@code session/cancel}, after which its thread is interrupted. Unset keeps the SDK
		 * default, 60 seconds; zero for no limit. Maps to
		 * {@code AcpAgentSupport.Builder.cancelGracePeriod}.
		 * @return the cancel grace period
		 */
		Optional<Duration> cancelGracePeriod();

		/**
		 * The longest a prompt turn may run before the agent answers it with error {@code -32800}
		 * (request cancelled). Unset keeps the SDK default, no limit; zero also means no limit.
		 * Maps to {@code AcpAgentSupport.Builder.maxPromptDuration}.
		 * @return the maximum prompt duration
		 */
		Optional<Duration> maxPromptDuration();

		/**
		 * Whether the application exits when the stdio transport ends: the client closed standard
		 * input and every answer has been written. It applies to the single transport (stdio, or an
		 * {@code AcpAgentTransport} bean of the application's own), not to HTTP. Maps to
		 * {@code AcpAgentSettings.shutdownOnTransportEnd}.
		 * @return whether to exit on transport end
		 */
		@WithDefault("true")
		boolean shutdownOnTransportEnd();

		/**
		 * Returns the agent transport's run-time properties, {@code quarkus.acp.agent.transport.*}.
		 * @return the transport settings
		 */
		AgentTransport transport();

	}

	/** The {@code quarkus.acp.agent.transport.*} properties read at startup. */
	interface AgentTransport {

		/**
		 * Returns the Streamable HTTP endpoint's limits,
		 * {@code quarkus.acp.agent.transport.http.*}. The port is the Quarkus HTTP port
		 * ({@code quarkus.http.port}).
		 * @return the HTTP settings
		 */
		AgentHttp http();

	}

	/**
	 * The {@code quarkus.acp.agent.transport.http.*} properties: the limits of the Streamable HTTP
	 * and WebSocket endpoint served when {@code quarkus.acp.agent.transport.type} is {@code http}.
	 * They bound every buffer the endpoint keeps for a client; each unset value keeps the SDK
	 * default, and together they map to the SDK's {@code StreamableHttpAcpAgentTransportOptions}.
	 * The HTTP/2 stream limit is the Quarkus server's own,
	 * {@code quarkus.http.limits.max-concurrent-streams}.
	 */
	interface AgentHttp {

		/**
		 * The largest POST body and WebSocket text message accepted from a client. A larger POST
		 * body is answered 413, and a larger WebSocket message closes the connection. Unset keeps
		 * the SDK default, 16M. The Quarkus server has limits of its own, which an HTTP build
		 * raises to 16 MB ({@code quarkus.http.limits.max-body-size},
		 * {@code quarkus.http.websocket-server.max-message-size} and {@code max-frame-size}): raise
		 * them too when you raise this past 16 MB.
		 * @return the inbound size limit
		 */
		Optional<MemorySize> maxPostBodySize();

		/**
		 * How often an open SSE stream gets a keep-alive comment, which stops proxies from cutting
		 * idle streams; zero turns them off. Unset keeps the SDK default, 15 seconds.
		 * @return the keep-alive interval
		 */
		Optional<Duration> keepAliveInterval();

		/**
		 * How many events one SSE stream keeps unsent while no client is reading it, to send when
		 * the client reconnects; one more closes the connection. Unset keeps the SDK default, 1024.
		 * @return the mailbox capacity
		 */
		OptionalInt mailboxCapacity();

		/**
		 * How many events are queued for a client that is reading an SSE stream; one more detaches
		 * that client and keeps the events for its next GET. Unset keeps the SDK default, 1024.
		 * @return the pending SSE event limit
		 */
		OptionalInt maxPendingSseEvents();

		/**
		 * How many WebSocket frames a connection queues for sending; one more closes the
		 * connection. Unset keeps the SDK default, 1024.
		 * @return the pending WebSocket frame limit
		 */
		OptionalInt maxWebSocketPendingFrames();

		/**
		 * How many session streams one connection may open before the agent knows the session, as a
		 * client does before {@code session/load}; a further one is refused. Unset keeps the SDK
		 * default, 64.
		 * @return the provisional session limit
		 */
		OptionalInt maxProvisionalSessions();

		/**
		 * How long a graceful shutdown waits for the connections to close before it closes them at
		 * once. When the application stops, every HTTP and WebSocket connection is closed first,
		 * within this timeout. Unset keeps the SDK default, 5 seconds.
		 * @return the shutdown timeout
		 */
		Optional<Duration> shutdownTimeout();

	}

	/**
	 * The {@code quarkus.acp.client.*} properties: the ACP client beans ({@code AcpAsyncClient},
	 * {@code AcpSyncClient}) the application can inject. They are used when a client bean is first
	 * injected; an application that injects none needs no client properties.
	 */
	interface Client {

		/**
		 * How long the client waits for the agent to answer a request other than a prompt. Unset
		 * keeps the SDK default, 60 seconds. Closing the client when the application stops also
		 * waits at most this long plus 10 seconds. Maps to
		 * {@code AcpClient.AsyncSpec.requestTimeout}.
		 * @return the request timeout
		 */
		Optional<Duration> requestTimeout();

		/**
		 * How long a prompt turn may take before the client cancels it: {@code prompt} then fails
		 * with a {@code TimeoutException} and the client sends the agent a
		 * {@code $/cancel_request}. Unset (the default) for no limit, and zero also means none:
		 * prompts are not bound by the request timeout. Maps to
		 * {@code AcpClient.AsyncSpec.promptTimeout}.
		 * @return the prompt timeout
		 */
		Optional<Duration> promptTimeout();

		/**
		 * Returns the client transport's properties, {@code quarkus.acp.client.transport.*}.
		 * @return the transport settings
		 */
		ClientTransport transport();

		/**
		 * Returns the advertised capabilities, {@code quarkus.acp.client.capabilities.*}.
		 * @return the capabilities
		 */
		Capabilities capabilities();

	}

	/**
	 * The {@code quarkus.acp.client.transport.*} properties: which transport the client uses, and
	 * each transport's own settings. Set the command or URI of one transport and it is chosen.
	 */
	interface ClientTransport {

		/**
		 * The transport: {@code stdio}, {@code websocket} or {@code http}. Unset, it is the one of
		 * {@code stdio.command}, {@code websocket.uri} and {@code http.uri} that is set; with
		 * several set, it must be given. A type that is set wins, and creating the client fails
		 * when its own command or URI is missing, naming that property.
		 * @return the transport type
		 */
		Optional<AcpTransportType> type();

		/**
		 * Returns the stdio transport's properties, {@code quarkus.acp.client.transport.stdio.*}.
		 * @return the stdio settings
		 */
		Stdio stdio();

		/**
		 * Returns the WebSocket transport's properties,
		 * {@code quarkus.acp.client.transport.websocket.*}.
		 * @return the WebSocket settings
		 */
		WebSocket websocket();

		/**
		 * Returns the Streamable HTTP transport's properties,
		 * {@code quarkus.acp.client.transport.http.*}.
		 * @return the HTTP settings
		 */
		Http http();

	}

	/**
	 * The {@code quarkus.acp.client.transport.stdio.*} properties: the agent process a stdio client
	 * launches when the client is created, then talks to over the process's standard input and
	 * output.
	 */
	interface Stdio {

		/**
		 * The command that starts the agent, such as an executable on the PATH. Setting it selects
		 * the stdio transport. Maps to {@code AcpClientSettings.Stdio.command}.
		 * @return the command
		 */
		Optional<String> command();

		/**
		 * The command's arguments, in order, such as {@code --acp}; a comma separates several.
		 * Default none.
		 * @return the arguments
		 */
		Optional<List<String>> args();

		/**
		 * Environment variables added to the agent process, one property per variable
		 * ({@code quarkus.acp.client.transport.stdio.env.<NAME>}). The process inherits the
		 * application's whole environment, and these add to it or replace a variable of the same
		 * name. Default none.
		 * @return the environment
		 */
		Map<String, String> env();

	}

	/** The {@code quarkus.acp.client.transport.websocket.*} properties: the agent's WebSocket endpoint. */
	interface WebSocket {

		/**
		 * The endpoint, a {@code ws://} or {@code wss://} URI such as
		 * {@code ws://localhost:8080/acp}. Setting it selects the WebSocket transport.
		 * @return the URI
		 */
		Optional<URI> uri();

		/**
		 * How long the client waits for the WebSocket to connect, the handshake. Default 10
		 * seconds, which replaces the transport's own default. Maps to
		 * {@code WebSocketAcpClientTransport.connectTimeout}.
		 * @return the connect timeout
		 */
		@WithDefault("10s")
		Duration connectTimeout();

	}

	/** The {@code quarkus.acp.client.transport.http.*} properties: the agent's Streamable HTTP endpoint. */
	interface Http {

		/**
		 * The endpoint, an {@code http://} or {@code https://} URI such as
		 * {@code http://localhost:8080/acp}. Setting it selects the Streamable HTTP transport.
		 * @return the URI
		 */
		Optional<URI> uri();

	}

	/**
	 * The {@code quarkus.acp.client.capabilities.*} properties: the client capabilities advertised
	 * in {@code initialize}, all off by default. Advertise only what the application registers
	 * handlers for, through an {@code AcpClientCustomizer} bean; a capability turned on without its
	 * handler makes creating the client fail, naming the property. Together they map to
	 * {@code AcpClientSettings.Capabilities}.
	 */
	interface Capabilities {

		/**
		 * Whether the client advertises that it serves {@code fs/read_text_file}. Needs a read-file
		 * handler.
		 * @return the capability
		 */
		@WithDefault("false")
		boolean readTextFile();

		/**
		 * Whether the client advertises that it serves {@code fs/write_text_file}. Needs a
		 * write-file handler.
		 * @return the capability
		 */
		@WithDefault("false")
		boolean writeTextFile();

		/**
		 * Whether the client advertises that it serves the {@code terminal/*} methods. Needs the
		 * terminal handlers.
		 * @return the capability
		 */
		@WithDefault("false")
		boolean terminal();

		/**
		 * Whether the client advertises that it serves form-mode {@code elicitation/create}. Needs
		 * an elicitation handler.
		 * @return the capability
		 */
		@WithDefault("false")
		boolean elicitationForm();

		/**
		 * Whether the client advertises that it serves URL-mode {@code elicitation/create}. Needs
		 * an elicitation handler.
		 * @return the capability
		 */
		@WithDefault("false")
		boolean elicitationUrl();

		/**
		 * Whether the client advertises that it accepts boolean session config options. Needs no
		 * handler.
		 * @return the capability
		 */
		@WithDefault("false")
		boolean booleanConfigOptions();

	}

}
