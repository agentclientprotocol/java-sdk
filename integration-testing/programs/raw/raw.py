#!/usr/bin/env python3
"""The raw JSON-RPC conformance driver of the cross-SDK suite (integration-testing/README.md).

It puts frames on the wire that no SDK's typed API will send: malformed JSON, JSON that is no
JSON-RPC message, ids of every legal and illegal type, unknown methods and notifications, wrong
params, stray responses, an error without "message", "result": null, oversized frames. It checks
the receiver's answer against JSON-RPC 2.0 and the ACP schema's ErrorCode list
(schema/v1/schema.json $defs.ErrorCode: -32700 parse error, -32600 invalid request, -32601 method
not found, -32602 invalid params, -32603 internal error, -32800 request cancelled, -32000
authentication required, -32002 resource not found) and, on Streamable HTTP, against the
transport RFD (docs/rfds/streamable-http-websocket-transport.mdx). Python 3 standard library
only: no SDK is involved, so what it sends is exactly what the case says.

Two roles, three transports each:

  raw.py client --transport stdio                 spawns bash -c "exec $AGENT_CMD", probes the agent
  raw.py client --transport http --url http://127.0.0.1:<port>/acp
  raw.py client --transport ws   --url ws://127.0.0.1:<port>/acp
  raw.py agent  --transport stdio                 an agent on stdin/stdout
  raw.py agent  --transport http|ws --port <p>    an agent on /acp (Streamable HTTP and WebSocket
                                                  upgrade on the same endpoint), prints READY <port>

client role: runs the cases of CLIENT_CASES that apply to the transport (or --cases a,b), prints
one "STEP raw.<case> PASS|FAIL (<ms> ms) -> <detail>" per case and a final RESULT line, exits 0.
After each case it checks that the connection survived (a session/new still answers). A stdio
agent's stderr is relayed as the Contracts say (STEP lines verbatim, the rest "agent| ...").

agent role: serves the catalogue steps of AGENT_ROLE_STEPS to an SDK client, answering some of
its requests in ways no SDK agent would (an error without "message", "result": null for the
all-optional responses), and the "#raw <case>" prompts of the Java client's raw mode. During the
plain-text prompt "hello" (update.agent_message_chunk) it runs AGENT_CASES against the client,
printing "STEP agent.raw.<case> PASS|FAIL ..." on stderr. RAW_AGENT_CASES=a,b runs only those.

Silence checks ("no reply") use a fence rather than a fixed wait: after the frame under test the
driver sends an ordinary request; a receiver answers in order, so an answer to the frame under
test arrives before the fence's (plus FENCE_GRACE for one a handler sends late).

--target <name>: evidence mode, for running the driver against a peer SDK. Every case line is
printed as "EVIDENCE <name> <transport> <case> PASS|FAIL ..." instead of STEP, so the run documents
the peer's behaviour and never gates a scenario; the client role also runs EVIDENCE_ONLY cases.

gen_conf.py turns these case lists and expectations/raw.json into configs/conf-java-*.json.
"""

import base64
import hashlib
import http.client
import json
import os
import queue
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STEP_TIMEOUT = int(os.environ.get("STEP_TIMEOUT_MS", "15000")) / 1000.0
FENCE_GRACE = 0.15  # seconds after the fence's answer during which a late answer still counts
MAX_FRAME = 16 * 1024 * 1024  # the Java agent's POST body and WebSocket message limit (G0c)
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

# fixtures (steps.json "fixtures")
CLIENT_INIT = {
    "protocolVersion": 1,
    "clientCapabilities": {"fs": {"readTextFile": True, "writeTextFile": True}, "terminal": False},
    "clientInfo": {"name": "interop-raw-client", "version": "1"},
}
AGENT_INIT = {
    "protocolVersion": 1,
    "agentCapabilities": {"loadSession": True, "sessionCapabilities": {"delete": {}}},
    "authMethods": [{"id": "interop-auth", "name": "Interop auth", "description": "Accepts any authenticate call"}],
    "agentInfo": {"name": "interop-raw-agent", "version": "1"},
}
MODES = {
    "currentModeId": "interop-mode-a",
    "availableModes": [{"id": "interop-mode-a", "name": "Mode A"}, {"id": "interop-mode-b", "name": "Mode B"}],
}
PERMISSION = {
    "toolCall": {"toolCallId": "perm-1", "title": "interop permission", "kind": "edit", "status": "pending"},
    "options": [
        {"optionId": "allow", "name": "Allow", "kind": "allow_once"},
        {"optionId": "reject", "name": "Reject", "kind": "reject_once"},
    ],
}
UNKNOWN_UPDATE = {"sessionUpdate": "interop_future_update", "payload": {"x": 1}}

PARSE_ERROR, INVALID_REQUEST, METHOD_NOT_FOUND, INVALID_PARAMS = -32700, -32600, -32601, -32602


class Fail(Exception):
    pass


OUT_LOCK = threading.Lock()  # one writer at a time: relayed agent lines must not split ours


def out(line):
    with OUT_LOCK:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()


def log(*parts):
    with OUT_LOCK:
        print(*parts, file=sys.stderr, flush=True)


def short(value, n=240):
    s = value if isinstance(value, str) else json.dumps(value, separators=(",", ":"))
    s = s.replace("\n", "\\n")
    return s if len(s) <= n else s[:n] + "... (%d chars)" % len(s)


def dumps(obj):
    return json.dumps(obj, separators=(",", ":"))


def parse(text):
    """A received frame: the decoded JSON value, or {"_unparsed": text} when it is not JSON."""
    try:
        return json.loads(text)
    except ValueError:
        return {"_unparsed": text}


def is_response(m):
    return isinstance(m, dict) and "method" not in m and ("result" in m or "error" in m)


def is_request(m):
    return isinstance(m, dict) and isinstance(m.get("method"), str) and "id" in m


def is_notification(m):
    return isinstance(m, dict) and isinstance(m.get("method"), str) and "id" not in m


def error_code(m):
    e = m.get("error") if isinstance(m, dict) else None
    return e.get("code") if isinstance(e, dict) else None


def same_id(a, b):
    """JSON-RPC ids compare by value and type: 1 is not "1", 0 is not null, true is not 1."""
    return type(a) is type(b) and a == b


# =====================================================================================
# Transports: one message channel per connection. send() writes one frame (text, exactly as
# given); recv() returns the next decoded frame or None on timeout; closed is set at EOF.
# =====================================================================================


UNCLEAN = []  # every stdio agent's stdout lines that are not one JSON-RPC message each


class StdioChannel:
    """Client side: the agent is a child process; its stderr is relayed to our stdout."""

    def __init__(self, cmd, relay):
        self.proc = subprocess.Popen(["bash", "-c", "exec " + cmd], stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.q = queue.Queue()
        self.closed = False
        self.unclean = UNCLEAN
        self.relay = relay
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._stderr, daemon=True).start()

    def _read(self):
        for raw in self.proc.stdout:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if not line.strip():
                continue
            m = parse(line)
            msgs = m if isinstance(m, list) and m else [m]  # a batch reply is JSON-RPC too
            if any(not isinstance(x, dict) or "_unparsed" in x or "jsonrpc" not in x for x in msgs):
                self.unclean.append(short(line, 120))
            self.q.put(m)
        self.closed = True
        self.q.put(None)

    def _stderr(self):
        for raw in self.proc.stderr:
            self.relay(raw.decode("utf-8", "replace").rstrip("\r\n"))

    def send(self, text, **_):
        try:
            self.proc.stdin.write(text.encode("utf-8") + b"\n")
            self.proc.stdin.flush()
        except (BrokenPipeError, OSError) as e:
            raise Fail("write to the agent's stdin failed: %s" % e)
        return None

    def recv(self, timeout):
        try:
            return self.q.get(timeout=max(0.0, timeout))
        except queue.Empty:
            return None

    def close(self):
        try:
            self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.proc.kill()


class StdioServerChannel:
    """Agent side over our own stdin/stdout."""

    def __init__(self):
        self.q = queue.Queue()
        self.closed = False
        self.lock = threading.Lock()
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for raw in sys.stdin.buffer:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if line.strip():
                self.q.put(parse(line))
        self.closed = True
        self.q.put(None)

    def send(self, text, **_):
        with self.lock:
            sys.stdout.buffer.write(text.encode("utf-8") + b"\n")
            sys.stdout.buffer.flush()

    def recv(self, timeout):
        try:
            return self.q.get(timeout=max(0.0, timeout))
        except queue.Empty:
            return None


# ---------------------------------------------------------------- WebSocket framing


def ws_frame(opcode, payload, mask):
    head = bytearray([0x80 | opcode])
    n = len(payload)
    mbit = 0x80 if mask else 0
    if n < 126:
        head.append(mbit | n)
    elif n < 65536:
        head.append(mbit | 126)
        head += struct.pack("!H", n)
    else:
        head.append(mbit | 127)
        head += struct.pack("!Q", n)
    if not mask:
        return bytes(head) + payload
    if n > 1024 * 1024:
        # A zero masking key is legal and leaves the payload as is: no 16 MB XOR in Python.
        return bytes(head) + b"\0\0\0\0" + payload
    key = os.urandom(4)
    rep = (key * (n // 4 + 1))[:n]
    masked = (int.from_bytes(payload, "big") ^ int.from_bytes(rep, "big")).to_bytes(n, "big") if n else b""
    return bytes(head) + key + masked


class WsChannel:
    """A WebSocket connection over a connected socket, either side."""

    def __init__(self, sock, rfile, client):
        self.sock = sock
        self.rfile = rfile
        self.client = client  # clients mask their frames
        self.q = queue.Queue()
        self.closed = False
        self.close_code = None
        self.lock = threading.Lock()
        threading.Thread(target=self._read, daemon=True).start()

    @staticmethod
    def connect(url, timeout=10):
        u = urllib.parse.urlsplit(url)
        sock = socket.create_connection((u.hostname, u.port or 80), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        req = ("GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n") % (u.path or "/", u.hostname,
                                                                             u.port or 80, key)
        sock.sendall(req.encode())
        rfile = sock.makefile("rb")
        status = rfile.readline().decode("latin-1").strip()
        headers = {}
        while True:
            line = rfile.readline().decode("latin-1").strip()
            if not line:
                break
            k, _, v = line.partition(":")
            headers[k.strip().lower()] = v.strip()
        if " 101 " not in status + " ":
            sock.close()
            raise Fail("WebSocket upgrade answered %r" % status)
        sock.settimeout(None)
        ch = WsChannel(sock, rfile, client=True)
        ch.connection_id = headers.get("acp-connection-id")
        return ch

    def _read_exact(self, n):
        data = self.rfile.read(n)
        if data is None or len(data) < n:
            raise EOFError()
        return data

    def _read(self):
        parts = []
        try:
            while True:
                b0, b1 = self._read_exact(2)
                opcode, fin = b0 & 0x0F, b0 & 0x80
                n = b1 & 0x7F
                if n == 126:
                    n = struct.unpack("!H", self._read_exact(2))[0]
                elif n == 127:
                    n = struct.unpack("!Q", self._read_exact(8))[0]
                key = self._read_exact(4) if b1 & 0x80 else None
                payload = self._read_exact(n) if n else b""
                if key and n:
                    rep = (key * (n // 4 + 1))[:n]
                    payload = (int.from_bytes(payload, "big") ^ int.from_bytes(rep, "big")).to_bytes(n, "big")
                if opcode == 0x8:
                    self.close_code = struct.unpack("!H", payload[:2])[0] if len(payload) >= 2 else 1005
                    try:
                        self._send_frame(0x8, payload[:2])
                    except OSError:
                        pass
                    break
                if opcode == 0x9:
                    self._send_frame(0xA, payload)
                    continue
                if opcode == 0xA:
                    continue
                if opcode in (0x1, 0x2):
                    parts = [(opcode, payload)]
                elif opcode == 0x0:
                    parts.append((0x0, payload))
                if fin and parts:
                    first = parts[0][0]
                    data = b"".join(p for _, p in parts)
                    parts = []
                    if first == 0x1:  # binary frames are ignored (RFD)
                        self.q.put(parse(data.decode("utf-8", "replace")))
        except (EOFError, OSError, ValueError):
            pass
        self.closed = True
        self.q.put(None)

    def _send_frame(self, opcode, payload):
        with self.lock:
            self.sock.sendall(ws_frame(opcode, payload, self.client))

    def send(self, text, **_):
        try:
            self._send_frame(0x1, text.encode("utf-8"))
        except OSError as e:
            raise Fail("WebSocket send failed: %s" % e)

    def recv(self, timeout):
        try:
            return self.q.get(timeout=max(0.0, timeout))
        except queue.Empty:
            return None

    def close(self):
        try:
            self._send_frame(0x8, struct.pack("!H", 1000))
        except OSError:
            pass
        deadline = time.time() + 2
        while not self.closed and time.time() < deadline:
            time.sleep(0.02)
        try:
            self.sock.close()
        except OSError:
            pass


# ---------------------------------------------------------------- Streamable HTTP, client side


class HttpChannel:
    """Client side of Streamable HTTP over HTTP/1.1 (the stdlib has no HTTP/2): POST each frame,
    read the connection-scoped and session-scoped SSE streams into one queue."""

    def __init__(self, url):
        u = urllib.parse.urlsplit(url)
        self.host, self.port, self.path = u.hostname, u.port or 80, u.path or "/"
        self.q = queue.Queue()
        self.closed = False
        self.connection_id = None
        self.streams = {}  # session id (None: the connection stream) -> HTTPConnection
        self.inbound_scope = {}  # id of an agent request -> session id of the stream it came on
        self.post_conn = None

    def http(self, method, body=None, headers=None, timeout=30):
        """One request on its own connection: (status, headers, body bytes)."""
        c = http.client.HTTPConnection(self.host, self.port, timeout=timeout)
        try:
            c.request(method, self.path, body=body, headers=headers or {})
            r = c.getresponse()
            data = r.read()
            return r.status, {k.lower(): v for k, v in r.getheaders()}, data
        finally:
            c.close()

    def headers(self, session=None, extra=None):
        h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        if self.connection_id:
            h["Acp-Connection-Id"] = self.connection_id
        if session:
            h["Acp-Session-Id"] = session
        h.update(extra or {})
        return {k: v for k, v in h.items() if v is not None}

    def initialize(self, params, rid=0):
        body = dumps({"jsonrpc": "2.0", "id": rid, "method": "initialize", "params": params})
        status, hdrs, data = self.http("POST", body.encode(), self.headers())
        if status != 200:
            raise Fail("initialize POST answered %d: %s" % (status, short(data.decode("utf-8", "replace"))))
        self.connection_id = hdrs.get("acp-connection-id")
        if not self.connection_id:
            raise Fail("initialize answered without an Acp-Connection-Id header")
        self.open_stream(None)
        return parse(data.decode("utf-8"))

    def open_stream(self, session):
        if session in self.streams:
            return
        c = http.client.HTTPConnection(self.host, self.port, timeout=None)
        h = {"Accept": "text/event-stream", "Acp-Connection-Id": self.connection_id}
        if session:
            h["Acp-Session-Id"] = session
        c.request("GET", self.path, headers=h)
        r = c.getresponse()
        if r.status != 200:
            body = r.read()
            c.close()
            raise Fail("GET %s stream answered %d: %s" % (session or "connection", r.status, short(body.decode())))
        self.streams[session] = c
        threading.Thread(target=self._sse, args=(r, session), daemon=True).start()

    def _sse(self, resp, session):
        data = []
        try:
            while True:
                line = resp.readline()
                if not line:
                    break
                line = line.decode("utf-8", "replace").rstrip("\r\n")
                if line == "":
                    if data:
                        m = parse("\n".join(data))
                        if is_request(m):
                            self.inbound_scope[dumps(m["id"])] = session
                        self.q.put(m)
                        data = []
                elif line.startswith("data:"):
                    data.append(line[5:].lstrip(" ") if line.startswith("data: ") else line[5:])
        except (OSError, ValueError, http.client.HTTPException):
            pass
        if session is None:
            self.closed = True
            self.q.put(None)

    def send(self, text, session=None, extra_headers=None):
        """POSTs one frame. The session header comes from the frame's params.sessionId, or for a
        response from the stream its request came on. Returns (status, body)."""
        if session is None:
            m = parse(text)
            if isinstance(m, dict):
                p = m.get("params")
                if isinstance(p, dict) and isinstance(p.get("sessionId"), str):
                    session = p["sessionId"]
                elif is_response(m) and "id" in m:
                    session = self.inbound_scope.get(dumps(m["id"]))
        status, _, data = self.http("POST", text.encode("utf-8"), self.headers(session, extra_headers))
        return status, data.decode("utf-8", "replace")

    def recv(self, timeout):
        try:
            return self.q.get(timeout=max(0.0, timeout))
        except queue.Empty:
            return None

    def close(self):
        if self.connection_id:
            try:
                self.http("DELETE", None, {"Acp-Connection-Id": self.connection_id}, timeout=5)
            except OSError:
                pass
        for c in self.streams.values():
            try:
                c.close()
            except OSError:
                pass


# =====================================================================================
# Client role
# =====================================================================================


class Probe:
    """The raw client's view of one connection: sends frames, waits for answers, and answers the
    agent's own requests (permission: allow; fs: write/read the file) unless a case overrides."""

    def __init__(self, transport, url, relay):
        self.transport = transport
        self.url = url
        self.relay = relay
        self.next_id = 1000
        self.updates = []  # (sessionId, update)
        self.answer = None  # case override: fn(request) -> frame text or None for the default
        self.agent_requests = []
        self.dir = tempfile.mkdtemp(prefix="acp-raw-")
        self.ch = None

    def open(self):
        if self.transport == "stdio":
            self.ch = StdioChannel(os.environ["AGENT_CMD"], self.relay)
            init = self.call("initialize", CLIENT_INIT)
        elif self.transport == "ws":
            self.ch = WsChannel.connect(self.url)
            init = self.call("initialize", CLIENT_INIT)
        else:
            self.ch = HttpChannel(self.url)
            init = self.ch.initialize(CLIENT_INIT, rid=self.new_id())
        if not isinstance(init, dict) or not isinstance(init.get("result"), dict):
            raise Fail("initialize failed: %s" % short(init))
        self.init = init["result"]
        return self

    def new_id(self):
        self.next_id += 1
        return self.next_id

    def send(self, text, **kw):
        return self.ch.send(text, **kw)

    def frame(self, method, params=None, rid="auto"):
        m = {"jsonrpc": "2.0"}
        if rid != "none":
            m["id"] = self.new_id() if rid == "auto" else rid
        m["method"] = method
        if params is not None:
            m["params"] = params
        return m

    def handle(self, m):
        """Anything that is not the awaited reply: record updates, answer agent requests."""
        if is_notification(m):
            if m["method"] == "session/update" and isinstance(m.get("params"), dict):
                self.updates.append((m["params"].get("sessionId"), m["params"].get("update")))
            return
        if is_request(m):
            self.agent_requests.append(m)
            text = self.answer(m) if self.answer else None
            self.send(text if text is not None else dumps(self.default_answer(m)))

    def default_answer(self, m):
        p = m.get("params") if isinstance(m.get("params"), dict) else {}
        meth = m["method"]
        if meth == "session/request_permission":
            opts = p.get("options") or []
            pick = next((o for o in opts if isinstance(o, dict) and o.get("kind") == "allow_once"),
                        opts[0] if opts else {"optionId": "allow"})
            return {"jsonrpc": "2.0", "id": m["id"], "result": {"outcome": {"outcome": "selected",
                                                                           "optionId": pick.get("optionId")}}}
        if meth == "fs/write_text_file":
            try:
                with open(p["path"], "w") as f:
                    f.write(p.get("content", ""))
                return {"jsonrpc": "2.0", "id": m["id"], "result": {}}
            except (OSError, KeyError, TypeError) as e:
                return {"jsonrpc": "2.0", "id": m["id"], "error": {"code": -32603, "message": str(e)}}
        if meth == "fs/read_text_file":
            try:
                with open(p["path"]) as f:
                    return {"jsonrpc": "2.0", "id": m["id"], "result": {"content": f.read()}}
            except (OSError, KeyError, TypeError) as e:
                return {"jsonrpc": "2.0", "id": m["id"], "error": {"code": -32002, "message": str(e)}}
        return {"jsonrpc": "2.0", "id": m["id"], "error": {"code": METHOD_NOT_FOUND, "message": "Method not found"}}

    def wait(self, pred, timeout=None, what="a reply"):
        deadline = time.time() + (STEP_TIMEOUT if timeout is None else timeout)
        while True:
            left = deadline - time.time()
            if left <= 0:
                raise Fail("TIMEOUT after %d ms waiting for %s" % (int((timeout or STEP_TIMEOUT) * 1000), what))
            m = self.ch.recv(left)
            if m is None:
                if self.ch.closed:
                    raise Fail("the connection closed while waiting for %s%s" % (what, self.close_note()))
                continue
            if pred(m):
                return m
            self.handle(m)

    def close_note(self):
        code = getattr(self.ch, "close_code", None)
        rc = self.ch.proc.poll() if isinstance(self.ch, StdioChannel) else None
        return (" (close code %s)" % code if code else "") + (" (agent exit %s)" % rc if rc is not None else "")

    def reply_to(self, rid, timeout=None):
        return self.wait(lambda m: is_response(m) and "id" in m and same_id(m["id"], rid), timeout,
                         "the reply to id %s" % dumps(rid))

    def call(self, method, params, timeout=None):
        f = self.frame(method, params)
        self.send(dumps(f))
        return self.reply_to(f["id"], timeout)

    def drain(self, seconds):
        """Handles what arrives for a while; returns the responses (none were awaited)."""
        got = []
        deadline = time.time() + seconds
        while time.time() < deadline:
            m = self.ch.recv(deadline - time.time())
            if m is None:
                if self.ch.closed:
                    raise Fail("the connection closed%s" % self.close_note())
                continue
            if is_response(m) or (isinstance(m, dict) and "_unparsed" in m):
                got.append(m)
            else:
                self.handle(m)
        return got

    def quiet(self):
        """The responses that arrive before a fence: a session/new sent after the frame under
        test, plus FENCE_GRACE. A receiver answers frames in order, so an answer to the frame
        under test comes first; the grace covers one that a handler sends late."""
        fence = self.frame("session/new", {"cwd": self.dir, "mcpServers": []})
        self.send(dumps(fence))
        got = []
        deadline = time.time() + STEP_TIMEOUT
        fenced = None
        while True:
            left = (fenced + FENCE_GRACE if fenced else deadline) - time.time()
            if left <= 0:
                if fenced:
                    return got
                raise Fail("TIMEOUT after %d ms waiting for the fence session/new" % int(STEP_TIMEOUT * 1000))
            m = self.ch.recv(left)
            if m is None:
                if self.ch.closed:
                    raise Fail("the connection closed%s" % self.close_note())
                continue
            if is_response(m) and "id" in m and same_id(m["id"], fence["id"]):
                fenced = time.time()
            elif is_response(m) or (isinstance(m, dict) and "_unparsed" in m):
                got.append(m)
            else:
                self.handle(m)

    def new_session(self):
        r = self.call("session/new", {"cwd": self.dir, "mcpServers": []})
        sid = (r.get("result") or {}).get("sessionId") if isinstance(r.get("result"), dict) else None
        if not isinstance(sid, str) or not sid:
            raise Fail("session/new failed: %s" % short(r))
        if self.transport == "http":
            self.ch.open_stream(sid)
        return sid

    def prompt(self, sid, text, timeout=None):
        return self.call("session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text": text}]}, timeout)

    def chunks(self, sid):
        out = []
        for s, u in self.updates:
            if s == sid and isinstance(u, dict) and u.get("sessionUpdate") == "agent_message_chunk":
                c = u.get("content") or {}
                if c.get("type") == "text":
                    out.append(c.get("text"))
        return out

    def alive(self):
        self.new_session()

    def close(self):
        if self.ch:
            self.ch.close()


def expect_error(m, code, rid="any"):
    """An error response with this code (and, unless rid is "any", this exact id)."""
    if not is_response(m) or "error" not in m:
        raise Fail("expected error %d, got %s" % (code, short(m)))
    if rid != "any" and not ("id" in m and same_id(m["id"], rid)):
        raise Fail("error %s answered with id %s, expected %s" % (error_code(m), dumps(m.get("id", "<missing>")),
                                                                 dumps(rid)))
    if error_code(m) != code:
        raise Fail("expected error %d, got %s" % (code, short(m)))
    if not isinstance(m["error"].get("message"), str):
        raise Fail("error %d without a string message (schema Error.message is required): %s" % (code, short(m)))
    return "error %d id %s: %s" % (code, dumps(m.get("id")), short(m["error"].get("message"), 80))


def null_id_error(p, code, timeout=None):
    """The answer to an unreadable frame: an error response with "id": null."""
    m = p.wait(lambda x: is_response(x) and "id" in x and x["id"] is None, timeout,
               "an error response with \"id\": null")
    return expect_error(m, code, None)


def http_status(p, status_got, want, body):
    if status_got not in want:
        raise Fail("HTTP %d, expected %s: %s" % (status_got, "/".join(map(str, want)), short(body, 120)))
    return "HTTP %d" % status_got


# ---------------------------------------------------------------- client-role cases
# Each takes a Probe (opened and initialized), returns a PASS detail or raises Fail. The
# connection-survived check runs after it unless the case says otherwise.

def unreadable_case(text, code, http_want):
    def case(p):
        if p.transport == "http":
            # The RFD defines no status for an unreadable body: refused with a 4xx (or the 501 it
            # names for a batch), or accepted and answered like on the other transports.
            status, body = p.send(text)
            if status == 202:
                return "HTTP 202, then " + null_id_error(p, code)
            return http_status(p, status, http_want, body)
        p.send(text)
        return null_id_error(p, code)
    return case


def c_invalid_request_method_type(p):
    f = '{"jsonrpc":"2.0","id":"raw-m","method":42}'
    if p.transport == "http":
        status, body = p.send(f)
        if status != 202:
            return http_status(p, status, (400,), body)
        # Accepted on HTTP: the -32600 must then arrive on the connection stream.
    else:
        p.send(f)
    m = p.wait(lambda x: is_response(x) and "id" in x and (x["id"] is None or x["id"] == "raw-m"),
               what="an error response (id \"raw-m\" or null)")
    return expect_error(m, INVALID_REQUEST)


def c_invalid_request_id_type(p):
    f = dumps({"jsonrpc": "2.0", "id": {"x": 1}, "method": "session/new", "params": {"cwd": p.dir, "mcpServers": []}})
    if p.transport == "http":
        status, body = p.send(f)
        if status == 202:
            # Accepted on HTTP: the error must then arrive on a stream.
            m = p.wait(lambda x: is_response(x) and "id" in x and x["id"] in (None, {"x": 1}),
                       what="an error response for the object id")
            return expect_error(m, INVALID_REQUEST)
        return http_status(p, status, (400,), body)
    p.send(f)
    m = p.wait(lambda x: is_response(x) and "id" in x and (x["id"] is None or x["id"] == {"x": 1}),
               what="an error response for the object id")
    if m["id"] is not None:
        raise Fail("answered with the object id echoed (JSON-RPC: id must be a string, number or null; "
                   "an invalid request is answered with id null): %s" % short(m))
    return expect_error(m, INVALID_REQUEST, None)


def c_invalid_request_version(p):
    f = dumps({"jsonrpc": "1.0", "id": "raw-v", "method": "session/new", "params": {"cwd": p.dir, "mcpServers": []}})
    if p.transport == "http":
        status, body = p.send(f)
        if status != 202:
            return http_status(p, status, (400,), body)
    else:
        p.send(f)
    m = p.wait(lambda x: is_response(x) and "id" in x and x["id"] in (None, "raw-v"),
               what="the answer to a \"jsonrpc\": \"1.0\" request")
    if "result" in m:
        raise Fail("a request with \"jsonrpc\": \"1.0\" was executed (JSON-RPC 2.0: jsonrpc MUST be exactly "
                   "\"2.0\"; expected -32600): %s" % short(m))
    return expect_error(m, INVALID_REQUEST)


def c_batch(p):
    f = "[" + dumps({"jsonrpc": "2.0", "id": "raw-b", "method": "session/new",
                     "params": {"cwd": p.dir, "mcpServers": []}}) + "]"
    if p.transport == "http":
        status, body = p.send(f)
        return http_status(p, status, (501,), body)  # RFD: "Batch JSON-RPC requests return 501."
    p.send(f)
    m = p.wait(lambda x: isinstance(x, list) or (is_response(x) and "id" in x and x["id"] in (None, "raw-b")),
               what="an answer to a one-element batch")
    if isinstance(m, list):
        return "answered as a batch: %s" % short(m, 100)
    if "result" in m:
        raise Fail("the batch's request was answered unbatched (JSON-RPC 2.0: a batch is answered with an "
                   "array, or rejected): %s" % short(m))
    return expect_error(m, INVALID_REQUEST)


def c_method_not_found(method):
    def case(p):
        f = p.frame(method, {})
        if p.transport == "http":
            status, body = p.send(dumps(f))
            if status != 202:
                raise Fail("POST answered %d (expected 202 and a -32601 on a stream): %s" % (status, short(body)))
        else:
            p.send(dumps(f))
        return expect_error(p.reply_to(f["id"]), METHOD_NOT_FOUND, f["id"])
    return case


def c_no_reply(frame_fn):
    def case(p):
        f = frame_fn(p)
        if p.transport == "http":
            status, body = p.send(f)
            if status not in (202, 400):
                raise Fail("POST answered %d (expected 202): %s" % (status, short(body)))
        else:
            p.send(f)
        got = p.quiet()
        if got:
            raise Fail("answered a notification or response, which JSON-RPC 2.0 forbids: %s" % short(got[0]))
        return "no reply"
    return case


def c_id(rid):
    def case(p):
        f = {"jsonrpc": "2.0", "id": rid, "method": "session/new", "params": {"cwd": p.dir, "mcpServers": []}}
        text = dumps(f)
        if p.transport == "http":
            status, body = p.send(text)
            if status != 202:
                raise Fail("POST answered %d: %s" % (status, short(body)))
        else:
            p.send(text)
        # Any response: the flow is sequential, so one with another id is this request's, changed.
        m = p.wait(lambda x: is_response(x) and "id" in x, what="the reply to id %s" % dumps(rid))
        if not same_id(m["id"], rid):
            raise Fail("answered with id %s for request id %s: %s" % (dumps(m["id"]), dumps(rid), short(m)))
        if "result" not in m:
            raise Fail("session/new with id %s failed: %s" % (dumps(rid), short(m)))
        return "answered with id %s" % dumps(m["id"])
    return case


def c_invalid_params(build):
    def case(p):
        method, params = build(p)
        f = p.frame(method, params)
        if p.transport == "http":
            status, body = p.send(dumps(f))
            if status != 202:
                return http_status(p, status, (400,), body)
        else:
            p.send(dumps(f))
        return expect_error(p.reply_to(f["id"]), INVALID_PARAMS, f["id"])
    return case


def prompt_params(p):
    sid = p.new_session()
    return "session/prompt", {"sessionId": sid, "prompt": "hello"}


def c_callee(prompt_text, answer, check):
    """The agent calls the client during a prompt; the raw client answers per `answer`."""
    def case(p):
        sid = p.new_session()
        seen = []

        def override(m):
            seen.append(m)
            return answer(m)
        p.answer = override
        try:
            r = p.prompt(sid, prompt_text.replace("{dir}", p.dir))
        finally:
            p.answer = None
        if not seen:
            raise Fail("the agent never called the client: %s" % short(r))
        return check(p, sid, r, seen)
    return case


def ans_error_without_message(m):
    return dumps({"jsonrpc": "2.0", "id": m["id"], "error": {"code": -32603}})


def ans_null_result(m):
    return dumps({"jsonrpc": "2.0", "id": m["id"], "result": None})


def chk_fs_write_ok(p, sid, r, seen):
    if "result" not in r or r["result"].get("stopReason") != "end_turn":
        raise Fail("the prompt did not end end_turn: %s" % short(r))
    end = time.time() + 1
    while "fs write ok" not in p.chunks(sid) and time.time() < end:
        p.drain(0.05)
    if "fs write ok" not in p.chunks(sid):
        raise Fail("no chunk \"fs write ok\" (the agent refused \"result\": null for the all-optional "
                   "WriteTextFileResponse): %s" % p.chunks(sid))
    return "\"result\": null accepted for fs/write_text_file; chunk \"fs write ok\""


def chk_fs_read_error(p, sid, r, seen):
    end = time.time() + 1
    while not any(c.startswith("fs read error") for c in p.chunks(sid) if c) and time.time() < end:
        p.drain(0.05)
    chunks = p.chunks(sid)
    if "error" in r:
        # The agent program let the failed call fail its prompt: still an error, not a hang.
        return "the prompt failed with %s (the agent got an error, not a hang)" % short(r["error"], 120)
    if not any(c.startswith("fs read error") for c in chunks if c):
        raise Fail("the agent did not get an error from fs/read_text_file: chunks %s" % chunks)
    return "the agent got an error, not a hang: chunk %s" % short([c for c in chunks if c.startswith("fs read error")][0], 60)


def raw_post(p, body, timeout=30, session=None):
    """A POST written by hand, so a server that answers before reading the whole body (413 on
    Content-Length) is still heard: (status, body)."""
    sock = socket.create_connection((p.ch.host, p.ch.port), timeout=timeout)
    try:
        head = ("POST %s HTTP/1.1\r\nHost: %s:%d\r\nContent-Type: application/json\r\n"
                "Accept: application/json, text/event-stream\r\nAcp-Connection-Id: %s\r\n%sContent-Length: %d\r\n"
                "Connection: close\r\n\r\n") % (p.ch.path, p.ch.host, p.ch.port, p.ch.connection_id,
                                                  "Acp-Session-Id: %s\r\n" % session if session else "", len(body))
        try:
            sock.sendall(head.encode() + body)
        except OSError:
            pass  # refused mid-body: the status is already on the wire
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = sock.recv(65536)
            if not chunk:
                break
            data += chunk
        line = data.split(b"\r\n", 1)[0].decode("latin-1")
        parts = line.split(" ")
        if len(parts) < 2 or not parts[1].isdigit():
            raise Fail("no HTTP status for a %d-byte POST (got %r)" % (len(body), line))
        return int(parts[1]), data.split(b"\r\n\r\n", 1)[-1].decode("utf-8", "replace")
    finally:
        sock.close()


# Codes the schema's ErrorCode list gives a meaning that a rejected concurrent prompt does not
# have: -32000 is "authentication required", -32002 "resource not found", -32800 "request
# cancelled"; -32700 and -32601 are about the frame and the method.
WRONG_FOR_CONCURRENT = {-32000: "authentication required", -32002: "resource not found",
                        -32800: "request cancelled", -32700: "parse error", -32601: "method not found"}


def c_prompt_concurrent(p):
    """A second session/prompt while the first is still running on the same session. The spec
    has no rule for it; whatever the agent does, an error must not claim a meaning the schema's
    ErrorCode list gives to another condition, and the first prompt must still complete."""
    sid = p.new_session()
    a = p.frame("session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text": "#permission allow"}]})
    p.send(dumps(a))
    req = p.wait(lambda m: is_request(m) and m.get("method") == "session/request_permission",
                 what="the permission request of the first prompt (it holds the turn open)")
    b = p.frame("session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text": "concurrent"}]})
    p.send(dumps(b))
    rb = p.reply_to(b["id"])
    p.send(dumps(p.default_answer(req)))
    ra = p.reply_to(a["id"])
    if (ra.get("result") or {}).get("stopReason") != "end_turn":
        raise Fail("the first prompt did not complete end_turn after the second was answered: %s" % short(ra))
    if "result" in rb:
        return "the second prompt was served (stopReason %s); the first completed" % rb["result"].get("stopReason")
    code = error_code(rb)
    if not isinstance(code, int):
        raise Fail("the second prompt's error has no integer code: %s" % short(rb))
    if code in WRONG_FOR_CONCURRENT:
        raise Fail("the second prompt was rejected with %d, which the schema's ErrorCode list defines as \"%s\": %s"
                   % (code, WRONG_FOR_CONCURRENT[code], short(rb, 160)))
    return "the second prompt was rejected with %d; the first completed end_turn" % code


def big_prompt(p, sid, size):
    """A session/prompt frame of exactly `size` bytes: plain text, so an agent echoes it."""
    f = p.frame("session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text": ""}]})
    pad = size - len(dumps(f))
    f["params"]["prompt"][0]["text"] = "x" * pad
    return f


def c_oversized(p):
    """A valid prompt frame of 16 MB + 1 byte. No spec text limits a message; the Java agent
    limits a POST body and a WebSocket message to 16 MB (G0c). Either the transport refuses it
    the way HTTP and WebSocket define (413; close 1009) and the server takes a new connection,
    or the prompt is answered and the connection survives. stdio has no limit: answered."""
    big = MAX_FRAME + 1
    sid = p.new_session()
    f = big_prompt(p, sid, big)
    text = dumps(f)
    if p.transport == "http":
        status, body = raw_post(p, text.encode("utf-8"), session=sid)
        if status == 413:
            return "HTTP 413 for a %d-byte prompt" % big
        if status != 202:
            raise Fail("HTTP %d for a %d-byte prompt (expected 413, or 202 and an answer): %s" % (status, big, short(body)))
        r = p.reply_to(f["id"], timeout=max(STEP_TIMEOUT, 30))
        return "accepted (202) and answered (%s)" % ("error %s" % error_code(r) if "error" in r else "result")
    if p.transport == "ws":
        try:
            p.ch.send(text)
        except Fail as e:
            note = str(e)  # the server may close before the whole frame is written
            deadline = time.time() + 2
            while not p.ch.closed and time.time() < deadline:
                time.sleep(0.05)
            if p.ch.close_code is None:
                raise Fail("the connection was dropped without a close frame (%s); expected close 1009" % note)
        deadline = time.time() + max(STEP_TIMEOUT, 30)
        r = None
        while not p.ch.closed and time.time() < deadline:
            m = p.ch.recv(0.2)
            if m is None:
                continue
            if is_response(m) and same_id(m.get("id"), f["id"]):
                r = m
                break
            if not is_response(m):
                p.handle(m)
        if r is not None:
            return "answered (%s)" % ("error %s" % error_code(r) if "error" in r else "result")
        if not p.ch.closed:
            raise Fail("TIMEOUT: a %d-byte message was neither answered nor refused (close 1009)" % big)
        if p.ch.close_code != 1009:
            raise Fail("closed with code %s, expected 1009 (message too big)" % p.ch.close_code)
        # The connection is gone by design; the server must still take a new one.
        p.close()
        p.open()
        return "closed with 1009 for a %d-byte message; a new connection initializes" % big
    p.send(text)
    r = p.reply_to(f["id"], timeout=max(STEP_TIMEOUT, 30))
    return "answered a %d-byte line (%s)" % (big, "error %s" % error_code(r) if "error" in r else "result")


def c_parse_error_large(p):
    """16 MB + 1 byte that is not JSON, on stdio: a parse error like any other."""
    big = MAX_FRAME + 1
    p.send("x" * big)
    return null_id_error(p, PARSE_ERROR) + " (for a %d-byte line)" % big


def c_eof_answers(p):
    """Requests written just before stdin closes are still answered before the agent exits.
    No spec text requires it; an agent that exits at EOF with answers unwritten loses replies a
    client that pipes requests and closes stdin (`printf ... | agent`) waits for. Racy by
    nature, so evidence only (EVIDENCE_ONLY), never a gate."""
    proc = subprocess.Popen(["bash", "-c", "exec " + os.environ["AGENT_CMD"]], stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    frames = [dumps({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": CLIENT_INIT})]
    frames += [dumps({"jsonrpc": "2.0", "id": 2 + i, "method": "session/new",
                      "params": {"cwd": p.dir, "mcpServers": []}}) for i in range(5)]
    try:
        out, _ = proc.communicate(("\n".join(frames) + "\n").encode(), timeout=STEP_TIMEOUT)
    except subprocess.TimeoutExpired:
        proc.kill()
        raise Fail("TIMEOUT: the agent did not exit within %d ms of stdin's EOF" % int(STEP_TIMEOUT * 1000))
    ids = set()
    for line in out.decode("utf-8", "replace").splitlines():
        m = parse(line)
        if is_response(m) and isinstance(m.get("id"), int):
            ids.add(m["id"])
    missing = sorted(set(range(1, 7)) - ids)
    if missing:
        raise Fail("the agent exited at EOF with %d of 6 requests unanswered (ids %s)" % (len(missing), missing))
    return "all 6 requests written before EOF were answered"


def c_stdout_clean(p):
    """Every line a stdio agent wrote to stdout in this run was one JSON-RPC message
    (transports.mdx: the agent MUST NOT write anything else to stdout)."""
    if UNCLEAN:
        raise Fail("non-JSON-RPC lines on the agent's stdout: %s" % UNCLEAN[:3])
    return "every stdout line of the agent was a JSON-RPC message"


def h_content_type(p):
    status, _, body = p.ch.http("POST", dumps(p.frame("session/new", {"cwd": p.dir, "mcpServers": []})).encode(),
                                p.ch.headers(extra={"Content-Type": "text/plain"}))
    return http_status(p, status, (415,), body.decode())


def h_get_accept(p):
    status, _, body = p.ch.http("GET", None, {"Accept": "application/json", "Acp-Connection-Id": p.ch.connection_id})
    return http_status(p, status, (406,), body.decode())


def h_missing_connection(p):
    status, _, body = p.ch.http("POST", dumps(p.frame("session/new", {"cwd": p.dir, "mcpServers": []})).encode(),
                                {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"})
    return http_status(p, status, (400,), body.decode())


def h_unknown_connection(p):
    status, _, body = p.ch.http("POST", dumps(p.frame("session/new", {"cwd": p.dir, "mcpServers": []})).encode(),
                                p.ch.headers(extra={"Acp-Connection-Id": "no-such-connection"}))
    return http_status(p, status, (404,), body.decode())


def h_unknown_session_stream(p):
    try:
        status, _, body = p.ch.http("GET", None, {"Accept": "text/event-stream", "Acp-Connection-Id": p.ch.connection_id,
                                                  "Acp-Session-Id": "no-such-session"}, timeout=3)
    except (socket.timeout, TimeoutError):
        raise Fail("no answer within 3 s: the stream for an unknown Acp-Session-Id was held open (RFD: 404)")
    return http_status(p, status, (404,), body.decode())


def h_missing_session_header(p):
    sid = p.new_session()
    f = p.frame("session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text": "hello"}]})
    status, _, body = p.ch.http("POST", dumps(f).encode(), p.ch.headers(session=None))
    if status == 202:
        p.reply_to(f["id"])  # drain the answer before the liveness check
    return http_status(p, status, (400,), body.decode())


def h_delete_missing(p):
    status, _, body = p.ch.http("DELETE", None, {})
    return http_status(p, status, (400,), body.decode())


def nf(method, params_fn=lambda p: {}):
    return lambda p: dumps({"jsonrpc": "2.0", "method": method, "params": params_fn(p)})


ALL = ("stdio", "http", "ws")
# (case id, transports, function). Order is run order.
CLIENT_CASES = [
    ("raw.parse-error", ALL, unreadable_case('{"jsonrpc":"2.0","id":1,"method":', PARSE_ERROR, (400,))),
    ("raw.invalid-request.empty-object", ALL, unreadable_case("{}", INVALID_REQUEST, (400,))),
    ("raw.invalid-request.not-object", ALL, unreadable_case("42", INVALID_REQUEST, (400,))),
    ("raw.invalid-request.null", ALL, unreadable_case("null", INVALID_REQUEST, (400,))),
    ("raw.invalid-request.empty-batch", ALL, unreadable_case("[]", INVALID_REQUEST, (501,))),
    ("raw.invalid-request.method-type", ALL, c_invalid_request_method_type),
    ("raw.invalid-request.id-type", ALL, c_invalid_request_id_type),
    ("raw.invalid-request.version", ALL, c_invalid_request_version),
    ("raw.batch", ALL, c_batch),
    ("raw.method-not-found", ALL, c_method_not_found("interop/no_such_method")),
    ("raw.method-not-found.ext", ALL, c_method_not_found("_interop/no_such_method")),
    ("raw.notification.unknown", ALL, c_no_reply(nf("interop/no_such_notification"))),
    ("raw.notification.unknown-ext", ALL, c_no_reply(nf("_interop/note", lambda p: {"n": 1}))),
    ("raw.notification.bad-params", ALL, c_no_reply(lambda p: dumps({"jsonrpc": "2.0", "method": "session/cancel",
                                                                     "params": {"sessionId": 42}}))),
    ("raw.id.null", ALL, c_id(None)),
    ("raw.id.string", ALL, c_id("raw-id-1")),
    ("raw.id.zero", ALL, c_id(0)),
    ("raw.id.large", ALL, c_id(9007199254740993)),
    ("raw.invalid-params.type", ALL, c_invalid_params(lambda p: ("session/new", {"cwd": 42, "mcpServers": []}))),
    ("raw.invalid-params.by-position", ALL, c_invalid_params(lambda p: ("session/new", [p.dir, []]))),
    ("raw.invalid-params.missing", ALL, c_invalid_params(lambda p: ("session/new", {"mcpServers": []}))),
    ("raw.invalid-params.prompt", ALL, c_invalid_params(prompt_params)),
    ("raw.stray-response.unknown-id", ALL, c_no_reply(lambda p: '{"jsonrpc":"2.0","id":"no-such-request","result":{}}')),
    ("raw.stray-response.null-id", ALL, c_no_reply(lambda p: '{"jsonrpc":"2.0","id":null,"result":{}}')),
    ("raw.stray-response.null-id-error", ALL, c_no_reply(
        lambda p: '{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}')),
    ("raw.callee.error-without-message", ALL, c_callee("#fs read {dir}/no-such-file.txt", ans_error_without_message,
                                                       chk_fs_read_error)),
    ("raw.callee.result-null-required", ALL, c_callee("#fs read {dir}/no-such-file.txt", ans_null_result,
                                                      chk_fs_read_error)),
    ("raw.callee.result-null-optional", ALL, c_callee("#fs write {dir}/raw-null.txt raw", ans_null_result,
                                                      chk_fs_write_ok)),
    ("raw.prompt.concurrent", ALL, c_prompt_concurrent),
    ("raw.oversized", ALL, c_oversized),
    ("raw.parse-error.large", ("stdio",), c_parse_error_large),
    ("raw.http.content-type", ("http",), h_content_type),
    ("raw.http.get-accept", ("http",), h_get_accept),
    ("raw.http.missing-connection", ("http",), h_missing_connection),
    ("raw.http.unknown-connection", ("http",), h_unknown_connection),
    ("raw.http.unknown-session-stream", ("http",), h_unknown_session_stream),
    ("raw.http.missing-session-header", ("http",), h_missing_session_header),
    ("raw.http.delete-missing", ("http",), h_delete_missing),
    ("raw.stdio.eof-answers", ("stdio",), c_eof_answers),
    ("raw.stdio.stdout-clean", ("stdio",), c_stdout_clean),
]
# Cases whose outcome is timing-dependent: run with --target (evidence) or --cases, never in the
# generated scenarios.
EVIDENCE_ONLY = {"raw.stdio.eof-answers"}


def client_case_ids(transport, evidence=False):
    return [c for c, ts, _ in CLIENT_CASES if transport in ts and (evidence or c not in EVIDENCE_ONLY)]


class Reporter:
    def __init__(self, target, transport):
        self.target = target
        self.transport = transport
        self.passed = self.failed = self.hangs = 0
        self.lock = threading.Lock()

    def line(self, case, ok, ms, detail):
        detail = detail.replace("\n", " ").replace("\r", " ")
        with self.lock:
            if ok:
                self.passed += 1
            else:
                self.failed += 1
                self.hangs += "TIMEOUT" in detail
            if self.target:
                out("EVIDENCE %s %s %s %s (%d ms) -> %s" % (self.target, self.transport, case,
                                                           "PASS" if ok else "FAIL", ms, detail))
            else:
                out("STEP %s %s (%d ms) -> %s" % (case, "PASS" if ok else "FAIL", ms, detail))

    def result(self):
        out("RESULT pass=%d fail=%d hangs=%d" % (self.passed, self.failed, self.hangs))


def relay_line(target):
    def relay(line):
        # A stdio agent's stderr: STEP lines verbatim (Contracts §2), everything else prefixed.
        out(line if line.startswith("STEP ") and not target else "agent| " + line)
    return relay


def recover(p):
    """After a failed case: keep the connection if it still answers, else start a new one."""
    if p is None or p.ch is None or p.ch.closed:
        return None
    try:
        p.answer = None
        p.drain(0.3)
        p.new_session()
        return p
    except Exception:  # noqa: BLE001 - any failure means a fresh connection
        p.close()
        return None


def run_client(transport, url, cases, target):
    rep = Reporter(target, transport)
    relay = relay_line(target)
    p = None
    for case in cases:
        fn = next((f for c, _, f in CLIENT_CASES if c == case), None)
        t0 = time.time()
        if fn is None:
            rep.line(case, False, 0, "unknown-step")
            continue
        try:
            if p is None or p.ch is None or p.ch.closed:
                if p is not None:
                    p.close()
                p = Probe(transport, url, relay).open()
            detail = fn(p)
            try:
                p.alive()
            except Fail as e:
                raise Fail("%s; but then the connection did not survive: %s" % (detail, e))
            rep.line(case, True, int((time.time() - t0) * 1000), detail)
        except Fail as e:
            rep.line(case, False, int((time.time() - t0) * 1000), str(e))
            p = recover(p)
        except Exception as e:  # noqa: BLE001 - a broken probe is a FAIL with its cause, not a crash
            rep.line(case, False, int((time.time() - t0) * 1000), "%s: %s" % (type(e).__name__, e))
            if p is not None:
                p.close()
                p = None
    if p is not None:
        p.close()
    rep.result()


# =====================================================================================
# Agent role
# =====================================================================================

# The client steps (steps.json) a raw-agent cell runs, and how the raw agent shapes each one.
AGENT_ROLE_STEPS = [
    "init.initialize",            # answered normally; stray frames precede the answer (stdio, ws)
    "session.new",                # answered with fixtures.modes; stray frames precede it (stdio, ws)
    "session.load",               # session/load answered "result": null (all-optional response)
    "update.agent_message_chunk",  # the AGENT_CASES run during this prompt, then an unknown update
    "update.unknown",             # #emit unknown: the unknown update, then the chunk "after-unknown"
    "perm.selected",              # normal
    "fs.write",                   # normal; the client's answer is checked
    "error.method-not-found",     # answered -32601 without "message"
    "mode.set",                   # session/set_mode answered "result": null
    "auth.authenticate",          # authenticate answered "result": null
    "auth.logout",                # logout answered "result": null
    "session.delete",             # session/delete answered "result": null
    "http.reconnect",             # http only
    "stdio.eof-exit",             # stdio only
    "conn.close",
]
# The Java client's own raw mode (client.sh --mode raw, programs/java Raw.java): one prompt
# "#raw <case>" per step, answered by the raw agent as that file describes.
JAVA_RAW_MODE_STEPS = ["init.initialize", "raw.error-no-message", "raw.null-id-response", "raw.unknown-update",
                       "raw.null-result", "conn.close"]
AGENT_ROLE_STEP_TRANSPORTS = {"http.reconnect": ("http",), "stdio.eof-exit": ("stdio",)}

# What the raw agent sends the client during the plain-text prompt: (case, transports).
AGENT_CASES = [
    ("raw.parse-error", ALL), ("raw.invalid-request.empty-object", ALL), ("raw.invalid-request.not-object", ALL),
    ("raw.invalid-request.null", ALL), ("raw.invalid-request.empty-batch", ALL),
    ("raw.invalid-request.method-type", ALL), ("raw.invalid-request.id-type", ALL),
    ("raw.invalid-request.version", ALL),
    ("raw.method-not-found", ALL), ("raw.method-not-found.ext", ALL),
    ("raw.notification.unknown", ALL), ("raw.notification.unknown-ext", ALL),
    ("raw.notification.bad-params", ALL), ("raw.update.unknown-session", ALL),
    ("raw.id.null", ALL), ("raw.id.string", ALL), ("raw.id.zero", ALL), ("raw.id.large", ALL),
    ("raw.invalid-params", ALL),
    ("raw.stray-response.unknown-id", ALL), ("raw.stray-response.null-id", ALL),
    ("raw.stray-response.null-id-error", ALL),
    ("raw.update.unknown", ALL),
]


def agent_step_ids(transport):
    return [s for s in AGENT_ROLE_STEPS if transport in AGENT_ROLE_STEP_TRANSPORTS.get(s, ALL)]


def agent_case_ids(transport):
    only = [c.strip() for c in os.environ.get("RAW_AGENT_CASES", "").split(",") if c.strip()]
    return [c for c, ts in AGENT_CASES if transport in ts and (not only or c in only)]


SESSIONS = set()  # every session the agent created, across connections (http.reconnect loads one)
SESSIONS_LOCK = threading.Lock()


class AgentConn:
    """One client connection of the raw agent. `chan.send(text, session=...)` routes a frame
    (the session matters on Streamable HTTP only)."""

    def __init__(self, chan, transport, target):
        self.chan = chan
        self.transport = transport
        self.target = target
        self.client_caps = {}
        self.next_id = 0
        self.deferred = deque()
        self.ran_cases = False
        self.sid = None

    # ---------------------------------------------------------------- plumbing

    def out(self, obj_or_text, session=None):
        self.chan.send(obj_or_text if isinstance(obj_or_text, str) else dumps(obj_or_text), session=session)

    def recv(self, timeout):
        if self.deferred:
            return self.deferred.popleft()
        return self.chan.recv(timeout)

    def step(self, case, ok, t0, detail):
        detail = detail.replace("\n", " ")
        ms = int((time.time() - t0) * 1000)
        if self.target:
            log("EVIDENCE %s %s agent.%s %s (%d ms) -> %s" % (self.target, self.transport, case,
                                                              "PASS" if ok else "FAIL", ms, detail))
        else:
            log("STEP agent.%s %s (%d ms) -> %s" % (case, "PASS" if ok else "FAIL", ms, detail))

    def await_reply(self, pred, timeout, what):
        """Waits for a client frame matching pred; other frames are kept for the main loop."""
        deadline = time.time() + timeout
        held = []
        try:
            while True:
                left = deadline - time.time()
                if left <= 0:
                    raise Fail("TIMEOUT after %d ms waiting for %s" % (int(timeout * 1000), what))
                m = self.chan.recv(left)
                if m is None:
                    if self.chan.closed:
                        raise Fail("the connection closed while waiting for %s" % what)
                    continue
                if pred(m):
                    return m
                held.append(m)
        finally:
            self.deferred.extend(held)

    def quiet(self):
        """The responses that arrive before a fence (a permission request sent after the frame
        under test) plus FENCE_GRACE; other frames are kept for the main loop."""
        self.next_id += 1
        fid = "raw-fence-%d" % self.next_id
        self.out({"jsonrpc": "2.0", "id": fid, "method": "session/request_permission",
                  "params": dict(PERMISSION, sessionId=self.sid)}, self.sid)
        got, held = [], []
        deadline = time.time() + STEP_TIMEOUT
        fenced = None
        try:
            while True:
                left = (fenced + FENCE_GRACE if fenced else deadline) - time.time()
                if left <= 0:
                    if fenced:
                        return got
                    raise Fail("TIMEOUT after %d ms waiting for the fence" % int(STEP_TIMEOUT * 1000))
                m = self.chan.recv(left)
                if m is None:
                    if self.chan.closed:
                        raise Fail("the connection closed")
                    continue
                if is_response(m) and "id" in m and same_id(m["id"], fid):
                    fenced = time.time()
                elif is_response(m) or (isinstance(m, dict) and "_unparsed" in m):
                    got.append(m)
                else:
                    held.append(m)
        finally:
            self.deferred.extend(held)

    def request(self, method, params, session=None, rid=None, timeout=None):
        if rid is None:
            self.next_id += 1
            rid = "raw-agent-%d" % self.next_id
        self.out({"jsonrpc": "2.0", "id": rid, "method": method, "params": params}, session)
        return self.await_reply(lambda m: is_response(m) and "id" in m and same_id(m["id"], rid),
                                STEP_TIMEOUT if timeout is None else timeout, "the reply to %s" % method)

    def ping(self, sid):
        """The liveness check after each case: a permission request the client must answer."""
        r = self.request("session/request_permission", dict(PERMISSION, sessionId=sid), session=sid)
        if "result" not in r:
            raise Fail("the liveness permission request failed: %s" % short(r))

    def strays(self, session):
        # Frames no SDK sends, ahead of an ordinary answer: on stdio and WebSocket only, since on
        # HTTP the answers to initialize and session/new do not travel on a stream.
        if self.transport == "http":
            return
        for f in ('{"jsonrpc":"2.0","id":"no-such-request","result":{}}',
                  '{"jsonrpc":"2.0","id":null,"result":{}}',
                  '{"jsonrpc":"2.0","method":"_interop/note","params":{"n":1}}'):
            self.out(f, session)

    # ---------------------------------------------------------------- serving

    def serve(self):
        while True:
            m = self.recv(1.0)
            if m is None:
                if self.chan.closed:
                    return
                continue
            if is_request(m):
                self.on_request(m)
            # notifications (session/cancel) and responses need nothing here

    def answer(self, m, result=None, error=None, session=None, raw=None):
        if raw is not None:
            self.out(raw, session)
        elif error is not None:
            self.out({"jsonrpc": "2.0", "id": m["id"], "error": error}, session)
        else:
            self.out({"jsonrpc": "2.0", "id": m["id"], "result": result}, session)

    def on_request(self, m):
        method = m["method"]
        p = m.get("params") if isinstance(m.get("params"), dict) else {}
        sid = p.get("sessionId") if isinstance(p.get("sessionId"), str) else None
        # Streamable HTTP: the answers to session/load (and initialize, session/new) travel on
        # the connection stream; every other request naming a session is answered on its stream.
        scope = None if method == "session/load" else sid
        log("[raw-agent] %s %s" % (method, short(p, 160)))
        if method == "initialize":
            self.client_caps = p.get("clientCapabilities") or {}
            self.strays(None)
            return self.answer(m, AGENT_INIT)
        if method == "session/new":
            with SESSIONS_LOCK:
                sid = "raw-sess-%d" % (len(SESSIONS) + 1)
                SESSIONS.add(sid)
            self.strays(None)
            return self.answer(m, {"sessionId": sid, "modes": MODES})
        if method == "session/load":
            # LoadSessionResponse has only optional fields: "result": null must read as {}.
            return self.answer(m, None, session=None)
        if method in ("session/set_mode", "authenticate", "logout", "session/delete"):
            # Each response type has only optional fields: "result": null must read as {}.
            t0 = time.time()
            if method == "session/set_mode":
                with SESSIONS_LOCK:
                    known = sid in SESSIONS
                ok = known and p.get("modeId") == "interop-mode-b"
                self.step("mode.set", ok, t0, "set_mode %s on %s session %s" % (
                    p.get("modeId"), "a known" if known else "an unknown", sid))
                if p.get("modeId") not in ("interop-mode-a", "interop-mode-b"):
                    return self.answer(m, error={"code": INVALID_PARAMS, "message": "unknown mode"}, session=scope)
            if method == "authenticate":
                self.step("auth.authenticate", p.get("methodId") == "interop-auth", t0, "methodId %s" % p.get("methodId"))
            return self.answer(m, None, session=scope)
        if method == "session/prompt":
            return self.prompt(m, sid, p)
        # error.method-not-found: an error without "message" (schema Error.message is required),
        # so the client must fail the call with -32601 rather than hang or crash on it.
        return self.answer(m, raw='{"jsonrpc":"2.0","id":%s,"error":{"code":-32601}}' % dumps(m["id"]), session=scope)

    def chunk(self, sid, text):
        self.out({"jsonrpc": "2.0", "method": "session/update", "params": {
            "sessionId": sid, "update": {"sessionUpdate": "agent_message_chunk",
                                         "content": {"type": "text", "text": text}}}}, sid)

    def prompt(self, m, sid, p):
        blocks = p.get("prompt") if isinstance(p.get("prompt"), list) else []
        text = next((b.get("text") for b in blocks if isinstance(b, dict) and b.get("type") == "text"), "")
        if text == "#permission allow":
            t0 = time.time()
            try:
                r = self.request("session/request_permission", dict(PERMISSION, sessionId=sid), session=sid)
                out = (r.get("result") or {}).get("outcome") or {}
                said = "selected %s" % out.get("optionId") if out.get("outcome") == "selected" else "cancelled"
                self.step("perm.selected", said == "selected allow", t0, "outcome " + said)
            except Fail as e:
                said = "error"
                self.step("perm.selected", False, t0, str(e))
            self.chunk(sid, "permission: " + said)
        elif text.startswith("#fs write "):
            t0 = time.time()
            rest = text[len("#fs write "):]
            path, _, content = rest.partition(" ")
            try:
                r = self.request("fs/write_text_file", {"sessionId": sid, "path": path, "content": content},
                                 session=sid)
                ok = "result" in r
                self.step("fs.write", ok, t0, "fs/write_text_file answered " + short(r, 120))
                self.chunk(sid, "fs write ok" if ok else "fs write error %s" % error_code(r))
            except Fail as e:
                self.step("fs.write", False, t0, str(e))
                self.chunk(sid, "fs write error timeout")
        elif text == "#emit unknown":
            self.out({"jsonrpc": "2.0", "method": "session/update",
                      "params": {"sessionId": sid, "update": UNKNOWN_UPDATE}}, sid)
            self.chunk(sid, "after-unknown")
        elif text.startswith("#raw "):
            # The Java client's --mode raw steps (programs/java Raw.java): raw.<case>.
            case = text[len("#raw "):].strip()
            if case == "error-no-message":
                return self.answer(m, raw='{"jsonrpc":"2.0","id":%s,"error":{"code":-32603}}' % dumps(m["id"]),
                                   session=sid)
            if case == "null-id-response":
                self.out('{"jsonrpc":"2.0","id":null,"result":{}}', sid)
            elif case == "unknown-update":
                self.out({"jsonrpc": "2.0", "method": "session/update",
                          "params": {"sessionId": sid, "update": UNKNOWN_UPDATE}}, sid)
                self.chunk(sid, "after-unknown")
            elif case == "null-result":
                return self.answer(m, None, session=sid)  # PromptResponse requires stopReason
            else:
                return self.answer(m, error={"code": INVALID_PARAMS, "message": "unknown raw case: " + case},
                                   session=sid)
        elif text.startswith("#"):
            return self.answer(m, error={"code": INVALID_PARAMS, "message": "unknown directive: %s"
                                         % text.split(" ")[0]}, session=sid)
        else:
            if text == "hello" and not self.ran_cases:
                # update.agent_message_chunk's prompt: the agent-role cases run inside it, once.
                self.ran_cases = True
                self.run_cases(sid)
            # An update kind from the future, then the ordinary chunks: the client must drop or
            # surface it and keep going (the client step checks the chunks that follow).
            self.out({"jsonrpc": "2.0", "method": "session/update",
                      "params": {"sessionId": sid, "update": UNKNOWN_UPDATE}}, sid)
            self.chunk(sid, "echo: ")
            self.chunk(sid, text)
        return self.answer(m, {"stopReason": "end_turn"}, session=sid)

    # ---------------------------------------------------------------- agent-role cases

    def run_cases(self, sid):
        self.sid = sid
        for case in agent_case_ids(self.transport):
            t0 = time.time()
            try:
                detail = self.case(case, sid)
                try:
                    self.ping(sid)
                except Fail as e:
                    raise Fail("%s; but then the connection did not survive: %s" % (detail, e))
                self.step(case, True, t0, detail)
            except Fail as e:
                self.step(case, False, t0, str(e))
                if self.chan.closed:
                    return

    def unreadable(self, text, code, sid):
        self.out(text, sid)
        if self.transport == "http":
            # On an SSE stream the client has no channel to answer on but a POST of its own;
            # JSON-RPC 2.0 has no rule for it. Accept silence or the -32700/-32600 answer.
            got = self.quiet()
            for g in got:
                if not (is_response(g) and g.get("id", 0) is None and error_code(g) == code):
                    raise Fail("answered %s" % short(g))
            return "skipped" if not got else "answered %d id null" % code
        m = self.await_reply(lambda x: is_response(x) and "id" in x and x["id"] is None, STEP_TIMEOUT,
                             "an error response with \"id\": null")
        return expect_error(m, code, None)

    def case(self, case, sid):
        perm = dict(PERMISSION, sessionId=sid)
        if case == "raw.parse-error":
            return self.unreadable('{"jsonrpc":"2.0","id":1,"method":', PARSE_ERROR, sid)
        if case == "raw.invalid-request.empty-object":
            return self.unreadable("{}", INVALID_REQUEST, sid)
        if case == "raw.invalid-request.not-object":
            return self.unreadable("42", INVALID_REQUEST, sid)
        if case == "raw.invalid-request.null":
            return self.unreadable("null", INVALID_REQUEST, sid)
        if case == "raw.invalid-request.empty-batch":
            return self.unreadable("[]", INVALID_REQUEST, sid)
        if case == "raw.invalid-request.method-type":
            self.out('{"jsonrpc":"2.0","id":"raw-m","method":42}', sid)
            if self.transport == "http":
                return self.unreadable_tail("raw-m", INVALID_REQUEST)
            m = self.await_reply(lambda x: is_response(x) and "id" in x and x["id"] in (None, "raw-m"), STEP_TIMEOUT,
                                 "an error response")
            return expect_error(m, INVALID_REQUEST)
        if case == "raw.invalid-request.id-type":
            self.out(dumps({"jsonrpc": "2.0", "id": {"x": 1}, "method": "session/request_permission", "params": perm}),
                     sid)
            if self.transport == "http":
                return self.unreadable_tail({"x": 1}, INVALID_REQUEST)
            m = self.await_reply(lambda x: is_response(x) and "id" in x and x["id"] in (None, {"x": 1}), STEP_TIMEOUT,
                                 "an error response for the object id")
            if m["id"] is not None:
                raise Fail("answered with the object id echoed: %s" % short(m))
            return expect_error(m, INVALID_REQUEST, None)
        if case == "raw.invalid-request.version":
            self.out(dumps({"jsonrpc": "1.0", "id": "raw-v", "method": "session/request_permission", "params": perm}),
                     sid)
            m = self.await_reply(lambda x: is_response(x) and "id" in x and x["id"] in (None, "raw-v"), STEP_TIMEOUT,
                                 "the answer to a \"jsonrpc\": \"1.0\" request")
            if "result" in m:
                raise Fail("a request with \"jsonrpc\": \"1.0\" was executed (expected -32600): %s" % short(m))
            return expect_error(m, INVALID_REQUEST)
        if case in ("raw.method-not-found", "raw.method-not-found.ext"):
            method = "interop/no_such_method" if case == "raw.method-not-found" else "_interop/no_such_method"
            r = self.request(method, {"sessionId": sid}, session=sid)
            return expect_error(r, METHOD_NOT_FOUND)
        if case.startswith("raw.notification.") or case.startswith("raw.stray-response.") \
                or case == "raw.update.unknown-session":
            frame = {
                "raw.notification.unknown": {"jsonrpc": "2.0", "method": "interop/no_such_notification",
                                             "params": {"sessionId": sid}},
                "raw.notification.unknown-ext": {"jsonrpc": "2.0", "method": "_interop/note",
                                                 "params": {"sessionId": sid, "n": 1}},
                "raw.notification.bad-params": {"jsonrpc": "2.0", "method": "session/update",
                                                "params": {"sessionId": sid, "update": 5}},
                "raw.update.unknown-session": {"jsonrpc": "2.0", "method": "session/update", "params": {
                    "sessionId": "no-such-session", "update": {"sessionUpdate": "agent_message_chunk",
                                                               "content": {"type": "text", "text": "stray"}}}},
                "raw.stray-response.unknown-id": {"jsonrpc": "2.0", "id": "no-such-request", "result": {}},
                "raw.stray-response.null-id": {"jsonrpc": "2.0", "id": None, "result": {}},
                "raw.stray-response.null-id-error": {"jsonrpc": "2.0", "id": None,
                                                     "error": {"code": -32700, "message": "Parse error"}},
            }[case]
            self.out(frame, sid)
            got = self.quiet()
            if got:
                raise Fail("answered a notification or response: %s" % short(got[0]))
            return "no reply"
        if case == "raw.update.unknown":
            self.out({"jsonrpc": "2.0", "method": "session/update", "params": {"sessionId": sid,
                                                                               "update": UNKNOWN_UPDATE}}, sid)
            got = self.quiet()
            if got:
                raise Fail("answered a notification: %s" % short(got[0]))
            return "no reply"
        if case.startswith("raw.id."):
            rid = {"raw.id.null": None, "raw.id.string": "raw-id-1", "raw.id.zero": 0,
                   "raw.id.large": 9007199254740993}[case]
            self.out({"jsonrpc": "2.0", "id": rid, "method": "session/request_permission", "params": perm}, sid)
            m = self.await_reply(lambda x: is_response(x) and "id" in x, STEP_TIMEOUT, "the reply to id %s" % dumps(rid))
            if not same_id(m["id"], rid):
                raise Fail("answered with id %s for request id %s: %s" % (dumps(m["id"]), dumps(rid), short(m)))
            if "result" not in m:
                raise Fail("the permission request with id %s failed: %s" % (dumps(rid), short(m)))
            return "answered with id %s" % dumps(m["id"])
        if case == "raw.invalid-params":
            r = self.request("session/request_permission", {"sessionId": sid, "toolCall": PERMISSION["toolCall"],
                                                             "options": "allow"}, session=sid)
            return expect_error(r, INVALID_PARAMS)
        raise Fail("unknown-step")

    def unreadable_tail(self, rid, code):
        got = self.quiet()
        for g in got:
            if not (is_response(g) and (g.get("id") is None or g.get("id") == rid) and error_code(g) == code):
                raise Fail("answered %s" % short(g))
        return "skipped" if not got else "answered %d" % code


# ---------------------------------------------------------------- agent role: stdio


def run_agent_stdio(target):
    conn = AgentConn(StdioServerChannel(), "stdio", target)
    log("[raw-agent] stdio agent started")
    conn.serve()
    log("[raw-agent] stdin closed; exiting")


# ---------------------------------------------------------------- agent role: HTTP and WebSocket


class HttpConn:
    """One Streamable HTTP connection of the raw agent: an inbound queue fed by POSTs, and an
    outbound queue per stream (None: the connection-scoped stream)."""

    def __init__(self, cid):
        self.id = cid
        self.q = queue.Queue()
        self.closed = False
        self.streams = {}
        self.lock = threading.Lock()

    def stream(self, session):
        with self.lock:
            return self.streams.setdefault(session, queue.Queue())

    def send(self, text, session=None):
        # The agent passes the session for everything tied to one (its stream); responses to
        # initialize, session/new and session/load, and the rest, use the connection stream.
        self.stream(session).put(text)

    def recv(self, timeout):
        try:
            return self.q.get(timeout=max(0.0, timeout))
        except queue.Empty:
            return None

    def close(self):
        self.closed = True
        self.q.put(None)
        with self.lock:
            for s in self.streams.values():
                s.put(None)


def make_handler(target, conns):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        def log_line(self, status, extra=""):
            log("[http] %s %s %s -> %d conn=\"%s\" sess=\"%s\" upgrade=\"%s\"%s" % (
                self.command, self.path, self.request_version, status, self.headers.get("Acp-Connection-Id", ""),
                self.headers.get("Acp-Session-Id", ""), self.headers.get("Upgrade", ""), extra))

        def reply(self, status, body=b"", ctype="text/plain", headers=None):
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if body:
                self.wfile.write(body)
            self.log_line(status)

        def conn(self):
            cid = self.headers.get("Acp-Connection-Id")
            if not cid:
                self.reply(400, b"Acp-Connection-Id header required")
                return None
            c = conns.get(cid)
            if c is None:
                self.reply(404)
            return c

        def do_POST(self):
            n = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(n).decode("utf-8", "replace")
            m = parse(body)
            if isinstance(m, dict) and m.get("method") == "initialize" and "Acp-Connection-Id" not in self.headers:
                cid = "raw-conn-%s" % os.urandom(6).hex()
                hc = HttpConn(cid)
                agent = AgentConn(hc, "http", target)
                first = []
                hc_send = hc.send
                hc.send = lambda text, session=None: first.append(text)
                agent.on_request(m)
                hc.send = hc_send
                conns[cid] = hc
                threading.Thread(target=agent.serve, daemon=True).start()
                return self.reply(200, first[-1].encode(), "application/json", {"Acp-Connection-Id": cid})
            c = self.conn()
            if c is None:
                return
            c.q.put(m)
            self.reply(202)

        def do_DELETE(self):
            c = self.conn()
            if c is None:
                return
            conns.pop(c.id, None)
            c.close()
            self.reply(202)

        def do_GET(self):
            if self.headers.get("Upgrade", "").lower() == "websocket":
                return self.websocket()
            if "text/event-stream" not in self.headers.get("Accept", ""):
                return self.reply(406, b"client must accept text/event-stream")
            c = self.conn()
            if c is None:
                return
            session = self.headers.get("Acp-Session-Id") or None
            q = c.stream(session)
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            self.log_line(200, " stream=%s" % (session or "connection"))
            self.close_connection = True
            try:
                while True:
                    try:
                        text = q.get(timeout=15)
                    except queue.Empty:
                        self.wfile.write(b": keep-alive\n\n")
                        self.wfile.flush()
                        continue
                    if text is None:
                        break
                    self.wfile.write(b"event: message\ndata: " + text.encode("utf-8") + b"\n\n")
                    self.wfile.flush()
            except OSError:
                pass

        def websocket(self):
            key = self.headers.get("Sec-WebSocket-Key", "")
            accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
            cid = "raw-conn-%s" % os.urandom(6).hex()
            self.send_response(101)
            self.send_header("Upgrade", "websocket")
            self.send_header("Connection", "Upgrade")
            self.send_header("Sec-WebSocket-Accept", accept)
            self.send_header("Acp-Connection-Id", cid)
            self.end_headers()
            self.wfile.flush()
            self.log_line(101)
            self.close_connection = True
            AgentConn(WsChannel(self.connection, self.rfile, client=False), "ws", target).serve()

    return Handler


def run_agent_http(port, target, transport):
    conns = {}
    server = ThreadingHTTPServer(("127.0.0.1", port), make_handler(target, conns))
    server.daemon_threads = True
    log("[raw-agent] listening http://127.0.0.1:%d/acp (HTTP and WebSocket)" % server.server_address[1])
    print("READY %d" % server.server_address[1], flush=True)
    server.serve_forever()


# =====================================================================================


def usage(problem):
    log("raw: " + problem)
    log("usage: raw.py client --transport stdio (AGENT_CMD) | --transport http|ws --url <url> "
        "[--cases a,b] [--target <name>]")
    log("       raw.py agent --transport stdio | --transport http|ws --port <port> [--target <name>]")
    log("       raw.py list client|agent <transport>")
    sys.exit(2)


def main(argv):
    if len(argv) >= 1 and argv[0] == "list":
        if len(argv) != 3:
            usage("list needs a role and a transport")
        ids = client_case_ids(argv[2]) if argv[1] == "client" else agent_case_ids(argv[2])
        print("\n".join(ids))
        return
    if not argv or argv[0] not in ("client", "agent"):
        usage("the first argument is the role: client or agent")
    role, args = argv[0], argv[1:]
    opts = {"--transport": None, "--url": None, "--port": "0", "--cases": None, "--target": None}
    i = 0
    while i < len(args):
        if args[i] not in opts or i + 1 >= len(args):
            usage("unknown argument or missing value: %s" % args[i])
        opts[args[i]] = args[i + 1]
        i += 2
    transport = opts["--transport"]
    if transport not in ALL:
        usage("--transport stdio|http|ws is required")
    target = opts["--target"]
    if role == "client":
        if transport == "stdio" and not os.environ.get("AGENT_CMD"):
            usage("AGENT_CMD is required for stdio")
        if transport != "stdio" and not opts["--url"]:
            usage("--url is required for " + transport)
        cases = opts["--cases"].split(",") if opts["--cases"] else client_case_ids(transport, evidence=bool(target))
        run_client(transport, opts["--url"], [c.strip() for c in cases if c.strip()], target)
        sys.exit(0)
    if transport == "stdio":
        run_agent_stdio(target)
    else:
        run_agent_http(int(opts["--port"]), target, transport)


if __name__ == "__main__":
    main(sys.argv[1:])
