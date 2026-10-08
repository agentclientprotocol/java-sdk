package com.agentclientprotocol.conformance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.markpollack.judge.ai.model.NativeSnapshot;

import static org.assertj.core.api.Assertions.assertThat;

/** The durable native capture keeps the whole SDK response on disk, however large, and only a digest in facts. */
class AcpNativeCaptureTest {

    @Test
    void writesTheCompleteNativeResponseToTheRunDirectoryAndReferencesItByDigest(@TempDir Path temp) throws Exception {
        Path run = temp.resolve("runs").resolve("synthetic-native");
        String big = "x".repeat(3 * 1024 * 1024); // three times the bridge's default in-memory bound
        NativeSnapshot snapshot = new AcpNativeCapture<Map<String, Object>>(run)
                .capture(Map.of("result", big, "sessionId", "synthetic"));
        Path file = run.resolve("native-response.json");
        assertThat(file).exists();
        assertThat(Files.size(file)).isGreaterThan(3L * 1024 * 1024);
        assertThat(snapshot.facts()).containsEntry("nativeResponseFile", file.toString())
                .containsKey("nativeResponseSha256").containsKey("nativeResponseBytes");
        assertThat(snapshot.artifacts()).hasSize(1);
        assertThat(snapshot.artifacts().getFirst().id()).isEqualTo("native-response.json");
        assertThat(snapshot.artifacts().getFirst().sha256())
                .isEqualTo(AcpRequirementRoster.sha256(Files.readAllBytes(file)));
        assertThat(Files.readString(file)).contains("\"sessionId\":\"synthetic\"");
    }
}
