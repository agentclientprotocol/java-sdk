package com.agentclientprotocol.conformance;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.judge.agentclient.AgentClientEvalModel;
import io.github.markpollack.judge.ai.requirements.Rfc2119Jury;
import io.github.markpollack.judge.serialization.VerdictCodec;
import io.github.markpollack.judge.verdict.Verdict;

/**
 * The code actually loaded for the producer and its bridges: location and SHA-256 of each JAR. A
 * snapshot version string alone does not identify a mutable snapshot; these digests do.
 */
public final class ProducerIdentity {

    private ProducerIdentity() {
    }

    public static Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Class<?> type : List.of(Verdict.class, Rfc2119Jury.class, VerdictCodec.class, AgentClientEvalModel.class,
                AgentClient.class)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                entry.put("location", "unknown");
            }
            else {
                try {
                    Path jar = Path.of(source.getLocation().toURI());
                    entry.put("location", jar.toString());
                    entry.put("sha256", Files.isRegularFile(jar) ? AcpRequirementRoster.sha256(Files.readAllBytes(jar))
                            : "not-a-file");
                }
                catch (URISyntaxException | IOException e) {
                    entry.put("location", source.getLocation().toString());
                    entry.put("sha256", "unreadable: " + e.getMessage());
                }
            }
            out.put(type.getName(), entry);
        }
        return out;
    }
}
