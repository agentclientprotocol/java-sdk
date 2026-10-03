/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A close that fails is reported as the failure it is: not as a timeout that never happened,
 * and not through Reactor's "Operator called default onErrorDropped" ERROR.
 */
class CloseFailureLoggingTest {

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	private final List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(AcpSyncClient.class),
			(Logger) LoggerFactory.getLogger(AcpTransport.class));

	private final List<Throwable> dropped = new CopyOnWriteArrayList<>();

	@BeforeEach
	void capture() {
		this.logs.start();
		this.loggers.forEach(logger -> logger.addAppender(this.logs));
		Hooks.onErrorDropped(this.dropped::add);
	}

	@AfterEach
	void release() {
		Hooks.resetOnErrorDropped();
		this.loggers.forEach(logger -> logger.detachAppender(this.logs));
		this.logs.stop();
	}

	@Test
	void aSyncClientWhoseCloseFailsLogsTheFailureNotATimeout() {
		AcpSyncClient client = AcpClient.sync(new FailingCloseTransport()).build();

		assertThat(client.closeGracefully()).isFalse();

		assertThat(warnings()).singleElement().satisfies(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage()).doesNotContain("timeout").contains("Output closed");
		});
	}

	@Test
	void aTransportCloseThatFailsIsLoggedNotDropped() {
		AcpTransport transport = new FailingCloseTransport();

		transport.close();

		assertThat(this.dropped).isEmpty();
		assertThat(warnings()).singleElement().satisfies(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage()).contains("Output closed");
		});
	}

	private List<ILoggingEvent> warnings() {
		return this.logs.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList();
	}

	/** A transport whose graceful close fails at once, as a WebSocket closed twice did. */
	private static final class FailingCloseTransport extends MockAcpClientTransport {

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.error(new IOException("Output closed"));
		}

	}

}
