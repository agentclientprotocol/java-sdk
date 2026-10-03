/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.time.Duration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import com.agentclientprotocol.sdk.util.Assert;
import com.agentclientprotocol.sdk.util.OutboundSinks;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The agent side of the stdio transport: serves the one client that started this agent
 * process, reading ACP messages as lines of JSON from standard input and writing them to
 * standard output. Use it in an agent's {@code main} when a client such as an editor launches
 * the agent; to serve clients over the network, use the {@code acp-streamable-http-jetty}
 * module's {@code StreamableHttpAcpAgentTransport} instead. Pass it to
 * {@link com.agentclientprotocol.sdk.agent.AcpAgent#sync(AcpAgentTransport)
 * AcpAgent.sync(transport)} or
 * {@link com.agentclientprotocol.sdk.agent.AcpAgent#async(AcpAgentTransport)
 * AcpAgent.async(transport)}, or to the builder of an annotated agent
 * ({@code AcpAgentSupport}), and run the agent:
 *
 * <pre>{@code
 * AcpSyncAgent agent = AcpAgent.sync(new StdioAcpAgentTransport())
 *     .initializeHandler(request -> AcpSchema.InitializeResponse.ok())
 *     .newSessionHandler(request ->
 *         new AcpSchema.NewSessionResponse(UUID.randomUUID().toString(), null, null))
 *     .promptHandler((request, context) -> {
 *         context.sendMessage("Hello");
 *         return AcpSchema.PromptResponse.endTurn();
 *     })
 *     .build();
 * agent.run(); // returns once the client has closed standard input and every reply is written
 * }</pre>
 *
 * <p>Standard output carries the protocol, so the agent must log to standard error: a single
 * log line on standard output corrupts the stream. A line on standard input that is not a
 * JSON-RPC message is reported to the exception handler, answered with a JSON-RPC error and
 * skipped. The transport is thread-safe: messages may be sent from any thread, and are written
 * one at a time. It reads and writes on two daemon threads of its own
 * ({@code acp-agent-inbound} and {@code acp-agent-outbound}), so an agent's {@code main} must
 * wait for {@link #awaitTermination()}, as {@code AcpSyncAgent.run()} does.
 *
 * <p><b>The end of standard input.</b> A client that closes the agent's standard input sends
 * nothing more, but may still read standard output until it ends (a script that writes its
 * requests and closes the pipe does). So the end of standard input is not a cancellation:
 * every request already received is still answered, and the notifications its handler sends
 * are written, in the order they are sent. Requests the agent sends to the client can no
 * longer be answered: one still waiting fails at once with a JSON-RPC error ({@code -32603}),
 * and one sent later fails with an {@link AcpConnectionException}. Once every request received
 * has been answered, standard output is closed and {@link #awaitTermination()} completes, so a
 * {@code main} waiting on it returns. The drain is bounded: a request still unanswered after
 * the drain timeout ({@link #DEFAULT_DRAIN_TIMEOUT} unless given) is answered with
 * {@code -32800} (request cancelled), the transport terminates without waiting for it, and its
 * handler's late answer is dropped.
 *
 * <p><b>Closing does not release {@code System.in}.</b> The transport reads standard input on
 * its {@code acp-agent-inbound} thread, and a read of {@code System.in} cannot be interrupted.
 * Closing the transport stops it handing messages on, but a reader blocked in a read stays
 * blocked, holding {@code System.in}'s lock, until the next line arrives or standard input
 * ends; it then discards that line and ends. The transport never closes {@code System.in},
 * which belongs to the process. In an agent process that is harmless: the process owns its
 * standard input and exits with it. Elsewhere it is not: under Maven Surefire, for instance,
 * standard input carries the test fork's commands, and a reader left on it takes those bytes
 * and can stall the fork's exit. Embedders and tests should therefore use the
 * {@linkplain #StdioAcpAgentTransport(AcpJsonMapper, InputStream, OutputStream) constructor
 * that takes explicit streams}, or the in-memory transport of the {@code acp-test} module, and
 * keep the {@code System.in} constructors for an agent's {@code main}. A test suite that cannot
 * avoid them (an application whose configuration builds this transport by default) can
 * install an empty standard input before any test runs, for instance with
 * {@code System.setIn(new java.io.ByteArrayInputStream(new byte[0]))} in a JUnit launcher
 * session listener. The transport takes {@code System.in} when it is constructed, so its
 * reader then sees the end of input at once and ends (and, as at any end of input, the
 * transport closes the output stream it was given), while Surefire keeps its own reference to
 * the real standard input.
 *
 * @author Mark Pollack
 */
public class StdioAcpAgentTransport implements AcpAgentTransport {

	private static final Logger logger = LoggerFactory.getLogger(StdioAcpAgentTransport.class);

	/**
	 * How long, by default, the requests received before standard input ended may take to be
	 * answered: 60 seconds, as long as
	 * {@link com.agentclientprotocol.sdk.spec.PromptTimeouts#DEFAULT_CANCEL_GRACE_PERIOD} gives
	 * a cancelled prompt. Pass another value to the four-argument constructor.
	 */
	public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(60);

	/** The message of the error that fails a request to the client once its input has ended. */
	static final String CLIENT_INPUT_ENDED = "The ACP client closed its input; it can no longer answer requests";

	private final AcpJsonMapper jsonMapper;

	private final InputStream inputStream;

	private final OutputStream outputStream;

	private final Sinks.Many<JSONRPCMessage> inboundSink;

	private final Sinks.Many<JSONRPCMessage> outboundSink;

	private final Sinks.One<Void> inboundReady = Sinks.one();

	private final Sinks.One<Void> outboundReady = Sinks.one();

	private final Sinks.One<Void> terminationSink = Sinks.one();

	private final Scheduler inboundScheduler;

	private final Scheduler outboundScheduler;

	private final AtomicBoolean isClosing = new AtomicBoolean(false);

	private final AtomicBoolean isStarted = new AtomicBoolean(false);

	private final AtomicBoolean terminated = new AtomicBoolean(false);

	/** How long the requests received before standard input ended may take to be answered. */
	private final Duration drainTimeout;

	/** The ids of the client's requests not answered yet. */
	private final Set<Object> awaitingAgent = ConcurrentHashMap.newKeySet();

	/** The agent's requests the client has not answered yet. */
	private final UnansweredRequests awaitingClient = new UnansweredRequests();

	/** The session's handler, which also takes the errors failing requests to the client. */
	private volatile @Nullable Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler;

	private volatile @Nullable Disposable drainTimer;

	private Consumer<Throwable> exceptionHandler = t -> logger.error("Transport error", t);

	/**
	 * Creates a transport on {@code System.in} and {@code System.out}, with the JSON mapper
	 * found on the classpath ({@link AcpJsonMapper#createDefault()}), for an agent process's
	 * {@code main}. Closing it does not release {@code System.in} (see the class
	 * documentation); embedders and tests pass explicit streams instead.
	 */
	public StdioAcpAgentTransport() {
		this(AcpJsonMapper.createDefault());
	}

	/**
	 * Creates a transport on {@code System.in} and {@code System.out}, with the given JSON
	 * mapper, for an agent process's {@code main}. Closing it does not release
	 * {@code System.in} (see the class documentation); embedders and tests pass explicit
	 * streams instead.
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @throws IllegalArgumentException if {@code jsonMapper} is null
	 */
	@SuppressWarnings("SystemOut") // the stdio transport is the one owner of System.out
	public StdioAcpAgentTransport(AcpJsonMapper jsonMapper) {
		this(jsonMapper, System.in, System.out);
	}

	/**
	 * Creates a transport on the given streams, with the default drain timeout, for an agent
	 * embedded in another program or under test.
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @param inputStream the stream the client's messages are read from
	 * @param outputStream the stream the agent's messages are written to; closed once the
	 * input has ended and every request has been answered
	 * @throws IllegalArgumentException if an argument is null
	 */
	public StdioAcpAgentTransport(AcpJsonMapper jsonMapper, InputStream inputStream, OutputStream outputStream) {
		this(jsonMapper, inputStream, outputStream, DEFAULT_DRAIN_TIMEOUT);
	}

	/**
	 * Creates a transport on the given streams with its own drain timeout: how long the
	 * requests received before the input ends may take to be answered.
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @param inputStream the stream the client's messages are read from
	 * @param outputStream the stream the agent's messages are written to; closed once the
	 * input has ended and every request has been answered
	 * @param drainTimeout the drain timeout; positive
	 * @throws IllegalArgumentException if an argument is null or {@code drainTimeout} is not
	 * positive
	 */
	public StdioAcpAgentTransport(AcpJsonMapper jsonMapper, InputStream inputStream, OutputStream outputStream,
			Duration drainTimeout) {
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");
		Assert.notNull(inputStream, "The InputStream can not be null");
		Assert.notNull(outputStream, "The OutputStream can not be null");
		Assert.notNull(drainTimeout, "The drainTimeout can not be null");
		Assert.isTrue(!drainTimeout.isNegative() && !drainTimeout.isZero(), "The drainTimeout must be positive");

		this.jsonMapper = jsonMapper;
		this.inputStream = inputStream;
		this.outputStream = outputStream;
		this.drainTimeout = drainTimeout;

		this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
		this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();

		// Use daemon threads so JVM can exit if closeGracefully() isn't called
		this.inboundScheduler = Schedulers.fromExecutorService(
				Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "acp-agent-inbound");
					t.setDaemon(true);
					return t;
				}), "agent-inbound");
		this.outboundScheduler = Schedulers.fromExecutorService(
				Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "acp-agent-outbound");
					t.setDaemon(true);
					return t;
				}), "agent-outbound");
	}

	/**
	 * {@inheritDoc}
	 * <p>Starts the thread that reads the input stream and the thread that writes the output
	 * stream, when the returned Mono is subscribed. A second call fails with an
	 * {@link IllegalStateException}, even when the Mono of the first was never subscribed.
	 */
	@Override
	public Mono<Void> start(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		if (!isStarted.compareAndSet(false, true)) {
			return Mono.error(new IllegalStateException("Already started"));
		}

		return Mono.fromRunnable(() -> {
			logger.info("ACP agent transport starting");
			this.handler = handler;
			handleIncomingMessages(handler);
			startInboundProcessing();
			startOutboundProcessing();
			logger.info("ACP agent transport started");
		}).then(Mono.zip(inboundReady.asMono(), outboundReady.asMono()).then());
	}

	private void handleIncomingMessages(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		OutboundSinks.replyThrough(this.inboundSink, handler, this.outboundSink, this.inboundScheduler::dispose);
	}

	/**
	 * Starts the inbound processing thread that reads JSON-RPC messages from stdin.
	 * Messages are deserialized and emitted to the inbound sink.
	 */
	private void startInboundProcessing() {
		this.inboundScheduler.schedule(() -> {
			inboundReady.tryEmitValue(null);
			try {
				readMessages(new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8)));
			}
			catch (Exception e) {
				logIfNotClosing("Error in inbound processing", e);
				exceptionHandler.accept(e);
			}
			finally {
				endInput();
			}
		});
	}

	/**
	 * Standard input has ended (or failed): fails the requests to the client still waiting,
	 * starts the drain timeout, and ends the inbound stream. The replies still to come are
	 * written; the transport terminates once the last is (see {@link #terminate()}).
	 */
	private void endInput() {
		logger.debug("Agent transport input ended");
		for (Object id : this.awaitingClient.end()) {
			failRequestToClient(id);
		}
		if (!this.isClosing.get()) {
			this.drainTimer = AcpSchedulers.after(this.drainTimeout).subscribe(tick -> abandonDrain());
		}
		this.inboundSink.tryEmitComplete();
	}

	/**
	 * Fails a request to the client that can no longer be answered, as the session fails any
	 * request: with an error response, here one the client did not send.
	 */
	private void failRequestToClient(Object id) {
		Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> sessionHandler = this.handler;
		if (sessionHandler == null) {
			return;
		}
		JSONRPCMessage failure = new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, id, null,
				new AcpSchema.JSONRPCError(AcpErrorCodes.INTERNAL_ERROR, CLIENT_INPUT_ENDED, null));
		Mono.just(failure)
			.transform(sessionHandler)
			.subscribe(reply -> logger.debug("Unexpected reply to a failed request: {}", reply),
					error -> logger.debug("Failing request {} to the client failed", id, error));
	}

	/**
	 * The drain timeout has passed: on the writer's thread, so that no reply is written after
	 * it, answers each request still unanswered with -32800 and terminates.
	 */
	private void abandonDrain() {
		try {
			this.outboundScheduler.schedule(this::cancelUnanswered);
		}
		catch (RejectedExecutionException e) {
			logger.debug("Drain timeout after the transport terminated");
		}
	}

	private void cancelUnanswered() {
		if (this.terminated.get()) {
			return;
		}
		this.isClosing.set(true);
		logger.warn("{} request(s) received before the client closed its input are still unanswered after {};"
				+ " answering them as cancelled", this.awaitingAgent.size(), this.drainTimeout);
		for (Object id : List.copyOf(this.awaitingAgent)) {
			if (this.awaitingAgent.remove(id)) {
				writeQuietly(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, id, null,
						new AcpSchema.JSONRPCError(AcpErrorCodes.REQUEST_CANCELLED,
								"Request cancelled: not answered within " + this.drainTimeout
										+ " after the client closed its input",
								null)));
			}
		}
		terminate();
		try {
			this.outboundSink.emitComplete(Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)));
		}
		catch (Sinks.EmissionException e) {
			logger.debug("Outbound stream already ended", e);
		}
	}

	private void writeQuietly(JSONRPCMessage message) {
		try {
			writeLine(message);
		}
		catch (IOException e) {
			logger.debug("Stream closed while cancelling unanswered requests", e);
		}
	}

	/**
	 * Ends the transport, once: after an end of input, flushes and closes standard output,
	 * so the client reading it sees the end; then completes {@link #awaitTermination()}.
	 */
	private void terminate() {
		if (!this.terminated.compareAndSet(false, true)) {
			return;
		}
		Disposable timer = this.drainTimer;
		if (timer != null) {
			timer.dispose();
		}
		if (this.awaitingClient.ended()) {
			synchronized (this.outputStream) {
				try {
					this.outputStream.flush();
					this.outputStream.close();
				}
				catch (IOException e) {
					logger.debug("Closing the output stream failed", e);
				}
			}
		}
		this.terminationSink.tryEmitValue(null);
		logger.debug("Agent transport terminated");
	}

	/**
	 * Reads one message per line until stdin ends, the transport closes, or the inbound
	 * sink refuses a message. A line that is not a JSON-RPC message does not stop it.
	 */
	private void readMessages(BufferedReader reader) {
		boolean reading = true;
		while (reading && !isClosing.get()) {
			reading = readMessage(reader);
		}
	}

	/**
	 * Reads the next line and emits its message to the inbound sink.
	 * @return whether to go on reading
	 */
	private boolean readMessage(BufferedReader reader) {
		String line;
		try {
			line = reader.readLine();
		}
		catch (IOException e) {
			// Closing interrupts the read: that is the expected end, not an error to report.
			if (!isClosing.get()) {
				logger.error("Error reading from stdin", e);
				exceptionHandler.accept(e);
			}
			return false;
		}
		if (line == null || isClosing.get()) {
			return false;
		}
		if (line.isBlank()) {
			return true;
		}
		logger.debug("Received JSON message ({} characters)", line.length());
		JSONRPCMessage message;
		try {
			message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, line);
		}
		catch (Exception e) {
			rejectUnreadable(line, e);
			return true;
		}
		recordInbound(message);
		if (!this.inboundSink.tryEmitNext(message).isSuccess()) {
			logIfNotClosing("Failed to enqueue inbound message");
			return false;
		}
		return true;
	}

	/** Records a request from the client as awaiting its answer, and an answer from the client. */
	private void recordInbound(JSONRPCMessage message) {
		if (message instanceof AcpSchema.JSONRPCRequest request && request.id() != null) {
			this.awaitingAgent.add(request.id());
		}
		else if (message instanceof AcpSchema.JSONRPCResponse response && response.id() != null) {
			this.awaitingClient.answered(response.id());
		}
	}

	/**
	 * Reports a line that is not a JSON-RPC message, answers it with the JSON-RPC error for
	 * an unreadable message (its id is unknown, so null), and lets reading go on, as the
	 * WebSocket and Streamable HTTP transports do.
	 */
	private void rejectUnreadable(String line, Exception e) {
		logIfNotClosing("Skipped an inbound line that is not a JSON-RPC message", e);
		exceptionHandler.accept(e);
		try {
			OutboundSinks.emit(outboundSink, AcpSchema.unreadableMessageResponse(jsonMapper, line));
		}
		catch (Sinks.EmissionException emission) {
			logIfNotClosing("Failed to answer an unreadable inbound line", emission);
		}
	}

	/**
	 * Starts the outbound processing thread that writes JSON-RPC messages to stdout.
	 * Messages are serialized to JSON and written with a newline delimiter.
	 */
	private void startOutboundProcessing() {
		outboundSink.asFlux()
			.doOnSubscribe(subscription -> outboundReady.tryEmitValue(null))
			.publishOn(outboundScheduler)
			.<JSONRPCMessage>handle(this::write)
			.doOnComplete(() -> {
				isClosing.set(true);
				terminate();
				outboundScheduler.dispose();
			})
			.doOnError(e -> {
				if (!isClosing.get()) {
					logger.error("Error in outbound processing", e);
					isClosing.set(true);
					terminate();
					outboundScheduler.dispose();
				}
			})
			// Logged above unless closing; not dropped to Reactor's ERROR hook.
			.subscribe(ignored -> {
			}, error -> logger.debug("Outbound processing ended: {}", error.toString()));
	}

	/**
	 * Writes one message to stdout as a line of JSON; once the transport is closing, ends
	 * the outbound stream instead.
	 */
	private void write(JSONRPCMessage message, SynchronousSink<JSONRPCMessage> sink) {
		if (isClosing.get()) {
			sink.complete();
			return;
		}
		try {
			writeLine(message);
			if (message instanceof AcpSchema.JSONRPCResponse response && response.id() != null) {
				this.awaitingAgent.remove(response.id());
			}
			sink.next(message);
		}
		catch (IOException e) {
			if (isClosing.get()) {
				logger.debug("Stream closed during shutdown", e);
				return;
			}
			logger.error("Error writing message", e);
			exceptionHandler.accept(e);
			sink.error(new RuntimeException(e));
		}
	}

	private void writeLine(JSONRPCMessage message) throws IOException {
		// Messages are delimited by newlines, and MUST NOT contain embedded newlines.
		String jsonMessage = jsonMapper.writeValueAsString(message)
			.replace("\r\n", "\\n")
			.replace("\n", "\\n")
			.replace("\r", "\\n");
		synchronized (outputStream) {
			outputStream.write(jsonMessage.getBytes(StandardCharsets.UTF_8));
			outputStream.write("\n".getBytes(StandardCharsets.UTF_8));
			outputStream.flush();
		}
		logger.debug("Sent JSON message ({} characters)", jsonMessage.length());
	}

	/**
	 * {@inheritDoc}
	 * <p>Waits until the transport has started, then queues the message for the writer
	 * thread. A request sent once standard input has ended fails with an
	 * {@link AcpConnectionException}, since no answer can come; responses and notifications
	 * are still written until the transport terminates. Once the transport is closed, the
	 * Mono fails with an {@link AcpConnectionException}.
	 */
	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		return Mono.zip(inboundReady.asMono(), outboundReady.asMono()).then(Mono.defer(() -> {
			if (message instanceof AcpSchema.JSONRPCRequest request && request.id() != null
					&& !this.awaitingClient.sent(request.id())) {
				return Mono.error(new AcpConnectionException(CLIENT_INPUT_ENDED));
			}
			try {
				OutboundSinks.emit(outboundSink, message);
			}
			catch (Sinks.EmissionException e) {
				return Mono.error(OutboundSinks.isClosed(e) ? new AcpConnectionException("The transport is closed", e)
						: new AcpConnectionException("The message could not be queued: " + e.getReason(), e));
			}
			return Mono.empty();
		}));
	}

	/**
	 * {@inheritDoc}
	 * <p>Stops handing on the client's messages and writing the agent's (messages still queued
	 * are dropped), stops the transport's threads and completes {@link #awaitTermination()}.
	 * It closes the output stream only if standard input has already ended. It never closes
	 * the input stream, and does not end a read already in progress: on {@code System.in},
	 * which cannot be interrupted, the reader thread stays blocked until the next line or the
	 * end of standard input, then discards what it read and ends (see the class
	 * documentation). {@link #close()} does the same.
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			logger.debug("Agent transport closing gracefully");
			isClosing.set(true);
			inboundSink.tryEmitComplete();
			outboundSink.tryEmitComplete();
		}).then(Mono.fromRunnable(() -> {
			try {
				inboundScheduler.dispose();
				outboundScheduler.dispose();
				terminate();
				logger.debug("Agent transport closed");
			}
			catch (Exception e) {
				logger.error("Error during graceful shutdown", e);
			}
		}));
	}

	/**
	 * {@inheritDoc}
	 * <p>The default logs each error at ERROR. Call it before {@link #start}.
	 */
	@Override
	public void setExceptionHandler(Consumer<Throwable> handler) {
		this.exceptionHandler = handler;
	}

	/**
	 * {@inheritDoc}
	 * <p>It completes after the transport is closed, or after standard input has ended and
	 * every request received before has been answered and written (or the drain timeout has
	 * passed) and standard output has been closed.
	 * @return a Mono that completes when the transport terminates
	 */
	@Override
	public Mono<Void> awaitTermination() {
		return terminationSink.asMono();
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return jsonMapper.convertValue(data, typeRef);
	}

	private void logIfNotClosing(String message) {
		if (!isClosing.get()) {
			logger.error(message);
		}
	}

	private void logIfNotClosing(String message, Exception e) {
		if (!isClosing.get()) {
			logger.error(message, e);
		}
	}

}
