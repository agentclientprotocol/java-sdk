"""Fixtures of the step catalogue (integration-testing/steps.json, "fixtures"), hard-coded as the
contract asks. Shared by the Python interop agent and client."""

import json
import sys
import time

CLIENT_CAPABILITIES = {
    "fs": {"readTextFile": True, "writeTextFile": True},
    "terminal": True,
    "elicitation": {"form": {}, "url": {}},
    "auth": {"terminal": True},
    "session": {"configOptions": {"boolean": {}}},
}
CLIENT_INFO = {"name": "interop-python-client", "version": "1"}

AGENT_CAPABILITIES = {
    "loadSession": True,
    "promptCapabilities": {"image": False, "audio": False, "embeddedContext": False},
    "mcpCapabilities": {"http": False, "sse": False},
    "sessionCapabilities": {"list": {}, "resume": {}, "close": {}, "delete": {}},
    "auth": {"logout": {}},
}
AUTH_METHOD = {"id": "interop-auth", "name": "Interop auth", "description": "Accepts any authenticate call"}
TERMINAL_AUTH_METHOD = {
    "type": "terminal",
    "id": "interop-terminal-auth",
    "name": "Interop terminal auth",
    "args": ["--login"],
}
AGENT_INFO = {"name": "interop-python-agent", "version": "1"}

MODES = {
    "currentModeId": "interop-mode-a",
    "availableModes": [{"id": "interop-mode-a", "name": "Mode A"}, {"id": "interop-mode-b", "name": "Mode B"}],
}
MODE_IDS = {m["id"] for m in MODES["availableModes"]}


def config_options(model="model-a", verbose=False, with_boolean=False):
    """fixtures.configOptions with the given current values; verbose only when the client
    advertised session.configOptions.boolean."""
    opts = [
        {
            "id": "model",
            "name": "Model",
            "type": "select",
            "currentValue": model,
            "options": [{"value": "model-a", "name": "Model A"}, {"value": "model-b", "name": "Model B"}],
        }
    ]
    if with_boolean:
        opts.append({"id": "verbose", "name": "Verbose", "type": "boolean", "currentValue": verbose})
    return opts


PERMISSION_TOOL_CALL = {"toolCallId": "perm-1", "title": "interop permission", "kind": "edit", "status": "pending"}
PERMISSION_OPTIONS = [
    {"optionId": "allow", "name": "Allow", "kind": "allow_once"},
    {"optionId": "reject", "name": "Reject", "kind": "reject_once"},
]

FS_READ_CONTENT = "line1\nline2\nline3\n"

EMIT = {
    "user_message_chunk": {"content": {"type": "text", "text": "user-chunk"}},
    "agent_thought_chunk": {"content": {"type": "text", "text": "thinking"}},
    "tool_call": {"toolCallId": "call-1", "title": "interop tool", "kind": "read", "status": "pending"},
    "tool_call_update": {
        "toolCallId": "call-1",
        "status": "completed",
        "content": [{"type": "content", "content": {"type": "text", "text": "tool output"}}],
    },
    "plan": {
        "entries": [
            {"content": "step one", "priority": "high", "status": "pending"},
            {"content": "step two", "priority": "low", "status": "completed"},
        ]
    },
    "available_commands_update": {
        "availableCommands": [{"name": "interop", "description": "interop command", "input": {"hint": "args"}}]
    },
    "current_mode_update": {"currentModeId": "interop-mode-b"},
    "session_info_update": {"title": "interop title"},
    "usage_update": {"used": 100, "size": 1000, "cost": {"amount": 0.01, "currency": "USD"}},
}
UNKNOWN_UPDATE = {"sessionUpdate": "interop_future_update", "payload": {"x": 1}}

EXT_METHOD = "_interop/ping"
EXT_NOTIFICATION = "_interop/note"
EXT_PARAMS = {"n": 1}
EXT_RESULT = {"pong": 1}

META_KEY = "interop"
META_VALUE = "m1"

ELICIT_FORM_MESSAGE = "interop form"
ELICIT_FORM_SCHEMA = {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}
ELICIT_FORM_ANSWER = {"action": "accept", "content": {"name": "interop"}}
ELICIT_URL = {"elicitationId": "elic-1", "url": "https://example.invalid/interop", "message": "interop url"}


def compact(value):
    return json.dumps(value, separators=(",", ":"), sort_keys=False)


def dump(model):
    """A schema model (or plain value) as JSON-shaped data with wire names."""
    if model is None:
        return None
    if hasattr(model, "model_dump"):
        return model.model_dump(mode="json", by_alias=True, exclude_none=True)
    return model


def step_line(out, step_id, ok, started, detail):
    ms = int((time.monotonic() - started) * 1000)
    detail = " ".join(str(detail).split())
    if len(detail) > 400:
        detail = detail[:400] + "..."
    print(f"STEP {step_id} {'PASS' if ok else 'FAIL'} ({ms} ms) -> {detail}", file=out, flush=True)


def log(*parts):
    print(*parts, file=sys.stderr, flush=True)
