package com.agentclientprotocol.conformance;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.markpollack.agents.claude.ClaudeAgentModel;
import io.github.markpollack.agents.claude.ClaudeAgentOptions;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.judge.agentclient.AgentClientEvalModel;
import io.github.markpollack.judge.ai.model.EvalModel;

/**
 * The one place that decides where the audit's answer comes from.
 *
 * <ul>
 * <li><b>replay</b> (default): {@link AcpRecordedResponse} over a captured run. No inference.</li>
 * <li><b>live</b>: {@link AgentClientEvalModel} over an {@link AgentClient} whose working directory
 * is the pinned SDK workspace, wrapped in {@link AcpRunCapture} so the raw request, response and
 * native invocation facts are retained. Requires an explicit run id; a live run is always a new,
 * immutable recording.</li>
 * </ul>
 * Both are wrapped in {@link AcpAuditContext}, so replay regenerates exactly the request that was
 * captured and can prove it.
 */
public final class AcpJudgeBackends {

    private AcpJudgeBackends() {
    }

    /** A judging agent scoped to one absolute workspace; see the PetClinic lesson on default options. */
    public static AgentClient judgingAgent(Path workspace, Duration timeout) {
        Path scope = workspace.toAbsolutePath().normalize();
        ClaudeAgentModel model = ClaudeAgentModel.builder()
                .workingDirectory(scope)
                .timeout(timeout)
                .build();
        // The working directory that reaches the agent comes from the CLIENT's default options.
        return AgentClient.builder(model)
                .defaultOptions(ClaudeAgentOptions.builder()
                        .workingDirectory(scope.toString())
                        .timeout(timeout)
                        .build())
                .build();
    }

    public static EvalModel replay(Path runsDir, String runId, AcpRequirementRoster roster, String sdkCommit) {
        return new AcpAuditContext(new AcpRecordedResponse(runsDir, runId), sdkCommit, roster.protocolCommit(),
                roster.revision());
    }

    public static EvalModel live(Path workspace, Duration timeout, Path runsDir, String runId,
            AcpRequirementRoster roster, String sdkCommit) {
        return liveOver(new AgentClientEvalModel(judgingAgent(workspace, timeout)), workspace, runsDir, runId, roster,
                sdkCommit);
    }

    /** Live wiring over any investigative backend; used by tests with a synthetic delegate. */
    static EvalModel liveOver(EvalModel investigative, Path workspace, Path runsDir, String runId,
            AcpRequirementRoster roster, String sdkCommit) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("subjectWorkspace", workspace.toAbsolutePath().normalize().toString());
        identity.put("subjectCommit", sdkCommit);
        identity.put("protocolCommit", roster.protocolCommit());
        identity.put("rosterRevision", roster.revision());
        identity.put("rosterSha256", roster.rosterSha256());
        identity.put("rosterSize", roster.requirements().size());
        identity.put("producer", ProducerIdentity.describe());
        // Context first, capture second: request.txt must be exactly what the investigative backend received,
        // which is also what replay regenerates and compares against.
        return new AcpAuditContext(new AcpRunCapture(investigative, runsDir, runId, identity), sdkCommit,
                roster.protocolCommit(), roster.revision());
    }
}
