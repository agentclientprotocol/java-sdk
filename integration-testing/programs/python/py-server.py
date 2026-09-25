"""Interop agent on the Python SDK's Streamable HTTP ASGI app, served by Hypercorn with h2c enabled.

Each prompt streams two updates; a prompt containing "permission" first asks the client.
Env: PORT. Prints "READY <port>" on stdout once bound; one [http] line per request with the
HTTP version Hypercorn negotiated (scenarios assert HTTP/2 for the Java client).
"""
import asyncio
import os
import sys

from acp import Agent, InitializeResponse, LoadSessionResponse, NewSessionResponse, PromptResponse
from acp.http.asgi import create_asgi_app
from acp.schema import AgentCapabilities, AgentMessageChunk, PermissionOption, TextContentBlock, ToolCallUpdate

SESSIONS = set()


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def chunk(text):
    return AgentMessageChunk(session_update="agent_message_chunk", content=TextContentBlock(type="text", text=text))


class EchoAgent(Agent):
    def on_connect(self, conn):
        self._conn = conn

    async def initialize(self, protocol_version, client_capabilities=None, client_info=None, **kw):
        log("[agent] initialize")
        return InitializeResponse(protocol_version=protocol_version, agent_capabilities=AgentCapabilities(load_session=True))

    async def new_session(self, cwd="", **kw):
        sid = "py-" + os.urandom(4).hex()
        SESSIONS.add(sid)
        log("[agent] session/new", sid)
        return NewSessionResponse(session_id=sid)

    async def load_session(self, cwd, session_id, mcp_servers=None, **kw):
        log("[agent] session/load", session_id)
        await self._conn.session_update(session_id=session_id, update=chunk("replayed history"))
        return LoadSessionResponse()

    async def prompt(self, session_id, prompt, **kw):
        text = " ".join((b.get("text", "") if isinstance(b, dict) else getattr(b, "text", "")) for b in prompt)
        log("[agent] session/prompt", text)
        if "permission" in text:
            r = await self._conn.request_permission(
                session_id=session_id,
                tool_call=ToolCallUpdate(tool_call_id="tc-1", title="Run thing", kind="execute", status="pending"),
                options=[PermissionOption(option_id="allow_once", name="Allow once", kind="allow_once"),
                         PermissionOption(option_id="reject_once", name="Reject once", kind="reject_once")])
            log("[agent] permission result", r)
        for t in ("echo: ", text):
            await self._conn.session_update(session_id=session_id, update=chunk(t))
        return PromptResponse(stop_reason="end_turn")


inner = create_asgi_app(lambda conn: EchoAgent())


async def app(scope, receive, send):
    if scope["type"] != "http":
        return await inner(scope, receive, send)
    h = {k.decode().lower(): v.decode() for k, v in scope["headers"]}
    line = (f"[http] {scope['method']} {scope['path']} HTTP/{scope['http_version']} ct={h.get('content-type')} "
            f"accept={h.get('accept')} conn={h.get('acp-connection-id')} sess={h.get('acp-session-id')} upgrade={h.get('upgrade')}")

    async def send2(msg):
        if msg["type"] == "http.response.start":
            rh = {k.decode().lower(): v.decode() for k, v in msg.get("headers", [])}
            log(f"{line} -> {msg['status']} ct={rh.get('content-type')}")
        await send(msg)

    return await inner(scope, receive, send2)


async def main():
    import hypercorn.asyncio
    from hypercorn.config import Config

    port = int(os.environ.get("PORT", "0"))
    c = Config()
    c.bind = [f"127.0.0.1:{port}"]
    c.alpn_protocols = ["h2", "http/1.1"]
    ready = asyncio.Event()

    async def announce():
        # Hypercorn has no "bound" callback; poll the port, then announce.
        for _ in range(200):
            try:
                _, w = await asyncio.open_connection("127.0.0.1", port)
                w.close()
                print(f"READY {port}", flush=True)
                return
            except OSError:
                await asyncio.sleep(0.05)

    asyncio.create_task(announce())
    await hypercorn.asyncio.serve(app, c)


asyncio.run(main())
