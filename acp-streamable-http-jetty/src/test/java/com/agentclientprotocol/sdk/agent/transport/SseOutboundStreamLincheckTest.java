/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.lincheck.datastructures.BooleanGen;
import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.Validate;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Model checks the SSE mailbox of {@link SseOutboundStream} with Lincheck: the agent pushes
 * events while clients attach (a new GET takes the stream over), the container completes or
 * fails pending writes on its own threads, and clients reset their streams, in any
 * interleaving.
 *
 * <p>
 * The servlet side is a fake container. Its output stream is either synchronous (a flush
 * completes at once) or asynchronous (a flush stays pending, {@code isReady()} is false, until
 * the container completes it and calls {@code onWritePossible}). A reset stream behaves like
 * Jetty's HTTP/2 output: a write is accepted and silently discarded, its flush completes at
 * once, and only the next write fails.
 * </p>
 *
 * <p>
 * The invariant, checked after every interleaving once pending writes have settled and a
 * healthy client has attached: every pushed event reached a client; the first deliveries are
 * in push order; and an event arrived twice only if it was written to a subscriber that was
 * detached before the write was confirmed (the documented pending-write takeover case).
 * </p>
 */
class SseOutboundStreamLincheckTest {

	/**
	 * Multiplies the number of scenarios explored. The default keeps this test to seconds in
	 * the build; CI's lincheck job raises it with -Dlincheck.scale. Scenarios and interleavings
	 * are generated from a fixed seed, so a run is repeatable.
	 */
	private static final int SCALE = Integer.getInteger("lincheck.scale", 1);

	private static @Nullable Level previousLevel;

	/**
	 * The stream logs takeovers and backpressure; Lincheck model checks every instruction a
	 * thread runs, the log appender's included, so logging stays off while it runs.
	 */
	@BeforeAll
	static void quietLogs() {
		Logger logger = (Logger) LoggerFactory.getLogger(SseOutboundStream.class);
		previousLevel = logger.getLevel();
		logger.setLevel(Level.OFF);
	}

	/**
	 * Defines the response proxy class before Lincheck instruments the JDK: on Java 17,
	 * generating a proxy class inside a model-checked thread fails in ProxyGenerator. Later
	 * proxies reuse the class.
	 */
	@BeforeAll
	static void defineResponseProxyClass() {
		new Client(false, new Deliveries());
	}

	@AfterAll
	static void restoreLogs() {
		((Logger) LoggerFactory.getLogger(SseOutboundStream.class)).setLevel(previousLevel);
	}

	@Test
	void noEventIsLostOrReordered() {
		new ModelCheckingOptions().iterations(30 * SCALE)
			.invocationsPerIteration(100)
			.threads(2)
			.actorsPerThread(3)
			.actorsBefore(1)
			.actorsAfter(1)
			.check(Mailbox.class);
	}

	@Param(name = "client", gen = IntGen.class, conf = "0:1")
	public static class Mailbox {

		/** Two pending events detach a subscriber: the backpressure path is part of the model. */
		private final SseOutboundStream stream = new SseOutboundStream(64, 2);

		private final Deliveries deliveries = new Deliveries();

		private final List<Client> clients = new CopyOnWriteArrayList<>();

		/**
		 * Events pushed so far; the event is its number. Guarded by this test's monitor on the
		 * stream, which makes numbering and pushing one step.
		 */
		private int pushed;

		@Operation
		public void push() {
			synchronized (stream) {
				stream.push(Integer.toString(pushed));
				pushed++;
			}
		}

		@Operation
		public void subscribe(@Param(gen = BooleanGen.class) boolean asyncWrites) throws IOException {
			Client client = new Client(asyncWrites, deliveries);
			clients.add(client);
			stream.subscribe(client.context, client.response);
		}

		/** The container completes the client's pending write (or fails it, if the client reset). */
		@Operation
		public void completeWrite(@Param(name = "client") int index) {
			if (index < clients.size()) {
				clients.get(index).output.completePendingWrite();
			}
		}

		/** The client drops the stream; the server learns of it only on a later write. */
		@Operation
		public void resetByClient(@Param(name = "client") int index) {
			if (index < clients.size()) {
				clients.get(index).output.reset();
			}
		}

		@Operation
		public void keepAlive() {
			stream.keepAlive();
		}

		@Validate
		public void everyEventDeliveredInOrder() throws IOException {
			clients.forEach(client -> client.output.completePendingWrite());
			Client healthy = new Client(false, deliveries);
			clients.add(healthy);
			stream.subscribe(healthy.context, healthy.response);
			clients.forEach(client -> client.output.completePendingWrite());

			int total;
			synchronized (stream) {
				total = pushed;
			}
			deliveries.check(total);
		}

	}

	/** What clients received, across every stream that was attached. */
	static final class Deliveries {

		private final Queue<Integer> received = new ConcurrentLinkedQueue<>();

		/** Events written to a subscriber that was detached before the write was confirmed. */
		private final Set<Integer> mayRepeat = ConcurrentHashMap.newKeySet();

		void check(int pushed) {
			List<Integer> firsts = new ArrayList<>();
			Set<Integer> seen = ConcurrentHashMap.newKeySet();
			for (Integer event : received) {
				if (seen.add(event)) {
					firsts.add(event);
				}
				else if (!mayRepeat.contains(event)) {
					throw new IllegalStateException("event " + event + " delivered twice: " + received);
				}
			}
			List<Integer> expected = new ArrayList<>();
			for (int event = 0; event < pushed; event++) {
				expected.add(event);
			}
			if (!firsts.equals(expected)) {
				throw new IllegalStateException(
						"pushed " + expected + " but first deliveries were " + firsts + " (all: " + received + ")");
			}
		}

	}

	/** One GET: its async context, response and output stream. */
	static final class Client {

		final FakeOutput output;

		final FakeContext context;

		final HttpServletResponse response;

		Client(boolean asyncWrites, Deliveries deliveries) {
			this.output = new FakeOutput(asyncWrites, deliveries);
			this.context = new FakeContext(output);
			this.response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { HttpServletResponse.class }, (proxy, method, args) -> {
						return switch (method.getName()) {
							case "getOutputStream" -> output;
							case "equals" -> proxy == args[0];
							case "hashCode" -> System.identityHashCode(proxy);
							case "toString" -> "FakeResponse";
							default -> throw new UnsupportedOperationException(method.getName());
						};
					});
		}

	}

	/** A servlet output stream that delivers the SSE events it flushes to {@link Deliveries}. */
	static final class FakeOutput extends ServletOutputStream {

		private final boolean asyncWrites;

		private final Deliveries deliveries;

		private @Nullable WriteListener listener;

		private final StringBuilder written = new StringBuilder();

		/** A flushed write the container has not completed; isReady() is false meanwhile. */
		private @Nullable String pending;

		/** Events delivered since the last successful write, not yet confirmed by the stream. */
		private final List<Integer> unconfirmed = new ArrayList<>();

		private boolean reset;

		private boolean failNextWrite;

		private boolean completed;

		FakeOutput(boolean asyncWrites, Deliveries deliveries) {
			this.asyncWrites = asyncWrites;
			this.deliveries = deliveries;
		}

		@Override
		public synchronized boolean isReady() {
			return pending == null;
		}

		@Override
		public synchronized void setWriteListener(WriteListener writeListener) {
			this.listener = writeListener;
		}

		@Override
		public void write(int b) throws IOException {
			write(new byte[] { (byte) b }, 0, 1);
		}

		@Override
		public synchronized void write(byte[] bytes, int off, int len) throws IOException {
			if (pending != null) {
				throw new IllegalStateException("write while not ready");
			}
			if (failNextWrite) {
				throw new IOException("stream reset by the client");
			}
			if (len == 0 && !reset) {
				// An empty write that succeeds confirms what was delivered before it.
				unconfirmed.clear();
			}
			if (!reset) {
				written.append(new String(bytes, off, len, StandardCharsets.UTF_8));
			}
		}

		@Override
		public synchronized void flush() throws IOException {
			if (pending != null) {
				throw new IllegalStateException("flush while not ready");
			}
			if (failNextWrite) {
				throw new IOException("stream reset by the client");
			}
			if (reset) {
				// Accepted and discarded; only the next write reports the reset.
				written.setLength(0);
				failNextWrite = true;
				return;
			}
			String frames = written.toString();
			written.setLength(0);
			if (asyncWrites) {
				pending = frames;
			}
			else {
				deliver(frames);
			}
		}

		void completePendingWrite() {
			WriteListener callback;
			boolean succeeded;
			synchronized (this) {
				String frames = pending;
				if (frames == null) {
					return;
				}
				pending = null;
				succeeded = !reset;
				if (succeeded) {
					deliver(frames);
				}
				else {
					failNextWrite = true;
				}
				callback = completed ? null : listener;
			}
			if (callback == null) {
				return;
			}
			if (!succeeded) {
				callback.onError(new IOException("stream reset by the client"));
				return;
			}
			try {
				callback.onWritePossible();
			}
			catch (IOException e) {
				callback.onError(e);
			}
		}

		synchronized void reset() {
			reset = true;
		}

		/** The response completes: what this stream holds unconfirmed may also reach the client. */
		synchronized void onComplete() {
			completed = true;
			deliveries.mayRepeat.addAll(unconfirmed);
			deliveries.mayRepeat.addAll(events(written.toString()));
			if (pending != null) {
				deliveries.mayRepeat.addAll(events(pending));
			}
		}

		private void deliver(String frames) {
			List<Integer> events = events(frames);
			if (completed) {
				deliveries.mayRepeat.addAll(events);
			}
			unconfirmed.addAll(events);
			deliveries.received.addAll(events);
		}

		private static List<Integer> events(String frames) {
			List<Integer> events = new ArrayList<>();
			for (String frame : frames.split("\n\n")) {
				if (frame.startsWith("data: ")) {
					events.add(Integer.parseInt(frame.substring("data: ".length())));
				}
			}
			return events;
		}

	}

	/** An async context whose complete() completes the response of its output. */
	static final class FakeContext implements AsyncContext {

		private final FakeOutput output;

		FakeContext(FakeOutput output) {
			this.output = output;
		}

		@Override
		public void complete() {
			output.onComplete();
		}

		@Override
		public void addListener(AsyncListener listener) {
		}

		@Override
		public void addListener(AsyncListener listener, ServletRequest request, ServletResponse response) {
		}

		@Override
		public ServletRequest getRequest() {
			throw new UnsupportedOperationException();
		}

		@Override
		public ServletResponse getResponse() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean hasOriginalRequestAndResponse() {
			return true;
		}

		@Override
		public void dispatch() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void dispatch(String path) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void dispatch(ServletContext context, String path) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void start(Runnable run) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends AsyncListener> T createListener(Class<T> type) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void setTimeout(long timeout) {
		}

		@Override
		public long getTimeout() {
			return 0;
		}

	}

}
