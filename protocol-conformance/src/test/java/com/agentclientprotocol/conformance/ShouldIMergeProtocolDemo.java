package com.agentclientprotocol.conformance;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.github.markpollack.judge.assertj.Assertions;

/**
 * The separate, opt-in acceptance assertion over the same configured jury: does this SDK satisfy
 * every applicable derived requirement of stable ACP v1? Replays the named recording; passes only
 * on a PASS conclusion. A FAIL here is a rejected subject, not a broken harness — harness failures
 * surface in {@link AcpProtocolHarnessTest} and {@link AcpProtocolReplayTest} first.
 *
 * <p>Run with {@code -Dacp.conformance.assert=true -Dacp.conformance.run=<run-id>}.
 */
@EnabledIfSystemProperty(named = "acp.conformance.assert", matches = "true")
class ShouldIMergeProtocolDemo {

    @Test
    void theSdkSatisfiesEveryApplicableStableAcpV1Requirement() {
        String runId = System.getProperty("acp.conformance.run");
        String sdk = System.getenv().getOrDefault("ACP_CONFORMANCE_SDK_COMMIT", "unspecified");
        AcpRequirementRoster roster = AcpRequirementRoster.load(Path.of("spec"));
        var jury = AcpProtocolConformanceDemo.configuredJury(
                AcpJudgeBackends.replay(Path.of("runs"), runId, roster, sdk), roster);
        Assertions.assertThat(jury).isPassed();
    }
}
