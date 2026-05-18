# Streamable HTTP Fixture Server

This in-repo TypeScript fixture is the conformance harness for the Java Streamable HTTP client transport.

Current scope:

- strict fixture behavior
- HTTP-only
- named startup-selected scenarios
- canonical transcript serialization owned by the fixture

Current scenarios:

- `happy-path`
- `permission-round-trip`
- `session-load`
- `two-sessions`
- `wrong-stream-response`
- `validation-failures`

Fixture-wide validation already covers cookies and transport headers. Strict client-side
routing rejection is tested in the Java unit tests because it should fail before the
fixture ever sees a request.

Manual use:

```bash
npm install
npm run build
node dist/server.js --scenario happy-path --port 8080
```

The server prints a single JSON `ready` line on startup. Golden transcripts live in
`golden/` and are compared by the Java integration tests.

The harness is intentionally small and local to this repository for now. It may later become a reusable ACP conformance fixture once the remote transport ecosystem settles.
