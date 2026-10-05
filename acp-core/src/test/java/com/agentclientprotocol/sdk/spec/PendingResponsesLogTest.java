/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How {@link PendingResponses} logs a response it cannot match to a request.
 */
class PendingResponsesLogTest {

	private ListAppender<ILoggingEvent> appender;

	private Logger logger;

	@BeforeEach
	void captureLogs() {
		this.logger = (Logger) LoggerFactory.getLogger(PendingResponses.class);
		this.appender = new ListAppender<>();
		this.appender.start();
		this.logger.addAppender(this.appender);
	}

	@AfterEach
	void releaseLogs() {
		this.logger.detachAppender(this.appender);
		this.appender.stop();
	}

	/**
	 * JSON-RPC 2.0 answers a message whose id could not be read with an error whose id is
	 * null, so a null-id error is the peer reporting something it could not read, not a bug
	 * in this SDK's request sending.
	 */
	@Test
	void aNullIdErrorIsLoggedAsThePeersErrorReportAtWarn() {
		PendingResponses pending = new PendingResponses(() -> null, RuntimeException::new, "agent");

		pending.complete(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, null, null,
				new AcpSchema.JSONRPCError(-32700, "Parse error", null)));

		List<ILoggingEvent> events = this.appender.list.stream()
			.filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
			.toList();
		assertThat(events).hasSize(1);
		assertThat(events.get(0).getLevel()).isEqualTo(Level.WARN);
		assertThat(events.get(0).getFormattedMessage()).contains("agent")
			.contains("-32700")
			.contains("Parse error")
			.doesNotContain("bug");
	}

	/** A stray response with no id answers nothing this side sent: a WARN, not an ERROR about a bug. */
	@Test
	void aResponseWithoutAnIdIsLoggedAtWarn() {
		PendingResponses pending = new PendingResponses(() -> null, RuntimeException::new, "agent");

		pending.complete(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, null, java.util.Map.of(), null));

		List<ILoggingEvent> events = this.appender.list.stream()
			.filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
			.toList();
		assertThat(events).singleElement().satisfies(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage()).contains("agent").doesNotContain("bug");
		});
	}

	/**
	 * The peer's error message and data are its own text and can carry anything: at INFO and
	 * above the error is logged by its code and the SDK's description of the code; the
	 * message and data stay at DEBUG.
	 */
	@Test
	void aNullIdErrorIsLoggedWithoutThePeersMessageOrDataAboveDebug() {
		PendingResponses pending = new PendingResponses(() -> null, RuntimeException::new, "agent");

		pending.complete(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, null, null,
				new AcpSchema.JSONRPCError(-32600, "MESSAGE-SECRET", java.util.Map.of("details", "DATA-SECRET"))));

		assertThat(this.appender.list).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
			.singleElement()
			.satisfies(event -> assertThat(event.getFormattedMessage()).contains("-32600")
				.contains("Invalid request")
				.doesNotContain("MESSAGE-SECRET")
				.doesNotContain("DATA-SECRET"));
	}

}
