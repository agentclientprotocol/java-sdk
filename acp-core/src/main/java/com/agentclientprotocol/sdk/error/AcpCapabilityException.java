/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

/**
 * Thrown when a call needs a capability the peer did not advertise in the {@code initialize}
 * exchange, so the SDK refuses it without sending anything. ACP lets a side call an optional method
 * only when the other side advertised it; check
 * {@link com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities} first to avoid it.
 * {@link #getCapability()} names the missing capability.
 *
 * <p>Where the SDK raises it:
 * <ul>
 * <li>On a client, the calls the agent must advertise: {@code loadSession}, {@code listSessions},
 * {@code closeSession}, {@code deleteSession}, {@code resumeSession}, {@code logout} and the
 * unstable fork and provider calls. The capabilities are {@code loadSession},
 * {@code sessionCapabilities.list} and its siblings, and {@code auth.logout}.</li>
 * <li>On an agent, the requests to the client the client must advertise: reading and writing files
 * ({@code fs.readTextFile}, {@code fs.writeTextFile}), terminals ({@code terminal}) and elicitation
 * ({@code elicitation.form} or {@code elicitation.url} for the mode asked). Before the client's
 * {@code initialize} has arrived nothing is known, and the agent sends the request.</li>
 * <li>The {@code requireX()} methods of {@code NegotiatedCapabilities}.</li>
 * </ul>
 *
 * <p>On the asynchronous API the call's {@code Mono} fails with it; the sync API throws it. The SDK
 * does not check prompt content against {@code promptCapabilities}.
 *
 * <p>Example:
 * <pre>{@code
 * try {
 *     client.loadSession(new AcpSchema.LoadSessionRequest(sessionId, "/workspace", List.of()));
 * }
 * catch (AcpCapabilityException e) {
 *     client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()));
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @see com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities
 * @see #toProtocolException()
 */
public class AcpCapabilityException extends AcpException {

	/** The missing capability, as its path in the ACP capabilities. */
	private final String capability;

	/**
	 * Creates the exception for a capability, with the message
	 * {@code "Capability not supported by peer: <capability>"}.
	 * @param capability the missing capability, as its path in the ACP capabilities, such as
	 * {@code "fs.readTextFile"}
	 */
	public AcpCapabilityException(String capability) {
		super(formatMessage(capability));
		this.capability = capability;
	}

	/**
	 * Creates the exception for a capability, with a message of your own.
	 * @param capability the missing capability, as its path in the ACP capabilities
	 * @param message the message
	 */
	public AcpCapabilityException(String capability, String message) {
		super(message);
		this.capability = capability;
	}

	/**
	 * Returns the missing capability, as its path in the ACP capabilities.
	 * @return the capability, such as {@code "fs.readTextFile"}, {@code "sessionCapabilities.list"}
	 * or {@code "elicitation.form"}
	 */
	public String getCapability() {
		return capability;
	}

	/**
	 * Returns a protocol exception for a handler to throw when it cannot serve a request because of
	 * this missing capability: code {@code -32600} (invalid request: the request is not valid for
	 * the negotiated capabilities), this exception's message, and the capability name as data. ACP
	 * defines no code of its own for a missing capability. The SDK does not call it.
	 * @return the protocol exception
	 */
	public AcpProtocolException toProtocolException() {
		return new AcpProtocolException(AcpErrorCodes.INVALID_REQUEST, getMessage(), capability);
	}

	private static String formatMessage(String capability) {
		return String.format("Capability not supported by peer: %s", capability);
	}

}
