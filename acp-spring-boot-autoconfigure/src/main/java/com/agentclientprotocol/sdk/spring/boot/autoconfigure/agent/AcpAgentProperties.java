/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.agentclientprotocol.sdk.integration.AcpAgentSettings;
import com.agentclientprotocol.sdk.integration.AcpTransportType;
import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * The {@code spring.acp.agent.*} configuration properties: how the application's {@code @AcpAgent}
 * bean is served. They choose the transport (stdio by default, or Streamable HTTP), set the agent's
 * timeouts and the executor its handler methods run on, and bound the HTTP endpoint. Set them in
 * {@code application.properties} or {@code application.yaml}; the agent autoconfiguration
 * ({@link AcpAgentAutoConfiguration}, {@link AcpAgentHttpAutoConfiguration}) reads them through
 * {@link #toSettings()}.
 *
 * <p>Most properties have no default of their own: a property left unset keeps the SDK's default,
 * which its getter names, so the agent behaves as it would without Spring. Durations take Spring's
 * forms ({@code 30s}, {@code 500ms}, {@code PT1M}) and sizes Spring's {@link DataSize} forms
 * ({@code 16MB}). A value the SDK refuses, such as a negative duration or a limit that is not
 * positive, fails the application's startup when the agent is built.
 *
 * <p>The properties map onto {@link AcpAgentSettings}, the framework-neutral settings the Micronaut
 * and Quarkus integrations fill from their own keys, so each property means the same there. The
 * timeouts and the handler executor end up on the annotated agent's builder,
 * {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport.Builder}.
 */
@ConfigurationProperties(prefix = "spring.acp.agent")
public class AcpAgentProperties {

	/**
	 * Whether to serve the application's @AcpAgent bean. Default true.
	 */
	private boolean enabled = true;

	/**
	 * How long a request the agent sends to the client waits for its answer. Unset keeps the SDK
	 * default, 60 seconds.
	 */
	private @Nullable Duration requestTimeout;

	/**
	 * How long a @Prompt method has to return after session/cancel before the agent answers the
	 * prompt "cancelled" itself. Unset keeps the SDK default, 60 seconds; zero for none.
	 */
	private @Nullable Duration cancelGracePeriod;

	/**
	 * How long a prompt may run before the agent answers it with error -32800. Unset keeps the SDK
	 * default, no limit; zero for none.
	 */
	private @Nullable Duration maxPromptDuration;

	/**
	 * Whether to close the application context when the stdio transport ends on its own. It ends
	 * when the client closes the agent's input; closing lets a keep-alive application exit. Default
	 * true.
	 */
	private boolean shutdownOnTransportEnd = true;

	/**
	 * The executor the agent's handler methods run on: the name of an Executor bean, or
	 * "none" for the SDK's pool of platform threads. Unset: the context's
	 * applicationTaskExecutor when spring.threads.virtual.enabled=true (virtual threads, JDK 21
	 * and later), else the SDK's pool of platform threads.
	 */
	private @Nullable String handlerExecutor;

	private AgentTransportProperties transport = new AgentTransportProperties();

	/**
	 * Returns whether the application's {@code @AcpAgent} bean is served
	 * ({@code spring.acp.agent.enabled}). Default {@code true}; maps to
	 * {@link AcpAgentSettings#enabled()}.
	 *
	 * <p>With {@code false} the agent autoconfiguration creates nothing: no agent factory, no stdio
	 * transport, no HTTP endpoint, and no agent on an {@code AcpAgentTransport} bean the application
	 * defines itself. More than one {@code @AcpAgent} bean is then no error.
	 * @return whether the agent is served
	 */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Sets {@code spring.acp.agent.enabled}; see {@link #isEnabled()}.
	 * @param enabled whether the agent is served
	 */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * Returns how long a request the agent sends to the client, such as a permission request or a
	 * file read, waits for its answer ({@code spring.acp.agent.request-timeout}). Default: unset,
	 * which keeps the SDK's default of 60 seconds. Maps to
	 * {@code AcpAgentSupport.Builder.requestTimeout}.
	 * @return the timeout, or {@code null} when unset
	 */
	public @Nullable Duration getRequestTimeout() {
		return requestTimeout;
	}

	/**
	 * Sets {@code spring.acp.agent.request-timeout}; see {@link #getRequestTimeout()}.
	 * @param requestTimeout the timeout, or {@code null} for the SDK's default
	 */
	public void setRequestTimeout(@Nullable Duration requestTimeout) {
		this.requestTimeout = requestTimeout;
	}

	/**
	 * Returns how long a {@code @Prompt} method has to return after the client sends
	 * {@code session/cancel} ({@code spring.acp.agent.cancel-grace-period}). When it passes, the
	 * agent answers the prompt with stop reason {@code cancelled} itself and interrupts the
	 * method's thread. Default: unset, which keeps the SDK's default of 60 seconds; zero turns the
	 * limit off. Maps to {@code AcpAgentSupport.Builder.cancelGracePeriod}.
	 * @return the grace period, or {@code null} when unset
	 */
	public @Nullable Duration getCancelGracePeriod() {
		return cancelGracePeriod;
	}

	/**
	 * Sets {@code spring.acp.agent.cancel-grace-period}; see {@link #getCancelGracePeriod()}.
	 * @param cancelGracePeriod the grace period, zero for none, or {@code null} for the SDK's
	 * default; not negative
	 */
	public void setCancelGracePeriod(@Nullable Duration cancelGracePeriod) {
		this.cancelGracePeriod = cancelGracePeriod;
	}

	/**
	 * Returns how long a prompt turn may run before the agent answers it with error {@code -32800}
	 * (request cancelled) ({@code spring.acp.agent.max-prompt-duration}). Default: unset, which
	 * keeps the SDK's default of no limit; zero also means no limit. Maps to
	 * {@code AcpAgentSupport.Builder.maxPromptDuration}.
	 * @return the longest a turn may run, or {@code null} when unset
	 */
	public @Nullable Duration getMaxPromptDuration() {
		return maxPromptDuration;
	}

	/**
	 * Sets {@code spring.acp.agent.max-prompt-duration}; see {@link #getMaxPromptDuration()}.
	 * @param maxPromptDuration the longest a turn may run, zero for no limit, or {@code null} for
	 * the SDK's default; not negative
	 */
	public void setMaxPromptDuration(@Nullable Duration maxPromptDuration) {
		this.maxPromptDuration = maxPromptDuration;
	}

	/**
	 * Returns whether the application context closes when the agent's stdio transport ends on its
	 * own ({@code spring.acp.agent.shutdown-on-transport-end}). Default {@code true}; maps to
	 * {@link AcpAgentSettings#shutdownOnTransportEnd()}.
	 *
	 * <p>A stdio transport ends when the client closes the agent's standard input and every answer
	 * has been written. The agent then has no one left to serve, so closing the context lets an
	 * application kept alive by {@code spring.main.keep-alive} exit. It applies to the single
	 * transport only: the stdio transport, or an {@code AcpAgentTransport} bean of the
	 * application's own. An HTTP endpoint serves many clients and never ends because one leaves.
	 * @return whether the context closes at transport end
	 */
	public boolean isShutdownOnTransportEnd() {
		return shutdownOnTransportEnd;
	}

	/**
	 * Sets {@code spring.acp.agent.shutdown-on-transport-end}; see
	 * {@link #isShutdownOnTransportEnd()}.
	 * @param shutdownOnTransportEnd whether the context closes at transport end
	 */
	public void setShutdownOnTransportEnd(boolean shutdownOnTransportEnd) {
		this.shutdownOnTransportEnd = shutdownOnTransportEnd;
	}

	/**
	 * Returns the executor the agent's handler methods run on
	 * ({@code spring.acp.agent.handler-executor}). Maps to
	 * {@code AcpAgentSupport.Builder.handlerExecutor}.
	 * <ul>
	 * <li>The name of an {@code Executor} bean: that bean. A plain {@code Executor}, such as a
	 * {@code TaskExecutor}, is adapted to an {@code ExecutorService}; cancelling a handler still
	 * interrupts its thread. The executor must allow blocking.</li>
	 * <li>{@code none}, in any case: the SDK's pool of platform threads.</li>
	 * <li>Unset (the default): the context's {@code applicationTaskExecutor} when
	 * {@code spring.threads.virtual.enabled=true} on JDK 21 and later, which starts a virtual
	 * thread per handler call; otherwise the SDK's pool of platform threads, on every JDK: the SDK
	 * follows Spring Boot's opt-in rather than its own default of virtual threads. Without
	 * virtual threads Spring Boot's {@code applicationTaskExecutor} is a pool of 8 threads by
	 * default, which would cap the prompts served at once; name it here to use it anyway.</li>
	 * </ul>
	 * A name that matches no bean, or a bean that is no {@code Executor}, fails the application's
	 * startup.
	 * @return the executor bean's name, {@code none}, or {@code null} when unset
	 */
	public @Nullable String getHandlerExecutor() {
		return handlerExecutor;
	}

	/**
	 * Sets {@code spring.acp.agent.handler-executor}; see {@link #getHandlerExecutor()}.
	 * @param handlerExecutor the executor bean's name, {@code none}, or {@code null} for the
	 * default
	 */
	public void setHandlerExecutor(@Nullable String handlerExecutor) {
		this.handlerExecutor = handlerExecutor;
	}

	/**
	 * Returns the transport properties, {@code spring.acp.agent.transport.*}.
	 * @return the transport properties
	 */
	public AgentTransportProperties getTransport() {
		return transport;
	}

	/**
	 * Replaces the transport properties, {@code spring.acp.agent.transport.*}.
	 * @param transport the transport properties
	 */
	public void setTransport(AgentTransportProperties transport) {
		this.transport = transport;
	}

	/**
	 * Returns these properties as the SDK's framework-neutral {@link AcpAgentSettings}, which the
	 * autoconfiguration passes to {@code acp-integration}. An unset timeout or limit stays unset
	 * ({@code null}), so the SDK applies its default; an unset transport type becomes
	 * {@link AcpTransportType#STDIO}. Each call builds new settings from the current values.
	 * @return the settings
	 */
	public AcpAgentSettings toSettings() {
		AgentHttpProperties http = transport.getHttp();
		DataSize maxPostBodySize = http.getMaxPostBodySize();
		AcpTransportType type = transport.getType();
		return AcpAgentSettings.builder()
			.enabled(enabled)
			.requestTimeout(requestTimeout)
			.cancelGracePeriod(cancelGracePeriod)
			.maxPromptDuration(maxPromptDuration)
			.shutdownOnTransportEnd(shutdownOnTransportEnd)
			.transport((type != null) ? type : AcpTransportType.STDIO)
			.path(http.getPath())
			.maxPostBodyBytes((maxPostBodySize != null) ? maxPostBodySize.toBytes() : null)
			.keepAliveInterval(http.getKeepAliveInterval())
			.mailboxCapacity(http.getMailboxCapacity())
			.maxPendingSseEvents(http.getMaxPendingSseEvents())
			.maxWebSocketPendingFrames(http.getMaxWebSocketPendingFrames())
			.maxProvisionalSessions(http.getMaxProvisionalSessions())
			.shutdownTimeout(http.getShutdownTimeout())
			.allowedOrigins(http.getAllowedOrigins())
			.listenerHost(http.getListener().getHost())
			.listenerPort(http.getListener().getPort())
			.maxConcurrentStreamsPerConnection(http.getListener().getMaxConcurrentStreamsPerConnection())
			.build();
	}

	/**
	 * The {@code spring.acp.agent.transport.*} properties: which transport serves the agent, and
	 * the Streamable HTTP endpoint's settings ({@link AgentHttpProperties}).
	 */
	public static class AgentTransportProperties {

		/**
		 * The transport: stdio (the default), or http for Streamable HTTP. websocket means the same
		 * as http; WebSocket upgrades are taken only outside a servlet web application.
		 */
		private @Nullable AcpTransportType type;

		private AgentHttpProperties http = new AgentHttpProperties();

		/**
		 * Returns the transport ({@code spring.acp.agent.transport.type}). Maps to
		 * {@link AcpAgentSettings#transport()}.
		 * <ul>
		 * <li>{@code stdio}, or unset (the default): the agent reads standard input and writes
		 * standard output, so the application must log to standard error.</li>
		 * <li>{@code http}: ACP Streamable HTTP, which needs {@code acp-streamable-http-jetty} on
		 * the classpath; without it the application fails at startup naming the module. See
		 * {@link AcpAgentHttpAutoConfiguration} for where the endpoint is served.</li>
		 * <li>{@code websocket}: the same as {@code http}. The SDK's own listener takes WebSocket
		 * upgrades on the endpoint's path; the servlet mounted in a servlet web application does
		 * not.</li>
		 * </ul>
		 * The value is read in any case.
		 * @return the transport, or {@code null} when unset
		 */
		public @Nullable AcpTransportType getType() {
			return type;
		}

		/**
		 * Sets {@code spring.acp.agent.transport.type}; see {@link #getType()}.
		 * @param type the transport, or {@code null} for stdio
		 */
		public void setType(@Nullable AcpTransportType type) {
			this.type = type;
		}

		/**
		 * Returns the Streamable HTTP endpoint's properties,
		 * {@code spring.acp.agent.transport.http.*}.
		 * @return the HTTP properties
		 */
		public AgentHttpProperties getHttp() {
			return http;
		}

		/**
		 * Replaces the Streamable HTTP endpoint's properties,
		 * {@code spring.acp.agent.transport.http.*}.
		 * @param http the HTTP properties
		 */
		public void setHttp(AgentHttpProperties http) {
			this.http = http;
		}

	}

	/**
	 * The {@code spring.acp.agent.transport.http.*} properties: the Streamable HTTP endpoint that
	 * serves the agent when {@code spring.acp.agent.transport.type} is {@code http} or
	 * {@code websocket}. In a servlet web application the endpoint is mounted on the application's
	 * own server at {@code path}; otherwise the SDK's listener serves it on {@code listener.port}
	 * ({@link ListenerProperties}), with WebSocket upgrades on the same path.
	 *
	 * <p>The limits bound every buffer the endpoint keeps for a client. Each one left unset keeps
	 * the SDK's default, which its getter names; together they map to
	 * {@link com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions}.
	 */
	public static class AgentHttpProperties {

		/**
		 * Endpoint path. Default /acp.
		 */
		private String path = AcpAgentSettings.DEFAULT_PATH;

		/**
		 * Largest accepted inbound message (POST body or WebSocket text message). Unset keeps the
		 * SDK default, 16MB.
		 */
		private @Nullable DataSize maxPostBodySize;

		/**
		 * Interval between SSE keep-alive comments; zero disables them. Unset keeps the SDK
		 * default, 15 seconds.
		 */
		private @Nullable Duration keepAliveInterval;

		/**
		 * Events retained per outbound stream while no subscriber is attached. Unset keeps the SDK
		 * default, 1024.
		 */
		private @Nullable Integer mailboxCapacity;

		/**
		 * Events queued for one attached SSE subscriber before it is detached. Unset keeps the SDK
		 * default, 1024.
		 */
		private @Nullable Integer maxPendingSseEvents;

		/**
		 * Frames queued for one WebSocket connection before it is closed. Unset keeps the SDK
		 * default, 1024.
		 */
		private @Nullable Integer maxWebSocketPendingFrames;

		/**
		 * Session streams a connection may open before the session is known. Unset keeps the SDK
		 * default, 64.
		 */
		private @Nullable Integer maxProvisionalSessions;

		/**
		 * How long closing the endpoint waits for its connections to close gracefully before
		 * closing the rest at once. Unset keeps the SDK default, 5 seconds.
		 */
		private @Nullable Duration shutdownTimeout;

		/**
		 * Browser origins accepted besides the loopback ones, such as https://app.example.com, or
		 * * for any. A request from any other origin is refused with 403. Default none.
		 */
		private List<String> allowedOrigins = new ArrayList<>();

		private ListenerProperties listener = new ListenerProperties();

		/**
		 * Returns the browser origins the endpoint accepts besides the loopback ones
		 * ({@code spring.acp.agent.transport.http.allowed-origins}). A request without an
		 * {@code Origin} header, or from {@code http(s)://localhost}, {@code 127.0.0.1} or
		 * {@code [::1]} on any port, is always accepted; any other origin is answered 403, over
		 * HTTP and on the WebSocket handshake, unless listed here. {@code *} accepts any origin.
		 * Default empty. Maps to {@link AcpAgentSettings.Http#allowedOrigins()}.
		 * @return the origins
		 */
		public List<String> getAllowedOrigins() {
			return allowedOrigins;
		}

		/**
		 * Sets {@code spring.acp.agent.transport.http.allowed-origins}; see
		 * {@link #getAllowedOrigins()}.
		 * @param allowedOrigins the origins
		 */
		public void setAllowedOrigins(List<String> allowedOrigins) {
			this.allowedOrigins = allowedOrigins;
		}

		/**
		 * Returns the endpoint's path ({@code spring.acp.agent.transport.http.path}). Default
		 * {@value AcpAgentSettings#DEFAULT_PATH}. In a servlet web application the servlet is
		 * mapped at this path on the application's server; the SDK's listener serves it on its own
		 * port. Maps to {@link AcpAgentSettings.Http#path()}.
		 * @return the path
		 */
		public String getPath() {
			return path;
		}

		/**
		 * Sets {@code spring.acp.agent.transport.http.path}; see {@link #getPath()}.
		 * @param path the path
		 */
		public void setPath(String path) {
			this.path = path;
		}

		/**
		 * Returns the largest message accepted from a client
		 * ({@code spring.acp.agent.transport.http.max-post-body-size}). A larger POST body is
		 * answered 413, and a larger WebSocket text message closes the connection. Default: unset,
		 * which keeps the SDK's default of 16 MB. Maps to the transport option
		 * {@code maxPostBodyBytes}.
		 * @return the limit, or {@code null} when unset
		 */
		public @Nullable DataSize getMaxPostBodySize() {
			return maxPostBodySize;
		}

		/**
		 * Sets {@code spring.acp.agent.transport.http.max-post-body-size}; see
		 * {@link #getMaxPostBodySize()}.
		 * @param maxPostBodySize the limit, positive, or {@code null} for the SDK's default
		 */
		public void setMaxPostBodySize(@Nullable DataSize maxPostBodySize) {
			this.maxPostBodySize = maxPostBodySize;
		}

		/**
		 * Returns the interval between keep-alive comments on open SSE streams, which stop proxies
		 * from cutting idle streams ({@code spring.acp.agent.transport.http.keep-alive-interval}).
		 * Zero turns them off. Default: unset, which keeps the SDK's default of 15 seconds. Maps to
		 * the transport option {@code keepAliveInterval}.
		 * @return the interval, or {@code null} when unset
		 */
		public @Nullable Duration getKeepAliveInterval() {
			return keepAliveInterval;
		}

		/**
		 * Sets {@code spring.acp.agent.transport.http.keep-alive-interval}; see
		 * {@link #getKeepAliveInterval()}.
		 * @param keepAliveInterval the interval, zero for none, or {@code null} for the SDK's
		 * default; not negative
		 */
		public void setKeepAliveInterval(@Nullable Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval;
		}

		/**
		 * Returns how many events one SSE stream keeps unsent while no client is reading it, to
		 * send when the client reconnects
		 * ({@code spring.acp.agent.transport.http.mailbox-capacity}). One more closes the
		 * connection. Default: unset, which keeps the SDK's default of 1024. Maps to the transport
		 * option {@code mailboxCapacity}.
		 * @return the event count, or {@code null} when unset
		 */
		public @Nullable Integer getMailboxCapacity() {
			return mailboxCapacity;
		}

		/**
		 * Sets {@code spring.acp.agent.transport.http.mailbox-capacity}; see
		 * {@link #getMailboxCapacity()}.
		 * @param mailboxCapacity the event count, positive, or {@code null} for the SDK's default
		 */
		public void setMailboxCapacity(@Nullable Integer mailboxCapacity) {
			this.mailboxCapacity = mailboxCapacity;
		}

		/**
		 * Returns how many events are queued for a client that is reading an SSE stream
		 * ({@code spring.acp.agent.transport.http.max-pending-sse-events}). One more detaches that
		 * client and keeps the events for its next GET. Default: unset, which keeps the SDK's
		 * default of 1024. Maps to the transport option {@code maxPendingSseEvents}.
		 * @return the event count, or {@code null} when unset
		 */
		public @Nullable Integer getMaxPendingSseEvents() {
			return maxPendingSseEvents;
		}

		/**
		 * Sets {@code spring.acp.agent.transport.http.max-pending-sse-events}; see
		 * {@link #getMaxPendingSseEvents()}.
		 * @param maxPendingSseEvents the event count, positive, or {@code null} for the SDK's
		 * default
		 */
		public void setMaxPendingSseEvents(@Nullable Integer maxPendingSseEvents) {
			this.maxPendingSseEvents = maxPendingSseEvents;
		}

		/**
		 * Returns how many frames are queued for one WebSocket connection
		 * ({@code spring.acp.agent.transport.http.max-web-socket-pending-frames}). One more closes
		 * the connection. Default: unset, which keeps the SDK's default of 1024. Maps to the
		 * transport option {@code maxWebSocketPendingFrames}.
		 * @return the frame count, or {@code null} when unset
		 */
		public @Nullable Integer getMaxWebSocketPendingFrames() {
			return maxWebSocketPendingFrames;
		}

		/**
		 * Sets {@code spring.acp.agent.transport.http.max-web-socket-pending-frames}; see
		 * {@link #getMaxWebSocketPendingFrames()}.
		 * @param maxWebSocketPendingFrames the frame count, positive, or {@code null} for the SDK's
		 * default
		 */
		public void setMaxWebSocketPendingFrames(@Nullable Integer maxWebSocketPendingFrames) {
			this.maxWebSocketPendingFrames = maxWebSocketPendingFrames;
		}

		/**
		 * Returns how many session streams a connection may open before the agent knows the
		 * session, as a client does before {@code session/load}
		 * ({@code spring.acp.agent.transport.http.max-provisional-sessions}). A further one is
		 * refused. Default: unset, which keeps the SDK's default of 64. Maps to the transport
		 * option {@code maxProvisionalSessions}.
		 * @return the stream count, or {@code null} when unset
		 */
		public @Nullable Integer getMaxProvisionalSessions() {
			return maxProvisionalSessions;
		}

		/**
		 * Sets {@code spring.acp.agent.transport.http.max-provisional-sessions}; see
		 * {@link #getMaxProvisionalSessions()}.
		 * @param maxProvisionalSessions the stream count, positive, or {@code null} for the SDK's
		 * default
		 */
		public void setMaxProvisionalSessions(@Nullable Integer maxProvisionalSessions) {
			this.maxProvisionalSessions = maxProvisionalSessions;
		}

		/**
		 * Returns how long closing the endpoint waits for its connections' agents to close before
		 * it closes the rest at once ({@code spring.acp.agent.transport.http.shutdown-timeout}).
		 * Default: unset, which keeps the SDK's default of 5 seconds. Maps to the transport option
		 * {@code shutdownTimeout}.
		 * @return the timeout, or {@code null} when unset
		 */
		public @Nullable Duration getShutdownTimeout() {
			return shutdownTimeout;
		}

		/**
		 * Sets {@code spring.acp.agent.transport.http.shutdown-timeout}; see
		 * {@link #getShutdownTimeout()}.
		 * @param shutdownTimeout the timeout, positive, or {@code null} for the SDK's default
		 */
		public void setShutdownTimeout(@Nullable Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
		}

		/**
		 * Returns the SDK listener's properties,
		 * {@code spring.acp.agent.transport.http.listener.*}.
		 * @return the listener properties
		 */
		public ListenerProperties getListener() {
			return listener;
		}

		/**
		 * Replaces the SDK listener's properties,
		 * {@code spring.acp.agent.transport.http.listener.*}.
		 * @param listener the listener properties
		 */
		public void setListener(ListenerProperties listener) {
			this.listener = listener;
		}

	}

	/**
	 * The {@code spring.acp.agent.transport.http.listener.*} properties: the SDK's own listener,
	 * which serves the endpoint outside a servlet web application. A servlet web application serves
	 * the endpoint on its own server and port ({@code server.port}) and ignores these.
	 */
	public static class ListenerProperties {

		/**
		 * Address the standalone listener binds. Unset binds the loopback interface only
		 * (127.0.0.1 and ::1); 0.0.0.0 exposes the agent on every interface.
		 */
		private @Nullable String host;

		/**
		 * Port of the standalone listener; 0 for an ephemeral port. Default 8080.
		 */
		private int port = AcpAgentSettings.DEFAULT_LISTENER_PORT;

		/**
		 * HTTP/2 streams one client connection may hold open. Unset keeps the SDK default, 1024.
		 */
		private @Nullable Integer maxConcurrentStreamsPerConnection;

		/**
		 * Returns the address the listener binds
		 * ({@code spring.acp.agent.transport.http.listener.host}). Default: unset, which binds the
		 * loopback interface only ({@code 127.0.0.1}, and {@code ::1} where the machine has IPv6),
		 * so only programs on the same machine can connect. {@code 0.0.0.0} exposes the agent on
		 * every interface; the endpoint has no authentication of its own, so do that only behind a
		 * proxy or firewall that controls who connects. A servlet web application's own server
		 * ignores it ({@code server.address} applies there). Maps to
		 * {@link AcpAgentSettings.Listener#host()}.
		 * @return the host, or {@code null} for loopback only
		 */
		public @Nullable String getHost() {
			return host;
		}

		/**
		 * Sets {@code spring.acp.agent.transport.http.listener.host}; see {@link #getHost()}.
		 * @param host a host name or address, or {@code null} for loopback only
		 */
		public void setHost(@Nullable String host) {
			this.host = host;
		}

		/**
		 * Returns the listener's port ({@code spring.acp.agent.transport.http.listener.port}).
		 * Default {@value AcpAgentSettings#DEFAULT_LISTENER_PORT}; {@code 0} picks a free port.
		 * Maps to {@link AcpAgentSettings.Listener#port()}.
		 * @return the port
		 */
		public int getPort() {
			return port;
		}

		/**
		 * Sets {@code spring.acp.agent.transport.http.listener.port}; see {@link #getPort()}.
		 * @param port the port, or {@code 0} for a free one
		 */
		public void setPort(int port) {
			this.port = port;
		}

		/**
		 * Returns how many HTTP/2 streams one client connection may hold open at once, each open
		 * SSE stream holding one
		 * ({@code spring.acp.agent.transport.http.listener.max-concurrent-streams-per-connection}).
		 * Default: unset, which keeps the SDK's default of 1024. Maps to the transport option
		 * {@code maxConcurrentStreamsPerConnection}.
		 * @return the stream count, or {@code null} when unset
		 */
		public @Nullable Integer getMaxConcurrentStreamsPerConnection() {
			return maxConcurrentStreamsPerConnection;
		}

		/**
		 * Sets
		 * {@code spring.acp.agent.transport.http.listener.max-concurrent-streams-per-connection};
		 * see {@link #getMaxConcurrentStreamsPerConnection()}.
		 * @param maxConcurrentStreamsPerConnection the stream count, positive, or {@code null} for
		 * the SDK's default
		 */
		public void setMaxConcurrentStreamsPerConnection(@Nullable Integer maxConcurrentStreamsPerConnection) {
			this.maxConcurrentStreamsPerConnection = maxConcurrentStreamsPerConnection;
		}

	}

}
