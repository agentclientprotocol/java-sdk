package com.agentclientprotocol.conformance;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.judge.judgment.Check;
import io.github.markpollack.judge.judgment.JudgmentStatus;
import io.github.markpollack.judge.verdict.AttemptDisposition;
import io.github.markpollack.judge.verdict.Verdict;

/** Display-only views over a retained Verdict. Always keep the whole Verdict; never recompute it. */
public final class RosterSummary {

    private RosterSummary() {
    }

    /** One check per roster item, in roster order, from the composite attempts. */
    public static List<Check> checks(Verdict verdict) {
        return verdict.compositeAttempts().stream()
                .map(attempt -> new Check(attempt.name(), attempt.verdict().individual().getFirst()))
                .toList();
    }

    public static Map<JudgmentStatus, Long> counts(Verdict verdict) {
        Map<JudgmentStatus, Long> counts = new EnumMap<>(JudgmentStatus.class);
        for (JudgmentStatus status : JudgmentStatus.values()) {
            counts.put(status, 0L);
        }
        for (Check check : checks(verdict)) {
            counts.merge(check.judgment().status(), 1L, Long::sum);
        }
        return counts;
    }

    /** Attempts the producer could not bind to the protocol (undeclared or duplicate answers). */
    public static long unbound(Verdict verdict) {
        return verdict.compositeAttempts().stream().filter(a -> a.disposition() != AttemptDisposition.USED).count();
    }

    public static String render(Verdict verdict) {
        StringBuilder out = new StringBuilder();
        Map<JudgmentStatus, Long> counts = counts(verdict);
        out.append("  roster size      ").append(verdict.roster().size()).append('\n');
        out.append("  composite items  ").append(verdict.compositeAttempts().size()).append('\n');
        out.append("  invocations      ").append(verdict.invocations().size()).append('\n');
        counts.forEach((status, n) -> out.append(String.format("  %-16s %d%n", status, n)));
        out.append("  protocol-unbound ").append(unbound(verdict)).append('\n');
        out.append("  conclusion       ").append(verdict.conclusion()).append('\n');
        for (Check check : checks(verdict)) {
            if (check.judgment().status() == JudgmentStatus.FAIL) {
                out.append("    FAIL ").append(check.id()).append(" - ").append(check.judgment().reasoning()).append('\n');
            }
        }
        // instrument errors usually share one reason (a refused replay, an incomplete run): group them
        Map<String, List<String>> errors = new java.util.LinkedHashMap<>();
        for (Check check : checks(verdict)) {
            if (check.judgment().status() == JudgmentStatus.ERROR) {
                errors.computeIfAbsent(String.valueOf(check.judgment().reasoning()), k -> new java.util.ArrayList<>())
                        .add(check.id());
            }
        }
        errors.forEach((reason, ids) -> out.append("    ERROR x").append(ids.size()).append(" (")
                .append(ids.size() == 1 ? ids.getFirst() : ids.getFirst() + " ... " + ids.getLast()).append(") - ")
                .append(reason).append('\n'));
        return out.toString();
    }
}
