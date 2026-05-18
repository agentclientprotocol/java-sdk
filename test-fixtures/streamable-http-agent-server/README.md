# Streamable HTTP Agent Demo Server

This is a small runnable Java ACP agent that serves the new Streamable HTTP
agent transport from a real Jetty web server.

Build the runnable jar:

```bash
./mvnw -q -pl test-fixtures/streamable-http-agent-server -am -DskipTests package
```

Run it:

```bash
java -jar test-fixtures/streamable-http-agent-server/target/acp-streamable-http-agent-server.jar --port 8080
```

Then drive it with the fixture client from another shell:

```bash
cd test-fixtures/streamable-http-client
npm install
npm run build
node dist/client.js --endpoint http://127.0.0.1:8080/acp --scenario happy-path
```

The demo supports `initialize`, `session/new`, `session/load`, `session/prompt`,
and `session/cancel`. Prompts containing the word `permission` also exercise the
agent-to-client `session/request_permission` round trip.
