package com.agentclientprotocol.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.markpollack.judge.ai.requirements.Rfc2119Requirement;
import io.github.markpollack.judge.ai.requirements.Rfc2119Specification;
import io.github.markpollack.judge.provenance.ArtifactRef;
import io.github.markpollack.judge.requirement.RequirementSource;

/**
 * The derived ACP v1 testing requirements, loaded from {@code spec/requirements.json} and bound to
 * the exact retained official bytes under {@code spec/official/}.
 *
 * <p>Loading verifies, before anything can execute, that the roster file matches its generated
 * digest sidecar and that every requirement's source file still hashes to the digest the roster
 * recorded. A mismatch is a {@link RosterIntegrityException}: a judge over a drifted roster would
 * produce a result that cites bytes nobody can open.
 *
 * <p>Every requirement is constructed natively with its complete clause text; nothing is
 * abbreviated, and the Agent Eval document parser (which expects the PetClinic {@code ### RULE-n}
 * convention) is not used.
 */
public final class AcpRequirementRoster {

    private static final Set<String> NATIVE_KEYWORDS = Set.of("MUST", "MUST NOT", "SHOULD", "SHOULD NOT", "MAY");

    private final String revision;
    private final String protocolCommit;
    private final String rosterSha256;
    private final List<Rfc2119Requirement> requirements;
    private final Map<String, JsonNode> records;

    private AcpRequirementRoster(String revision, String protocolCommit, String rosterSha256,
            List<Rfc2119Requirement> requirements, Map<String, JsonNode> records) {
        this.revision = revision;
        this.protocolCommit = protocolCommit;
        this.rosterSha256 = rosterSha256;
        this.requirements = List.copyOf(requirements);
        this.records = Map.copyOf(records);
    }

    /** Roster revision, e.g. {@code acp-v1-2797d331-r1}. */
    public String revision() {
        return revision;
    }

    /** Pinned protocol commit the roster was derived from. */
    public String protocolCommit() {
        return protocolCommit;
    }

    /** SHA-256 of the exact {@code requirements.json} bytes that were loaded. */
    public String rosterSha256() {
        return rosterSha256;
    }

    /** The complete roster, in roster order. */
    public List<Rfc2119Requirement> requirements() {
        return requirements;
    }

    /** The raw generated record for one requirement (role, responsibility, kind, pointers ...). */
    public JsonNode record(String id) {
        JsonNode node = records.get(id);
        if (node == null) {
            throw new IllegalArgumentException("Unknown requirement " + id);
        }
        return node;
    }

    /**
     * Load and verify the roster under {@code specDir} (the {@code protocol-conformance/spec} directory).
     * @throws RosterIntegrityException when any digest does not match
     */
    public static AcpRequirementRoster load(Path specDir) {
        Path rosterFile = specDir.resolve("requirements.json");
        byte[] rosterBytes = read(rosterFile);
        String rosterDigest = sha256(rosterBytes);
        String recorded = read(specDir.resolve("requirements.sha256"), rosterFile).split("\\s+")[0];
        if (!rosterDigest.equals(recorded)) {
            throw new RosterIntegrityException("requirements.json sha256 " + rosterDigest
                    + " does not match requirements.sha256 " + recorded + "; regenerate with scripts/import-spec.py");
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(rosterBytes);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Unreadable roster " + rosterFile, e);
        }
        String revision = text(root, "revision");
        String protocolCommit = text(root.path("protocol"), "commit");
        JsonNode items = root.path("requirements");
        if (!items.isArray() || items.isEmpty()) {
            throw new RosterIntegrityException("requirements.json carries no requirements");
        }
        if (root.path("count").asInt(-1) != items.size()) {
            throw new RosterIntegrityException("requirements.json count " + root.path("count") + " != " + items.size());
        }
        Map<String, String> digests = new HashMap<>();
        Set<String> ids = new HashSet<>();
        List<Rfc2119Requirement> out = new ArrayList<>();
        Map<String, JsonNode> records = new HashMap<>();
        for (JsonNode item : items) {
            String id = text(item, "id");
            if (!ids.add(id)) {
                throw new RosterIntegrityException("Duplicate requirement id " + id);
            }
            String keyword = text(item, "keyword");
            if (!NATIVE_KEYWORDS.contains(keyword)) {
                throw new RosterIntegrityException(id + ": keyword " + keyword + " is not a native RFC 2119 keyword");
            }
            JsonNode source = item.path("source");
            String path = text(source, "path");
            String selector = text(source, "selector");
            String expected = text(source, "sha256");
            String repository = text(source, "repository");
            Path retained = retainedPath(specDir, repository, path);
            String actual = digests.computeIfAbsent(retained.toString(), p -> sha256(read(retained)));
            if (!actual.equals(expected)) {
                throw new RosterIntegrityException(id + ": retained source " + retained + " hashes to " + actual
                        + " but the roster recorded " + expected);
            }
            String artifactId = source.path("commit").isNull() || source.path("commit").isMissingNode()
                    ? repository + ":" + path
                    : repository + "@" + text(source, "commit") + ":" + path;
            String applicability = item.path("applicability").isNull() ? null : item.path("applicability").asText();
            String clause = text(item, "text");
            Rfc2119Specification specification = new Rfc2119Specification(keyword, clause, text(item, "reason"),
                    applicability);
            RequirementSource origin = new RequirementSource(new ArtifactRef(artifactId, expected, selector), null);
            out.add(new Rfc2119Requirement(id, text(item, "revision"), clause, specification, origin));
            records.put(id, item);
        }
        return new AcpRequirementRoster(revision, protocolCommit, rosterDigest, out, records);
    }

    private static Path retainedPath(Path specDir, String repository, String path) {
        if (repository.startsWith("http")) {
            return specDir.resolve("official").resolve(path); // jsonrpc/specification.html
        }
        return specDir.resolve("official").resolve("agent-client-protocol").resolve(path);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            throw new RosterIntegrityException("requirements.json: missing or blank field '" + field + "'");
        }
        return value.asText();
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        }
        catch (IOException e) {
            throw new RosterIntegrityException("Cannot read " + path + ": " + e.getMessage());
        }
    }

    private static String read(Path sidecar, Path roster) {
        try {
            return Files.readString(sidecar, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new RosterIntegrityException("No digest sidecar " + sidecar + " for " + roster + ": " + e.getMessage());
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public String toString() {
        return "AcpRequirementRoster[" + revision + ", " + requirements.size() + " requirements, sha256 "
                + rosterSha256.substring(0, 12) + ", protocol " + protocolCommit.substring(0, 12) + "]";
    }

    static {
        Objects.requireNonNull(NATIVE_KEYWORDS);
    }
}
