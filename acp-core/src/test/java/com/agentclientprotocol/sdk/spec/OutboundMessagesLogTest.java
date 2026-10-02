/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A peer's error response fails the caller's request, and that failure is the report: the
 * session logs it at DEBUG only, without the error's data, which is the peer's payload.
 */
class OutboundMessagesLogTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final OutboundMessagesTest.RecordingTransport transport = new OutboundMessagesTest.RecordingTransport();

	private ListAppender<ILoggingEvent> appender;

	private Logger logger;

	@BeforeEach
	void captureLogs() {
		this.logger = (Logger) LoggerFactory.getLogger(OutboundMessages.class);
		this.appender = new ListAppender<>();
		this.appender.start();
		this.logger.addAppender(this.appender);
		this.logger.setLevel(Level.TRACE);
	}

	@AfterEach
	void releaseLogs() {
		this.logger.detachAppender(this.appender);
		this.logger.setLevel(null);
		this.appender.stop();
	}

	@Test
	void aPeerErrorResponseIsLoggedAtDebugWithoutItsData() {
		OutboundMessages outbound = new OutboundMessages(this.transport, TIMEOUT, () -> null,
				cause -> new IllegalStateException(cause), "agent");

		StepVerifier.create(outbound.sendRequest("session/set_config_option", "params", new TypeRef<String>() {
		}))
			.then(() -> outbound.complete(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION,
					this.transport.lastRequest().id(), null,
					new AcpSchema.JSONRPCError(-32602, "Invalid params", Map.of("secret", "payload-value")))))
			.expectError(AcpError.class)
			.verify(TIMEOUT);

		assertThat(this.appender.list).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.INFO)).isEmpty();
		assertThat(this.appender.list).extracting(ILoggingEvent::getFormattedMessage)
			.anySatisfy(message -> assertThat(message).contains("session/set_config_option").contains("-32602"))
			.allSatisfy(message -> assertThat(message).doesNotContain("payload-value"));
	}

}
