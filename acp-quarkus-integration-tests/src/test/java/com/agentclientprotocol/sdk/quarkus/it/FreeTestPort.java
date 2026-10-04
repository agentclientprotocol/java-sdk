/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Serves the tests on a free port instead of Quarkus's fixed test port 8081, so that two builds on
 * one machine do not collide. Not {@code quarkus.http.test-port=0}: the client bean's URI,
 * {@code quarkus.acp.client.transport.http.uri}, is read when the application starts, before the
 * server has bound a random port, so it would name port 0. A port picked here is known to both.
 */
public class FreeTestPort implements QuarkusTestResourceLifecycleManager {

	@Override
	public Map<String, String> start() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return Map.of("quarkus.http.test-port", String.valueOf(socket.getLocalPort()));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Override
	public void stop() {
	}

}
