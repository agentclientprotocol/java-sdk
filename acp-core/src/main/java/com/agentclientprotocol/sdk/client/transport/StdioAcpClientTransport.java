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
 * Implementation of the ACP Stdio transport that communicates with an agent process using
 * standard input/output streams. Messages are exchanged as newline-delimited JSON-RPC
 * messages over stdin/stdout, with errors and debug information sent to stderr.
 *
 * <p>
 * This is a full-featured transport with:
 * <ul>
 * <li>Thread-safe message processing with dedicated schedulers</li>
 * <li>Proper resource management and graceful shutdown</li>
 * <li>Error stream handling</li>
 * <li>Backpressure support via Reactor Sinks</li>
 * </ul>
 *
 * @author Mark Pollack
 * @author Christian Tzolov (MCP Java SDK)
 * @author Dariusz Jędrzejczyk (MCP Java SDK)
 */
public class StdioAcpClientTransport implements AcpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(StdioAcpClientTransport.class);

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

	/**
	 * A transport instance carries exactly one session. {@link #connect} subscribes the
	 * unicast inbound and outbound sinks and starts the agent process; a second call would
	 * subscribe them again and start a second process, so it is refused up front.
	 */
	private final AtomicBoolean isConnected = new AtomicBoolean(false);

	// visible for tests
	private Consumer<String> stdErrorHandler = error -> logger.info("STDERR Message received: {}", error);

	/**
	 * Creates a new StdioAcpClientTransport with the specified parameters using the default JsonMapper.
	 * @param params The parameters for configuring the agent process
	 */
	public StdioAcpClientTransport(AgentParameters params) {
		this(params, AcpJsonMapper.createDefault());
	}

	/**
	 * Creates a new StdioAcpClientTransport with the specified parameters and JsonMapper.
	 * @param params The parameters for configuring the agent process
	 * @param jsonMapper The JsonMapper to use for JSON serialization/deserialization
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
	 * Starts the agent process and initializes the message processing streams. This
	 * method sets up the process with the configured command, arguments, and environment,
	 * then starts the inbound, outbound, and error processing threads.
	 * @throws RuntimeException if the process fails to start or if the process streams
	 * are null
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
	 * Creates and returns a new ProcessBuilder instance. Protected to allow overriding in
	 * tests.
	 * @return A new ProcessBuilder instance
	 */
	protected ProcessBuilder getProcessBuilder() {
		return new ProcessBuilder();
	}

	/**
	 * Sets the handler for processing transport-level errors.
	 *
	 * <p>
	 * The provided handler will be called when errors occur during transport operations,
	 * such as connection failures or protocol violations.
	 * </p>
	 * @param errorHandler a consumer that processes error messages
	 */
	public void setStdErrorHandler(Consumer<String> errorHandler) {
		this.stdErrorHandler = errorHandler;
	}

	/**
	 * Waits for the agent process to exit.
	 * @throws IllegalStateException if {@link #connect} has not started the process
	 * @throws RuntimeException if the process is interrupted while waiting
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
			.subscribe();
	}

	private void handleIncomingErrors() {
		this.errorSink.asFlux().subscribe(e -> {
			this.stdErrorHandler.accept(e);
		});
	}

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
		readLines(this.inboundScheduler, process.getInputStream(), "input stream", this::emitInbound,
				this.inboundSink::tryEmitComplete);
	}

	private boolean emitInbound(String line) {
		try {
			logger.trace("RECV: {}", line);
			JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(this.jsonMapper, line);
			if (this.inboundSink.tryEmitNext(message).isSuccess()) {
				return true;
			}
			if (!isClosing) {
				logger.error("Failed to enqueue inbound message: {}", message);
			}
		}
		catch (Exception e) {
			if (!isClosing) {
				logger.error("Error processing inbound message for line: {}", line, e);
			}
		}
		return false;
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

	protected void handleOutbound(Function<Flux<JSONRPCMessage>, Flux<JSONRPCMessage>> outboundConsumer) {
		outboundConsumer.apply(outboundSink.asFlux()).doOnComplete(() -> {
			isClosing = true;
			outboundSink.tryEmitComplete();
		}).doOnError(e -> {
			if (!isClosing) {
				logger.error("Error in outbound processing", e);
				isClosing = true;
				outboundSink.tryEmitComplete();
			}
		}).subscribe();
	}

	/**
	 * Gracefully closes the transport by destroying the process and disposing of the
	 * schedulers. This method sends a TERM signal to the process and waits for it to exit
	 * before cleaning up resources.
	 * @return A Mono that completes when the transport is closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			isClosing = true;
			logger.debug("Initiating graceful shutdown");

			// Complete all sinks to stop accepting new messages
			inboundSink.tryEmitComplete();
			outboundSink.tryEmitComplete();
			errorSink.tryEmitComplete();

			// Destroy process FIRST - this closes streams and unblocks readLine()
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
		});
	}

	/**
	 * Sends TERM to the process and waits up to five seconds for it to exit, then kills it.
	 * Waits with a blocking waitFor() rather than Mono.fromFuture(process.onExit()), which
	 * would run on ForkJoinPool.commonPool.
	 */
	private static void stop(Process process) {
		logger.debug("Sending TERM to process");
		process.destroy();
		try {
			if (process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
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

	public Sinks.Many<String> getErrorSink() {
		return this.errorSink;
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return this.jsonMapper.convertValue(data, typeRef);
	}

}
