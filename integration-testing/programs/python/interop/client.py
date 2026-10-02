"""The Python interop client of the step catalogue (integration-testing/steps.json).

    STEPS=... client.sh --transport stdio              spawns bash -c "exec $AGENT_CMD"
    STEPS=... client.sh --transport http --url http://127.0.0.1:<p>/acp
    STEPS=... client.sh --transport ws   --url ws://127.0.0.1:<p>/acp

Runs the step ids of STEPS in order, printing one STEP <id> PASS|FAIL (<ms> ms) -> <detail> line
each, then RESULT pass=.. fail=.. updates_total=.. upd_<kind>=..; exits 0 whatever the outcomes.
On stdio the agent's stderr is relayed to stdout: STEP lines verbatim, the rest prefixed "agent| ".
Contract: integration-testing/README.md, "Contracts".
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import os
import shutil
import sys
import tempfile
import time
from collections import defaultdict
from pathlib import Path
from typing import Any

from acp import RequestError
from acp.client.connection import ClientSideConnection
from acp.connection import Connection
from acp.stdio import spawn_agent_process
from fixtures import (
    CLIENT_CAPABILITIES,
    CLIENT_INFO,
    ELICIT_FORM_ANSWER,
    EXT_NOTIFICATION,
    EXT_PARAMS,
    EXT_RESULT,
    FS_READ_CONTENT,
    META_KEY,
    META_VALUE,
    compact,
    dump,
    step_line,
)

STEP_TIMEOUT = int(os.environ.get("STEP_TIMEOUT_MS", "15000")) / 1000.0
UPDATE_GRACE = 1.0
REPLAY_GRACE = 2.0
STDIO_LIMIT = 64 * 1024 * 1024

TRANSPORT: str = ""
URL: str | None = None
DIR: Path = Path(".")

PASS = 0
FAIL = 0
UPDATES_TOTAL = 0
UPDATES_BY_KIND: dict[str, int] = defaultdict(int)

UNSUPPORTED_CANCEL_REQUEST = (
    "unsupported: python-sdk has no $/cancel_request API, only the method-name constant (acp/meta.py:49)"
)


class StepFailed(Exception):
    pass


def check(ok: bool, message: str) -> None:
    if not ok:
        raise StepFailed(message)


# ---------------------------------------------------------------- the Client handler


class Recorder:
    """Client-side handlers of one connection; records everything the steps assert on."""

    def __init__(self) -> None:
        self.conn: ClientSideConnection | None = None
        self.updates: dict[str, list[tuple[dict, dict]]] = defaultdict(list)  # sid -> [(update, notification _meta)]
        self.permission_requests: dict[str, list[dict]] = defaultdict(list)
        self.cancel_on_permission: set[str] = set()
        self.cancel_sent_at: dict[str, float] = {}
        self.ext_notifications: list[tuple[str, dict]] = []
        self.completed_elicitations: list[str] = []
        self.terminals: dict[str, Terminal] = {}
        self.next_terminal = 0

    def on_connect(self, conn: Any) -> None:
        self.conn = conn

    def chunks(self, sid: str, kind: str = "agent_message_chunk") -> list[str]:
        return [
            (u.get("content") or {}).get("text", "") for u, _ in self.updates.get(sid, []) if u.get("sessionUpdate") == kind
        ]

    def of_kind(self, sid: str, kind: str) -> list[dict]:
        return [u for u, _ in self.updates.get(sid, []) if u.get("sessionUpdate") == kind]

    async def session_update(self, session_id: str, update: Any, **kw: Any) -> None:
        global UPDATES_TOTAL
        u = dump(update)
        kind = u.get("sessionUpdate", "other")
        UPDATES_TOTAL += 1
        UPDATES_BY_KIND[kind] += 1
        self.updates[session_id].append((u, dict(kw)))

    async def request_permission(self, session_id: str, tool_call: Any, options: list, **kw: Any):
        req = {"toolCall": dump(tool_call), "options": [dump(o) for o in options], "_meta": dict(kw)}
        self.permission_requests[session_id].append(req)
        meta = dict(kw) or None
        if session_id in self.cancel_on_permission:
            self.cancel_sent_at[session_id] = time.monotonic()
            await self.conn.cancel(session_id=session_id)
            return {"outcome": {"outcome": "cancelled"}, **({"_meta": meta} if meta else {})}
        opts = req["options"]
        chosen = next((o for o in opts if o.get("kind") == "allow_once"), opts[0] if opts else None)
        if chosen is None:
            return {"outcome": {"outcome": "cancelled"}}
        return {"outcome": {"outcome": "selected", "optionId": chosen["optionId"]}, **({"_meta": meta} if meta else {})}

    async def write_text_file(self, session_id: str, path: str, content: str, **kw: Any):
        Path(path).write_text(content)
        # Returning None is how the Python SDK's examples answer: on the wire "result": null.
        return None

    async def read_text_file(self, session_id: str, path: str, line: int | None = None, limit: int | None = None, **kw):
        if path.endswith("slow.txt"):
            # Waits up to 10 s; the Python SDK has no $/cancel_request to end it sooner.
            await asyncio.sleep(10)
            return {"content": ""}
        p = Path(path)
        if not p.is_file():
            raise RequestError.resource_not_found(path)
        text = p.read_text()
        if line is not None or limit is not None:
            lines = text.splitlines(keepends=True)
            start = max((line or 1) - 1, 0)
            end = start + limit if limit is not None else len(lines)
            text = "".join(lines[start:end])
        return {"content": text}

    async def create_terminal(self, session_id: str, command: str, args=None, env=None, cwd=None,
                              output_byte_limit=None, **kw):
        self.next_terminal += 1
        tid = f"term-{self.next_terminal}"
        t = Terminal()
        await t.start(command, list(args or []), cwd)
        self.terminals[tid] = t
        return {"terminalId": tid}

    def _terminal(self, terminal_id: str) -> Terminal:
        t = self.terminals.get(terminal_id)
        if t is None:
            raise RequestError.invalid_params({"details": f"unknown terminal {terminal_id}"})
        return t

    async def terminal_output(self, session_id: str, terminal_id: str, **kw):
        t = self._terminal(terminal_id)
        out: dict = {"output": t.output.decode(errors="replace"), "truncated": False}
        if t.proc.returncode is not None:
            out["exitStatus"] = t.exit_status()
        return out

    async def wait_for_terminal_exit(self, session_id: str, terminal_id: str, **kw):
        t = self._terminal(terminal_id)
        await t.proc.wait()
        await t.drained
        return t.exit_status()

    async def kill_terminal(self, session_id: str, terminal_id: str, **kw):
        t = self._terminal(terminal_id)
        if t.proc.returncode is None:
            t.proc.kill()
        return {}

    async def release_terminal(self, session_id: str, terminal_id: str, **kw):
        t = self.terminals.pop(terminal_id, None)
        if t is not None and t.proc.returncode is None:
            t.proc.kill()
            await t.proc.wait()
        return {}

    async def create_elicitation(self, message: str, mode: Any, **kw):
        m = dump(mode) or {}
        if "requestedSchema" in m:
            return ELICIT_FORM_ANSWER
        return {"action": "accept"}

    async def complete_elicitation(self, elicitation_id: str, **kw):
        self.completed_elicitations.append(elicitation_id)

    async def ext_method(self, method: str, params: dict) -> dict:
        if "_" + method == "_interop/ping":
            return EXT_RESULT
        raise RequestError.method_not_found("_" + method)

    async def ext_notification(self, method: str, params: dict) -> None:
        self.ext_notifications.append(("_" + method, params))


class Terminal:
    def __init__(self) -> None:
        self.proc: asyncio.subprocess.Process = None  # type: ignore[assignment]
        self.output = b""
        self.drained: asyncio.Future = None  # type: ignore[assignment]

    async def start(self, command: str, args: list[str], cwd: str | None) -> None:
        self.proc = await asyncio.create_subprocess_exec(
            command, *args, cwd=cwd, stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT, start_new_session=True,
        )
        self.drained = asyncio.ensure_future(self._drain())

    async def _drain(self) -> None:
        while True:
            data = await self.proc.stdout.read(65536)
            if not data:
                return
            self.output += data

    def exit_status(self) -> dict:
        rc = self.proc.returncode
        if rc is not None and rc < 0:
            import signal

            return {"exitCode": None, "signal": signal.Signals(-rc).name}
        return {"exitCode": rc}


# ---------------------------------------------------------------- connections


class Conn:
    """One ACP connection over the selected transport."""

    def __init__(self) -> None:
        self.rec = Recorder()
        self.conn: ClientSideConnection = None  # type: ignore[assignment]
        self.init: dict = {}
        self._stack = contextlib.AsyncExitStack()
        self._relay: asyncio.Task | None = None
        self.process: asyncio.subprocess.Process | None = None

    @classmethod
    async def open(cls) -> Conn:
        c = cls()
        if TRANSPORT == "stdio":
            conn, proc = await c._stack.enter_async_context(
                spawn_agent_process(
                    c.rec, "bash", "-c", "exec " + os.environ["AGENT_CMD"], env=dict(os.environ),
                    transport_kwargs={"limit": STDIO_LIMIT}, use_unstable_protocol=True,
                )
            )
            c.conn, c.process = conn, proc
            c._relay = asyncio.ensure_future(relay_stderr(proc.stderr))
        elif TRANSPORT == "http":
            from acp.http import create_http_stream

            c.conn = ClientSideConnection(c.rec, create_http_stream(URL), use_unstable_protocol=True)
        else:
            from acp.ws import create_websocket_stream

            c.conn = ClientSideConnection(c.rec, await create_websocket_stream(URL), use_unstable_protocol=True)
        return c

    async def initialize(self) -> dict:
        r = await self.conn.initialize(
            protocol_version=1, client_capabilities=CLIENT_CAPABILITIES, client_info=CLIENT_INFO
        )
        self.init = dump(r)
        return self.init

    async def new_session(self, cwd: Path | None = None) -> tuple[str, dict]:
        r = await self.conn.new_session(cwd=str(cwd or DIR), mcp_servers=[])
        d = dump(r)
        check(bool(d.get("sessionId")), "empty sessionId")
        return d["sessionId"], d

    async def prompt(self, sid: str, text: str, **meta: Any) -> dict:
        r = await self.conn.prompt(session_id=sid, prompt=[{"type": "text", "text": text}], **meta)
        return dump(r)

    async def close(self) -> None:
        try:
            await self.conn.close()
            await self._stack.aclose()
        finally:
            if self._relay is not None:
                with contextlib.suppress(Exception):
                    await asyncio.wait_for(self._relay, 2)


async def relay_stderr(stream: asyncio.StreamReader) -> None:
    """Relays the agent's stderr: STEP lines verbatim, everything else prefixed "agent| "."""
    buf = b""
    while True:
        data = await stream.read(65536)
        if not data:
            break
        buf += data
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            emit_agent_line(line.decode(errors="replace"))
    if buf:
        emit_agent_line(buf.decode(errors="replace"))


def emit_agent_line(line: str) -> None:
    if line.startswith("STEP "):
        print(line, flush=True)
    else:
        if len(line) > 2000:
            line = line[:2000] + f"... ({len(line)} chars)"
        print("agent| " + line, flush=True)


MAIN: Conn | None = None


def main_conn() -> Conn:
    check(MAIN is not None, "no main connection (init.initialize did not run or failed)")
    return MAIN  # type: ignore[return-value]


async def wait_for(predicate, message, grace: float = UPDATE_GRACE) -> None:
    deadline = time.monotonic() + grace
    while True:
        if predicate():
            return
        if time.monotonic() >= deadline:
            raise StepFailed(message() if callable(message) else message)
        await asyncio.sleep(0.02)


def end_turn(r: dict) -> None:
    check(r.get("stopReason") == "end_turn", f"stopReason {r.get('stopReason')}")


async def first_tick(c: Conn, sid: str) -> None:
    await wait_for(lambda: "tick" in c.rec.chunks(sid), "no \"tick\" chunk", grace=5)


async def abandon(c: Conn, sid: str, turn: asyncio.Future) -> None:
    """After a failed step, cancel its still-running turn so it does not outlive the step."""
    if turn.done():
        return
    with contextlib.suppress(Exception):
        await c.conn.cancel(session_id=sid)
    turn.cancel()


# ---------------------------------------------------------------- steps


async def s_init_initialize() -> str:
    global MAIN
    MAIN = await Conn.open()
    r = await MAIN.initialize()
    check(r.get("protocolVersion") == 1, f"protocolVersion {r.get('protocolVersion')}")
    return f"protocolVersion=1 agentInfo={compact(r.get('agentInfo'))}"


async def s_init_agent_capabilities() -> str:
    caps = main_conn().init.get("agentCapabilities") or {}
    sc = caps.get("sessionCapabilities") or {}
    check(caps.get("loadSession") is True, f"loadSession {caps.get('loadSession')}")
    missing = [k for k in ("list", "resume", "close", "delete") if sc.get(k) is None]
    check(not missing, f"sessionCapabilities lacks {missing}: {compact(sc)}")
    return f"agentCapabilities {compact(caps)}"


async def echo_caps() -> dict:
    c = main_conn()
    sid, _ = await c.new_session()
    end_turn(await c.prompt(sid, "#echo-caps"))
    await wait_for(lambda: c.rec.chunks(sid), "no chunk")
    text = "".join(c.rec.chunks(sid))
    try:
        return json.loads(text)
    except ValueError:
        raise StepFailed(f"chunk is not JSON: {text[:200]!r}") from None


async def s_init_client_capabilities() -> str:
    caps = await echo_caps()
    fs = caps.get("fs") or {}
    check(fs.get("readTextFile") is True and fs.get("writeTextFile") is True and caps.get("terminal") is True,
          f"echoed {compact(caps)}")
    return f"echoed {compact(caps)}"


async def s_init_auth_methods() -> str:
    methods = main_conn().init.get("authMethods") or []
    check(any(m.get("id") == "interop-auth" for m in methods), f"authMethods {compact(methods)}")
    return f"authMethods {compact(methods)}"


async def s_init_agent_info() -> str:
    info = main_conn().init.get("agentInfo") or {}
    check(str(info.get("name", "")).startswith("interop-"), f"agentInfo {compact(info)}")
    return f"agentInfo {compact(info)}"


async def s_init_config_boolean() -> str:
    caps = await echo_caps()
    check(((caps.get("session") or {}).get("configOptions") or {}).get("boolean") is not None,
          f"echoed {compact(caps)} has no session.configOptions.boolean")
    return "echoed session.configOptions.boolean"


async def s_auth_authenticate() -> str:
    r = await main_conn().conn.authenticate(method_id="interop-auth")
    return f"authenticate -> {compact(dump(r))}"


async def s_auth_logout() -> str:
    r = await main_conn().conn.logout()
    return f"logout -> {compact(dump(r))}"


async def s_auth_logout_capability() -> str:
    caps = main_conn().init.get("agentCapabilities") or {}
    check((caps.get("auth") or {}).get("logout") is not None, f"agentCapabilities.auth {compact(caps.get('auth'))}")
    return "agentCapabilities.auth.logout present"


async def s_auth_terminal() -> str:
    methods = main_conn().init.get("authMethods") or []
    check(any(m.get("type") == "terminal" and m.get("id") == "interop-terminal-auth" for m in methods),
          f"no terminal auth method in {compact(methods)}")
    return "terminal auth method listed"


async def s_session_new() -> str:
    sid, _ = await main_conn().new_session()
    return f"sessionId={sid}"


async def load(c: Conn, sid: str) -> dict:
    return dump(await c.conn.load_session(cwd=str(DIR), session_id=sid, mcp_servers=[]))


async def s_session_load() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    await c.prompt(sid, "hello load")
    await load(c, sid)
    before = len(c.rec.chunks(sid))
    end_turn(await c.prompt(sid, "after load"))
    await wait_for(lambda: len(c.rec.chunks(sid)) > before, "no agent_message_chunk after the load")
    return f"loaded {sid}; prompt after load end_turn"


async def s_session_load_replay() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    end_turn(await c.prompt(sid, "replay me"))
    await asyncio.sleep(0.2)
    mark = len(c.rec.updates[sid])
    await load(c, sid)

    def replayed():
        later = [u for u, _ in c.rec.updates[sid][mark:]]
        user = any(u.get("sessionUpdate") == "user_message_chunk" and (u.get("content") or {}).get("text") == "replay me"
                   for u in later)
        agent = any(u.get("sessionUpdate") == "agent_message_chunk"
                    and "replay me" in (u.get("content") or {}).get("text", "") for u in later)
        return user and agent

    await wait_for(replayed, lambda: f"replay incomplete: {compact([u for u, _ in c.rec.updates[sid][mark:]])}",
                   grace=REPLAY_GRACE)
    return f"replayed {len(c.rec.updates[sid]) - mark} update(s)"


async def s_session_resume() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    end_turn(await c.prompt(sid, "before resume"))
    await asyncio.sleep(0.2)
    mark = len(c.rec.updates[sid])
    await c.conn.resume_session(session_id=sid, cwd=str(DIR))
    await asyncio.sleep(UPDATE_GRACE)
    extra = c.rec.updates[sid][mark:]
    check(not extra, f"{len(extra)} update(s) after resume: {compact([u for u, _ in extra])[:200]}")
    end_turn(await c.prompt(sid, "after resume"))
    return "resumed without replay; prompt after resume end_turn"


async def s_session_list() -> str:
    c = main_conn()
    cwd = DIR / "list"
    cwd.mkdir(exist_ok=True)
    sid, _ = await c.new_session(cwd)
    cursor = None
    seen = []
    for _ in range(10):
        r = dump(await c.conn.list_sessions(cwd=str(cwd), cursor=cursor))
        seen.extend(r.get("sessions") or [])
        cursor = r.get("nextCursor")
        if not cursor:
            break
    mine = [s for s in seen if s.get("sessionId") == sid]
    check(bool(mine), f"{sid} not listed in {compact(seen)[:200]}")
    check(mine[0].get("cwd") == str(cwd), f"listed cwd {mine[0].get('cwd')}")
    return f"listed {sid} among {len(seen)}"


async def s_session_close() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    slow = asyncio.ensure_future(c.prompt(sid, "#slow"))
    try:
        await first_tick(c, sid)
        r = dump(await c.conn.close_session(session_id=sid))
        closed = time.monotonic()
        check(r == {} or r is None, f"close answered {compact(r)}")
        try:
            pr = await asyncio.wait_for(asyncio.shield(slow), 5)
            ended = f"stopReason {pr.get('stopReason')}"
            check(pr.get("stopReason") == "cancelled", f"#slow ended with {ended}")
        except RequestError as e:
            ended = f"error {e.code}"
        except asyncio.TimeoutError:
            raise StepFailed("the #slow prompt did not end within 5 s of the close") from None
        took = int((time.monotonic() - closed) * 1000)
        try:
            await c.prompt(sid, "after close")
        except RequestError as e:
            return f"close {{}}; #slow ended ({ended}) {took} ms after; prompt after close failed {e.code}"
        raise StepFailed("the prompt after close succeeded")
    finally:
        await abandon(c, sid, slow)


async def s_session_delete() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    await c.conn.delete_session(session_id=sid)
    await c.conn.delete_session(session_id="no-such-session")
    return "both deletes answered without error"


async def s_session_multi() -> str:
    c = main_conn()
    a, _ = await c.new_session()
    b, _ = await c.new_session()
    ra, rb = await asyncio.gather(c.prompt(a, "multi A"), c.prompt(b, "multi B"))
    end_turn(ra)
    end_turn(rb)
    await wait_for(lambda: "".join(c.rec.chunks(a)) == "echo: multi A" and "".join(c.rec.chunks(b)) == "echo: multi B",
                   lambda: f"chunks A={c.rec.chunks(a)} B={c.rec.chunks(b)}")
    return "both end_turn, chunks routed per session"


async def s_session_fork() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    end_turn(await c.prompt(sid, "before fork"))
    r = dump(await c.conn.fork_session(session_id=sid, cwd=str(DIR)))
    fid = r.get("sessionId")
    check(bool(fid) and fid != sid, f"fork returned {compact(r)}")
    end_turn(await c.prompt(fid, "in fork"))
    return f"forked {sid} -> {fid}"


async def emit(directive: str) -> tuple[Conn, str, dict]:
    c = main_conn()
    sid, _ = await c.new_session()
    r = await c.prompt(sid, directive)
    return c, sid, r


async def s_update_agent_message_chunk() -> str:
    c, sid, r = await emit("hello")
    end_turn(r)
    await wait_for(lambda: "".join(c.rec.chunks(sid)) == "echo: hello",
                   lambda: f"chunks {c.rec.chunks(sid)} do not spell \"echo: hello\"")
    return "end_turn; chunks spell \"echo: hello\""


def update_step(kind: str, predicate, describe: str, directive: str | None = None, need_end_turn: bool = True):
    async def run() -> str:
        c, sid, r = await emit(directive or f"#emit {kind}")
        if need_end_turn:
            end_turn(r)
        await wait_for(lambda: any(predicate(u) for u in c.rec.of_kind(sid, kind)),
                       lambda: f"no matching {kind} in {compact(c.rec.of_kind(sid, kind))[:300]}")
        return describe

    return run


def text_of(u: dict) -> str:
    return (u.get("content") or {}).get("text", "")


async def s_update_tool_call_update() -> str:
    c, sid, r = await emit("#emit tool_call_update")
    end_turn(r)

    def ok():
        kinds = [(u.get("sessionUpdate"), u) for u, _ in c.rec.updates[sid]]
        starts = [i for i, (k, u) in enumerate(kinds) if k == "tool_call" and u.get("toolCallId") == "call-1"]
        ups = [i for i, (k, u) in enumerate(kinds) if k == "tool_call_update" and u.get("toolCallId") == "call-1"
               and u.get("status") == "completed"
               and any((x.get("content") or {}).get("text") == "tool output" for x in (u.get("content") or []))]
        return bool(starts and ups and starts[0] < ups[-1])

    await wait_for(ok, lambda: f"updates {compact([u for u, _ in c.rec.updates[sid]])[:300]}")
    return "tool_call call-1 then tool_call_update completed \"tool output\""


async def s_update_unknown() -> str:
    c, sid, r = await emit("#emit unknown")
    end_turn(r)
    await wait_for(lambda: "after-unknown" in c.rec.chunks(sid), lambda: f"chunks {c.rec.chunks(sid)}")
    return "unknown update dropped; \"after-unknown\" arrived; end_turn"


def stop_step(reason: str):
    async def run() -> str:
        _, _, r = await emit(f"#stop {reason}")
        check(r.get("stopReason") == reason, f"stopReason {r.get('stopReason')}")
        return f"stopReason {reason}"

    return run


async def s_mode_set() -> str:
    c = main_conn()
    sid, resp = await c.new_session()
    modes = resp.get("modes") or {}
    ids = [m.get("id") for m in modes.get("availableModes") or []]
    check("interop-mode-b" in ids, f"session/new modes {compact(modes)}")
    await c.conn.set_session_mode(session_id=sid, mode_id="interop-mode-b")
    return f"availableModes {ids}; set_mode interop-mode-b"


def option(opts: list, oid: str) -> dict:
    return next((o for o in opts or [] if o.get("id") == oid), {})


async def s_config_on_new() -> str:
    _, resp = await main_conn().new_session()
    model = option(resp.get("configOptions"), "model")
    check(model.get("type") == "select" and model.get("currentValue") == "model-a",
          f"configOptions {compact(resp.get('configOptions'))}")
    return "session/new carries model=model-a"


async def s_config_select() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    r = dump(await c.conn.set_config_option(config_id="model", session_id=sid, value="model-b"))
    check(option(r.get("configOptions"), "model").get("currentValue") == "model-b", f"response {compact(r)}")
    return f"configOptions {compact(r.get('configOptions'))[:200]}"


async def s_config_boolean() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    r = dump(await c.conn.set_config_option(config_id="verbose", session_id=sid, value=True))
    check(option(r.get("configOptions"), "verbose").get("currentValue") is True, f"response {compact(r)}")
    return "verbose=true"


async def s_perm_selected() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    end_turn(await c.prompt(sid, "#permission allow"))
    await wait_for(lambda: "permission: selected allow" in c.rec.chunks(sid),
                   lambda: f"no chunk \"permission: selected allow\" in {c.rec.chunks(sid)}")
    asked = c.rec.permission_requests[sid]
    check(len(asked) == 1, f"{len(asked)} permission requests, expected 1")
    check(bool(asked[0]["options"]), "permission request without options")
    return "one permission request; selected allow; end_turn"


async def s_perm_cancelled() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    c.rec.cancel_on_permission.add(sid)
    r = await c.prompt(sid, "#permission hold")
    sent = c.rec.cancel_sent_at.get(sid)
    check(sent is not None, "no permission request arrived")
    took = time.monotonic() - sent
    check(r.get("stopReason") == "cancelled", f"stopReason {r.get('stopReason')}")
    check(took <= 5, f"cancelled {took:.1f} s after the cancel")
    return f"stopReason cancelled {int(took * 1000)} ms after the cancel"


async def s_fs_write() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    f = DIR / "fs-write.txt"
    end_turn(await c.prompt(sid, f"#fs write {f} interop write"))
    await wait_for(lambda: "fs write ok" in c.rec.chunks(sid), lambda: f"no chunk \"fs write ok\" in {c.rec.chunks(sid)}")
    check(f.exists(), f"{f} was not written")
    content = f.read_text()
    check(content == "interop write", f"file content {content!r}")
    return "written through the client (handler answered null): \"interop write\""


async def fs_read(directive_suffix: str) -> tuple[Conn, str, dict]:
    (DIR / "fs-read.txt").write_text(FS_READ_CONTENT)
    c = main_conn()
    sid, _ = await c.new_session()
    r = await c.prompt(sid, f"#fs read {directive_suffix}")
    return c, sid, r


async def s_fs_read() -> str:
    c, sid, r = await fs_read(f"{DIR}/fs-read.txt")
    await wait_for(lambda: FS_READ_CONTENT in c.rec.chunks(sid), lambda: f"chunks {c.rec.chunks(sid)}")
    return "chunk equals fixtures.fsReadContent"


async def s_fs_read_range() -> str:
    c, sid, r = await fs_read(f"{DIR}/fs-read.txt line=2 limit=1")
    await wait_for(lambda: any(x.strip() == "line2" for x in c.rec.chunks(sid)), lambda: f"chunks {c.rec.chunks(sid)}")
    return "chunk \"line2\""


async def s_fs_read_missing() -> str:
    c, sid, r = await fs_read(f"{DIR}/no-such-file.txt")
    end_turn(r)
    await wait_for(lambda: any(x.startswith("fs read error") for x in c.rec.chunks(sid)),
                   lambda: f"chunks {c.rec.chunks(sid)}")
    return f"chunk {[x for x in c.rec.chunks(sid) if x.startswith('fs read error')][0]!r}"


async def s_term_run() -> str:
    c, sid, r = await emit("#terminal run echo hi")
    await wait_for(lambda: "terminal: hi exit=0" in c.rec.chunks(sid), lambda: f"chunks {c.rec.chunks(sid)}")
    return "chunk \"terminal: hi exit=0\""


async def s_term_kill() -> str:
    started = time.monotonic()
    c, sid, r = await emit("#terminal kill sleep 30")
    await wait_for(lambda: "terminal killed" in c.rec.chunks(sid), lambda: f"chunks {c.rec.chunks(sid)}")
    took = time.monotonic() - started
    check(took <= 5, f"\"terminal killed\" after {took:.1f} s")
    return f"chunk \"terminal killed\" after {int(took * 1000)} ms"


async def s_elicit_form() -> str:
    c, sid, r = await emit("#elicit form")
    expected = {"name": "interop"}

    def ok():
        for x in c.rec.chunks(sid):
            if x.startswith("elicit: accept "):
                with contextlib.suppress(ValueError):
                    return json.loads(x[len("elicit: accept "):]) == expected
        return False

    await wait_for(ok, lambda: f"chunks {c.rec.chunks(sid)}")
    return "chunk \"elicit: accept {\\\"name\\\":\\\"interop\\\"}\""


async def s_elicit_complete() -> str:
    c, sid, r = await emit("#elicit url")
    await wait_for(lambda: "elic-1" in c.rec.completed_elicitations,
                   lambda: f"no elicitation/complete for elic-1 (got {c.rec.completed_elicitations})")
    return "elicitation/complete elic-1 arrived"


async def s_cancel_prompt() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    slow = asyncio.ensure_future(c.prompt(sid, "#slow"))
    try:
        await first_tick(c, sid)
        sent = time.monotonic()
        await c.conn.cancel(session_id=sid)
        try:
            r = await asyncio.wait_for(asyncio.shield(slow), 5)
        except asyncio.TimeoutError:
            raise StepFailed("no response within 5 s of the cancel") from None
        check(r.get("stopReason") == "cancelled", f"stopReason {r.get('stopReason')}")
        return f"stopReason cancelled {int((time.monotonic() - sent) * 1000)} ms after the cancel"
    finally:
        await abandon(c, sid, slow)


async def s_cancel_prompt_while_cancelling() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    slow = asyncio.ensure_future(c.prompt(sid, "#slow grace=1000"))
    try:
        await first_tick(c, sid)
        await c.conn.cancel(session_id=sid)
        try:
            await c.prompt(sid, "during cancel")
            raise StepFailed("\"during cancel\" was accepted")
        except RequestError as e:
            check(e.code == -32600, f"\"during cancel\" failed with {e.code}, expected -32600")
        r = await asyncio.wait_for(asyncio.shield(slow), 5)
        check(r.get("stopReason") == "cancelled", f"first prompt stopReason {r.get('stopReason')}")
        end_turn(await c.prompt(sid, "after cancel"))
        return "cancelled; \"during cancel\" -32600; \"after cancel\" end_turn"
    finally:
        await abandon(c, sid, slow)


async def s_cancel_grace() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    hang = asyncio.ensure_future(c.prompt(sid, "#hang"))
    try:
        await asyncio.sleep(0.2)
        sent = time.monotonic()
        await c.conn.cancel(session_id=sid)
        try:
            r = await asyncio.wait_for(asyncio.shield(hang), 6)
        except asyncio.TimeoutError:
            raise StepFailed("no response within 6 s of the cancel") from None
        check(r.get("stopReason") == "cancelled", f"stopReason {r.get('stopReason')}")
        return f"SDK answered cancelled {int((time.monotonic() - sent) * 1000)} ms after the cancel"
    finally:
        await abandon(c, sid, hang)


async def s_cancel_request_unsupported() -> str:
    raise StepFailed(UNSUPPORTED_CANCEL_REQUEST)


async def s_cancel_request_agent() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    started = time.monotonic()
    r = await c.prompt(sid, f"#fs read-slow {DIR}/slow.txt")
    took = time.monotonic() - started
    end_turn(r)
    await wait_for(lambda: "cancel-request sent" in c.rec.chunks(sid), lambda: f"chunks {c.rec.chunks(sid)}")
    check(took <= 5, f"end_turn after {took:.1f} s")
    return f"\"cancel-request sent\" and end_turn after {int(took * 1000)} ms"


async def s_ext_agent_request() -> str:
    c, sid, r = await emit("#ext request _interop/ping")

    def ok():
        for x in c.rec.chunks(sid):
            if x.startswith("ext: "):
                with contextlib.suppress(ValueError):
                    return json.loads(x[5:]) == EXT_RESULT
        return False

    await wait_for(ok, lambda: f"chunks {c.rec.chunks(sid)}")
    return "chunk \"ext: {\\\"pong\\\":1}\""


async def s_ext_agent_notification() -> str:
    c, sid, r = await emit("#ext notify _interop/note")
    await wait_for(lambda: (EXT_NOTIFICATION, EXT_PARAMS) in c.rec.ext_notifications,
                   lambda: f"notifications {c.rec.ext_notifications}")
    return "_interop/note {\"n\":1} arrived"


async def s_ext_client_request() -> str:
    r = await main_conn().conn.ext_method("interop/ping", EXT_PARAMS)
    check(r == EXT_RESULT, f"result {compact(r)}")
    return "result {\"pong\":1}"


async def s_ext_client_notification() -> str:
    c = main_conn()
    await c.conn.ext_notification("interop/note", EXT_PARAMS)
    sid, _ = await c.new_session()
    await c.prompt(sid, "#ext last-notification")
    await wait_for(lambda: "ext last: _interop/note" in c.rec.chunks(sid), lambda: f"chunks {c.rec.chunks(sid)}")
    return "chunk \"ext last: _interop/note\""


async def s_meta_prompt() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    r = await c.prompt(sid, "#meta", **{META_KEY: META_VALUE})

    def chunk_meta():
        for u, nmeta in c.rec.updates[sid]:
            if u.get("sessionUpdate") == "agent_message_chunk" and text_of(u) == "meta":
                return (u.get("_meta") or {}).get(META_KEY) or nmeta.get(META_KEY)
        return None

    await wait_for(lambda: chunk_meta() is not None, lambda: f"no \"meta\" chunk with _meta in {c.rec.updates[sid]}")
    check(chunk_meta() == META_VALUE, f"chunk _meta {chunk_meta()}")
    check((r.get("_meta") or {}).get(META_KEY) == META_VALUE, f"PromptResponse _meta {r.get('_meta')}")
    return "update and PromptResponse _meta interop=m1"


async def s_meta_permission() -> str:
    c = main_conn()
    sid, _ = await c.new_session()
    end_turn(await c.prompt(sid, "#permission allow meta"))
    asked = c.rec.permission_requests[sid]
    check(len(asked) == 1, f"{len(asked)} permission requests")
    meta = asked[0]["_meta"]
    check(meta.get(META_KEY) == META_VALUE, f"request _meta {compact(meta) if meta else 'absent'}")
    return "request _meta interop=m1; echoed on the response"


async def s_error_method_not_found() -> str:
    c = main_conn()
    try:
        r = await c.conn.list_providers()
        raise StepFailed(f"providers/list answered {compact(dump(r))}")
    except RequestError as e:
        check(e.code == -32601, f"providers/list failed with {e.code} {e}")
    sid, _ = await c.new_session()
    return f"providers/list -32601; session/new after it: {sid}"


def big_prompt(n: int):
    async def run() -> str:
        c = main_conn()
        sid, _ = await c.new_session()
        await c.prompt(sid, "#len " + "x" * n)
        await wait_for(lambda: f"len={n}" in c.rec.chunks(sid), lambda: f"chunks {c.rec.chunks(sid)}")
        return f"chunk \"len={n}\""

    return run


def big_update(n: int):
    async def on(c: Conn) -> str:
        sid, _ = await c.new_session()
        try:
            await c.prompt(sid, f"#big {n}")
        except ConnectionError as e:
            raise StepFailed(f"ConnectionError: {e}{ws_close_info(c)}") from None
        await wait_for(lambda: any(len(x) == n for x in c.rec.chunks(sid)),
                       lambda: f"chunk lengths {[len(x) for x in c.rec.chunks(sid)]}")
        return f"one agent_message_chunk of {n} characters"

    async def run() -> str:
        if TRANSPORT != "ws":
            return await on(main_conn())
        # The Python WebSocket client closes the whole connection on an over-size message
        # (websockets' default max_size, 1 MiB), so this step runs on a connection of its own:
        # its failure must not take the main connection, and the later steps, with it.
        c = await Conn.open()
        try:
            await c.initialize()
            return await on(c)
        finally:
            with contextlib.suppress(Exception):
                await c.close()

    return run


def ws_close_info(c: Conn) -> str:
    ws = getattr(getattr(getattr(c.conn, "_conn", None), "_transport", None), "_ws", None)
    exc = getattr(getattr(ws, "protocol", None), "close_exc", None)
    return "" if exc is None else f" (websocket: {exc})"


async def s_http_reconnect() -> str:
    check(TRANSPORT == "http", f"http.reconnect does not apply to {TRANSPORT}")
    first = await Conn.open()
    try:
        await first.initialize()
        sid, _ = await first.new_session()
        end_turn(await first.prompt(sid, "before reconnect"))
    finally:
        await first.close()
    second = await Conn.open()
    try:
        await second.initialize()
        await load(second, sid)
        end_turn(await second.prompt(sid, "after reconnect"))
    finally:
        await second.close()
    return f"reconnected and loaded {sid}; prompt after reconnect end_turn"


async def s_stdio_eof_exit() -> str:
    check(TRANSPORT == "stdio", f"stdio.eof-exit does not apply to {TRANSPORT}")
    proc = await asyncio.create_subprocess_exec(
        "bash", "-c", "exec " + os.environ["AGENT_CMD"], env=dict(os.environ), limit=STDIO_LIMIT,
        stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
    )
    relay = asyncio.ensure_future(relay_stderr(proc.stderr))
    conn = Connection(lambda *a: _no_handler(*a), proc.stdin, proc.stdout)
    try:
        r = await conn.send_request("initialize", {"protocolVersion": 1, "clientCapabilities": {},
                                                   "clientInfo": CLIENT_INFO})
        check((r or {}).get("protocolVersion") == 1, f"initialize -> {compact(r)}")
        proc.stdin.close()
        eof = time.monotonic()
        try:
            await asyncio.wait_for(proc.wait(), 5)
        except asyncio.TimeoutError:
            proc.kill()
            await proc.wait()
            raise StepFailed("no exit on EOF within 5 s") from None
        return f"agent exited (code {proc.returncode}) {int((time.monotonic() - eof) * 1000)} ms after EOF"
    finally:
        if proc.returncode is None:
            proc.kill()
            await proc.wait()
        await conn.close()
        with contextlib.suppress(Exception):
            await asyncio.wait_for(relay, 2)


async def _no_handler(method: str, params: Any, is_notification: bool) -> Any:
    if is_notification:
        return None
    raise RequestError.method_not_found(method)


async def s_conn_close() -> str:
    c = main_conn()
    await c.close()
    return f"closed the {TRANSPORT} connection"


STEPS = {
    "init.initialize": s_init_initialize,
    "init.agent-capabilities": s_init_agent_capabilities,
    "init.client-capabilities": s_init_client_capabilities,
    "init.auth-methods": s_init_auth_methods,
    "init.agent-info": s_init_agent_info,
    "init.config-boolean": s_init_config_boolean,
    "auth.authenticate": s_auth_authenticate,
    "auth.logout": s_auth_logout,
    "auth.logout-capability": s_auth_logout_capability,
    "auth.terminal": s_auth_terminal,
    "session.new": s_session_new,
    "session.load": s_session_load,
    "session.load-replay": s_session_load_replay,
    "session.resume": s_session_resume,
    "session.list": s_session_list,
    "session.close": s_session_close,
    "session.delete": s_session_delete,
    "session.multi": s_session_multi,
    "session.fork": s_session_fork,
    "update.agent_message_chunk": s_update_agent_message_chunk,
    "update.user_message_chunk": update_step(
        "user_message_chunk", lambda u: text_of(u) == "user-chunk", "user_message_chunk \"user-chunk\""),
    "update.agent_thought_chunk": update_step(
        "agent_thought_chunk", lambda u: text_of(u) == "thinking", "agent_thought_chunk \"thinking\""),
    "update.tool_call": update_step(
        "tool_call",
        lambda u: u.get("toolCallId") == "call-1" and u.get("title") == "interop tool" and u.get("kind") == "read"
        and u.get("status") == "pending",
        "tool_call call-1 \"interop tool\" read pending", need_end_turn=False),
    "update.tool_call_update": s_update_tool_call_update,
    "update.tool_call-name": update_step(
        "tool_call", lambda u: u.get("name") == "read_file", "tool_call name read_file",
        directive="#emit tool_call name=read_file", need_end_turn=False),
    "update.plan": update_step(
        "plan",
        lambda u: [(e.get("content"), e.get("priority"), e.get("status")) for e in u.get("entries") or []]
        == [("step one", "high", "pending"), ("step two", "low", "completed")],
        "plan with the two fixture entries", need_end_turn=False),
    "update.available_commands_update": update_step(
        "available_commands_update",
        lambda u: any(cmd.get("name") == "interop" and (cmd.get("input") or {}).get("hint") == "args"
                      for cmd in u.get("availableCommands") or []),
        "command interop with hint args", need_end_turn=False),
    "update.current_mode_update": update_step(
        "current_mode_update", lambda u: u.get("currentModeId") == "interop-mode-b", "currentModeId interop-mode-b",
        need_end_turn=False),
    "update.config_option_update": update_step(
        "config_option_update", lambda u: option(u.get("configOptions"), "model").get("currentValue") == "model-b",
        "model currentValue model-b", need_end_turn=False),
    "update.session_info_update": update_step(
        "session_info_update", lambda u: u.get("title") == "interop title", "title \"interop title\"",
        need_end_turn=False),
    "update.usage_update": update_step(
        "usage_update",
        lambda u: u.get("used") == 100 and u.get("size") == 1000
        and (u.get("cost") or {}).get("amount") == 0.01 and (u.get("cost") or {}).get("currency") == "USD",
        "used 100 size 1000 cost 0.01 USD", need_end_turn=False),
    "update.unknown": s_update_unknown,
    "stop.max_tokens": stop_step("max_tokens"),
    "stop.refusal": stop_step("refusal"),
    "stop.max_turn_requests": stop_step("max_turn_requests"),
    "mode.set": s_mode_set,
    "config.on-new": s_config_on_new,
    "config.select": s_config_select,
    "config.boolean": s_config_boolean,
    "perm.selected": s_perm_selected,
    "perm.cancelled": s_perm_cancelled,
    "fs.write": s_fs_write,
    "fs.read": s_fs_read,
    "fs.read-range": s_fs_read_range,
    "fs.read-missing": s_fs_read_missing,
    "term.run": s_term_run,
    "term.kill": s_term_kill,
    "elicit.form": s_elicit_form,
    "elicit.complete": s_elicit_complete,
    "cancel.prompt": s_cancel_prompt,
    "cancel.prompt-while-cancelling": s_cancel_prompt_while_cancelling,
    "cancel.grace": s_cancel_grace,
    "cancel-request.client": s_cancel_request_unsupported,
    "cancel-request.agent": s_cancel_request_agent,
    "cancel-request.unknown": s_cancel_request_unsupported,
    "ext.agent-request": s_ext_agent_request,
    "ext.agent-notification": s_ext_agent_notification,
    "ext.client-request": s_ext_client_request,
    "ext.client-notification": s_ext_client_notification,
    "meta.prompt": s_meta_prompt,
    "meta.permission": s_meta_permission,
    "error.method-not-found": s_error_method_not_found,
    "big.prompt-1m": big_prompt(1048576),
    "big.update-1m": big_update(1048576),
    "big.prompt-8m": big_prompt(8388608),
    "big.update-8m": big_update(8388608),
    "http.reconnect": s_http_reconnect,
    "stdio.eof-exit": s_stdio_eof_exit,
    "conn.close": s_conn_close,
}


async def run_step(step_id: str) -> None:
    global PASS, FAIL
    fn = STEPS.get(step_id)
    started = time.monotonic()
    if fn is None:
        FAIL += 1
        print(f"STEP {step_id} FAIL (0 ms) -> unknown-step", flush=True)
        return
    task = asyncio.ensure_future(fn())
    try:
        detail = await asyncio.wait_for(task, STEP_TIMEOUT)
        PASS += 1
        step_line(sys.stdout, step_id, True, started, detail)
    except asyncio.TimeoutError:
        FAIL += 1
        step_line(sys.stdout, step_id, False, started, f"TIMEOUT after {int(STEP_TIMEOUT * 1000)} ms")
    except StepFailed as e:
        FAIL += 1
        step_line(sys.stdout, step_id, False, started, str(e))
    except RequestError as e:
        FAIL += 1
        step_line(sys.stdout, step_id, False, started, f"error {e.code} {e} {compact(e.data) if e.data else ''}")
    except Exception as e:  # noqa: BLE001 - every failure is a step result
        FAIL += 1
        step_line(sys.stdout, step_id, False, started, f"{type(e).__name__}: {e}")


def usage(problem: str) -> None:
    print(f"client: {problem}", file=sys.stderr)
    print("usage: STEPS=<ids> client --transport stdio (with AGENT_CMD) | --transport http|ws --url <url>",
          file=sys.stderr)
    sys.exit(2)


async def amain(steps: list[str]) -> None:
    for s in steps:
        await run_step(s)
    if MAIN is not None and "conn.close" not in steps:
        with contextlib.suppress(Exception):
            await asyncio.wait_for(MAIN.close(), 5)
    kinds = " ".join(f"upd_{k}={v}" for k, v in sorted(UPDATES_BY_KIND.items()))
    print(f"RESULT pass={PASS} fail={FAIL} updates_total={UPDATES_TOTAL} {kinds}".rstrip(), flush=True)


def main(argv: list[str]) -> None:
    global TRANSPORT, URL, DIR
    i = 0
    while i < len(argv):
        a = argv[i]
        if a in ("--transport", "--url"):
            if i + 1 >= len(argv):
                usage(f"{a} needs a value")
            if a == "--transport":
                TRANSPORT = argv[i + 1]
            else:
                URL = argv[i + 1]
            i += 2
        else:
            usage(f"unknown argument {a}")
    if TRANSPORT not in ("stdio", "http", "ws"):
        usage("--transport stdio|http|ws is required")
    if TRANSPORT != "stdio" and not URL:
        usage(f"--url is required for {TRANSPORT}")
    if TRANSPORT == "stdio" and not os.environ.get("AGENT_CMD"):
        usage("AGENT_CMD is required for stdio")
    steps = [s.strip() for s in os.environ.get("STEPS", "").split(",") if s.strip()]
    if not steps:
        usage("STEPS is required")
    DIR = Path(tempfile.mkdtemp(prefix="acp-interop-"))
    try:
        asyncio.run(amain(steps))
    finally:
        shutil.rmtree(DIR, ignore_errors=True)
    # Exit at once: an SDK task stuck on a dead peer must not keep the process alive.
    sys.stdout.flush()
    os._exit(0)


if __name__ == "__main__":
    main(sys.argv[1:])
