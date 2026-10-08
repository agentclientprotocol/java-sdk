package com.agentclientprotocol.conformance;

import io.github.markpollack.judge.serialization.VerdictCodec;

/** The producer's hard V6 document bound, named here so the harness can report against it. */
public final class VerdictCodecBound {

    public static final int MAXIMUM_BYTES = VerdictCodec.MAXIMUM_BYTES;

    private VerdictCodecBound() {
    }
}
