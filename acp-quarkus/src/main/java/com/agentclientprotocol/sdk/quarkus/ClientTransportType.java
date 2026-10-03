/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus;

/**
 * The transport an ACP client bean connects to its agent with.
 *
 * @author Mark Pollack
 */
public enum ClientTransportType {

	/** Launch the agent as a process and talk over its standard input and output. */
	STDIO,

	/** Connect to a WebSocket endpoint. */
	WEBSOCKET,

	/** Connect to a Streamable HTTP endpoint. */
	HTTP

}
