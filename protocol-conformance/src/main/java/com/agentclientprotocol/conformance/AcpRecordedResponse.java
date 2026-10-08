package com.agentclientprotocol.conformance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.github.markpollack.judge.ai.model.EvalModel;
import io.github.markpollack.judge.ai.model.EvalModelRequest;
import io.github.markpollack.judge.ai.model.EvalModelResponse;

/**
 * Replays a captured ACP run through the current jury and parser. Zero inference, no credentials,
 * no network.
 *
 * <p>A replay is honest only if the request it answers is the request that was recorded. The
 * roster is regenerated on every run, so this backend compares the regenerated request text with
 * {@code request.txt}; a difference means the roster, the preamble or the producer's prompt changed
 * since the recording, and the backend refuses rather than pairing an old answer with a new
 * question.
 *
 * <p>A missing recording is refused with {@code completed=false} and an explanation, which the
 * producer reports as an instrument ERROR on every requirement — never as a PASS, and never as a
 * finding about the SDK. No synthetic "all PASS" recording exists or may be added.
 */
public final class AcpRecordedResponse implements EvalModel {

    public static final String NO_RECORDING = "No recorded ACP run to replay at %s; capture one with "
            + "scripts/run.sh --live --capture <run-id> (an explicitly approved live run)";

    public static final String REQUEST_MISMATCH = "Recorded run %s answered a different request than the one "
            + "regenerated now (roster, preamble or producer prompt changed); refusing to pair them";

    private final Path runDir;

    public AcpRecordedResponse(Path runsDir, String runId) {
        this.runDir = runsDir.resolve(AcpRunCapture.requireRunId(runId));
    }

    public Path runDir() {
        return runDir;
    }

    @Override
    public EvalModelResponse generate(EvalModelRequest request) {
        Objects.requireNonNull(request);
        Path response = runDir.resolve("response.txt");
        Path recordedRequest = runDir.resolve("request.txt");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("recording", runDir.getFileName().toString());
        facts.put("replayMode", "verbatim recorded response; not fresh inference");
        if (!Files.isRegularFile(response) || !Files.isRegularFile(recordedRequest)) {
            return new EvalModelResponse(NO_RECORDING.formatted(runDir), "recorded", null, facts, false);
        }
        try {
            String recorded = Files.readString(recordedRequest, StandardCharsets.UTF_8);
            String regenerated = AcpRunCapture.requestText(request);
            if (!recorded.equals(regenerated)) {
                facts.put("recordedRequestSha256", AcpRequirementRoster.sha256(recorded.getBytes(StandardCharsets.UTF_8)));
                facts.put("regeneratedRequestSha256",
                        AcpRequirementRoster.sha256(regenerated.getBytes(StandardCharsets.UTF_8)));
                return new EvalModelResponse(REQUEST_MISMATCH.formatted(runDir.getFileName()), "recorded", null, facts,
                        false);
            }
            String text = Files.readString(response, StandardCharsets.UTF_8);
            Path runJson = runDir.resolve("run.json");
            if (Files.isRegularFile(runJson)) {
                facts.put("provenance", Files.readString(runJson, StandardCharsets.UTF_8));
            }
            return new EvalModelResponse(text, "recorded", null, facts, true);
        }
        catch (IOException e) {
            return new EvalModelResponse("Recorded run " + runDir + " is unreadable: " + e.getMessage(), "recorded",
                    null, facts, false);
        }
    }
}
