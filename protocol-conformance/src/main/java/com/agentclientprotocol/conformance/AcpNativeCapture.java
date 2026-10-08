package com.agentclientprotocol.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import io.github.markpollack.judge.ai.model.NativeCapture;
import io.github.markpollack.judge.ai.model.NativeSnapshot;
import io.github.markpollack.judge.provenance.ArtifactRef;

/**
 * Durable native capture: the complete SDK response is serialized verbatim to a file in the run
 * directory and referenced from the invocation facts by path and digest, instead of being inlined
 * under the bridge's default 1 MiB in-memory bound. Run 001 was lost to that bound after a 28-minute
 * investigation; nothing here is ever truncated or refused for size.
 *
 * @param <T> the native response type
 */
public final class AcpNativeCapture<T> implements NativeCapture<T> {

    private static final JsonMapper MAPPER;

    static {
        SimpleModule module = new SimpleModule();
        module.addSerializer(java.time.Duration.class, ToStringSerializer.instance);
        module.addSerializer(java.time.Instant.class, ToStringSerializer.instance);
        MAPPER = JsonMapper.builder().addModule(module).build();
    }

    private final Path runDir;

    public AcpNativeCapture(Path runDir) {
        this.runDir = runDir;
    }

    @Override
    public NativeSnapshot capture(T response) {
        try {
            Files.createDirectories(runDir);
            byte[] json = MAPPER.writeValueAsBytes(response);
            Path file = runDir.resolve("native-response.json");
            Files.write(file, json);
            ArtifactRef artifact = ArtifactRef.ofBytes("native-response.json", json, null);
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("nativeResponseFile", file.toString());
            facts.put("nativeResponseBytes", json.length);
            facts.put("nativeResponseSha256", artifact.sha256());
            return new NativeSnapshot(facts, List.of(artifact));
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot write native response under " + runDir, e);
        }
    }
}
