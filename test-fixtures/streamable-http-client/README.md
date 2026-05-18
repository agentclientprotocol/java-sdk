# Streamable HTTP Fixture Client

This in-repo TypeScript fixture drives the Java Streamable HTTP agent transport
through raw HTTP and SSE exchanges. It is intentionally small, strict, and scenario
driven so the wire contract stays visible while the Java server implementation evolves.

Scenarios:

- `happy-path`
- `permission-round-trip`
- `session-load`
- `two-sessions`
- `wrong-stream-response`
- `validation-failures`

Build once:

```bash
npm install
npm run build
```

Run against a local Java listener:

```bash
node dist/client.js --endpoint http://127.0.0.1:8080/acp --scenario happy-path
```
