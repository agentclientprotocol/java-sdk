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
 * Implementation of the ACP Stdio transport for agents that communicates with clients
 * using standard input/output streams. Messages are exchanged as newline-delimited JSON-RPC
 * messages over stdin/stdout, with errors and debug information sent to stderr.
 *
 * <p>
 * This is the agent-side counterpart to {@code StdioAcpClientTransport}. While the client
 * spawns an agent process and connects to its stdin/stdout, the agent transport reads from
 * the process's System.in and writes to System.out.
 * </p>
 *
 * <p>
 * Key features:
 * <ul>
 * <li>Thread-safe message processing with dedicated schedulers</li>
 * <li>Proper resource management and graceful shutdown</li>
 * <li>Backpressure support via Reactor Sinks</li>
 * </ul>
 *
 * <p>
 * <b>The end of standard input.</b> A client that closes the agent's standard input will
 * send nothing more, but may still read standard output until it ends (a script that writes
 * its requests and closes the pipe does). So the end of standard input is not a
 * cancellation: every request already received is still handled and answered, and the
 * notifications its handler sends are written, in the order they are sent. Requests the
 * agent sends to the client can no longer be answered: one waiting when the input ends fails
 * at once with a JSON-RPC error ({@code -32603}), and one sent after it fails
 * with an {@link AcpConnectionException}. Once every request received has been answered,
 * standard output is closed and {@link #awaitTermination()} completes. The drain is bounded:
 * a request still unanswered after the drain timeout ({@link #DEFAULT_DRAIN_TIMEOUT} unless
 * given) is answered with {@code -32800} (request cancelled) and the transport terminates
 * without waiting for it; its handler's late answer is dropped.
 * </p>
 *
 * @author Mark Pollack
 */
public class StdioAcpAgentTransport implements AcpAgentTransport {

	private static final Logger logger = LoggerFactory.getLogger(StdioAcpAgentTransport.class);

	/**
	 * How long, by default, the requests received before standard input ended may take to be
	 * answered: 60 seconds, as long as {@code PromptTimeouts.DEFAULT_CANCEL_GRACE_PERIOD}
	 * gives a cancelled prompt.
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
	 * Creates a new StdioAcpAgentTransport with the default JsonMapper using
	 * System.in and System.out for communication.
	 */
	public StdioAcpAgentTransport() {
		this(AcpJsonMapper.createDefault());
	}

	/**
	 * Creates a new StdioAcpAgentTransport with the specified JsonMapper using
	 * System.in and System.out for communication.
	 * @param jsonMapper The JsonMapper to use for JSON serialization/deserialization
	 */
	@SuppressWarnings("SystemOut") // the stdio transport is the one owner of System.out
	public StdioAcpAgentTransport(AcpJsonMapper jsonMapper) {
		this(jsonMapper, System.in, System.out);
	}

	/**
	 * Creates a new StdioAcpAgentTransport with the specified JsonMapper and streams.
	 * This constructor allows for custom streams (useful for testing).
	 * @param jsonMapper The JsonMapper to use for JSON serialization/deserialization
	 * @param inputStream The input stream to read messages from (client → agent)
	 * @param outputStream The output stream to write messages to (agent → client)
	 */
	public StdioAcpAgentTransport(AcpJsonMapper jsonMapper, InputStream inputStream, OutputStream outputStream) {
		this(jsonMapper, inputStream, outputStream, DEFAULT_DRAIN_TIMEOUT);
	}

	/**
	 * Creates a new StdioAcpAgentTransport with the specified JsonMapper, streams, and drain
	 * timeout.
	 * @param jsonMapper The JsonMapper to use for JSON serialization/deserialization
	 * @param inputStream The input stream to read messages from (client → agent)
	 * @param outputStream The output stream to write messages to (agent → client)
	 * @param drainTimeout how long the requests received before the input ends may take to be
	 * answered; positive
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
			.subscribe();
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
	 * Sends a message to the client. A request sent once the client's input has ended fails
	 * with an {@link AcpConnectionException}: no answer can come.
	 */
	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		return Mono.zip(inboundReady.asMono(), outboundReady.asMono()).then(Mono.defer(() -> {
			if (message instanceof AcpSchema.JSONRPCRequest request && request.id() != null
					&& !this.awaitingClient.sent(request.id())) {
				return Mono.error(new AcpConnectionException(CLIENT_INPUT_ENDED));
			}
			OutboundSinks.emit(outboundSink, message);
			return Mono.empty();
		}));
	}

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

	@Override
	public void setExceptionHandler(Consumer<Throwable> handler) {
		this.exceptionHandler = handler;
	}

	/**
	 * Completes when the transport terminates: after it is closed, or after standard input
	 * has ended and every request received before has been answered and written (or the
	 * drain timeout has passed), with standard output closed.
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
