"""The Python interop agent of the step catalogue (integration-testing/steps.json).

    agent.sh --transport stdio
    agent.sh --transport http|ws --port <port>

Driven entirely by the prompt text (the catalogue's "directives"); it keeps no script. On stdio,
stdout is the protocol stream: every log line and every STEP agent.<id> line goes to stderr, and
the agent exits when stdin reaches EOF. On http/ws it serves the Python SDK's ASGI app (Streamable
HTTP and WebSocket on the same /acp endpoint) with Hypercorn, prints READY <port> on stdout once
listening and logs one [http] line per request. Contract: integration-testing/README.md.

stdio runs with use_unstable_protocol=True, so session/resume, session/close and session/fork are
routed. The SDK's HTTP and WebSocket servers build their AgentSideConnection without that flag
(acp/http/server.py:270, acp/ws/server.py:55), so there those methods answer -32601 even though
this agent implements them (declared in expectations/python.json).
"""

from __future__ import annotations

import asyncio
import os
import socket
import sys
import time
import uuid
from typing import Any

from acp import RequestError, run_agent
from acp.schema import ElicitationFormSessionMode, ElicitationUrlSessionMode
from fixtures import (
    AGENT_CAPABILITIES,
    AGENT_INFO,
    AUTH_METHOD,
    ELICIT_FORM_MESSAGE,
    ELICIT_FORM_SCHEMA,
    ELICIT_URL,
    EMIT,
    EXT_PARAMS,
    EXT_RESULT,
    FS_READ_CONTENT,
    META_KEY,
    META_VALUE,
    MODE_IDS,
    MODES,
    PERMISSION_OPTIONS,
    PERMISSION_TOOL_CALL,
    TERMINAL_AUTH_METHOD,
    UNKNOWN_UPDATE,
    compact,
    config_options,
    dump,
    log,
    step_line,
)


def agent_step(step_id: str, ok: bool, started: float, detail: str) -> None:
    step_line(sys.stderr, "agent." + step_id, ok, started, detail)


class Turn:
    """One running session/prompt: how it was asked to stop, if it was."""

    def __init__(self) -> None:
        self.stop = asyncio.Event()
        self.reason: str | None = None

    def cancel(self, reason: str) -> None:
        if self.reason is None:
            self.reason = reason
        self.stop.set()


class Session:
    def __init__(self, session_id: str, cwd: str) -> None:
        self.id = session_id
        self.cwd = cwd
        self.history: list[tuple[str, list[str]]] = []
        self.mode = MODES["currentModeId"]
        self.model = "model-a"
        self.verbose = False
        self.closed = False
        self.turns: set[Turn] = set()


# Sessions are process-wide: a session created on one HTTP connection can be loaded on another.
SESSIONS: dict[str, Session] = {}


def _path(caps: dict, *keys: str) -> Any:
    v: Any = caps
    for k in keys:
        if not isinstance(v, dict):
            return None
        v = v.get(k)
    return v


class InteropAgent:
    def __init__(self, conn: Any) -> None:
        self.conn = conn
        self.client_caps: dict | None = None
        self.last_ext_notification: str | None = None

    # ------------------------------------------------------------ lifecycle

    def _with_boolean(self) -> bool:
        return _path(self.client_caps or {}, "session", "configOptions", "boolean") is not None

    def _session(self, session_id: str) -> Session:
        s = SESSIONS.get(session_id)
        if s is None:
            raise RequestError.invalid_params({"details": f"unknown session {session_id}"})
        if s.closed:
            raise RequestError.invalid_params({"details": f"session {session_id} is closed"})
        return s

    def _session_state(self, s: Session) -> dict:
        state = dict(MODES)
        state["currentModeId"] = s.mode
        return {"modes": state, "configOptions": config_options(s.model, s.verbose, self._with_boolean())}

    async def initialize(self, protocol_version: int, client_capabilities=None, client_info=None, **kw):
        if client_capabilities is not None:
            self.client_caps = client_capabilities.model_dump(mode="json", by_alias=True, exclude_unset=True)
        log(f"[agent] initialize protocolVersion={protocol_version} clientCapabilities={compact(self.client_caps)}")
        methods = [AUTH_METHOD]
        if _path(self.client_caps or {}, "auth", "terminal") is True:
            methods.append(TERMINAL_AUTH_METHOD)
        return {
            "protocolVersion": 1,
            "agentCapabilities": AGENT_CAPABILITIES,
            "authMethods": methods,
            "agentInfo": AGENT_INFO,
        }

    async def authenticate(self, method_id: str, **kw):
        agent_step("auth.authenticate", method_id == "interop-auth", time.monotonic(), f"methodId={method_id}")
        return {}

    async def logout(self, **kw):
        return {}

    async def new_session(self, cwd: str, additional_directories=None, mcp_servers=None, **kw):
        s = Session("py-" + uuid.uuid4().hex[:12], cwd)
        SESSIONS[s.id] = s
        log(f"[agent] session/new {s.id} cwd={cwd}")
        return {"sessionId": s.id, **self._session_state(s)}

    async def load_session(self, cwd: str, session_id: str, mcp_servers=None, additional_directories=None, **kw):
        s = self._session(session_id)
        log(f"[agent] session/load {session_id} replaying {len(s.history)} turn(s)")
        for text, chunks in list(s.history):
            await self._update(s.id, {"sessionUpdate": "user_message_chunk", "content": _text(text)})
            for c in chunks:
                await self._chunk(s.id, c)
        return self._session_state(s)

    async def resume_session(self, session_id: str, cwd: str, additional_directories=None, mcp_servers=None, **kw):
        s = self._session(session_id)
        log(f"[agent] session/resume {session_id}")
        return self._session_state(s)

    async def fork_session(self, session_id: str, cwd: str, additional_directories=None, mcp_servers=None, **kw):
        src = self._session(session_id)
        s = Session("py-" + uuid.uuid4().hex[:12], cwd)
        s.history = list(src.history)
        SESSIONS[s.id] = s
        log(f"[agent] session/fork {session_id} -> {s.id}")
        return {"sessionId": s.id, **self._session_state(s)}

    async def list_sessions(self, cwd: str | None = None, cursor: str | None = None, **kw):
        found = [s for s in SESSIONS.values() if cwd is None or s.cwd == cwd]
        return {"sessions": [{"sessionId": s.id, "cwd": s.cwd} for s in found]}

    async def close_session(self, session_id: str, **kw):
        started = time.monotonic()
        s = SESSIONS.get(session_id)
        if s is None:
            agent_step("session.close", False, started, f"close of unknown session {session_id}")
            raise RequestError.invalid_params({"details": f"unknown session {session_id}"})
        running = list(s.turns)
        s.closed = True
        for t in running:
            t.cancel("close")
        agent_step("session.close", bool(running), started,
                   f"closed {session_id}; cancelled {len(running)} running turn(s)")
        return {}

    async def delete_session(self, session_id: str, **kw):
        s = SESSIONS.pop(session_id, None)
        if s is not None:
            s.closed = True
            for t in list(s.turns):
                t.cancel("delete")
        return {}

    async def set_session_mode(self, session_id: str, mode_id: str, **kw):
        started = time.monotonic()
        s = SESSIONS.get(session_id)
        ok = s is not None and mode_id in MODE_IDS
        agent_step("mode.set", ok and mode_id == "interop-mode-b", started, f"session={session_id} modeId={mode_id}")
        if not ok:
            raise RequestError.invalid_params({"details": f"unknown session or mode: {session_id} {mode_id}"})
        s.mode = mode_id
        return {}

    async def set_config_option(self, config_id: str, session_id: str, value, **kw):
        s = self._session(session_id)
        if config_id == "model" and value in ("model-a", "model-b"):
            s.model = value
        elif config_id == "verbose" and isinstance(value, bool) and self._with_boolean():
            s.verbose = value
        else:
            raise RequestError.invalid_params({"details": f"unknown config option {config_id}={value!r}"})
        return {"configOptions": config_options(s.model, s.verbose, self._with_boolean())}

    async def cancel(self, session_id: str, **kw):
        s = SESSIONS.get(session_id)
        log(f"[agent] session/cancel {session_id} running={len(s.turns) if s else 0}")
        if s is not None:
            for t in list(s.turns):
                t.cancel("cancel")

    async def ext_method(self, method: str, params: dict) -> dict:
        if "_" + method == "_interop/ping":
            return EXT_RESULT
        raise RequestError.method_not_found("_" + method)

    async def ext_notification(self, method: str, params: dict) -> None:
        self.last_ext_notification = "_" + method
        log(f"[agent] extension notification _{method} {compact(params)}")

    # ------------------------------------------------------------ prompt turn

    async def _update(self, session_id: str, update: dict) -> None:
        await self.conn.session_update(session_id=session_id, update=update)

    async def _chunk(self, session_id: str, text: str, meta: dict | None = None) -> None:
        update = {"sessionUpdate": "agent_message_chunk", "content": _text(text)}
        if meta:
            update["_meta"] = meta
        await self._update(session_id, update)

    async def prompt(self, session_id: str, prompt: list, **kw):
        s = self._session(session_id)
        text = next((b.text for b in prompt if getattr(b, "type", None) == "text"), "")
        meta = dict(kw)
        log(f"[agent] session/prompt {session_id} {text[:80]!r}{'...' if len(text) > 80 else ''}")
        turn = Turn()
        s.turns.add(turn)
        try:
            if not text.startswith("#"):
                chunks = ["echo: ", text]
                for c in chunks:
                    await self._chunk(s.id, c)
                s.history.append((text, chunks))
                return {"stopReason": "end_turn"}
            name, _, rest = text[1:].partition(" ")
            handler = getattr(self, "_d_" + name.replace("-", "_"), None)
            if handler is None:
                raise RequestError(-32602, f"unknown directive: #{name}")
            resp = await handler(s, turn, rest, meta)
            log(f"[agent] session/prompt {session_id} #{name} -> {resp.get('stopReason')}")
            return resp
        finally:
            s.turns.discard(turn)

    # Directives: each returns the PromptResponse.

    async def _d_permission(self, s: Session, turn: Turn, rest: str, meta: dict):
        started = time.monotonic()
        # "allow": perm.selected; "allow meta": meta.permission (_meta on the request); "hold":
        # perm.cancelled.
        mode = rest.strip()
        hold = mode == "hold"
        if mode not in ("allow", "allow meta", "hold"):
            raise RequestError(-32602, f"unknown directive: #permission {rest}")
        extra = {META_KEY: META_VALUE} if mode == "allow meta" else {}
        resp = await self.conn.request_permission(
            session_id=s.id, tool_call=PERMISSION_TOOL_CALL, options=PERMISSION_OPTIONS, **extra
        )
        outcome = dump(resp.outcome) or {}
        selected = outcome.get("outcome") == "selected"
        if selected:
            await self._chunk(s.id, f"permission: selected {outcome.get('optionId')}")
        else:
            await self._chunk(s.id, "permission: cancelled")
        if hold:
            agent_step("perm.cancelled", outcome.get("outcome") == "cancelled", started, f"outcome {compact(outcome)}")
            return {"stopReason": "cancelled" if not selected else "end_turn"}
        if mode == "allow meta":
            rmeta = resp.field_meta or {}
            agent_step("meta.permission", rmeta.get(META_KEY) == META_VALUE, started,
                       f"RequestPermissionResponse _meta {compact(rmeta) if rmeta else 'absent'}")
        else:
            agent_step("perm.selected", selected and outcome.get("optionId") == "allow", started,
                       f"outcome {compact(outcome)}")
        return {"stopReason": "end_turn"}

    async def _d_fs(self, s: Session, turn: Turn, rest: str, meta: dict):
        started = time.monotonic()
        op, _, args = rest.partition(" ")
        fs = _path(self.client_caps or {}, "fs") or {}
        if op == "write":
            path, _, content = args.partition(" ")
            if fs.get("writeTextFile") is not True:
                agent_step("fs.write", False, started, "client did not advertise fs.writeTextFile")
                await self._chunk(s.id, "fs write error capability")
                return {"stopReason": "end_turn"}
            try:
                r = await self.conn.write_text_file(session_id=s.id, path=path, content=content)
            except RequestError as e:
                agent_step("fs.write", False, started, f"fs/write_text_file failed: {e.code} {e}")
                await self._chunk(s.id, f"fs write error {e.code}")
                return {"stopReason": "end_turn"}
            agent_step("fs.write", True, started, f"fs/write_text_file answered {compact(dump(r))}")
            await self._chunk(s.id, "fs write ok")
            return {"stopReason": "end_turn"}
        if op in ("read", "read-slow"):
            parts = args.split(" ")
            path = parts[0]
            opts = dict(p.split("=", 1) for p in parts[1:] if "=" in p)
            line = int(opts["line"]) if "line" in opts else None
            limit = int(opts["limit"]) if "limit" in opts else None
            step_id = "cancel-request.agent" if op == "read-slow" else ("fs.read-range" if opts else "fs.read")
            if fs.get("readTextFile") is not True:
                agent_step(step_id, False, started, "client did not advertise fs.readTextFile")
                await self._chunk(s.id, "fs read error capability")
                return {"stopReason": "end_turn"}
            if op == "read-slow":
                return await self._read_slow(s, path, started)
            try:
                r = await self.conn.read_text_file(session_id=s.id, path=path, line=line, limit=limit)
            except RequestError as e:
                agent_step("fs.read-missing", True, started, f"fs/read_text_file failed with {e.code} {e}")
                await self._chunk(s.id, f"fs read error {e.code}")
                return {"stopReason": "end_turn"}
            content = r.content
            if step_id == "fs.read-range":
                agent_step(step_id, content.strip() == "line2", started, f"content {content!r}")
            else:
                agent_step(step_id, content == FS_READ_CONTENT, started, f"content {content!r}")
            await self._chunk(s.id, content)
            return {"stopReason": "end_turn"}
        raise RequestError(-32602, f"unknown directive: #fs {op}")

    async def _read_slow(self, s: Session, path: str, started: float):
        # The Python SDK has no $/cancel_request: only the method-name constant exists
        # (acp/meta.py:49); no send API, and the connection does not route it. So the agent can
        # only abandon its own pending request locally, which puts nothing on the wire.
        read = asyncio.ensure_future(self.conn.read_text_file(session_id=s.id, path=path))
        await asyncio.sleep(0.2)
        read.cancel()
        agent_step("cancel-request.agent", False, started,
                   "cannot send $/cancel_request: python-sdk has no API for it (acp/meta.py:49)")
        await self._chunk(s.id, "cancel-request unsupported")
        return {"stopReason": "end_turn"}

    async def _d_emit(self, s: Session, turn: Turn, rest: str, meta: dict):
        kind, _, args = rest.partition(" ")
        opts = dict(p.split("=", 1) for p in args.split(" ") if "=" in p)
        if kind == "unknown":
            # The typed session_update refuses an unknown variant: send the raw notification.
            await self.conn._conn.send_notification("session/update", {"sessionId": s.id, "update": UNKNOWN_UPDATE})
            await self._chunk(s.id, "after-unknown")
            return {"stopReason": "end_turn"}
        if kind == "config_option_update":
            body = {"configOptions": config_options("model-b", s.verbose, self._with_boolean())}
        elif kind in EMIT:
            body = dict(EMIT[kind])
        else:
            raise RequestError(-32602, f"unknown directive: #emit {kind}")
        if kind == "tool_call_update":
            await self._update(s.id, {"sessionUpdate": "tool_call", **EMIT["tool_call"]})
        if kind == "tool_call" and "name" in opts:
            body["name"] = opts["name"]
        await self._update(s.id, {"sessionUpdate": kind, **body})
        return {"stopReason": "end_turn"}

    async def _d_stop(self, s: Session, turn: Turn, rest: str, meta: dict):
        await self._chunk(s.id, "stop")
        return {"stopReason": rest.strip()}

    async def _d_slow(self, s: Session, turn: Turn, rest: str, meta: dict):
        started = time.monotonic()
        opts = dict(p.split("=", 1) for p in rest.split(" ") if "=" in p)
        grace = int(opts.get("grace", "0")) / 1000.0
        deadline = started + 10.0
        while not turn.stop.is_set() and time.monotonic() < deadline:
            await self._chunk(s.id, "tick")
            try:
                await asyncio.wait_for(turn.stop.wait(), 0.1)
            except asyncio.TimeoutError:
                pass
        if not turn.stop.is_set():
            # Nothing cancelled the turn. A $/cancel_request (cancel-request.client) cannot reach
            # this handler: the Python SDK routes no such method (acp/meta.py:49 is only the name).
            log(f"[agent] #slow on {s.id} ran 10 s without a cancel")
            return {"stopReason": "end_turn"}
        end = time.monotonic() + grace
        while time.monotonic() < end and turn.reason == "cancel":
            await self._chunk(s.id, "tick")
            await asyncio.sleep(0.1)
        if turn.reason == "cancel":
            agent_step("cancel.prompt", True, started, "session/cancel arrived for the running session")
        return {"stopReason": "cancelled"}

    async def _d_hang(self, s: Session, turn: Turn, rest: str, meta: dict):
        await asyncio.Event().wait()

    async def _d_terminal(self, s: Session, turn: Turn, rest: str, meta: dict):
        started = time.monotonic()
        op, _, cmdline = rest.partition(" ")
        argv = cmdline.split(" ")
        step_id = "term.kill" if op == "kill" else "term.run"
        if op not in ("run", "kill"):
            raise RequestError(-32602, f"unknown directive: #terminal {op}")
        if (self.client_caps or {}).get("terminal") is not True:
            agent_step(step_id, False, started, "client did not advertise terminal")
            await self._chunk(s.id, "terminal error capability")
            return {"stopReason": "end_turn"}
        try:
            created = await self.conn.create_terminal(session_id=s.id, command=argv[0], args=argv[1:])
            tid = created.terminal_id
            if op == "run":
                ex = await self.conn.wait_for_terminal_exit(session_id=s.id, terminal_id=tid)
                out = await self.conn.terminal_output(session_id=s.id, terminal_id=tid)
                await self.conn.release_terminal(session_id=s.id, terminal_id=tid)
                output = out.output.strip()
                agent_step(step_id, "hi" in output and ex.exit_code == 0, started,
                           f"output {output!r} exitCode={ex.exit_code}")
                await self._chunk(s.id, f"terminal: {output} exit={ex.exit_code}")
                return {"stopReason": "end_turn"}
            await asyncio.sleep(0.2)
            await self.conn.kill_terminal(session_id=s.id, terminal_id=tid)
            killed = time.monotonic()
            ex = await asyncio.wait_for(self.conn.wait_for_terminal_exit(session_id=s.id, terminal_id=tid), 5)
            waited = time.monotonic() - killed
            await self.conn.release_terminal(session_id=s.id, terminal_id=tid)
            agent_step(step_id, waited <= 5, started, f"wait_for_exit {compact(dump(ex))} {int(waited * 1000)} ms after kill")
            await self._chunk(s.id, "terminal killed")
            return {"stopReason": "end_turn"}
        except (RequestError, asyncio.TimeoutError) as e:
            agent_step(step_id, False, started, f"terminal failed: {type(e).__name__} {e}")
            await self._chunk(s.id, f"terminal error {getattr(e, 'code', 'timeout')}")
            return {"stopReason": "end_turn"}

    async def _d_elicit(self, s: Session, turn: Turn, rest: str, meta: dict):
        started = time.monotonic()
        mode = rest.strip()
        if mode == "form":
            r = await self.conn.create_elicitation(
                message=ELICIT_FORM_MESSAGE,
                mode=ElicitationFormSessionMode(session_id=s.id, requested_schema=ELICIT_FORM_SCHEMA),
            )
            d = dump(r) or {}
            agent_step("elicit.form", d.get("action") == "accept" and (d.get("content") or {}).get("name") == "interop",
                       started, f"response {compact(d)}")
            await self._chunk(s.id, f"elicit: {d.get('action')} {compact(d.get('content'))}")
            return {"stopReason": "end_turn"}
        if mode == "url":
            await self.conn.create_elicitation(
                message=ELICIT_URL["message"],
                mode=ElicitationUrlSessionMode(
                    session_id=s.id, elicitation_id=ELICIT_URL["elicitationId"], url=ELICIT_URL["url"]
                ),
            )
            await self.conn.complete_elicitation(elicitation_id=ELICIT_URL["elicitationId"])
            await self._chunk(s.id, "elicit url done")
            return {"stopReason": "end_turn"}
        raise RequestError(-32602, f"unknown directive: #elicit {mode}")

    async def _d_ext(self, s: Session, turn: Turn, rest: str, meta: dict):
        started = time.monotonic()
        op, _, method = rest.partition(" ")
        method = method.strip()
        if op == "request":
            try:
                r = await self.conn.ext_method(method[1:], EXT_PARAMS)
            except RequestError as e:
                agent_step("ext.agent-request", False, started, f"{method} failed: {e.code} {e}")
                await self._chunk(s.id, f"ext error {e.code}")
                return {"stopReason": "end_turn"}
            agent_step("ext.agent-request", r == EXT_RESULT, started, f"result {compact(r)}")
            await self._chunk(s.id, f"ext: {compact(r)}")
            return {"stopReason": "end_turn"}
        if op == "notify":
            await self.conn.ext_notification(method[1:], EXT_PARAMS)
            await self._chunk(s.id, "ext notified")
            return {"stopReason": "end_turn"}
        if op == "last-notification":
            await self._chunk(s.id, f"ext last: {self.last_ext_notification or 'none'}")
            return {"stopReason": "end_turn"}
        raise RequestError(-32602, f"unknown directive: #ext {op}")

    async def _d_meta(self, s: Session, turn: Turn, rest: str, meta: dict):
        await self._chunk(s.id, "meta", meta or None)
        resp: dict = {"stopReason": "end_turn"}
        if meta:
            resp["_meta"] = meta
        return resp

    async def _d_echo_caps(self, s: Session, turn: Turn, rest: str, meta: dict):
        started = time.monotonic()
        ok = self.client_caps is not None
        agent_step("init.client-capabilities", ok, started,
                   "clientCapabilities received" if ok else "initialize carried no clientCapabilities")
        await self._chunk(s.id, compact(self.client_caps))
        return {"stopReason": "end_turn"}

    async def _d_len(self, s: Session, turn: Turn, rest: str, meta: dict):
        await self._chunk(s.id, f"len={len(rest)}")
        return {"stopReason": "end_turn"}

    async def _d_big(self, s: Session, turn: Turn, rest: str, meta: dict):
        await self._chunk(s.id, "x" * int(rest.strip()))
        return {"stopReason": "end_turn"}


def _text(text: str) -> dict:
    return {"type": "text", "text": text}


# ---------------------------------------------------------------- transports


async def serve_stdio() -> None:
    await run_agent(lambda conn: InteropAgent(conn), use_unstable_protocol=True)
    log("[agent] stdin reached EOF, exiting")


def logging_app(inner):
    async def app(scope, receive, send):
        if scope["type"] not in ("http", "websocket"):
            return await inner(scope, receive, send)
        h = {k.decode().lower(): v.decode() for k, v in scope["headers"]}
        method = scope.get("method", "GET")
        line = (f"[http] {method} {scope['path']} HTTP/{scope.get('http_version', '1.1')}")
        tail = (f"ct={h.get('content-type')} accept={h.get('accept')} conn={h.get('acp-connection-id')} "
                f"sess={h.get('acp-session-id')} upgrade=\"{h.get('upgrade')}\"")

        async def logged_send(msg):
            if msg["type"] == "http.response.start":
                log(f"{line} -> {msg['status']} {tail}")
            elif msg["type"] == "websocket.accept":
                log(f"{line} -> 101 {tail}")
            elif msg["type"] == "websocket.close":
                log(f"[http] websocket close code={msg.get('code')}")
            await send(msg)

        return await inner(scope, receive, logged_send)

    return app


async def serve_http(port: int) -> None:
    import hypercorn.asyncio
    from hypercorn.config import Config

    from acp.http.asgi import create_asgi_app

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("127.0.0.1", port))
    sock.listen(128)
    sock.set_inheritable(True)
    bound = sock.getsockname()[1]
    config = Config()
    config.bind = [f"fd://{sock.fileno()}"]
    config.accesslog = None
    config.graceful_timeout = 1.0
    config.websocket_max_message_size = 64 * 1024 * 1024
    app = logging_app(create_asgi_app(lambda conn: InteropAgent(conn)))
    server = asyncio.ensure_future(hypercorn.asyncio.serve(app, config))

    async def announce():
        for _ in range(400):
            try:
                _, w = await asyncio.open_connection("127.0.0.1", bound)
                w.close()
                print(f"READY {bound}", flush=True)
                return
            except OSError:
                await asyncio.sleep(0.025)

    asyncio.ensure_future(announce())
    await server


def usage(problem: str) -> None:
    print(f"agent: {problem}", file=sys.stderr)
    print("usage: agent --transport stdio | --transport http|ws --port <port>", file=sys.stderr)
    sys.exit(2)


def main(argv: list[str]) -> None:
    transport = None
    port = None
    i = 0
    while i < len(argv):
        a = argv[i]
        if a in ("--transport", "--port") and i + 1 >= len(argv):
            usage(f"{a} needs a value")
        if a == "--transport":
            transport = argv[i + 1]
            i += 2
        elif a == "--port":
            try:
                port = int(argv[i + 1])
            except ValueError:
                usage(f"bad port {argv[i + 1]}")
            i += 2
        else:
            usage(f"unknown argument {a}")
    if transport not in ("stdio", "http", "ws"):
        usage("--transport stdio|http|ws is required")
    if transport == "stdio":
        asyncio.run(serve_stdio())
        return
    if port is None:
        usage(f"--port is required for {transport}")
    # One Python server serves both Streamable HTTP and WebSocket on /acp.
    asyncio.run(serve_http(port))


if __name__ == "__main__":
    os.environ.setdefault("PYTHONUNBUFFERED", "1")
    main(sys.argv[1:])
