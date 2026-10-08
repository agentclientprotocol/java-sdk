package com.agentclientprotocol.conformance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.github.markpollack.judge.ai.model.EvalModel;
import io.github.markpollack.judge.ai.requirements.Rfc2119Jury;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.verdict.Verdict;

/**
 * One whole-roster investigation: official requirement → configured RFC 2119 jury → pinned
 * implementation and investigative tools → one vote → every judgment retained.
 *
 * <pre>
 *   --replay &lt;run-id&gt;                 replay a captured run (default mode; no inference)
 *   --live --capture &lt;run-id&gt;         one real investigative run, captured as a new immutable recording
 *   --assert-satisfied                exit 1 unless the retained conclusion is PASS
 *   --spec-dir, --runs-dir, --workspace, --sdk-commit, --timeout-minutes
 * </pre>
 * The jury is built publicly and {@code vote()} is called exactly once; the complete Verdict is
 * written as V6 JSON before any optional acceptance check.
 */
public final class AcpProtocolConformanceDemo {

    private AcpProtocolConformanceDemo() {
    }

    /** The configured jury: application-owned runtime and the complete roster. */
    public static Jury configuredJury(EvalModel runtime, AcpRequirementRoster roster) {
        return Rfc2119Jury.builder()
                .runtime(runtime)
                .requirements(roster.requirements())
                .build();
    }

    public static void main(String[] args) throws IOException {
        Options options = Options.parse(args);
        AcpRequirementRoster roster = AcpRequirementRoster.load(options.specDir);
        System.out.println("=== ACP protocol conformance: " + roster + " ===");
        EvalModel runtime;
        Path output;
        if (options.live) {
            runtime = AcpJudgeBackends.live(options.workspace, Duration.ofMinutes(options.timeoutMinutes),
                    options.runsDir, options.runId, roster, options.sdkCommit);
            output = options.runsDir.resolve(options.runId);
            System.out.println("backend: LIVE investigative agent over " + options.workspace.toAbsolutePath()
                    + " — capturing run " + options.runId);
        }
        else {
            runtime = AcpJudgeBackends.replay(options.runsDir, options.runId, roster, options.sdkCommit);
            output = Path.of("target", "replay", options.runId);
            System.out.println("backend: REPLAY of recorded run " + options.runId + " (no inference)");
        }
        System.out.println("producer: " + ProducerIdentity.describe());
        Jury jury = configuredJury(runtime, roster);
        Verdict verdict = jury.vote();
        RetainedVerdicts.Retained retained = RetainedVerdicts.write(verdict, output);
        System.out.println(RosterSummary.render(verdict));
        System.out.println("retained: " + retained.path().toAbsolutePath() + " (" + retained.format() + ", "
                + retained.parts() + " part(s), " + retained.bytes() + " bytes)");
        if (!verdict.invocations().getFirst().completed()) {
            System.out.println("REFUSED: the backend did not complete; see the ERROR reasons above. "
                    + "No judgment about the SDK was made.");
            System.exit(2);
        }
        if (options.assertSatisfied && verdict.conclusion() != Verdict.Conclusion.PASS) {
            System.out.println("NOT SATISFIED: conclusion is " + verdict.conclusion());
            System.exit(1);
        }
    }

    record Options(boolean live, String runId, Path specDir, Path runsDir, Path workspace, String sdkCommit,
            long timeoutMinutes, boolean assertSatisfied) {

        static Options parse(String[] args) {
            boolean live = false;
            boolean assertSatisfied = false;
            String runId = null;
            Path specDir = Path.of("spec");
            Path runsDir = Path.of("runs");
            Path workspace = Path.of("..");
            String sdkCommit = System.getenv().getOrDefault("ACP_CONFORMANCE_SDK_COMMIT", "unspecified");
            long timeout = 120;
            List<String> rest = new ArrayList<>(List.of(args));
            while (!rest.isEmpty()) {
                String arg = rest.removeFirst();
                switch (arg) {
                    case "--live" -> live = true;
                    case "--replay" -> {
                        live = false;
                        if (!rest.isEmpty() && !rest.getFirst().startsWith("--")) {
                            runId = rest.removeFirst();
                        }
                    }
                    case "--capture" -> runId = rest.removeFirst();
                    case "--assert-satisfied" -> assertSatisfied = true;
                    case "--spec-dir" -> specDir = Path.of(rest.removeFirst());
                    case "--runs-dir" -> runsDir = Path.of(rest.removeFirst());
                    case "--workspace" -> workspace = Path.of(rest.removeFirst());
                    case "--sdk-commit" -> sdkCommit = rest.removeFirst();
                    case "--timeout-minutes" -> timeout = Long.parseLong(rest.removeFirst());
                    default -> throw new IllegalArgumentException("Unknown argument " + arg);
                }
            }
            if (runId == null) {
                runId = System.getenv("ACP_CONFORMANCE_RUN");
            }
            if (runId == null) {
                throw new IllegalArgumentException(live ? "--live requires --capture <run-id>"
                        : "--replay requires a run id (argument or ACP_CONFORMANCE_RUN)");
            }
            return new Options(live, runId, specDir, runsDir, workspace, sdkCommit, timeout, assertSatisfied);
        }
    }
}
