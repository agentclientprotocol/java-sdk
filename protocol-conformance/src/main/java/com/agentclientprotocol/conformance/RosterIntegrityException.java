package com.agentclientprotocol.conformance;

/** The roster or its retained sources do not match their recorded digests; nothing may execute. */
public final class RosterIntegrityException extends RuntimeException {

    public RosterIntegrityException(String message) {
        super(message);
    }
}
