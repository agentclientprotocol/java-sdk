package com.agentclientprotocol.conformance;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.markpollack.judge.ai.model.EvalMessage;
import io.github.markpollack.judge.ai.model.EvalMessageRole;
import io.github.markpollack.judge.ai.model.EvalModel;
import io.github.markpollack.judge.ai.model.EvalModelRequest;
import io.github.markpollack.judge.ai.model.EvalModelResponse;
import io.github.markpollack.judge.ai.model.GeneratedInput;
import io.github.markpollack.judge.execution.NativeExecution;

/**
 * Prepends the ACP audit instruction to the roster prompt the producer generates, then delegates.
 *
 * <p>This is the "lower backend request adapter" of the plan: the producer still owns the roster,
 * the actual N, the response grammar and the parser; this wrapper only adds protocol context in
 * front of the generated request. It delegates {@link #execute} rather than wrapping
 * {@link #generate} in a lambda, so the delegate's native invocation facts, usage and raw response
 * are preserved unchanged.
 */
public final class AcpAuditContext implements EvalModel {

    private final EvalModel delegate;
    private final String preamble;

    public AcpAuditContext(EvalModel delegate, String sdkCommit, String protocolCommit, String rosterRevision) {
        this.delegate = Objects.requireNonNull(delegate);
        this.preamble = preamble(sdkCommit, protocolCommit, rosterRevision);
    }

    /** The exact instruction prepended to the generated roster prompt. */
    public String preamble() {
        return preamble;
    }

    static String preamble(String sdkCommit, String protocolCommit, String rosterRevision) {
        return """
            Assess the ACP Java SDK at %s against EVERY requirement in
            the supplied stable ACP v1 roster, derived from %s
            (roster revision %s).
            This is protocol conformance, including behavior and wire semantics.

            Investigate the configured workspace. Read implementation code, tests,
            schema and configuration; run relevant local tests as needed.
            Interpret each clause at its original RFC2119 strength: MAY permits
            omission; SHOULD permits a reasoned, documented exception. Apply only
            the declared role, responsibility and capability conditions. Assess
            SDK behavior separately from behavior an application must supply.

            Answer all supplied IDs exactly once, in roster order, using the
            generated response grammar:
            <ID>: PASS|FAIL|CANNOT_DETERMINE - <reason citing supporting file:line>
            NOT_APPLICABLE is allowed only for IDs with a declared applicability
            condition that demonstrably does not hold; explain why it does not.

            Insufficient evidence means CANNOT_DETERMINE, not PASS or a guessed FAIL.
            Do not invent requirements, evidence, citations or test outcomes.
            An existing passing test supports only the behavior it actually checks.
            Do not modify the implementation or fixtures or invoke other live models.
            Do not give an overall verdict: the jury computes it from your answers.

            """.formatted(sdkCommit, protocolCommit, rosterRevision);
    }

    private EvalModelRequest withContext(EvalModelRequest request) {
        if (request.messages().size() != 1 || request.messages().getFirst().role() != EvalMessageRole.USER) {
            throw new IllegalArgumentException("The ACP audit expects exactly one USER roster prompt");
        }
        EvalMessage original = request.messages().getFirst();
        return new EvalModelRequest(List.of(new EvalMessage(EvalMessageRole.USER, preamble + original.content())),
                request.options(), request.metadata());
    }

    @Override
    public Set<GeneratedInput> supportedInputs() {
        return delegate.supportedInputs();
    }

    @Override
    public void validateRequest(EvalModelRequest request) {
        delegate.validateRequest(withContext(request));
    }

    @Override
    public EvalModelResponse generate(EvalModelRequest request) {
        return delegate.generate(withContext(request));
    }

    @Override
    public NativeExecution<EvalModelResponse> execute(EvalModelRequest request) {
        return delegate.execute(withContext(request));
    }
}
