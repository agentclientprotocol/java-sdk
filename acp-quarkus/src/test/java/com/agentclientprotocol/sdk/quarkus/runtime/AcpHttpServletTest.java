/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The output stream the servlet writes to on quarkus-http. */
class AcpHttpServletTest {

	@Test
	void notReadyBetweenSetWriteListenerAndTheFirstCallback() throws IOException {
		ServletOutputStream container = mock(ServletOutputStream.class);
		when(container.isReady()).thenReturn(true);
		AtomicReference<WriteListener> registered = new AtomicReference<>();
		doAnswer(invocation -> {
			registered.set(invocation.getArgument(0));
			return null;
		}).when(container).setWriteListener(any());
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getOutputStream()).thenReturn(container);

		AcpHttpServlet.DeferredReadyResponse wrapped = new AcpHttpServlet.DeferredReadyResponse(response);
		ServletOutputStream output = wrapped.getOutputStream();
		assertThat(wrapped.getOutputStream()).isSameAs(output);
		assertThat(output.isReady()).as("blocking output, no listener").isTrue();

		WriteListener listener = mock(WriteListener.class);
		output.setWriteListener(listener);
		assertThat(output.isReady()).as("listener set, container has not called back").isFalse();

		registered.get().onWritePossible();
		verify(listener).onWritePossible();
		assertThat(output.isReady()).isTrue();

		IllegalStateException failure = new IllegalStateException("reset");
		registered.get().onError(failure);
		verify(listener).onError(failure);
	}

	@Test
	void writesGoToTheContainerStream() throws IOException {
		ServletOutputStream container = mock(ServletOutputStream.class);
		AcpHttpServlet.DeferredReadyOutputStream output = new AcpHttpServlet.DeferredReadyOutputStream(container);
		byte[] bytes = { 1, 2, 3 };
		output.write(7);
		output.write(bytes, 0, 3);
		output.flush();
		output.close();
		verify(container).write(7);
		verify(container).write(bytes, 0, 3);
		verify(container).flush();
		verify(container).close();
	}

}
