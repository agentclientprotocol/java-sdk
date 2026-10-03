/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A handler that throws an {@link Error} rather than an exception: code compiled against an
 * older SDK hits {@link NoSuchMethodError}, a failed {@code assert} throws
 * {@link AssertionError}. Reactor rethrows a {@link LinkageError} instead of signalling it,
 * so the request was never answered and the peer waited out its timeout. Every request is
 * answered {@code -32603} naming the method (no stack trace, no payload), the error is logged
 * at ERROR with the throwable, and the connection stays usable.
 */
class HandlerErrorTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/** Shorter than {@link #TIMEOUT}: a request that is never answered times out first. */
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

	private static final String INBOUND_LOGGER = "com.agentclientprotocol.sdk.spec.InboundMessages";

	private final InMemoryTransportPair transportPair = InMemoryTransportPair.create();

	private final List<Runnable> closers = new ArrayList<>();

	private ListAppender<ILoggingEvent> appender;

	private Logger logger;

	@BeforeEach
	void captureLogs() {
		this.logger = (Logger) LoggerFactory.getLogger(INBOUND_LOGGER);
		this.appender = new ListAppender<>();
		this.appender.start();
		this.logger.addAppender(this.appender);
	}

	@AfterEach
	void tearDown() {
		closers.forEach(Runnable::run);
		this.logger.detachAppender(this.appender);
		this.appender.stop();
		transportPair.closeGracefully().block(TIMEOUT);
	}

	// ---------------------------------------------------------------- agent side

	private AcpAsyncClient connectAsyncClient() {
		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport()).requestTimeout(REQUEST_TIMEOUT).build();
		closers.add(0, () -> client.closeGracefully().block(TIMEOUT));
		client.initialize().block(TIMEOUT);
		return client;
	}

	private void startSyncAgent(AcpAgent.SyncAgentBuilder builder) {
		AcpSyncAgent agent = builder.requestTimeout(REQUEST_TIMEOUT)
			.initializeHandler(request -> InitializeResponse.ok())
			.newSessionHandler(request -> new NewSessionResponse("s1", null, null))
			.build();
		closers.add(agent::closeGracefully);
		agent.start();
	}

	private void startAsyncAgent(AcpAgent.AsyncAgentBuilder builder) {
		AcpAsyncAgent agent = builder.requestTimeout(REQUEST_TIMEOUT)
			.initializeHandler(request -> Mono.just(InitializeResponse.ok()))
			.newSessionHandler(request -> Mono.just(new NewSessionResponse("s1", null, null)))
			.build();
		closers.add(() -> agent.closeGracefully().block(TIMEOUT));
		agent.start().block(TIMEOUT);
	}

	private static PromptRequest prompt() {
		return new PromptRequest("s1", List.of(new TextContent("hi")));
	}

	/** The first prompt fails with the error; the second, on the same connection, is answered. */
	private void assertPromptAnsweredInternalErrorThenConnectionServes(AcpAsyncClient client,
			Class<? extends Throwable> errorType) {
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
		assertInternalError(() -> client.prompt(prompt()).block(TIMEOUT), AcpSchema.METHOD_SESSION_PROMPT);
		assertLoggedAtError(AcpSchema.METHOD_SESSION_PROMPT, errorType);
		assertThat(client.prompt(prompt()).block(TIMEOUT).stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
	}

	private static void assertInternalError(Runnable call, String method) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(AcpError.class, error -> {
			assertThat(error.getCode()).isEqualTo(AcpErrorCodes.INTERNAL_ERROR);
			assertThat(error.getMessage()).contains(method).doesNotContain("\tat ").doesNotContain("secret");
			assertThat(error.getData()).isNull();
		});
	}

	private void assertLoggedAtError(String method, Class<? extends Throwable> errorType) {
		assertThat(appender.list).anySatisfy(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.ERROR);
			assertThat(event.getFormattedMessage()).contains(method);
			assertThat(event.getThrowableProxy()).isNotNull();
			assertThat(event.getThrowableProxy().getClassName()).isEqualTo(errorType.getName());
		});
	}

	/** Fails the first call with the error, then answers. */
	private static <T> Supplier<T> failingOnce(Supplier<? extends Error> error, Supplier<T> answer) {
		AtomicInteger calls = new AtomicInteger();
		return () -> {
			if (calls.getAndIncrement() == 0) {
				throw error.get();
			}
			return answer.get();
		};
	}

	@Test
	void syncAgentHandlerThrowingNoSuchMethodErrorIsAnswered() {
		Supplier<PromptResponse> handler = failingOnce(() -> new NoSuchMethodError("secret payload"),
				PromptResponse::endTurn);
		startSyncAgent(AcpAgent.sync(transportPair.agentTransport()).promptHandler((request, context) -> handler.get()));

		assertPromptAnsweredInternalErrorThenConnectionServes(connectAsyncClient(), NoSuchMethodError.class);
	}

	@Test
	void syncAgentHandlerThrowingAssertionErrorIsAnswered() {
		Supplier<PromptResponse> handler = failingOnce(() -> new AssertionError("secret payload"),
				PromptResponse::endTurn);
		startSyncAgent(AcpAgent.sync(transportPair.agentTransport()).promptHandler((request, context) -> handler.get()));

		assertPromptAnsweredInternalErrorThenConnectionServes(connectAsyncClient(), AssertionError.class);
	}

	@Test
	void asyncAgentHandlerThrowingNoSuchMethodErrorIsAnswered() {
		Supplier<Mono<PromptResponse>> handler = failingOnce(() -> new NoSuchMethodError("secret payload"),
				() -> Mono.just(PromptResponse.endTurn()));
		startAsyncAgent(
				AcpAgent.async(transportPair.agentTransport()).promptHandler((request, context) -> handler.get()));

		assertPromptAnsweredInternalErrorThenConnectionServes(connectAsyncClient(), NoSuchMethodError.class);
	}

	@Test
	void asyncAgentHandlerSignallingAssertionErrorIsAnswered() {
		AtomicInteger calls = new AtomicInteger();
		startAsyncAgent(AcpAgent.async(transportPair.agentTransport())
			.promptHandler((request, context) -> calls.getAndIncrement() == 0
					? Mono.error(new AssertionError("secret payload")) : Mono.just(PromptResponse.endTurn())));

		assertPromptAnsweredInternalErrorThenConnectionServes(connectAsyncClient(), AssertionError.class);
	}

	@Test
	void syncAgentNonPromptHandlerThrowingNoSuchMethodErrorIsAnswered() {
		AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
			.requestTimeout(REQUEST_TIMEOUT)
			.initializeHandler(request -> InitializeResponse.ok())
			.newSessionHandler(request -> {
				throw new NoSuchMethodError("secret payload");
			})
			.listSessionsHandler(request -> new AcpSchema.ListSessionsResponse(List.of()))
			.promptHandler((request, context) -> AcpSchema.PromptResponse.endTurn()).build();
		closers.add(agent::closeGracefully);
		agent.start();
		AcpAsyncClient client = connectAsyncClient();

		assertInternalError(() -> client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT),
				AcpSchema.METHOD_SESSION_NEW);
		assertThat(client.listSessions(new AcpSchema.ListSessionsRequest(null)).block(TIMEOUT).sessions()).isEmpty();
	}

	/**
	 * A {@link VirtualMachineError} is answered too, as far as the JVM still can, and is not
	 * hidden: it also goes to the uncaught-exception handler of the thread that ran the
	 * handler.
	 */
	@Test
	void stackOverflowErrorIsAnswered() {
		Supplier<PromptResponse> handler = failingOnce(StackOverflowError::new, PromptResponse::endTurn);
		startSyncAgent(AcpAgent.sync(transportPair.agentTransport()).promptHandler((request, context) -> handler.get()));

		assertPromptAnsweredInternalErrorThenConnectionServes(connectAsyncClient(), StackOverflowError.class);
	}

	// ---------------------------------------------------------------- client side

	/**
	 * The agent asks the client for permission; the client's handler fails with the error.
	 * Returns what the agent's request ended with.
	 */
	private AtomicReference<Throwable> agentAskingPermission() {
		AtomicReference<Throwable> outcome = new AtomicReference<>();
		startAsyncAgent(AcpAgent.async(transportPair.agentTransport())
			.promptHandler((request, context) -> context.askPermission("edit")
				.doOnError(outcome::set)
				.onErrorReturn(false)
				.thenReturn(PromptResponse.endTurn())));
		return outcome;
	}

	private void promptAndAssertClientAnsweredInternalError(Supplier<PromptResponse> prompt,
			AtomicReference<Throwable> outcome, Class<? extends Throwable> errorType) {
		assertThat(prompt.get().stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(outcome.get()).isInstanceOfSatisfying(AcpError.class, error -> {
			assertThat(error.getCode()).isEqualTo(AcpErrorCodes.INTERNAL_ERROR);
			assertThat(error.getMessage()).contains(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION)
				.doesNotContain("secret");
		});
		assertLoggedAtError(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, errorType);
		// The connection still works
		outcome.set(null);
		assertThat(prompt.get().stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
	}

	@Test
	void syncClientHandlerThrowingNoSuchMethodErrorIsAnswered() {
		AtomicReference<Throwable> outcome = agentAskingPermission();
		AtomicInteger calls = new AtomicInteger();
		AcpSyncClient client = AcpClient.sync(transportPair.clientTransport())
			.requestTimeout(REQUEST_TIMEOUT)
			.requestPermissionHandler(request -> {
				if (calls.getAndIncrement() == 0) {
					throw new NoSuchMethodError("secret payload");
				}
				return new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected("allow"));
			})
			.build();
		closers.add(0, client::closeGracefully);
		client.initialize();
		client.newSession(new NewSessionRequest("/workspace", List.of()));

		promptAndAssertClientAnsweredInternalError(() -> client.prompt(prompt()), outcome, NoSuchMethodError.class);
	}

	@Test
	void asyncClientHandlerThrowingAssertionErrorIsAnswered() {
		AtomicReference<Throwable> outcome = agentAskingPermission();
		AtomicInteger calls = new AtomicInteger();
		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(REQUEST_TIMEOUT)
			.requestPermissionHandler(request -> {
				if (calls.getAndIncrement() == 0) {
					throw new AssertionError("secret payload");
				}
				return Mono.just(new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected("allow")));
			})
			.build();
		closers.add(0, () -> client.closeGracefully().block(TIMEOUT));
		client.initialize().block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

		promptAndAssertClientAnsweredInternalError(() -> client.prompt(prompt()).block(TIMEOUT), outcome,
				AssertionError.class);
	}

	@Test
	void asyncClientHandlerThrowingNoSuchMethodErrorIsAnswered() {
		AtomicReference<Throwable> outcome = agentAskingPermission();
		AtomicInteger calls = new AtomicInteger();
		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(REQUEST_TIMEOUT)
			.requestPermissionHandler(request -> {
				if (calls.getAndIncrement() == 0) {
					throw new NoSuchMethodError("secret payload");
				}
				return Mono.just(new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected("allow")));
			})
			.build();
		closers.add(0, () -> client.closeGracefully().block(TIMEOUT));
		client.initialize().block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

		promptAndAssertClientAnsweredInternalError(() -> client.prompt(prompt()).block(TIMEOUT), outcome,
				NoSuchMethodError.class);
	}

}
