/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.http.server.AcpHttpEndpoint;
import com.agentclientprotocol.sdk.http.server.AcpHttpReply;
import com.agentclientprotocol.sdk.http.server.AcpWsHandshake;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.util.Assert;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * The ACP endpoint as a servlet, for serving remote clients from a Servlet 6 container you
 * already run, such as Spring Boot, Tomcat, Jetty or Undertow. It serves the Streamable HTTP
 * profile and, where the container has Jakarta WebSocket 2.1, WebSocket on the same path: a
 * request carrying {@code Upgrade: websocket} is upgraded by the servlet itself
 * ({@code ServerContainer.upgradeHttpToWebSocket}), so there is no second server and the
 * container's filters (security, observation, access logs) see the handshake too. It creates
 * a fresh agent for each client connection with an {@link AcpAgentFactory}.
 *
 * <p>Register it at the path of your choice, with async support on:
 *
 * <pre>{@code
 * void register(jakarta.servlet.ServletContext servletContext, AcpAgentFactory agentFactory) {
 *     var servlet = new StreamableHttpAcpServlet(AcpJsonMapper.createDefault(), agentFactory);
 *     jakarta.servlet.ServletRegistration.Dynamic registration =
 *         servletContext.addServlet("acp", servlet);
 *     registration.addMapping("/acp");
 *     registration.setAsyncSupported(true);
 * }
 * }</pre>
 *
 * <p>The servlet is a host of {@link AcpHttpEndpoint}, which holds every protocol rule: the
 * status table, routing, the SSE mailboxes and keep-alive, the WebSocket send queue and close
 * codes, and the {@code Origin} check (a browser request from an origin other than a loopback
 * one is refused with 403 unless listed in
 * {@link StreamableHttpAcpAgentTransportOptions.Builder#allowedOrigins allowedOrigins}). The
 * servlet only adapts I/O: requests through the container's async and non-blocking output
 * APIs, SSE streams that never time out ({@code AsyncContext.setTimeout(0)}), and WebSocket
 * frames through a Jakarta {@code Endpoint}. A container without a Jakarta WebSocket
 * implementation answers an upgrade request 501. The servlet has no authentication of its own:
 * protect its path as you would any other endpoint; the authenticated principal reaches the
 * endpoint.
 *
 * <p>The container's lifecycle drives the servlet: {@link #init()} starts the SSE keep-alive
 * and {@link #destroy()} closes every connection.
 *
 * <p><b>Close before a graceful container shutdown.</b> Every SSE stream and WebSocket a client
 * holds open is a request in flight, and a container that shuts down gracefully waits for
 * those before it destroys servlets. Spring Boot shuts down gracefully by default, so with a
 * client connected it waits its whole {@code spring.lifecycle.timeout-per-shutdown-phase}
 * (30 seconds) before {@code destroy()} is even called. Call {@link #closeGracefully()} before
 * the server stops instead, for instance from a {@code SmartLifecycle} in the default phase:
 * SSE streams get a closing comment and complete, and WebSockets close with 1001.
 *
 * @author Kaiser Dandangi
 * @author Mark Pollack
 */
public class StreamableHttpAcpServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpAcpServlet.class);

	private final transient AcpHttpEndpoint endpoint;

	/**
	 * Creates a servlet with the default limits and timings and the default JSON mapper
	 * ({@link AcpJsonMapper#createDefault()}).
	 * @param agentFactory creates the agent for each connection
	 * @throws IllegalArgumentException if {@code agentFactory} is null
	 */
	public StreamableHttpAcpServlet(AcpAgentFactory agentFactory) {
		this(AcpJsonMapper.createDefault(), agentFactory);
	}

	/**
	 * Creates a servlet with the default limits and timings.
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @param agentFactory creates the agent for each connection
	 * @throws IllegalArgumentException if an argument is null
	 */
	public StreamableHttpAcpServlet(AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory) {
		this(jsonMapper, agentFactory, StreamableHttpAcpAgentTransportOptions.defaults());
	}

	/**
	 * Creates a servlet with the limits and timings of {@code options}.
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @param agentFactory creates the agent for each connection
	 * @param options the endpoint's limits and timings; {@code host},
	 * {@code maxConcurrentStreamsPerConnection} and the thread settings are the container's
	 * to configure when this servlet is mounted in a container
	 * @throws IllegalArgumentException if an argument is null
	 */
	public StreamableHttpAcpServlet(AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory,
			StreamableHttpAcpAgentTransportOptions options) {
		this(AcpHttpEndpoint.create(jsonMapper, agentFactory, options));
	}

	/**
	 * Creates a servlet that serves an endpoint created elsewhere, such as one a framework wraps
	 * for observation.
	 * @param endpoint the endpoint
	 * @throws IllegalArgumentException if {@code endpoint} is null
	 */
	@UnstableAcpApi
	public StreamableHttpAcpServlet(AcpHttpEndpoint endpoint) {
		Assert.notNull(endpoint, "The endpoint can not be null");
		this.endpoint = endpoint;
	}

	/**
	 * Returns the endpoint this servlet serves.
	 * @return the endpoint
	 */
	@UnstableAcpApi
	public AcpHttpEndpoint endpoint() {
		return endpoint;
	}

	/**
	 * Starts the SSE keep-alive. The container calls it when it puts the servlet into service.
	 */
	@Override
	public void init() throws ServletException {
		super.init();
		endpoint.start();
	}

	/**
	 * Closes every connection, as {@link #closeGracefully()} does, within the shutdown timeout.
	 * The container calls it when it takes the servlet out of service.
	 */
	@Override
	public void destroy() {
		try {
			// closeGracefully() is itself bounded by the shutdown timeout; the margin only
			// guards against a close that does not even get to start.
			endpoint.closeGracefully().block(endpoint.options().shutdownTimeout().plusSeconds(1));
		}
		catch (RuntimeException e) {
			logger.warn("Streamable HTTP servlet did not close within {}: {}", endpoint.options().shutdownTimeout(),
					e.getMessage());
		}
		super.destroy();
	}

	/**
	 * Closes every connection this servlet holds: new connections are refused, an
	 * {@code initialize} still in flight is answered 503, in-flight prompts are cancelled, SSE
	 * responses get a closing comment and complete, and WebSockets close with 1001. A connection
	 * whose agent has not closed within the
	 * {@linkplain StreamableHttpAcpAgentTransportOptions#shutdownTimeout() shutdown timeout} is
	 * closed at once. Nothing here waits for a client. Only the first call has an effect.
	 * @return a Mono that completes when every connection has closed, at the latest after the
	 * shutdown timeout
	 */
	public Mono<Void> closeGracefully() {
		return endpoint.closeGracefully();
	}

	/**
	 * Sets the handler for the transport errors of every connection this servlet holds,
	 * including those opened before the call. The default logs them.
	 * @param handler receives the connections' transport errors
	 */
	public void setExceptionHandler(Consumer<Throwable> handler) {
		endpoint.setExceptionHandler(handler);
	}

	/**
	 * Returns the number of client connections this servlet holds, HTTP and WebSocket, not
	 * counting those whose {@code initialize} is still being answered.
	 * @return the number of open connections
	 */
	public int activeConnectionCount() {
		return endpoint.activeConnectionCount();
	}

	/**
	 * Hands every request to the endpoint and writes its answer: a WebSocket upgrade request to
	 * the endpoint's handshake, any other to {@link AcpHttpEndpoint#handle}. A reply that is
	 * ready at once is written on the request thread; one that is not (an {@code initialize}
	 * waiting for the agent) and every SSE stream continue asynchronously.
	 */
	@Override
	@SuppressWarnings("FutureReturnValueIgnored") // the callback writes and completes the response itself
	protected void service(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		ServletExchange exchange = new ServletExchange(request);
		if (exchange.isWebSocketUpgrade()) {
			upgrade(request, response, exchange);
			return;
		}
		CompletableFuture<@Nullable AcpHttpReply> reply = endpoint.handle(exchange).toFuture();
		if (reply.isDone() && !reply.isCompletedExceptionally()) {
			AcpHttpReply answer = reply.join();
			if (answer != null) {
				write(request, response, answer, null);
				return;
			}
		}
		AsyncContext asyncContext = request.startAsync();
		// The endpoint bounds the wait itself (initialize: 30 seconds).
		asyncContext.setTimeout(0);
		asyncContext.addListener(new CancelOnFailure(reply));
		reply.whenComplete((answer, error) -> {
			try {
				if (answer != null) {
					write(request, response, answer, asyncContext);
					return;
				}
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				complete(asyncContext);
			}
			catch (IOException | RuntimeException e) {
				logger.debug("Failed to write an ACP reply: {}", e.toString());
				complete(asyncContext);
			}
		});
	}

	private void upgrade(HttpServletRequest request, HttpServletResponse response, ServletExchange exchange)
			throws ServletException, IOException {
		AcpWsHandshake handshake = endpoint.webSocketHandshake(exchange);
		if (handshake instanceof AcpWsHandshake.Refused refused) {
			write(request, response, refused.reply(), null);
			return;
		}
		if (!JakartaWebSocketUpgrade.upgrade(request, response, (AcpWsHandshake.Accepted) handshake)) {
			writeText(response, HttpServletResponse.SC_NOT_IMPLEMENTED,
					"WebSocket is not available in this servlet container");
		}
	}

	private static void write(HttpServletRequest request, HttpServletResponse response, AcpHttpReply reply,
			@Nullable AsyncContext asyncContext) throws IOException {
		if (reply instanceof AcpHttpReply.EventStream stream) {
			AsyncContext context = (asyncContext != null) ? asyncContext : request.startAsync();
			// An SSE stream lives as long as the connection; the endpoint's keep-alive comments,
			// not a container timeout, keep it healthy.
			context.setTimeout(0);
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType(AcpHttpReply.EVENT_STREAM);
			stream.headers().forEach(response::setHeader);
			stream.frames().subscribe(new ServletSseWriter(context, response.getOutputStream()));
			return;
		}
		try {
			response.setStatus(reply.status());
			reply.headers().forEach(response::setHeader);
			if (reply instanceof AcpHttpReply.Body body) {
				response.setContentType(body.contentType());
				response.setCharacterEncoding(StandardCharsets.UTF_8.name());
				byte[] bytes = body.bodyBytes();
				response.setContentLength(bytes.length);
				response.getOutputStream().write(bytes);
			}
		}
		finally {
			if (asyncContext != null) {
				complete(asyncContext);
			}
		}
	}

	private static void writeText(HttpServletResponse response, int status, String body) throws IOException {
		response.setStatus(status);
		response.setContentType("text/plain");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
	}

	static void complete(AsyncContext asyncContext) {
		try {
			asyncContext.complete();
		}
		catch (IllegalStateException ignored) {
			// Already completed, or completed by the container after a client disconnect.
		}
	}

	/** Abandons a pending reply when the container fails the request (the client went away). */
	private static final class CancelOnFailure implements AsyncListener {

		private final CompletableFuture<@Nullable AcpHttpReply> reply;

		CancelOnFailure(CompletableFuture<@Nullable AcpHttpReply> reply) {
			this.reply = reply;
		}

		@Override
		public void onComplete(AsyncEvent event) {
		}

		@Override
		public void onTimeout(AsyncEvent event) {
			reply.cancel(false);
		}

		@Override
		public void onError(AsyncEvent event) {
			reply.cancel(false);
		}

		@Override
		public void onStartAsync(AsyncEvent event) {
		}

	}

}
