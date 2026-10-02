# Expected failures

Known failures of generated cells (`configs/x-*.json`, `configs/self-*.json`) are declared here,
never in the configs. `GenConfigs.java` copies every matching entry into the cell's
`assertions.expectedFailures`, so the runner's existing rules apply unchanged:

- a `STEP <id> FAIL` that is not declared fails the scenario;
- a declared failure must print `STEP <id> FAIL` and its line must contain `contains`;
- **a declared failure that passes fails the scenario.** When the gap closes (a Phase B item lands,
  a peer fixes a bug), the same change must delete the entry.

## Files

One file per language package, owned by that package: `java.json`, `typescript.json`, `rust.json`,
`python.json`, `kotlin.json`, `raw.json`. A file declares the failures caused by its own SDK or
program. `java.json` holds the Java gaps (`see: "P<n>"`); a peer file holds that peer's bugs
(`see: "peer:..."`) and the spec disagreements its pairs show (`see: "spec:..."`). `raw.json`'s
`expectations` array stays empty (the raw driver is no matrix language); its `conformance` array
is read only by `programs/raw/gen_conf.py`, for the `conf-java-*` scenarios.

```json
{
  "comment": "optional",
  "expectations": [
    { "step": "update.tool_call-name", "when": { "client": "java" }, "contains": "",
      "see": "P3", "reason": "Java ToolCall has no name (AcpSchema:1555)" },
    { "step": "agent.perm.cancelled", "when": { "agent": "python", "transport": ["http", "ws"] },
      "contains": "outcome", "see": "peer:python-sdk connection.py:239", "reason": "..." }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `step` | yes | A step id from `steps.json`, or `agent.<id>` for the agent-side assertion of a step that has one (`pass.agent`). |
| `when` | no | The cells it applies to. Keys `client`, `agent` (language ids from `matrix.json`), `transport` (`stdio`, `http`, `ws`), `profile` (`stable`, `unstable`). Each value is a string or an array; omitted keys match anything. |
| `contains` | yes | A substring the `STEP ... FAIL` line must contain; `""` accepts any failure. Prefer something specific (`-32601`, `404`, `TIMEOUT`, `unknown-step`). |
| `see` | yes | Where the fix lives: a Phase B item (`P1` ... `P9`, `P1b`), `peer:<sdk> <file:line>` (e.g. `peer:python-sdk http/server.py:260`), or `spec:<where>` for a spec contradiction. |
| `reason` | yes | One line: what fails and why. |

The generator rejects unknown fields, unknown step ids, unknown `when` values, a malformed `see`,
and an entry that matches no cell of the full matrix.

**When several entries match the same step in a cell**, the most specific wins: more `when` keys
first, then the entry from the client's language file, then the agent's, then the earlier entry.
An agent-side expectation (`agent.<id>`) also removes that step's required
`STEP agent.<id> PASS` line; a client-side expectation removes it too, since a failed client step
usually means the agent never got that far.

In a cell, a client-side entry is checked on the `client` process; an `agent.<id>` entry on the
`agent` process (HTTP and WebSocket) or on `client` (stdio, where the client relays the agent's
stderr).

## Phase B

Each Phase B commit deletes its `see: "Pn"` entries, regenerates, and shows `run-all.sh` green: the
gate for P3 is "`run-all.sh` green with zero `P3` entries left". The steps each item flips are listed
in `steps.json` (`phaseB`).
