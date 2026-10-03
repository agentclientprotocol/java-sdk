/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.Assert;
import com.agentclientprotocol.sdk.util.OutboundSinks;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The client side of the stdio transport: starts the agent as a child process and exchanges
 * ACP messages with it as lines of JSON on the process's standard input and output. Use it
 * when the client launches the agent itself, as editors and command-line tools do; to reach an
 * agent that is already running, use {@link StreamableHttpAcpClientTransport} or
 * {@link WebSocketAcpClientTransport}. Describe the process with {@link AgentParameters} and
 * pass the transport to {@code AcpClient.sync(transport)} or {@code AcpClient.async(transport)}:
 *
 * <pre>{@code
 * AgentParameters params = AgentParameters.builder("my-agent").arg("--acp").build();
 * try (AcpSyncClient client = AcpClient.sync(new StdioAcpClientTransport(params)).build()) {
 *     client.initialize();
 * }
 * }</pre>
 *
 * <p>The process starts when {@link #connect} runs, which building the client does, so a
 * command that cannot be started fails the build. One transport starts one process for one
 * client: a second {@code connect} is refused. The process runs in the client's working
 * directory and inherits the client's whole environment, with the variables of
 * {@link AgentParameters#getEnv()} added, so the agent can read every secret in that
 * environment. A line on the agent's standard output that is not a JSON-RPC message is
 * reported to the exception handler, answered with a JSON-RPC error and skipped.
 *
 * <p>Each line the agent writes to standard error is logged at INFO as {@code agent: <line>} on
 * the logger {@value #AGENT_STDERR_LOGGER}, so it stays visible by default. Set that logger's
 * level to WARN to hide it, or pass a handler of your own to {@link #setStdErrorHandler}.
 *
 * <p>{@link #closeGracefully()} lets the agent exit by itself. It closes the agent's standard
 * input and waits up to {@value #END_OF_INPUT_WAIT_MILLIS} ms for the process to exit, as an
 * ACP stdio agent does when its input ends; an agent built with this SDK answers what it
 * received, flushes and exits 0. A process still running then is sent SIGTERM
 * ({@link Process#destroy()}), and killed ({@link Process#destroyForcibly()}) if it has not
 * exited five seconds later. On POSIX systems, a logged exit code of 143 therefore means that
 * the agent ignored the end of its input. The process is stopped, and its end logged, once
 * however often the transport is closed: {@code closeGracefully()} followed by
 * {@code close()}, as try-with-resources does, is safe. The steps run on the thread that
 * subscribes to {@code closeGracefully()}, or that calls {@code close()}, and can hold it for
 * about seven seconds.
 *
 * <p>The transport is thread-safe: messages may be sent from any thread, and one writer thread
 * writes them one at a time, in the order they were queued. It uses three daemon threads of
 * its own ({@code acp-client-inbound}, {@code acp-client-outbound} and
 * {@code acp-client-error}), so it does not keep the JVM alive, but the agent process runs
 * until the transport is closed or the process ends by itself.
 *
 * @author Mark Pollack
 * @author Christian Tzolov (MCP Java SDK)
 * @author Dariusz Jędrzejczyk (MCP Java SDK)
 */
public class StdioAcpClientTransport implements AcpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(StdioAcpClientTransport.class);

	/**
	 * The name of the logger that receives the agent's standard error, one INFO event per
	 * line: {@value}. Set its level to WARN or OFF to hide the agent's output.
	 */
	public static final String AGENT_STDERR_LOGGER = "com.agentclientprotocol.sdk.client.transport.agent-stderr";

	private static final Logger agentStderr = LoggerFactory.getLogger(AGENT_STDERR_LOGGER);

	private final Sinks.Many<JSONRPCMessage> inboundSink;

	private final Sinks.Many<JSONRPCMessage> outboundSink;

	/** The agent process being communicated with; null until {@link #connect} starts it */
	private volatile @Nullable Process process;

	private final AcpJsonMapper jsonMapper;

	/** Scheduler for handling inbound messages from the agent process */
	private final Scheduler inboundScheduler;

	/** Scheduler for handling outbound messages to the agent process */
	private final Scheduler outboundScheduler;

	/** Scheduler for handling error messages from the agent process */
	private final Scheduler errorScheduler;

	/** Parameters for configuring and starting the agent process */
	private final AgentParameters params;

	private final Sinks.Many<String> errorSink;

	private volatile boolean isClosing = false;

	/** Set by {@link #closeGracefully}: the end of the agent process is then expected. */
	private volatile boolean closedLocally = false;

	/**
	 * Terminates when the transport can no longer deliver: empty once closed locally, with
	 * an error naming the exit when the agent process ended on its own.
	 */
	private final Sinks.One<Void> terminationSink = Sinks.one();

	/**
	 * A transport instance carries exactly one session. {@link #connect} subscribes the
	 * unicast inbound and outbound sinks and starts the agent process; a second call would
	 * subscribe them again and start a second process, so it is refused up front.
	 */
	private final AtomicBoolean isConnected = new AtomicBoolean(false);

	/**
	 * How long {@link #closeGracefully()} waits, after closing the agent's standard input, for
	 * the agent process to exit by itself before it sends SIGTERM: {@value} ms. An agent that
	 * needs longer to answer what it received is ended by SIGTERM before it is done.
	 */
	public static final long END_OF_INPUT_WAIT_MILLIS = 2_000;

	/** How long to wait for the agent process to exit after SIGTERM before killing it. */
	private static final Duration TERM_WAIT = Duration.ofSeconds(5);

	/** Set by the first {@link #closeGracefully}: the agent process is stopped once. */
	private final AtomicBoolean stopping = new AtomicBoolean(false);

	/** How long to wait for the agent process to exit after its standard output ends. */
	private static final Duration EXIT_WAIT = Duration.ofSeconds(5);

	private volatile Consumer<Throwable> exceptionHandler = t -> logger.error("Transport error", t);

	// visible for tests
	private Consumer<String> stdErrorHandler = line -> agentStderr.info("agent: {}", line);

	/**
	 * Creates a transport that will start the agent process described by {@code params}, with
	 * the JSON mapper found on the classpath ({@link AcpJsonMapper#createDefault()}). Nothing
	 * starts until {@link #connect}.
	 * @param params the command, arguments and environment of the agent process
	 * @throws IllegalArgumentException if {@code params} is null
	 */
	public StdioAcpClientTransport(AgentParameters params) {
		this(params, AcpJsonMapper.createDefault());
	}

	/**
	 * Creates a transport that will start the agent process described by {@code params}, with
	 * the given JSON mapper. Nothing starts until {@link #connect}.
	 * @param params the command, arguments and environment of the agent process
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @throws IllegalArgumentException if an argument is null
	 */
	public StdioAcpClientTransport(AgentParameters params, AcpJsonMapper jsonMapper) {
		Assert.notNull(params, "The params can not be null");
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");

		this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
		this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();

		this.params = params;

		this.jsonMapper = jsonMapper;

		this.errorSink = Sinks.many().unicast().onBackpressureBuffer();

		// Start threads - use daemon threads so JVM can exit if closeGracefully() isn't called
		this.inboundScheduler = Schedulers.fromExecutorService(
				Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "acp-client-inbound");
					t.setDaemon(true);
					return t;
				}), "inbound");
		this.outboundScheduler = Schedulers.fromExecutorService(
				Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "acp-client-outbound");
					t.setDaemon(true);
					return t;
				}), "outbound");
		this.errorScheduler = Schedulers.fromExecutorService(
				Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "acp-client-error");
					t.setDaemon(true);
					return t;
				}), "error");
	}

	/**
	 * {@inheritDoc}
	 * <p>Starts the agent process with the command, arguments and environment of the
	 * {@link AgentParameters}, and the threads that read its standard output and standard error
	 * and write its standard input. The work runs on the thread that subscribes to the returned
	 * Mono, when it subscribes. Messages sent before are kept and written once the process
	 * runs. What the handler returns is not written: the client session sends its answers
	 * itself.
	 * @param handler receives each message the agent writes
	 * @return a Mono that completes once the process has started; it errors with an
	 * {@link IllegalStateException} if this transport was connected before, and with a
	 * {@link RuntimeException} if the process cannot be started
	 */
	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		// The guard runs at subscribe time, not at assembly, so a connect publisher that is
		// subscribed twice is refused the second time and one that is never subscribed does
		// not consume the connection.
		return Mono.defer(() -> {
			if (!isConnected.compareAndSet(false, true)) {
				return Mono.error(new IllegalStateException("StdioAcpClientTransport is already connected. "
						+ "A transport instance carries exactly one session and cannot be reused: build one client per "
						+ "transport, or share one AcpAsyncClient by wrapping it with new AcpSyncClient(asyncClient)."));
			}
			return Mono.fromRunnable(() -> {
				logger.info("ACP agent starting.");
				handleIncomingMessages(handler);
				handleIncomingErrors();

				// Prepare command and environment
				List<String> fullCommand = new ArrayList<>();
				fullCommand.add(params.getCommand());
				fullCommand.addAll(params.getArgs());

				ProcessBuilder processBuilder = this.getProcessBuilder();
				processBuilder.command(fullCommand);
				processBuilder.environment().putAll(params.getEnv());

				// Start the process
				Process started;
				try {
					started = processBuilder.start();
				}
				catch (IOException e) {
					throw new RuntimeException("Failed to start process with command: " + fullCommand, e);
				}
				this.process = started;

				// Start threads
				startInboundProcessing(started);
				startOutboundProcessing(started);
				startErrorProcessing(started);
				logger.info("ACP agent started");
			});
		});
	}

	/**
	 * Returns the {@link ProcessBuilder} that {@link #connect} starts the agent process with,
	 * after setting its command and adding {@link AgentParameters#getEnv()} to its environment.
	 * A subclass may override it to configure what {@code AgentParameters} cannot, such as the
	 * working directory. It must leave standard input, output and error as pipes, which the
	 * transport reads and writes.
	 * @return a new process builder
	 */
	protected ProcessBuilder getProcessBuilder() {
		return new ProcessBuilder();
	}

	/**
	 * Replaces the handler for the lines the agent process writes to its standard error. The
	 * default logs each line at INFO on {@value #AGENT_STDERR_LOGGER}. Call it before
	 * {@link #connect}. The handler runs on the transport's {@code acp-client-error} thread, one
	 * line at a time; standard error is not read while it runs, so a handler that blocks can
	 * stall an agent that writes a lot to standard error.
	 * @param errorHandler receives each line, without its line terminator; not null
	 */
	public void setStdErrorHandler(Consumer<String> errorHandler) {
		this.stdErrorHandler = errorHandler;
	}

	/**
	 * {@inheritDoc}
	 * <p>The default logs each error at ERROR. The end of the agent process is not reported
	 * here but by {@link #awaitTermination()}.
	 * @throws IllegalArgumentException if {@code handler} is null
	 */
	@Override
	public void setExceptionHandler(Consumer<Throwable> handler) {
		Assert.notNull(handler, "The handler can not be null");
		this.exceptionHandler = handler;
	}

	/**
	 * Blocks until the agent process exits. It does not stop the process; close the transport
	 * for that.
	 * @throws IllegalStateException if {@link #connect} has not started the process
	 * @throws RuntimeException if the calling thread is interrupted while it waits
	 */
	public void awaitForExit() {
		Process process = this.process;
		if (process == null) {
			throw new IllegalStateException("The agent process has not been started: connect first");
		}
		try {
			process.waitFor();
		}
		catch (InterruptedException e) {
			throw new RuntimeException("Process interrupted", e);
		}
	}

	/**
	 * Starts the error processing thread that reads from the process's error stream.
	 * Error messages are logged and emitted to the error sink.
	 */
	private void startErrorProcessing(Process process) {
		readLines(this.errorScheduler, process.getErrorStream(), "error stream", this::emitError,
				this.errorSink::tryEmitComplete);
	}

	private boolean emitError(String line) {
		try {
			if (this.errorSink.tryEmitNext(line).isSuccess()) {
				return true;
			}
			if (!isClosing) {
				logger.error("Failed to emit error message");
			}
		}
		catch (Exception e) {
			if (!isClosing) {
				logger.error("Error processing error message", e);
			}
		}
		return false;
	}

	/**
	 * Reads one of the process's output streams line by line on its own thread, until the
	 * stream ends, the transport closes, or {@code accept} refuses a line; then marks the
	 * transport closing and runs {@code onEnd}.
	 */
	private void readLines(Scheduler scheduler, InputStream stream, String streamName, Predicate<String> accept,
			Runnable onEnd) {
		scheduler.schedule(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				while (!isClosing) {
					String line = reader.readLine();
					if (line == null || !accept.test(line)) {
						break;
					}
				}
			}
			catch (IOException e) {
				if (!isClosing) {
					logger.error("Error reading from " + streamName, e);
					this.exceptionHandler.accept(e);
				}
			}
			finally {
				isClosing = true;
				onEnd.run();
			}
		});
	}

	private void handleIncomingMessages(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> inboundMessageHandler) {
		this.inboundSink.asFlux()
			.flatMap(message -> Mono.just(message)
				.transform(inboundMessageHandler)
				.contextWrite(ctx -> ctx.put("observation", "myObservation")))
			.subscribe(ignored -> {
			}, error -> logger.warn("Inbound message processing ended with an error", error));
	}

	private void handleIncomingErrors() {
		this.errorSink.asFlux()
			.subscribe(line -> this.stdErrorHandler.accept(line),
					error -> logger.warn("Stopped handing the agent's standard error to its handler", error));
	}

	/**
	 * {@inheritDoc}
	 * <p>The message is queued when this method is called, not when the returned Mono is
	 * subscribed, and the Mono completes at once; the writer thread writes it as one line. A
	 * message sent before {@link #connect} is written once the process starts. Once the
	 * transport is closed, messages are dropped and the Mono still completes.
	 */
	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		OutboundSinks.emit(this.outboundSink, message);
		return Mono.empty();
	}

	/**
	 * Starts the inbound processing thread that reads JSON-RPC messages from the
	 * process's input stream. Messages are deserialized and emitted to the inbound sink.
	 */
	private void startInboundProcessing(Process process) {
		readLines(this.inboundScheduler, process.getInputStream(), "input stream", this::emitInbound, () -> {
			this.inboundSink.tryEmitComplete();
			terminated(process);
		});
	}

	/**
	 * The agent's standard output has ended: once the process has exited, reports the end of
	 * the transport, naming the exit, unless it was closed locally.
	 */
	private void terminated(Process process) {
		if (this.closedLocally) {
			this.terminationSink.tryEmitEmpty();
			return;
		}
		String reason;
		try {
			// The agent normally exits right after its output ends; one that closed its
			// output but goes on running can deliver nothing more either.
			reason = process.waitFor(EXIT_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
					? exitDescription(process.exitValue())
					: "ACP agent process closed its standard output but has not exited";
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			reason = "ACP agent process closed its standard output";
		}
		if (this.closedLocally) {
			this.terminationSink.tryEmitEmpty();
			return;
		}
		logger.info("{}", reason);
		this.terminationSink.tryEmitError(new AcpConnectionException(reason));
	}

	/**
	 * Names how the agent process ended. On POSIX systems Java reports a process killed by
	 * signal {@code n} as exit code {@code 128 + n}.
	 */
	static String exitDescription(int exitCode) {
		String description = "ACP agent process exited with code " + exitCode;
		boolean windows = System.getProperty("os.name", "").startsWith("Windows");
		if (!windows && exitCode > 128 && exitCode < 128 + 65) {
			description += " (signal " + (exitCode - 128) + ")";
		}
		return description;
	}

	/**
	 * Emits the message a line of the agent's output carries. A line that is not a JSON-RPC
	 * message is reported, answered, and skipped; a blank line is skipped.
	 * @return whether to go on reading
	 */
	private boolean emitInbound(String line) {
		if (line.isBlank()) {
			return true;
		}
		logger.trace("RECV: {}", line);
		JSONRPCMessage message;
		try {
			message = AcpSchema.deserializeJsonRpcMessage(this.jsonMapper, line);
		}
		catch (Exception e) {
			rejectUnreadable(line, e);
			return true;
		}
		if (this.inboundSink.tryEmitNext(message).isSuccess()) {
			return true;
		}
		if (!isClosing) {
			logger.error("Failed to enqueue an inbound {}", message.getClass().getSimpleName());
			logger.debug("Inbound message not enqueued: {}", message);
		}
		return false;
	}

	/**
	 * Reports a line that is not a JSON-RPC message to the exception handler and answers it
	 * with the JSON-RPC error for an unreadable message (its id is unknown, so null): the
	 * agent may send requests, so this side is the server for them.
	 */
	private void rejectUnreadable(String line, Exception e) {
		if (!isClosing) {
			logger.error("Skipped an inbound line that is not a JSON-RPC message", e);
			logger.debug("Skipped inbound line: {}", line);
		}
		this.exceptionHandler.accept(e);
		try {
			OutboundSinks.emit(this.outboundSink, AcpSchema.unreadableMessageResponse(this.jsonMapper, line));
		}
		catch (Sinks.EmissionException emission) {
			if (!isClosing) {
				logger.error("Failed to answer an unreadable inbound line", emission);
			}
		}
	}

	/**
	 * Starts the outbound processing thread that writes JSON-RPC messages to the
	 * process's output stream. Messages are serialized to JSON and written with a newline
	 * delimiter.
	 */
	private void startOutboundProcessing(Process process) {
		this.handleOutbound(messages -> messages
			// this bit is important since writes come from user threads, and we
			// want to ensure that the actual writing happens on a dedicated thread
			.publishOn(outboundScheduler)
			.handle((message, sink) -> write(process, message, sink)));
	}

	/**
	 * Writes one message to the process's standard input as a line of JSON; once the
	 * transport is closing, drops it.
	 */
	private void write(Process process, JSONRPCMessage message, SynchronousSink<JSONRPCMessage> sink) {
		if (isClosing) {
			return;
		}
		try {
			// Messages are delimited by newlines, and MUST NOT contain embedded newlines.
			String jsonMessage = jsonMapper.writeValueAsString(message)
				.replace("\r\n", "\\n")
				.replace("\n", "\\n")
				.replace("\r", "\\n");
			logger.trace("SEND: {}", jsonMessage);
			var os = process.getOutputStream();
			synchronized (os) {
				os.write(jsonMessage.getBytes(StandardCharsets.UTF_8));
				os.write("\n".getBytes(StandardCharsets.UTF_8));
				os.flush();
			}
			sink.next(message);
		}
		catch (IOException e) {
			sink.error(new RuntimeException(e));
		}
	}

	/**
	 * Applies {@code outboundConsumer} to the stream of messages queued by
	 * {@link #sendMessage} and subscribes to the result; when that stream completes or fails,
	 * the transport stops writing. {@link #connect} calls it with the function that writes
	 * each message to the agent's standard input on the writer thread. A subclass that
	 * overrides it must apply the function, or nothing is written.
	 * @param outboundConsumer turns the queued messages into the messages written
	 */
	protected void handleOutbound(Function<Flux<JSONRPCMessage>, Flux<JSONRPCMessage>> outboundConsumer) {
		outboundConsumer.apply(outboundSink.asFlux()).doOnComplete(() -> {
			isClosing = true;
			outboundSink.tryEmitComplete();
		}).doOnError(e -> {
			if (!isClosing) {
				logger.error("Error in outbound processing", e);
				this.exceptionHandler.accept(e);
				isClosing = true;
				outboundSink.tryEmitComplete();
			}
			// Logged above unless closing; not dropped to Reactor's ERROR hook.
		}).subscribe(ignored -> {
		}, error -> logger.debug("Outbound processing ended: {}", error.toString()));
	}

	/**
	 * {@inheritDoc}
	 * <p>Lets the agent exit by itself, in this order: stops delivering and writing messages,
	 * closes the agent's standard input, waits up to {@value #END_OF_INPUT_WAIT_MILLIS} ms for
	 * the process to exit, sends SIGTERM, and after five more seconds kills it; then stops the
	 * transport's threads and completes {@link #awaitTermination()}. The steps run on the
	 * subscribing thread. Only the first close stops the process; a later one completes at
	 * once.
	 * @return a Mono that completes when the process has exited, or been killed, and the
	 * transport is closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			if (!this.stopping.compareAndSet(false, true)) {
				logger.debug("Already closed");
				return;
			}
			closedLocally = true;
			isClosing = true;
			logger.debug("Initiating graceful shutdown");

			// Complete all sinks to stop accepting new messages
			inboundSink.tryEmitComplete();
			outboundSink.tryEmitComplete();
			errorSink.tryEmitComplete();

			// Stop the process FIRST - its end closes the streams and unblocks readLine()
			Process process = this.process;
			if (process != null) {
				stop(process);
			}

			// Now that process is dead and streams closed, threads should be unblocked
			try {
				inboundScheduler.dispose();
				errorScheduler.dispose();
				outboundScheduler.dispose();
				logger.debug("Graceful shutdown completed");
			}
			catch (Exception e) {
				logger.error("Error during graceful shutdown", e);
			}
			terminationSink.tryEmitEmpty();
		});
	}

	/**
	 * Closes the process's standard input and waits for it to exit by itself; then sends TERM
	 * and waits up to five seconds, then kills it. Waits with a blocking waitFor() rather than
	 * Mono.fromFuture(process.onExit()), which would run on ForkJoinPool.commonPool.
	 */
	private static void stop(Process process) {
		try {
			var stdin = process.getOutputStream();
			synchronized (stdin) {
				stdin.close();
			}
		}
		catch (IOException e) {
			logger.debug("Closing the agent's standard input failed: {}", e.getMessage());
		}
		try {
			if (process.waitFor(END_OF_INPUT_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
				logExit(process.exitValue());
				return;
			}
			logger.debug("Agent still running {} ms after the end of its input; sending TERM",
					END_OF_INPUT_WAIT_MILLIS);
			process.destroy();
			if (process.waitFor(TERM_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
				logExit(process.exitValue());
			}
			else {
				logger.warn("Process did not exit within timeout, forcing kill");
				process.destroyForcibly();
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			logger.debug("Interrupted while waiting for process exit");
		}
	}

	private static void logExit(int exitCode) {
		// 143 = SIGTERM (128+15), 137 = SIGKILL (128+9) - expected when we destroy
		if (exitCode == 0 || exitCode == 143 || exitCode == 137) {
			logger.info("ACP agent process stopped (exit code {})", exitCode);
		}
		else {
			logger.warn("Process terminated unexpectedly with code {}", exitCode);
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>It completes when the transport is closed locally. It errors with an
	 * {@link AcpConnectionException} when the agent process ends by itself, once its standard
	 * output has been read to the end; the message names the exit code (and, on POSIX systems,
	 * the signal), or says that the agent closed its standard output without exiting.
	 * @return a Mono that terminates when the transport does
	 */
	@Override
	public Mono<Void> awaitTermination() {
		return this.terminationSink.asMono();
	}

	/**
	 * Returns the sink that carries the lines the agent writes to its standard error. The
	 * transport subscribes to it when it connects, and the sink accepts a single subscriber,
	 * so read the lines with {@link #setStdErrorHandler} instead of subscribing here.
	 * @return the standard-error sink
	 */
	public Sinks.Many<String> getErrorSink() {
		return this.errorSink;
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return this.jsonMapper.convertValue(data, typeRef);
	}

}
