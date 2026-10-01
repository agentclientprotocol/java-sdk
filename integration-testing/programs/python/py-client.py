"""Interop client on the Python SDK's Streamable HTTP client. Usage: python py-client.py <url>.

Prints one "STEP <name> PASS|FAIL" line per step and a RESULT line with pass/fail counts and the
number of session updates each step received and in total. Per-step counts are indicative only:
update handlers run as tasks, so an update can land after the response that follows it.
"""
import asyncio
import re
import sys
import time

from acp import connect_to_agent
from acp.http import create_http_stream
from acp.interfaces import Client
from acp.schema import TextContentBlock

UPDATES = 0
TOTAL = 0
PER_STEP = {}
COUNTS = {"pass": 0, "fail": 0}


class C(Client):
    async def request_permission(self, session_id, tool_call, options, **kw):
        o = options[0]
        oid = o["optionId"] if isinstance(o, dict) else o.option_id
        print("  permission request", oid, flush=True)
        return {"outcome": {"outcome": "selected", "optionId": oid}}

    async def session_update(self, session_id, update, **kw):
        global UPDATES, TOTAL
        UPDATES += 1
        TOTAL += 1
        c = getattr(update, "content", None)
        print("  update:", getattr(c, "text", update), flush=True)


async def step(name, coro):
    global UPDATES
    UPDATES = 0
    t = time.time()
    try:
        r = await asyncio.wait_for(coro, 15)
        COUNTS["pass"] += 1
        print(f"STEP {name} PASS ({int((time.time() - t) * 1000)} ms) -> {r!r}"[:220], flush=True)
        return r
    except BaseException as e:  # noqa: BLE001 - report every failure as a step result
        COUNTS["fail"] += 1
        print(f"STEP {name} FAIL ({int((time.time() - t) * 1000)} ms) -> {type(e).__name__}: {e}", flush=True)
        return None
    finally:
        PER_STEP[re.sub(r"[^A-Za-z0-9]+", "_", name) + "_updates"] = UPDATES


async def main():
    tr = create_http_stream(sys.argv[1])
    conn = connect_to_agent(C(), tr)
    await step("initialize", conn.initialize(protocol_version=1))
    s = await step("session/new", conn.new_session(cwd="/tmp", mcp_servers=[]))
    sid = s.session_id if s else "missing"
    for n, t in (("prompt1", "hello one"), ("prompt2", "hello two"), ("prompt3-permission", "please ask permission")):
        await step(n, conn.prompt(session_id=sid, prompt=[TextContentBlock(type="text", text=t)]))
    await step("session/load", conn.load_session(cwd="/tmp", session_id=sid, mcp_servers=[]))
    await step("prompt-after-load", conn.prompt(session_id=sid, prompt=[TextContentBlock(type="text", text="after load")]))
    await step("close", conn.close())
    await tr.close()
    print(f"RESULT pass={COUNTS['pass']} fail={COUNTS['fail']} updates_total={TOTAL} " + " ".join(f"{k}={v}" for k, v in PER_STEP.items()), flush=True)


asyncio.run(main())
