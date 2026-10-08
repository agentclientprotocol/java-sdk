/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

/**
 * Thrown by {@code initialize} when the agent answers with a protocol version this SDK does not
 * speak. The client has then closed the connection, as ACP v1 says it should (initialization,
 * "Version Negotiation"): no later call is sent, and the user-facing application should show the
 * message, which names both versions.
 *
 * @see com.agentclientprotocol.sdk.client.AcpAsyncClient#initialize()
 */
public class AcpVersionException extends AcpException {

	private static final long serialVersionUID = 1L;

	private final int agentVersion;

	private final int clientVersion;

	/**
	 * Creates the exception for the two versions that did not match.
	 * @param agentVersion the version the agent answered with
	 * @param clientVersion the version this client speaks
	 */
	public AcpVersionException(int agentVersion, int clientVersion) {
		super("The agent answered initialize with protocol version " + agentVersion + ", which this client does not "
				+ "support; it speaks version " + clientVersion + ". The connection has been closed.");
		this.agentVersion = agentVersion;
		this.clientVersion = clientVersion;
	}

	/**
	 * Returns the protocol version the agent answered with.
	 * @return the agent's version
	 */
	public int getAgentVersion() {
		return this.agentVersion;
	}

	/**
	 * Returns the protocol version this client speaks.
	 * @return the client's version
	 */
	public int getClientVersion() {
		return this.clientVersion;
	}

}
