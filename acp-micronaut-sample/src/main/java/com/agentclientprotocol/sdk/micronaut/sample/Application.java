/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.sample;

import io.micronaut.runtime.Micronaut;

/**
 * Starts the agent. Over stdio (the default) standard output carries the protocol, so the
 * banner is off and {@code logback.xml} logs to standard error; the process exits when its
 * client closes the agent's input. With {@code -Dacp.agent.transport.type=http} it serves
 * Streamable HTTP and WebSocket on {@code acp.agent.transport.http.listener.port} until stopped.
 */
public final class Application {

	private Application() {
	}

	/**
	 * Runs the application.
	 * @param args command-line arguments, which may set properties ({@code --acp.agent...})
	 */
	public static void main(String[] args) {
		Micronaut.build(args).mainClass(Application.class).banner(false).start();
	}

}
