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
 * {@code sessionCapabilities.list} and its siblings, and {@code auth.logout}. Also a
 * {@code session/new}, {@code session/load}, {@code session/resume} or {@code session/fork} that
 * names additional directories, which needs {@code sessionCapabilities.additionalDirectories}.</li>
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
 * <p>It is the caller's failure and is never sent. The one case where a peer must answer a request
 * for a capability that was not advertised is ACP's: a client asked for an elicitation in a mode
 * it did not declare answers {@code -32602} (invalid params), which the SDK's client does. A
 * handler with a similar case throws an {@link AcpProtocolException} with
 * {@link AcpErrorCodes#INVALID_PARAMS}.
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

	private static String formatMessage(String capability) {
		return String.format("Capability not supported by peer: %s", capability);
	}

}
