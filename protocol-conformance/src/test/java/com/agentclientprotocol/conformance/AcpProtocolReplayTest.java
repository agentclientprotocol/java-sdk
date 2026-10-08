package com.agentclientprotocol.conformance;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.github.markpollack.judge.ai.model.EvalModel;
import io.github.markpollack.judge.ai.requirements.NativeRequirementCodecs;
import io.github.markpollack.judge.judgment.JudgmentStatus;
import io.github.markpollack.judge.verdict.Verdict;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression over a REAL recorded ACP run: verifies the retained outcome and result integrity,
 * never that every clause passed. Enabled with {@code -Dacp.conformance.run=<run-id>} once a
 * genuine capture exists under {@code runs/}; until then it is skipped, not faked.
 */
@EnabledIfSystemProperty(named = "acp.conformance.run", matches = ".+")
class AcpProtocolReplayTest {

    @Test
    void replayReproducesTheRecordedOutcomeWithoutInference() {
        String runId = System.getProperty("acp.conformance.run");
        String sdk = System.getProperty("acp.conformance.sdk", System.getenv().getOrDefault("ACP_CONFORMANCE_SDK_COMMIT", "unspecified"));
        AcpRequirementRoster roster = AcpRequirementRoster.load(Path.of("spec"));
        AtomicInteger calls = new AtomicInteger();
        EvalModel recorded = AcpJudgeBackends.replay(Path.of("runs"), runId, roster, sdk);
        EvalModel counted = request -> { calls.incrementAndGet(); return recorded.generate(request); };
        Verdict verdict = AcpProtocolConformanceDemo.configuredJury(counted, roster).vote();
        assertThat(calls).hasValue(1);
        assertThat(verdict.roster()).hasSize(roster.requirements().size());
        assertThat(verdict.compositeAttempts()).hasSize(roster.requirements().size());
        assertThat(verdict.invocations()).hasSize(1);
        var counts = RosterSummary.counts(verdict);
        // the recording must be a genuine complete answer, not a refusal
        assertThat(counts.get(JudgmentStatus.ERROR)).as("replay refused or malformed: %s", counts).isZero();
        assertThat(RosterSummary.unbound(verdict)).isZero();
        String root = verdict.invocations().getFirst().id();
        for (int i = 0; i < roster.requirements().size(); i++) {
            var child = verdict.compositeAttempts().get(i).verdict().individual().getFirst();
            assertThat(child.requirement()).isSameAs(roster.requirements().get(i));
            assertThat(child.invocationIds()).containsExactly(root);
        }
        RetainedVerdicts.Retained retained = RetainedVerdicts.write(verdict, Path.of("target", "replay-test", runId));
        assertThat(retained.path()).exists();
        if (retained.format().equals("v6")) {
            var codec = NativeRequirementCodecs.codec();
            Verdict reopened = codec.read(codec.write(verdict));
            assertThat(reopened.conclusion()).isEqualTo(verdict.conclusion());
            assertThat(RosterSummary.counts(reopened)).isEqualTo(counts);
        }
        assertThat(calls).hasValue(1);
        System.out.println(RosterSummary.render(verdict));
    }
}
