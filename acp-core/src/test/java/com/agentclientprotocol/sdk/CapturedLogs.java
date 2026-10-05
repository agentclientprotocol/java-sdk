/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captures every event the SDK's loggers emit while open, to check what a log at INFO or
 * above would carry: the formatted message and each exception's message, causes included.
 */
public final class CapturedLogs implements AutoCloseable {

	private final Logger logger = (Logger) LoggerFactory.getLogger("com.agentclientprotocol.sdk");

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	private CapturedLogs() {
		this.appender.start();
		this.logger.addAppender(this.appender);
	}

	public static CapturedLogs open() {
		return new CapturedLogs();
	}

	public List<ILoggingEvent> events() {
		return List.copyOf(this.appender.list);
	}

	/** Asserts that no event at INFO or above, nor any exception logged with one, contains {@code text}. */
	public void assertNoneAtInfoOrAboveContains(String text) {
		assertThat(events()).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
			.allSatisfy(event -> {
				assertThat(event.getFormattedMessage()).doesNotContain(text);
				for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
					assertThat(messageOf(proxy)).as("exception logged with: %s", event.getFormattedMessage())
						.doesNotContain(text);
				}
			});
	}

	private static String messageOf(IThrowableProxy proxy) {
		@Nullable String message = proxy.getMessage();
		return message == null ? "" : message;
	}

	@Override
	public void close() {
		this.logger.detachAppender(this.appender);
		this.appender.stop();
	}

}
