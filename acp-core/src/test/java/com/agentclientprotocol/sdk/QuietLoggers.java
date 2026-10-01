/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * Turns the loggers of the classes under a Lincheck test off for its duration. The test
 * configuration logs this SDK at DEBUG, and Lincheck model checks every instruction a
 * thread runs, the appender's included: a log line inside an operation multiplies the
 * interleavings to explore until the run looks hung.
 */
public final class QuietLoggers implements AutoCloseable {

	private final Map<Logger, @Nullable Level> previous = new LinkedHashMap<>();

	private QuietLoggers(Class<?>... classes) {
		for (Class<?> type : classes) {
			Logger logger = (Logger) LoggerFactory.getLogger(type);
			previous.put(logger, logger.getLevel());
			logger.setLevel(Level.OFF);
		}
	}

	public static QuietLoggers of(Class<?>... classes) {
		return new QuietLoggers(classes);
	}

	@Override
	public void close() {
		previous.forEach(Logger::setLevel);
	}

}
