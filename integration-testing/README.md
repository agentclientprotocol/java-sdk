# Cross-SDK and load scenarios

Interop tests of the Streamable HTTP transport against the TypeScript, Rust and Python ACP SDKs,
plus load runs of the Java listener. Each scenario starts real processes in several languages,
checks what they print, and tears them all down.

This directory is **not** a Maven module and the root `pom.xml` does not reference it, so
`./mvnw verify` and the release build never run it. It runs locally with the scripts below, and in
CI through `.github/workflows/cross-sdk.yml` (manual dispatch plus nightly; never on push or pull
request).

## Prerequisites

| Tool | Version | Used for |
|---|---|---|
| JDK (with `jcmd`) | 17+ | the SDK, the Java programs, the runner, heap and thread samples |
| [JBang](https://www.jbang.dev/download/) | 0.135+ | `RunScenario.java` |
| Node.js and npm | 20 | the TypeScript SDK and its programs |
| Rust (`cargo`) | stable | the Rust SDK and its programs |
| Python | 3.12 (`python3 -m venv`) | the Python SDK (plus Hypercorn) and its programs |
| git | any | cloning the peer SDKs |

The first run clones the three peer SDKs from GitHub and builds them (npm, cargo, a venv), which
takes a few minutes. Later runs reuse the checkouts in `.cache/` and rebuild a peer only when its
ref resolves to a new commit.

## Running

Every scenario tests **this working tree**: the SDK is installed from the checkout
(`./mvnw -DskipTests install`) before the Java programs are built against it.

One scenario (from `integration-testing/`):

```bash
cd integration-testing
jbang RunScenario.java interop-ts-server            # installs the SDK first
jbang RunScenario.java load-300 --skip-sdk-install  # reuse the SDK already installed
jbang RunScenario.java --list
```

All scenarios, with a pass/fail table at the end (exits non-zero on any unexpected failure):

```bash
integration-testing/scripts/run-all.sh
integration-testing/scripts/run-all.sh --only interop-java-java,load-50
```

Against a tag or other ref of the peer SDKs (the default, from `peers.json`, is `main`):

```bash
# every peer at the same ref
integration-testing/scripts/run-all.sh --peers-ref v1.0.0
# one peer at a tag, the others at main
integration-testing/scripts/run-all.sh --peer typescript-sdk=v0.5.0
jbang RunScenario.java interop-python-server --peer python-sdk=1.0.0rc2
```

A ref can be a branch, a tag or a commit SHA. Each ref gets its own checkout,
`.cache/peers/<name>@<ref>`, so switching refs does not throw away a build.

Logs for each run go to `logs/<scenario>/`: one file per process (`server.log`, `client.log`,
`clients.log`), per build (`build-*.log`, `peer-*.log`) and `result.txt`. `run-all.sh` also writes
`logs/run-all/summary.txt`. CI uploads the whole `logs/` directory as an artifact.

## Scenarios

| Scenario | What runs |
|---|---|
| `interop-java-java` | Java client -> Java server; HTTP/2 (h2c) on every request |
| `interop-ts-server` | Java client -> TypeScript server, wired as the SDK's example ships it (HTTP handler plus WebSocket upgrade listener); HTTP/1.1 |
| `interop-rust-server` | Java client -> Rust server (axum); HTTP/1.1 |
| `interop-python-server` | Java client -> Python server (Hypercorn, h2c); HTTP/2; one **expected failure**, below |
| `interop-ts-client` | TypeScript client -> Java server; HTTP/1.1 |
| `interop-rust-client` | Rust client -> Java server; HTTP/1.1 |
| `interop-python-client` | Python client -> Java server; HTTP/1.1 |
| `load-50`, `load-300`, `load-1000` | N Java clients on their own connections, each: initialize, session/new, then 10 (or 5) prompts streaming two updates each |
| `load-shared-300` | 300 clients sharing one HttpClient, so one HTTP/2 connection |

The interop steps are the same in every cell: `initialize`, `session/new`, two prompts that each
stream two updates, a prompt that triggers a permission round trip, `session/load` on the same
connection, a prompt after the load, and the DELETE on close (checked in the server's request log).
The Java client then reconnects on a new connection, loads the same session and prompts again.

**Expected failure.** Java client -> Python server: reconnect-then-load gets a 404. The RFD
contradicts itself: its reconnect diagram opens the session stream before `session/load`, and it
also says an `Acp-Session-Id` the connection does not know is a 404. The Python server follows the
second rule; the TypeScript, Rust and Java servers accept the pre-open. The scenario declares the
two affected steps under `expectedFailures`. The suite stays green and shows them as XFAIL. If the
Python server starts accepting the pre-open, the steps pass, the scenario **fails**, and the
expectation has to be updated.

**Load thresholds** (first baselines, from the 2026-09-25 pre-release run): `errors == 0`, every
prompt answered (`ok == expected`), every update delivered (`updates == 2*ok`), at most 4 SDK
threads (`acp-*`) on the server at peak and after close, server heap after a full GC at most
256 MB at peak and 64 MB after close. p50/p99 latency is recorded in the results and never
asserted.

## How a scenario is described

`configs/<scenario>.json`:

```jsonc
{
  "name": "interop-ts-server",
  "timeoutSec": 240,
  "peers": ["typescript-sdk"],            // prepared from peers.json before anything is built
  "processes": [
    { "name": "server", "role": "server", "language": "node",
      "dir": "${ROOT}/programs/node", "build": null, "run": "node ts-server.mjs",
      "env": { "TS_SDK": "${peer.typescript-sdk}", "PORT": "${PORT}" },
      "ready": { "line": "READY", "timeoutSec": 60 } },
    { "name": "client", "role": "client", "language": "java",
      "dir": "${ROOT}/programs/java", "build": "${MVNW} -q -B ... compile ...",
      "run": "java -cp ... interop.JavaClientMain http://127.0.0.1:${PORT}/acp", "timeoutSec": 120 }
  ],
  "assertions": {
    "requiredOutput":  { "client": ["STEP initialize PASS", "negotiated HTTP_1_1"] },
    "forbiddenOutput": { "client": ["negotiated HTTP_2"] },
    "requiredPatterns": { "server": ["\\[http\\] DELETE /acp HTTP/1\\.1 .*-> 202"] },
    "checks": ["client RESULT fail == 0", "client RESULT prompt1_updates >= 2"],
    "expectedFailures": [ { "process": "client", "step": "...", "contains": "404", "reason": "...", "see": "..." } ],
    "report": { "clients": ["GOAWAY received"] }
  }
}
```

- **Order and lifetime.** Every `build` runs first (identical builds run once). Servers start in
  order, and each must print its `ready` line in time. Clients then run in order under their
  `timeoutSec` and must exit with `exitCode` (default 0). Every process tree is killed in a
  `finally`, including on Ctrl-C.
- **Variables.** `${PORT}` (a free port, one per scenario), `${ROOT}` (this directory), `${REPO}`,
  `${MVNW}`, `${ACP_VERSION}` (from the root pom), `${CACHE}`, `${LOG_DIR}`, and for each peer
  `${peer.<name>}` (checkout path), `${peerRef.<name>}` and `${peerKey.<name>}`. An unknown
  variable is an error.
- **Samples.** A server can list `samples`, which are jcmd samples taken `on` `ready`, on a `line`
  another process prints, or on another process's `exit` (after `delayMs`). Each sample is a full
  GC, then heap used, total threads and SDK threads. It is recorded as
  `SAMPLE <label> threads=.. acp_threads=.. heap_mb=.. rss_mb=..` in that server's output.
- **Checks.** A check reads `<process> RESULT <lhs> <op> <rhs>` or
  `<process> SAMPLE <label> <lhs> <op> <rhs>`. A term is a number, a key, or `<number>*<key>`,
  and the operators are `== != <= >= < >`. Values come from the `key=value` pairs on the process's
  `RESULT` lines (all of them merged) or its last `SAMPLE <label>` line.
- **Steps.** Every `STEP <name> FAIL` line fails the scenario unless it is declared in
  `expectedFailures`. A declared failure that passes also fails the scenario.
- **Report.** `report` counts lines containing a substring and shows the count in the results
  without asserting on it. It is for known, timing-dependent behaviour: in `load-shared-300`,
  Jetty's HTTP/2 rate control can send GOAWAY when all 300 clients close at once.

The programs live in `programs/<language>/`. Each prints `READY <port>` when it is listening (the
servers), `STEP <name> PASS|FAIL` per step and a final `RESULT key=value ...` line (the clients),
and one `[http]` line per request with the negotiated HTTP version (the servers).

## Layout

```
RunScenario.java          JBang entry point
jbang-lib/                config model, peer checkouts, processes, jcmd sampler, assertions
peers.json                peer SDK git URLs, default refs and build commands
configs/                  one JSON file per scenario
programs/java|node|rust|python   the interop and load programs
scripts/run-all.sh        every scenario plus a summary table
.cache/, logs/            generated, git-ignored
```
