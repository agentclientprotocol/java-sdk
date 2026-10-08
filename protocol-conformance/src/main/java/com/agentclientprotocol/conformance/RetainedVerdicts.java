package com.agentclientprotocol.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.markpollack.judge.ai.requirements.NativeRequirementCodecs;
import io.github.markpollack.judge.portable.PreservationLimitException;
import io.github.markpollack.judge.verdict.Verdict;

/**
 * Persists the complete Verdict. The strict V6 document is preferred; when the producer refuses it
 * because the 240-item full-text verdict exceeds its 1 MiB preservation bound, the complete verdict
 * is retained as a portable Jackson document instead and the refusal is recorded next to it. Nothing
 * is truncated or sampled on either path.
 */
public final class RetainedVerdicts {

    private static final ObjectMapper PORTABLE = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public record Retained(String format, Path path, long bytes, boolean v6Refused) {
    }

    private RetainedVerdicts() {
    }

    public static Retained write(Verdict verdict, Path directory) {
        try {
            Files.createDirectories(directory);
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("producerBoundBytes", VerdictCodecBound.MAXIMUM_BYTES);
            try {
                String v6 = NativeRequirementCodecs.codec().write(verdict);
                Path path = directory.resolve("verdict-v6.json");
                Files.writeString(path, v6, StandardCharsets.UTF_8);
                long bytes = v6.getBytes(StandardCharsets.UTF_8).length;
                note.put("format", "v6");
                note.put("v6Refused", false);
                note.put("bytes", bytes);
                Files.writeString(directory.resolve("retention.json"), PORTABLE.writeValueAsString(note));
                return new Retained("v6", path, bytes, false);
            }
            catch (PreservationLimitException refused) {
                String portable = PORTABLE.writeValueAsString(verdict);
                Path path = directory.resolve("verdict-portable.json");
                Files.writeString(path, portable, StandardCharsets.UTF_8);
                long bytes = portable.getBytes(StandardCharsets.UTF_8).length;
                note.put("format", "portable-jackson");
                note.put("v6Refused", true);
                note.put("v6Refusal", refused.getMessage());
                note.put("bytes", bytes);
                Files.writeString(directory.resolve("retention.json"), PORTABLE.writeValueAsString(note));
                return new Retained("portable-jackson", path, bytes, true);
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot retain verdict under " + directory, e);
        }
    }
}
