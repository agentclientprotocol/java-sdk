/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.QuietLoggers;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpClientSession;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCNotification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StdioAcpClientTransport} in this JVM against a child process: the outbound writer,
 * the inbound and standard-error readers, and the end of the agent process.
 */
class StdioAcpClientTransportProcessTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	private StdioAcpClientTransport transport;

	@AfterEach
	void tearDown() {
		if (transport != null) {
			transport.closeGracefully().block(TIMEOUT);
		}
	}

	private static AgentParameters echoAgent() {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		return AgentParameters.builder(java)
			.arg("-cp")
			.arg(System.getProperty("java.class.path"))
			.arg(EchoAgent.class.getName())
			.build();
	}

	@Test
	void messagesRoundTripThroughTheAgentProcess() throws InterruptedException {
		List<JSONRPCMessage> received = new CopyOnWriteArrayList<>();
		CountDownLatch receivedTwo = new CountDownLatch(2);
		List<String> stderr = new CopyOnWriteArrayList<>();
		CountDownLatch ready = new CountDownLatch(1);
		transport = new StdioAcpClientTransport(echoAgent());
		transport.setStdErrorHandler(line -> {
			stderr.add(line);
			ready.countDown();
		});
		transport.connect(message -> message.doOnNext(m -> {
			received.add(m);
			receivedTwo.countDown();
		}).then(Mono.empty())).block(TIMEOUT);

		transport.sendMessage(new JSONRPCNotification("note/one", Map.of("text", "line one\nline two"))).block(TIMEOUT);
		transport.sendMessage(new JSONRPCNotification("note/two", null)).block(TIMEOUT);

		assertThat(receivedTwo.await(30, TimeUnit.SECONDS)).as("echoed: %s", received).isTrue();
		assertThat(received).extracting(m -> ((JSONRPCNotification) m).method())
			.containsExactly("note/one", "note/two");
		assertThat(((JSONRPCNotification) received.get(0)).params()).isEqualTo(Map.of("text", "line one\nline two"));
		assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(stderr).contains(EchoAgent.READY);
	}

	/**
	 * Without a handler of its own, the agent's standard error goes to INFO on a dedicated
	 * logger, with a short prefix, so it stays visible and can be turned down by name.
	 */
	@Test
	void agentStandardErrorIsLoggedAtInfoOnItsOwnLogger() throws InterruptedException {
		Logger logger = (Logger) LoggerFactory.getLogger(StdioAcpClientTransport.AGENT_STDERR_LOGGER);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		try {
			transport = new StdioAcpClientTransport(echoAgent());
			transport.connect(message -> message.then(Mono.empty())).block(TIMEOUT);

			long deadline = System.nanoTime() + TIMEOUT.toNanos();
			while (logs.list.isEmpty() && System.nanoTime() < deadline) {
				Thread.sleep(20);
			}
		}
		finally {
			logger.detachAppender(logs);
			logs.stop();
		}

		assertThat(StdioAcpClientTransport.AGENT_STDERR_LOGGER)
			.isEqualTo("com.agentclientprotocol.sdk.client.transport.agent-stderr");
		assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.INFO);
		assertThat(logs.list.get(0).getLoggerName()).isEqualTo(StdioAcpClientTransport.AGENT_STDERR_LOGGER);
		assertThat(logs.list.get(0).getFormattedMessage()).isEqualTo("agent: " + EchoAgent.READY);
	}

	/**
	 * A malformed line from the agent is reported to the exception handler, answered with
	 * -32700 and a null id (which the echo agent sends back), and skipped: the message after
	 * it still arrives. Before, the first such line stopped the reader for good, silently.
	 */
	@Test
	void aMalformedLineIsReportedAnsweredAndSkipped() throws InterruptedException {
		List<JSONRPCMessage> received = new CopyOnWriteArrayList<>();
		CountDownLatch receivedThree = new CountDownLatch(3);
		List<Throwable> reported = new CopyOnWriteArrayList<>();
		transport = new StdioAcpClientTransport(echoAgent());
		transport.setStdErrorHandler(line -> {
		});
		transport.setExceptionHandler(reported::add);
		transport.connect(message -> message.doOnNext(m -> {
			received.add(m);
			receivedThree.countDown();
		}).then(Mono.empty())).block(TIMEOUT);

		try (QuietLoggers quiet = QuietLoggers.of(StdioAcpClientTransport.class)) {
			transport.sendMessage(new JSONRPCNotification("note/one", null)).block(TIMEOUT);
			transport.sendMessage(new JSONRPCNotification(EchoAgent.MALFORMED, null)).block(TIMEOUT);
			transport.sendMessage(new JSONRPCNotification("note/two", null)).block(TIMEOUT);
			assertThat(receivedThree.await(30, TimeUnit.SECONDS)).as("received: %s", received).isTrue();
		}

		assertThat(received).filteredOn(JSONRPCNotification.class::isInstance)
			.extracting(m -> ((JSONRPCNotification) m).method())
			.containsExactly("note/one", "note/two");
		assertThat(received).filteredOn(AcpSchema.JSONRPCResponse.class::isInstance)
			.singleElement()
			.satisfies(m -> {
				AcpSchema.JSONRPCResponse answer = (AcpSchema.JSONRPCResponse) m;
				assertThat(answer.id()).isNull();
				assertThat(answer.error()).isNotNull();
				assertThat(answer.error().code()).isEqualTo(-32700);
			});
		assertThat(reported).hasSize(1);
	}

	/**
	 * A malformed line is the peer's payload and can carry personal data: it is logged at
	 * DEBUG at most, never in the ERROR that reports it.
	 */
	@Test
	void aMalformedLineIsNotLoggedAboveDebug() throws InterruptedException {
		Logger logger = (Logger) LoggerFactory.getLogger(StdioAcpClientTransport.class);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		try {
			CountDownLatch answered = new CountDownLatch(1);
			transport = new StdioAcpClientTransport(echoAgent());
			transport.setStdErrorHandler(line -> {
			});
			transport.setExceptionHandler(e -> {
			});
			transport.connect(message -> message.doOnNext(m -> answered.countDown()).then(Mono.empty()))
				.block(TIMEOUT);

			transport.sendMessage(new JSONRPCNotification(EchoAgent.MALFORMED, null)).block(TIMEOUT);
			assertThat(answered.await(30, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			logger.detachAppender(logs);
			logs.stop();
		}

		assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.ERROR)
			.extracting(ILoggingEvent::getFormattedMessage)
			.anySatisfy(message -> assertThat(message).contains("not a JSON-RPC message"));
		assertThat(logs.list).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
			.allSatisfy(event -> {
				assertThat(event.getFormattedMessage()).doesNotContain("someone@example.com");
				if (event.getThrowableProxy() != null) {
					assertThat(event.getThrowableProxy().getMessage()).doesNotContain("someone@example.com");
				}
			});
	}

	@Test
	void awaitForExitReturnsWhenTheAgentExits() {
		transport = new StdioAcpClientTransport(echoAgent());
		transport.setStdErrorHandler(line -> {
		});
		transport.connect(message -> message.then(Mono.empty())).block(TIMEOUT);

		transport.sendMessage(new JSONRPCNotification(EchoAgent.EXIT, null)).block(TIMEOUT);

		Mono.fromRunnable(transport::awaitForExit).block(TIMEOUT);
	}

	/**
	 * The agent exits while a request is waiting: the request fails at once, naming the
	 * exit code, instead of waiting out the request timeout; closing still works.
	 */
	@Test
	void anAgentThatExitsMidRequestFailsThePendingRequestPromptly() {
		transport = new StdioAcpClientTransport(echoAgent());
		transport.setStdErrorHandler(line -> {
		});
		Duration requestTimeout = Duration.ofMinutes(5);
		AcpClientSession session = new AcpClientSession(requestTimeout, transport, Map.of(), Map.of(),
				Function.identity());

		long start = System.nanoTime();
		try (QuietLoggers quiet = QuietLoggers.of(StdioAcpClientTransport.class, AcpClientSession.class)) {
			assertThatThrownBy(() -> session.sendRequest(EchoAgent.EXIT_3, Map.of(), new TypeRef<Map<String, Object>>() {
			}).block(TIMEOUT)).hasMessageContaining("terminated")
				.rootCause()
				.hasMessageContaining("ACP agent process exited with code 3");
		}
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));

		session.closeGracefully().block(TIMEOUT);
	}

	/** An agent killed by a signal: the reason names the signal Java reports as 128 + n. */
	@Test
	@DisabledOnOs(OS.WINDOWS)
	void terminationNamesTheSignalThatKilledTheAgent() {
		transport = new StdioAcpClientTransport(echoAgent());
		transport.setStdErrorHandler(line -> {
		});
		transport.connect(message -> message.then(Mono.empty())).block(TIMEOUT);

		try (QuietLoggers quiet = QuietLoggers.of(StdioAcpClientTransport.class)) {
			transport.sendMessage(new JSONRPCNotification(EchoAgent.KILL, null)).block(TIMEOUT);

			assertThatThrownBy(() -> transport.awaitTermination().block(TIMEOUT))
				.hasMessageContaining("ACP agent process exited with code 137 (signal 9)");
		}
	}

	/** Closing the transport ends {@code awaitTermination} without an error. */
	@Test
	void closingTheTransportCompletesAwaitTermination() {
		transport = new StdioAcpClientTransport(echoAgent());
		transport.setStdErrorHandler(line -> {
		});
		transport.connect(message -> message.then(Mono.empty())).block(TIMEOUT);
		Mono<Void> termination = transport.awaitTermination().cache();
		termination.subscribe(v -> {
		}, e -> {
		});

		transport.closeGracefully().block(TIMEOUT);

		termination.block(TIMEOUT);
	}

	@Test
	void anAgentThatCannotBeStartedFailsTheConnect() {
		transport = new StdioAcpClientTransport(AgentParameters.builder("/nonexistent/acp-agent-binary").build());

		assertThatThrownBy(() -> transport.connect(message -> message).block(TIMEOUT))
			.hasMessageContaining("Failed to start process")
			.hasMessageContaining("/nonexistent/acp-agent-binary");
	}

	/** Echoes each stdin line to stdout, and exits on the {@link #EXIT} notification. */
	public static final class EchoAgent {

		static final String READY = "echo agent ready";

		static final String EXIT = "test/exit";

		/** Exits with status 3 instead of echoing. */
		static final String EXIT_3 = "test/exit3";

		/** Kills itself with SIGKILL instead of echoing. */
		static final String KILL = "test/kill";

		/** Answered with a line that is not JSON instead of its echo. */
		static final String MALFORMED = "test/malformed";

		/** The line that is not JSON; it carries personal data, as an agent's output can. */
		static final String MALFORMED_LINE = "{not json someone@example.com";

		private EchoAgent() {
		}

		public static void main(String[] args) throws Exception {
			System.err.println(READY);
			System.err.flush();
			BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
			String line;
			while ((line = in.readLine()) != null) {
				if (line.contains("\"" + EXIT + "\"")) {
					return;
				}
				if (line.contains("\"" + EXIT_3 + "\"")) {
					System.exit(3);
				}
				if (line.contains("\"" + KILL + "\"")) {
					// A process may not destroy itself through ProcessHandle; ask kill(1).
					new ProcessBuilder("kill", "-KILL", Long.toString(ProcessHandle.current().pid())).start()
						.waitFor();
					Thread.sleep(Long.MAX_VALUE);
				}
				String echo = line.contains("\"" + MALFORMED + "\"") ? MALFORMED_LINE : line;
				System.out.write((echo + "\n").getBytes(StandardCharsets.UTF_8));
				System.out.flush();
			}
		}

	}

}
