/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

/**
 * A request named a session this connection does not know, or would exceed the bound on
 * provisional sessions. The servlet answers it with 404.
 *
 * @author Kaiser Dandangi
 */
final class UnknownSessionException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	UnknownSessionException(String message) {
		super(message);
	}

}
