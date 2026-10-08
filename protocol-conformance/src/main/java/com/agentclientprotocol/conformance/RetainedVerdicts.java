package com.agentclientprotocol.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.markpollack.judge.ai.requirements.NativeRequirementCodecs;
import io.github.markpollack.judge.judgment.Judgment;
import io.github.markpollack.judge.portable.PreservationLimitException;
import io.github.markpollack.judge.requirement.Requirement;
import io.github.markpollack.judge.serialization.VerdictCodec;
import io.github.markpollack.judge.verdict.AttemptDisposition;
import io.github.markpollack.judge.verdict.CompositeAttempt;
import io.github.markpollack.judge.verdict.Verdict;

/**
 * Persists and reopens the complete roster Verdict as strict V6 documents.
 *
 * <p>The producer bounds one V6 document at {@link VerdictCodec#MAXIMUM_BYTES} (1 MiB) and refuses
 * rather than truncates. A 240-item full-text verdict is larger than that, so the verdict is written
 * as <em>parts</em>: {@code root.json} (the collective judgment, as a one-member verdict) and
 * {@code part-NN.json}, each a complete V6 roster verdict over a contiguous slice of the composite
 * attempts, every one under the bound. {@link #read} reopens every part through the producer's codec
 * and reassembles the original Verdict; {@code manifest.json} records the slices and digests.
 * Nothing is truncated, sampled or merged: the reassembled Verdict equals the one that was voted.
 */
public final class RetainedVerdicts {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String SINGLE = "verdict-v6.json";
    private static final String PARTS = "verdict-v6-parts";

    public record Retained(String format, Path path, long bytes, int parts) {
    }

    private RetainedVerdicts() {
    }

    public static Retained write(Verdict verdict, Path directory) {
        VerdictCodec codec = NativeRequirementCodecs.codec();
        try {
            Files.createDirectories(directory);
            try {
                String v6 = codec.write(verdict);
                Path path = directory.resolve(SINGLE);
                Files.writeString(path, v6, StandardCharsets.UTF_8);
                return new Retained("v6", path, v6.getBytes(StandardCharsets.UTF_8).length, 1);
            }
            catch (PreservationLimitException tooLarge) {
                return writeParts(verdict, directory.resolve(PARTS), codec, tooLarge.getMessage());
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot retain verdict under " + directory, e);
        }
    }

    private static Retained writeParts(Verdict verdict, Path dir, VerdictCodec codec, String refusal)
            throws IOException {
        Files.createDirectories(dir);
        List<Map<String, Object>> parts = new ArrayList<>();
        long total = 0;
        String root = codec.write(Verdict.single("roster-root", verdict.judgment()));
        Path rootPath = dir.resolve("root.json");
        Files.writeString(rootPath, root, StandardCharsets.UTF_8);
        total += root.getBytes(StandardCharsets.UTF_8).length;
        List<CompositeAttempt> attempts = verdict.compositeAttempts();
        List<? extends Requirement<?>> roster = verdict.roster();
        if (roster.size() != attempts.size()) {
            throw new IllegalStateException("Roster verdict with " + roster.size() + " requirements but "
                    + attempts.size() + " attempts cannot be sliced");
        }
        int from = 0;
        int size = attempts.size();
        int index = 1;
        while (from < attempts.size()) {
            int to = Math.min(attempts.size(), from + size);
            Verdict shard = shard(verdict, from, to);
            String encoded;
            try {
                encoded = codec.write(shard);
            }
            catch (PreservationLimitException stillTooLarge) {
                if (size == 1) {
                    throw new IllegalStateException("A single roster item exceeds the producer's V6 bound: "
                            + attempts.get(from).name(), stillTooLarge);
                }
                size = Math.max(1, size / 2);
                continue;
            }
            String file = String.format("part-%02d.json", index++);
            Files.writeString(dir.resolve(file), encoded, StandardCharsets.UTF_8);
            long bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
            total += bytes;
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("file", file);
            part.put("from", from);
            part.put("to", to);
            part.put("bytes", bytes);
            part.put("sha256", AcpRequirementRoster.sha256(encoded.getBytes(StandardCharsets.UTF_8)));
            parts.add(part);
            from = to;
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "v6-parts");
        manifest.put("producerBoundBytes", VerdictCodec.MAXIMUM_BYTES);
        manifest.put("singleDocumentRefusal", refusal);
        manifest.put("declaredCardinality", verdict.declaredCardinality());
        manifest.put("root", Map.of("file", "root.json",
                "sha256", AcpRequirementRoster.sha256(root.getBytes(StandardCharsets.UTF_8))));
        manifest.put("parts", parts);
        manifest.put("totalBytes", total);
        Path manifestPath = dir.resolve("manifest.json");
        Files.writeString(manifestPath, JSON.writeValueAsString(manifest), StandardCharsets.UTF_8);
        return new Retained("v6-parts", manifestPath, total, parts.size());
    }

    /** A complete V6 roster verdict over attempts [from, to), with the producer's own collective rule. */
    private static Verdict shard(Verdict verdict, int from, int to) {
        List<CompositeAttempt> attempts = verdict.compositeAttempts().subList(from, to);
        return Verdict.advancedBuilder()
                .judgment(collective(attempts))
                .roster(verdict.roster().subList(from, to))
                .declaredCardinality(to - from)
                .invocations(verdict.invocations())
                .compositeAttempts(attempts)
                .provenance(verdict.provenance())
                .build();
    }

    /** The same rule Rfc2119Jury applies to a roster, so every shard is a coherent roster record. */
    static Judgment collective(List<CompositeAttempt> attempts) {
        boolean failed = false;
        boolean incomplete = false;
        boolean applicable = false;
        for (CompositeAttempt attempt : attempts) {
            if (attempt.disposition() != AttemptDisposition.USED) {
                incomplete = true;
                continue;
            }
            Verdict.Conclusion c = Objects.requireNonNull(attempt.verdict()).conclusion();
            failed |= c == Verdict.Conclusion.FAIL;
            applicable |= c != Verdict.Conclusion.NOT_APPLICABLE;
            incomplete |= c != Verdict.Conclusion.PASS && c != Verdict.Conclusion.NOT_APPLICABLE;
        }
        return failed ? Judgment.fail("An established roster requirement was violated (slice)")
                : incomplete ? Judgment.abstain("Not every applicable declared requirement was established (slice)")
                : applicable ? Judgment.pass("Every applicable declared requirement passed (slice)")
                : Judgment.notApplicable("Every declared requirement was justifiably excluded (slice)");
    }

    /** Reopen a retained verdict (single document or parts) through the producer's codec; no execution. */
    public static Verdict read(Path directory) {
        VerdictCodec codec = NativeRequirementCodecs.codec();
        try {
            Path single = directory.resolve(SINGLE);
            if (Files.isRegularFile(single)) {
                return codec.read(Files.readString(single, StandardCharsets.UTF_8));
            }
            Path dir = directory.resolve(PARTS);
            JsonNode manifest = JSON.readTree(Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8));
            String rootText = Files.readString(dir.resolve(manifest.path("root").path("file").asText()), StandardCharsets.UTF_8);
            requireDigest(rootText, manifest.path("root").path("sha256").asText(), "root.json");
            Judgment root = codec.read(rootText).judgment();
            List<Requirement<?>> roster = new ArrayList<>();
            List<CompositeAttempt> attempts = new ArrayList<>();
            List<io.github.markpollack.judge.provenance.Invocation> invocations = null;
            io.github.markpollack.judge.verdict.VerdictProvenance provenance = null;
            int expectedFrom = 0;
            for (JsonNode part : manifest.path("parts")) {
                String file = part.path("file").asText();
                String text = Files.readString(dir.resolve(file), StandardCharsets.UTF_8);
                requireDigest(text, part.path("sha256").asText(), file);
                if (part.path("from").asInt() != expectedFrom) {
                    throw new IllegalStateException("Parts are not contiguous at " + file);
                }
                Verdict shard = codec.read(text);
                if (shard.compositeAttempts().size() != part.path("to").asInt() - expectedFrom) {
                    throw new IllegalStateException("Part " + file + " does not carry its declared slice");
                }
                roster.addAll(shard.roster());
                attempts.addAll(shard.compositeAttempts());
                if (invocations == null) {
                    invocations = shard.invocations();
                    provenance = shard.provenance();
                }
                else if (!invocations.equals(shard.invocations())) {
                    throw new IllegalStateException("Part " + file + " carries different invocations");
                }
                expectedFrom = part.path("to").asInt();
            }
            int declared = manifest.path("declaredCardinality").asInt();
            if (attempts.size() != declared || roster.size() != declared) {
                throw new IllegalStateException("Reassembled " + attempts.size() + " attempts for a declared cardinality of " + declared);
            }
            return Verdict.advancedBuilder()
                    .judgment(root)
                    .roster(roster)
                    .declaredCardinality(declared)
                    .invocations(Objects.requireNonNull(invocations))
                    .compositeAttempts(attempts)
                    .provenance(provenance)
                    .build();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot reopen verdict under " + directory, e);
        }
    }

    private static void requireDigest(String text, String expected, String file) {
        String actual = AcpRequirementRoster.sha256(text.getBytes(StandardCharsets.UTF_8));
        if (!actual.equals(expected)) {
            throw new IllegalStateException("Retained part " + file + " hashes to " + actual + ", manifest says " + expected);
        }
    }
}
