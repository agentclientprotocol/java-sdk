//! Shared by the interop agent and client: the fixtures of steps.json (hard-coded, as the
//! contract asks), JSON helpers and the STEP line format.
#![allow(dead_code)]

use serde::de::DeserializeOwned;
use serde_json::{Value, json};
use std::time::Instant;

pub const META_KEY: &str = "interop";
pub const META_VALUE: &str = "m1";
pub const FS_READ_CONTENT: &str = "line1\nline2\nline3\n";
pub const EXT_METHOD: &str = "_interop/ping";
pub const EXT_NOTIFICATION: &str = "_interop/note";

/// Deserializes a fixture into a typed schema value; the fixtures are constants, so a failure
/// is a program bug.
pub fn typed<T: DeserializeOwned>(v: Value) -> T {
    let text = v.to_string();
    serde_json::from_value(v).unwrap_or_else(|e| panic!("fixture does not fit the schema type: {e}: {text}"))
}

pub fn to_json<T: serde::Serialize>(t: &T) -> Value {
    serde_json::to_value(t).unwrap_or(Value::Null)
}

/// `{"interop": "m1"}`
pub fn meta() -> serde_json::Map<String, Value> {
    let mut m = serde_json::Map::new();
    m.insert(META_KEY.into(), Value::String(META_VALUE.into()));
    m
}

pub fn has_meta(v: &Value) -> bool {
    v.get("_meta").and_then(|m| m.get(META_KEY)).and_then(Value::as_str) == Some(META_VALUE)
}

/// fixtures.client.clientCapabilities: everything the Rust client implements.
pub fn client_capabilities() -> Value {
    json!({
        "fs": { "readTextFile": true, "writeTextFile": true },
        "terminal": true,
        "elicitation": { "form": {}, "url": {} },
        "auth": { "terminal": true },
        "session": { "configOptions": { "boolean": {} } }
    })
}

pub fn agent_capabilities() -> Value {
    json!({
        "loadSession": true,
        "promptCapabilities": { "image": false, "audio": false, "embeddedContext": false },
        "mcpCapabilities": { "http": false, "sse": false },
        "sessionCapabilities": { "list": {}, "resume": {}, "close": {}, "delete": {} },
        "auth": { "logout": {} }
    })
}

pub fn auth_method() -> Value {
    json!({ "id": "interop-auth", "name": "Interop auth", "description": "Accepts any authenticate call" })
}

pub fn terminal_auth_method() -> Value {
    json!({ "type": "terminal", "id": "interop-terminal-auth", "name": "Interop terminal auth", "args": ["--login"] })
}

pub fn modes(current: &str) -> Value {
    json!({
        "currentModeId": current,
        "availableModes": [
            { "id": "interop-mode-a", "name": "Mode A" },
            { "id": "interop-mode-b", "name": "Mode B" }
        ]
    })
}

/// fixtures.configOptions with the given values; `verbose` only when the client advertised
/// session.configOptions.boolean.
pub fn config_options(model: &str, verbose: Option<bool>) -> Value {
    let mut list = vec![json!({
        "id": "model", "name": "Model", "type": "select", "currentValue": model,
        "options": [ { "value": "model-a", "name": "Model A" }, { "value": "model-b", "name": "Model B" } ]
    })];
    if let Some(v) = verbose {
        list.push(json!({ "id": "verbose", "name": "Verbose", "type": "boolean", "currentValue": v }));
    }
    Value::Array(list)
}

pub fn permission_tool_call() -> Value {
    json!({ "toolCallId": "perm-1", "title": "interop permission", "kind": "edit", "status": "pending" })
}

pub fn permission_options() -> Value {
    json!([
        { "optionId": "allow", "name": "Allow", "kind": "allow_once" },
        { "optionId": "reject", "name": "Reject", "kind": "reject_once" }
    ])
}

/// fixtures.emit.<kind> as a complete `update` object (with its sessionUpdate tag).
pub fn emit(kind: &str, name: Option<&str>, verbose: Option<bool>) -> Option<Value> {
    let mut v = match kind {
        "user_message_chunk" => json!({ "content": { "type": "text", "text": "user-chunk" } }),
        "agent_thought_chunk" => json!({ "content": { "type": "text", "text": "thinking" } }),
        "tool_call" => json!({ "toolCallId": "call-1", "title": "interop tool", "kind": "read", "status": "pending" }),
        "tool_call_update" => json!({ "toolCallId": "call-1", "status": "completed",
            "content": [ { "type": "content", "content": { "type": "text", "text": "tool output" } } ] }),
        "plan" => json!({ "entries": [
            { "content": "step one", "priority": "high", "status": "pending" },
            { "content": "step two", "priority": "low", "status": "completed" } ] }),
        "available_commands_update" => json!({ "availableCommands": [
            { "name": "interop", "description": "interop command", "input": { "hint": "args" } } ] }),
        "current_mode_update" => json!({ "currentModeId": "interop-mode-b" }),
        "config_option_update" => json!({ "configOptions": config_options("model-b", verbose) }),
        "session_info_update" => json!({ "title": "interop title" }),
        "usage_update" => json!({ "used": 100, "size": 1000, "cost": { "amount": 0.01, "currency": "USD" } }),
        "unknown" => return Some(json!({ "sessionUpdate": "interop_future_update", "payload": { "x": 1 } })),
        _ => return None,
    };
    if kind == "tool_call"
        && let Some(n) = name
    {
        v["name"] = Value::String(n.into());
    }
    v["sessionUpdate"] = Value::String(kind.into());
    Some(v)
}

pub fn elicitation_schema() -> Value {
    json!({ "type": "object", "properties": { "name": { "type": "string" } }, "required": ["name"] })
}

pub fn ms(t0: Instant) -> u128 {
    t0.elapsed().as_millis()
}

/// One line, never empty.
pub fn one_line(s: &str) -> String {
    let s: String = s.chars().map(|c| if c == '\n' || c == '\r' { ' ' } else { c }).collect();
    if s.chars().count() > 300 { format!("{}... ({} chars)", s.chars().take(300).collect::<String>(), s.len()) } else { s }
}

/// A JSON-RPC error's code, from the SDK error.
pub fn code_of(e: &agent_client_protocol::Error) -> i64 {
    to_json(e).get("code").and_then(Value::as_i64).unwrap_or(0)
}

pub fn describe(e: &agent_client_protocol::Error) -> String {
    one_line(&format!("error {} {}", code_of(e), to_json(e)))
}
