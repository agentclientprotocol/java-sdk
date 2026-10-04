/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.io.IOException;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.jspecify.annotations.Nullable;

/**
 * The SDK's Streamable HTTP servlet as a bean, so the Quarkus servlet container (Undertow on the
 * Quarkus HTTP server) takes this instance, built over the application's agent. The extension maps
 * it at {@code quarkus.acp.agent.transport.http.path} for an HTTP agent; it serves HTTP and SSE,
 * while {@link AcpWebSocketRoute} takes the WebSocket upgrades on the same path. Part of the
 * extension's wiring; an application does not use it directly.
 * <p><b>Non-blocking output on quarkus-http.</b> The SDK opens an SSE stream by setting a
 * {@link WriteListener} and, as the Servlet specification allows while {@code isReady()} is true,
 * writing at once on the request thread. quarkus-http (the Undertow fork behind
 * {@code quarkus-undertow}) reports {@code isReady()} true there, but a write queued in the same
 * cycle as the listener's registration fails the request ({@code UT005080}: async IO resumed and
 * dispatched in the same cycle). The output stream given to the servlet therefore reports not ready
 * until the container has called {@code onWritePossible} once; the SDK then writes from that
 * callback, as it does after any incomplete write. </p>
 *
 * @author Mark Pollack
 */
@Singleton
public class AcpHttpServlet extends StreamableHttpAcpServlet {

	private static final long serialVersionUID = 1L;

	AcpHttpServlet(AcpHttpEndpoint endpoint) {
		super(endpoint.jsonMapper(), endpoint.agentFactory(), endpoint.options());
	}

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		super.service(request, new DeferredReadyResponse(response));
	}

	/** A response whose output stream is not ready before the container's first write callback. */
	static final class DeferredReadyResponse extends HttpServletResponseWrapper {

		private @Nullable DeferredReadyOutputStream output;

		DeferredReadyResponse(HttpServletResponse response) {
			super(response);
		}

		@Override
		public synchronized ServletOutputStream getOutputStream() throws IOException {
			DeferredReadyOutputStream current = output;
			if (current == null) {
				current = new DeferredReadyOutputStream(super.getOutputStream());
				output = current;
			}
			return current;
		}

	}

	/**
	 * Delegates every operation, except that once a write listener is set, {@code isReady()}
	 * is false until the container has invoked the listener's {@code onWritePossible}.
	 */
	static final class DeferredReadyOutputStream extends ServletOutputStream {

		private final ServletOutputStream delegate;

		private volatile boolean listening;

		private volatile boolean writePossibleSignalled;

		DeferredReadyOutputStream(ServletOutputStream delegate) {
			this.delegate = delegate;
		}

		@Override
		public boolean isReady() {
			if (listening && !writePossibleSignalled) {
				return false;
			}
			return delegate.isReady();
		}

		@Override
		public void setWriteListener(WriteListener listener) {
			listening = true;
			delegate.setWriteListener(new WriteListener() {

				@Override
				public void onWritePossible() throws IOException {
					writePossibleSignalled = true;
					listener.onWritePossible();
				}

				@Override
				public void onError(Throwable error) {
					listener.onError(error);
				}

			});
		}

		@Override
		public void write(int b) throws IOException {
			delegate.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			delegate.write(b, off, len);
		}

		@Override
		public void flush() throws IOException {
			delegate.flush();
		}

		@Override
		public void close() throws IOException {
			delegate.close();
		}

	}

}
