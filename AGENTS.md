# ACP Java SDK Agent Instructions

This public repository owns code, tests, Maven builds, releases, shipped contracts, and public
documentation. Private planning and control state are authoritative in
`/home/mark/projects/acp-java-steward`; read its `BINDING.md` before planning or executing work.

This repository belongs to the `agentclientprotocol` organization and is maintained, not owned,
from that steward. Repository settings, secrets, branch protection and organization membership are
the organization's; do not plan work that assumes them.

Use `./mvnw`, never `mvn`. The normal gate is `./mvnw clean verify`, which runs the unit tests and
the `*IT` integration classes through Failsafe. Before 0.15.0 the integration tests never ran, so
"tests pass" on older history did not include them.

Releases happen only by dispatching the organization's `release.yml` workflow; a local
`-Prelease deploy` is not a path, because the local publishing token is not entitled to the
`com.agentclientprotocol` namespace. The workflow's release commit bumps every reactor `pom.xml` and
tags; it does not touch `CHANGELOG.md`. Keep the changelog current by hand, in the same change
that earns the entry.

The wire format is the contract. Polymorphic types write their discriminator exactly once
(`Access.WRITE_ONLY` on the record component, `visible = true` on the type info); a duplicate JSON
key passes Jackson-based peers and breaks strict ones. Unstable protocol methods carry
`@UnstableAcpApi`; a method without it is a stable promise.

Client-session notifications are delivered in order through a single sink drained by `concatMap`,
and `closeGracefully()` waits for that drain, bounded by the request timeout, while `close()`
interrupts. Sync clients always have async handlers, because every sync consumer is wrapped in
`subscribeOn`. Keep both properties when touching `AcpClientSession` or a transport, and add the
test that would have caught their loss.

Fixtures and reproductions come from the protocol's own schema or a real peer, not from memory of
what the schema said. The canonical spec is `agentclientprotocol/agent-client-protocol`; check the
released schema version before adding or changing a method.

The project is Apache-2.0 with the verbatim license text; `LICENSE` and `NOTICE` ship in every
artifact. Commit messages contain no AI attribution.

Do not copy private planning, current-action, roadmap, checkpoint, or dirty-tree state into public
files. The ignored `plans/` tree in a checkout is transition material, not public authority.
