/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the peer receives when a request handler fails, on both sides: an
 * {@link AcpProtocolException} is the handler's intended answer and is sent as it is; an
 * {@link AcpError} from a request the handler made passes the peer's error on; anything else
 * is {@code -32603} with a generic message, its detail only in the handling side's log.
 */
class HandlerFailureAnswerTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String SECRET = "jdbc:postgresql://db/prod?password=hunter2";

	private final InMemoryTransportPair transportPair = InMemoryTransportPair.create();

	private final AtomicReference<AcpAsyncAgent> agentRef = new AtomicReference<>();

	private AcpAsyncClient client;

	private ListAppender<ILoggingEvent> appender;

	private Logger logger;

	@BeforeEach
	void captureLogs() {
		this.logger = (Logger) LoggerFactory.getLogger("com.agentclientprotocol.sdk.spec");
		this.appender = new ListAppender<>();
		this.appender.start();
		this.logger.addAppender(this.appender);
		this.logger.setLevel(Level.DEBUG);
	}

	@AfterEach
	void tearDown() {
		this.logger.detachAppender(this.appender);
		this.logger.setLevel(null);
		this.appender.stop();
		if (client != null) {
			client.closeGracefully().block(TIMEOUT);
		}
		if (agentRef.get() != null) {
			agentRef.get().closeGracefully().block(TIMEOUT);
		}
	}

	private void connect(AcpAgent.AsyncAgentBuilder agentBuilder, AcpClient.AsyncSpec clientSpec) {
		AcpAsyncAgent agent = agentBuilder.requestTimeout(TIMEOUT)
			.initializeHandler(request -> Mono
				.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
			.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn()))
			.build();
		agentRef.set(agent);
		agent.start().block(TIMEOUT);
		client = clientSpec.requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);
	}

	private AcpAgent.AsyncAgentBuilder agent() {
		return AcpAgent.async(transportPair.agentTransport());
	}

	private AcpClient.AsyncSpec clientSpec() {
		return AcpClient.async(transportPair.clientTransport());
	}

	private static AcpError failure(Runnable call) {
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		try {
			call.run();
		}
		catch (Throwable t) {
			thrown.set(t);
		}
		assertThat(thrown.get()).isInstanceOf(AcpError.class);
		return (AcpError) thrown.get();
	}

	@Test
	void anAgentHandlerExceptionIsAnsweredWithAGenericInternalErrorAndLoggedAtTheAgent() {
		connect(agent().extRequestHandler("_x/plain",
				params -> Mono.error(new IllegalStateException("cannot connect to " + SECRET))), clientSpec());

		AcpError error = failure(() -> client.sendExtRequest("_x/plain", Map.of()).block(TIMEOUT));

		assertThat(error.getCode()).isEqualTo(AcpErrorCodes.INTERNAL_ERROR);
		assertThat(error.getError().message()).isEqualTo("Internal error");
		assertThat(error.getData()).isNull();
		assertThat(error.toString()).doesNotContain("hunter2");
		assertThat(this.appender.list).anySatisfy(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage()).contains("_x/plain");
			assertThat(event.getThrowableProxy().getMessage()).contains("hunter2");
		});
	}

	@Test
	void aClientHandlerExceptionIsAnsweredWithAGenericInternalError() {
		connect(agent(), clientSpec().extRequestHandler("_client/plain", params -> {
			throw new IllegalArgumentException("no file " + SECRET);
		}));

		AcpError error = failure(() -> agentRef.get().sendExtRequest("_client/plain", Map.of()).block(TIMEOUT));

		assertThat(error.getCode()).isEqualTo(AcpErrorCodes.INTERNAL_ERROR);
		assertThat(error.getError().message()).isEqualTo("Internal error");
		assertThat(error.toString()).doesNotContain("hunter2");
	}

	@Test
	void anAcpProtocolExceptionKeepsItsCodeMessageAndData() {
		connect(agent().extRequestHandler("_x/protocol",
				params -> Mono.error(new AcpProtocolException(AcpErrorCodes.RESOURCE_NOT_FOUND, "No such thing",
						Map.of("id", 7)))),
				clientSpec());

		AcpError error = failure(() -> client.sendExtRequest("_x/protocol", Map.of()).block(TIMEOUT));

		assertThat(error.getCode()).isEqualTo(AcpErrorCodes.RESOURCE_NOT_FOUND);
		assertThat(error.getError().message()).isEqualTo("No such thing");
		assertThat(error.getData()).isEqualTo(Map.of("id", 7));
	}

	@Test
	void aPeerErrorThatEscapesAHandlerPassesThePeersCodeMessageAndDataOn() {
		// The agent's handler asks the client for something the client does not serve, and
		// lets the client's -32601 escape.
		connect(agent().extRequestHandler("_x/proxy",
				params -> agentRef.get().sendExtRequest("_client/missing", Map.of())), clientSpec());

		AcpError error = failure(() -> client.sendExtRequest("_x/proxy", Map.of()).block(TIMEOUT));

		assertThat(error.getCode()).isEqualTo(AcpErrorCodes.METHOD_NOT_FOUND);
		assertThat(error.getError().message()).contains("_client/missing");
	}

	@Test
	void aPeerErrorWithDataThatEscapesAHandlerKeepsItsData() {
		AcpSchema.JSONRPCError peerError = new AcpSchema.JSONRPCError(AcpErrorCodes.RESOURCE_NOT_FOUND,
				"Resource not found", Map.of("uri", "file:///missing.txt"));
		connect(agent().extRequestHandler("_x/proxy", params -> Mono.error(new AcpError(peerError))), clientSpec());

		AcpError error = failure(() -> client.sendExtRequest("_x/proxy", Map.of()).block(TIMEOUT));

		assertThat(error.getError()).isEqualTo(peerError);
	}

}
