package com.agentclientprotocol.conformance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.markpollack.judge.ai.requirements.Rfc2119Requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The real roster loads completely, natively and bound to hash-verified official bytes. */
class AcpRequirementRosterTest {

    static final Path SPEC = Path.of("spec");

    @Test
    void loadsTheCompleteDerivedRosterWithExactSourceAssociations() {
        AcpRequirementRoster roster = AcpRequirementRoster.load(SPEC);
        assertThat(roster.revision()).isEqualTo("acp-v1-2797d331-r1");
        assertThat(roster.protocolCommit()).isEqualTo("2797d33125c14e5bcfc815952930130c43e5bc4c");
        assertThat(roster.requirements()).hasSize(240);
        Set<String> ids = new HashSet<>();
        for (Rfc2119Requirement requirement : roster.requirements()) {
            assertThat(ids.add(requirement.id())).as("unique id %s", requirement.id()).isTrue();
            assertThat(requirement.id()).doesNotContain(":").hasSizeLessThanOrEqualTo(64);
            assertThat(requirement.revision()).startsWith("acp-v1-2797d331-r1.");
            assertThat(requirement.specification().keyword()).isIn("MUST", "MUST NOT", "SHOULD", "SHOULD NOT", "MAY");
            // the native requirement text is the complete clause, never a title
            assertThat(requirement.text()).isEqualTo(requirement.specification().requirement());
            assertThat(requirement.source().artifact().sha256()).hasSize(64);
            assertThat(requirement.source().artifact().selector()).isNotBlank();
            assertThat(requirement.source().nativeId()).isNull();
        }
        // one multi-line clause survives complete (elicitation.mdx:17-18 wraps across lines)
        Rfc2119Requirement wrapped = byId(roster, "ACP-V1-ELIC-CLIENT-IDENTIFY-AGENT");
        assertThat(wrapped.text()).startsWith("Clients MUST clearly identify the Agent requesting information")
                .endsWith("provide clear decline and cancel controls.");
        assertThat(wrapped.source().artifact().selector()).isEqualTo("L17-L18");
        assertThat(wrapped.source().artifact().id())
                .isEqualTo("agentclientprotocol/agent-client-protocol@2797d33125c14e5bcfc815952930130c43e5bc4c:"
                        + "docs/protocol/v1/elicitation.mdx");
        // conditional and optional clauses carry their declared semantics
        assertThat(byId(roster, "ACP-V1-SETUP-LOAD-REPLAY").specification().applicability()).contains("loadSession");
        assertThat(byId(roster, "ACP-V1-OVERVIEW-ABSOLUTE-PATHS").specification().applicability()).isNull();
        assertThat(byId(roster, "ACP-V1-INIT-CAPABILITIES-OPTIONAL").specification().keyword()).isEqualTo("MAY");
        assertThat(roster.record("ACP-V1-INIT-CAPABILITIES-OPTIONAL").path("original_keyword").asText())
                .isEqualTo("OPTIONAL");
        // JSON-RPC incorporation and schema derivations are present and labelled
        assertThat(byId(roster, "ACP-V1-JSONRPC-RESULT-ON-SUCCESS").specification().keyword()).isEqualTo("MUST");
        assertThat(roster.record("ACP-V1-JSONRPC-RESULT-ON-SUCCESS").path("original_keyword").asText())
                .isEqualTo("REQUIRED");
        assertThat(roster.record("ACP-V1-SCHEMA-PROMPT-SHAPE").path("kind").asText()).isEqualTo("derived");
        assertThat(roster.record("ACP-V1-SCHEMA-PROMPT-SHAPE").path("pointers")).hasSize(2);
        long conditional = roster.requirements().stream().filter(r -> r.specification().applicability() != null).count();
        assertThat(conditional).isEqualTo(116);
    }

    @Test
    void refusesARosterWhoseRetainedSourceBytesChanged(@TempDir Path temp) throws IOException {
        Path copy = copySpec(temp);
        Path page = copy.resolve("official/agent-client-protocol/docs/protocol/v1/overview.mdx");
        Files.writeString(page, Files.readString(page).replace("MUST", "SHOULD"), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> AcpRequirementRoster.load(copy))
                .isInstanceOf(RosterIntegrityException.class)
                .hasMessageContaining("overview.mdx")
                .hasMessageContaining("hashes to");
    }

    @Test
    void refusesARosterFileWhoseDigestDoesNotMatchItsSidecar(@TempDir Path temp) throws IOException {
        Path copy = copySpec(temp);
        Path roster = copy.resolve("requirements.json");
        Files.writeString(roster, Files.readString(roster).replace("\"count\": 240", "\"count\": 239"));
        assertThatThrownBy(() -> AcpRequirementRoster.load(copy))
                .isInstanceOf(RosterIntegrityException.class)
                .hasMessageContaining("does not match requirements.sha256");
    }

    static Rfc2119Requirement byId(AcpRequirementRoster roster, String id) {
        return roster.requirements().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    static Path copySpec(Path temp) throws IOException {
        Path copy = temp.resolve("spec");
        try (Stream<Path> files = Files.walk(SPEC)) {
            for (Path source : files.toList()) {
                Path target = copy.resolve(SPEC.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                }
                else {
                    Files.copy(source, target);
                }
            }
        }
        return copy;
    }
}
