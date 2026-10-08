package com.agentclientprotocol.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.markpollack.judge.ai.model.EvalMessage;
import io.github.markpollack.judge.ai.model.EvalModel;
import io.github.markpollack.judge.ai.model.EvalModelRequest;
import io.github.markpollack.judge.ai.model.EvalModelResponse;
import io.github.markpollack.judge.ai.model.GeneratedInput;
import io.github.markpollack.judge.execution.NativeExecution;

/**
 * Captures one backend execution into an immutable run directory, without rewriting anything.
 *
 * <p>Files written under {@code runs/<run-id>/}:
 * <ul>
 * <li>{@code request.txt} — the complete request text as delivered to the delegate (after the ACP context)</li>
 * <li>{@code response.txt} — the delegate's verbatim response text, even when malformed</li>
 * <li>{@code invocation.json} — the producer's native invocation record: id, protocol, completed, model,
 * duration, facts (including usage when the backend reported it) and failure class</li>
 * <li>{@code run.json} — identities: run id, start/end instants, subject and roster digests</li>
 * <li>{@code failure.txt} — only when the delegate threw; the run is then retained as a failed run</li>
 * </ul>
 * A run id that already exists is refused: capture never replaces a previous recording.
 */
public final class AcpRunCapture implements EvalModel {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final EvalModel delegate;
    private final Path runDir;
    private final Map<String, Object> identity;
    private boolean used;

    public AcpRunCapture(EvalModel delegate, Path runsDir, String runId, Map<String, Object> identity) {
        this.delegate = Objects.requireNonNull(delegate);
        this.runDir = runsDir.resolve(requireRunId(runId));
        this.identity = new LinkedHashMap<>(identity);
        if (Files.exists(runDir.resolve("request.txt")) || Files.exists(runDir.resolve("run.json"))) {
            throw new IllegalStateException("Run " + runDir + " already exists; a capture never replaces a recording");
        }
    }

    static String requireRunId(String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Run id must be a short file-safe token, got " + runId);
        }
        return runId;
    }

    public Path runDir() {
        return runDir;
    }

    @Override
    public Set<GeneratedInput> supportedInputs() {
        return delegate.supportedInputs();
    }

    @Override
    public void validateRequest(EvalModelRequest request) {
        delegate.validateRequest(request);
    }

    @Override
    public EvalModelResponse generate(EvalModelRequest request) {
        return execute(request).answer();
    }

    @Override
    public synchronized NativeExecution<EvalModelResponse> execute(EvalModelRequest request) {
        if (used) {
            throw new IllegalStateException("Run " + runDir + " was already captured; one capture is one execution");
        }
        used = true;
        Instant started = Instant.now();
        try {
            Files.createDirectories(runDir);
            write("request.txt", requestText(request));
            Map<String, Object> run = new LinkedHashMap<>(identity);
            run.put("runId", runDir.getFileName().toString());
            run.put("started", started.toString());
            run.put("state", "started");
            writeJson("run.json", run);
            NativeExecution<EvalModelResponse> execution;
            try {
                execution = delegate.execute(request);
            }
            catch (RuntimeException | Error failure) {
                write("failure.txt", failure.getClass().getName() + ": " + failure.getMessage());
                run.put("ended", Instant.now().toString());
                run.put("state", "failed");
                writeJson("run.json", run);
                throw failure;
            }
            EvalModelResponse answer = execution.answer();
            write("response.txt", answer.text() == null ? "" : answer.text());
            var invocation = execution.invocation();
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("id", invocation.id());
            facts.put("protocol", invocation.protocol());
            facts.put("completed", invocation.completed());
            facts.put("model", invocation.model());
            facts.put("durationMillis", invocation.durationMillis());
            facts.put("nativeFacts", invocation.nativeFacts());
            facts.put("artifacts", invocation.artifacts());
            facts.put("failure", invocation.cause() == null ? null
                    : invocation.cause().getClass().getName() + ": " + invocation.cause().getMessage());
            facts.put("usageKnown", answer.usage() != null);
            writeJson("invocation.json", facts);
            run.put("ended", Instant.now().toString());
            run.put("state", answer.completed() ? "completed" : "returned-incomplete");
            run.put("answerState", answer.answerState().name());
            writeJson("run.json", run);
            return execution;
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot capture run " + runDir, e);
        }
    }

    static String requestText(EvalModelRequest request) {
        StringBuilder text = new StringBuilder();
        for (EvalMessage message : request.messages()) {
            text.append(message.content());
        }
        return text.toString();
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(runDir.resolve(name), content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeJson(String name, Object value) throws IOException {
        Files.writeString(runDir.resolve(name), JSON.writeValueAsString(value), StandardCharsets.UTF_8);
    }
}
