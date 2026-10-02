//! The Rust interop agent of the step catalogue (integration-testing/steps.json), driven by
//! directives in the prompt text. One binary serves every scenario.
//!
//!   interop-agent --transport stdio                ACP on stdin/stdout; exits when stdin ends
//!   interop-agent --transport http|ws --port <p>   Streamable HTTP and WebSocket on /acp (one axum
//!                                                  server handles both); prints READY <port>
//!
//! Every diagnostic and every `STEP agent.<id>` line goes to stderr (on stdio, stdout is the
//! protocol stream). Sessions live in one process-wide store, so a session created on one HTTP
//! connection can be loaded on another (http.reconnect).
mod common;

use agent_client_protocol::schema::v1::*;
use agent_client_protocol::{Agent, Client, ConnectTo, ConnectionTo, Dispatch, Handled, Responder, UntypedMessage};
use common::*;
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering::SeqCst};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::{Duration, Instant};
use tokio::sync::watch;

/// Why a running turn was asked to stop.
#[derive(Clone, Copy, Debug, PartialEq)]
enum Stop {
    Running,
    Cancel,
    Close,
}

struct Session {
    cwd: String,
    /// Plain-text turns: (prompt text, agent chunks), replayed on session/load.
    history: Vec<(String, Vec<String>)>,
    closed: bool,
    running: usize,
    stop: watch::Sender<(u64, Stop)>,
    mode: String,
    model: String,
    verbose: bool,
    /// Whether #config grouped added fixtures.groupedConfigOption (config.grouped), and its value.
    grouped: bool,
    effort: String,
}

impl Session {
    fn new(cwd: String) -> Self {
        Session {
            cwd,
            history: Vec::new(),
            closed: false,
            running: 0,
            stop: watch::channel((0, Stop::Running)).0,
            mode: "interop-mode-a".into(),
            model: "model-a".into(),
            verbose: false,
            grouped: false,
            effort: "effort-low".into(),
        }
    }

    fn signal(&self, why: Stop) {
        self.stop.send_modify(|(n, s)| {
            *n += 1;
            *s = why;
        });
    }
}

static SESSIONS: LazyLock<Mutex<HashMap<String, Session>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT_SESSION: AtomicU64 = AtomicU64::new(1);

/// Per-connection state.
#[derive(Default)]
struct Conn {
    /// The clientCapabilities of the initialize request, as received.
    caps: Mutex<Option<Value>>,
    last_ext_notification: Mutex<Option<String>>,
}

impl Conn {
    fn cap(&self, path: &[&str]) -> bool {
        let caps = self.caps.lock().unwrap();
        let mut v = match caps.as_ref() {
            Some(v) => v,
            None => return false,
        };
        for p in path {
            match v.get(p) {
                Some(x) => v = x,
                None => return false,
            }
        }
        !(v.is_null() || *v == Value::Bool(false))
    }

    fn boolean_options(&self) -> Option<bool> {
        if self.cap(&["session", "configOptions", "boolean"]) { Some(false) } else { None }
    }
}

fn log(s: &str) {
    eprintln!("{s}");
}

/// `STEP agent.<id> PASS|FAIL (<ms> ms) -> <detail>` on stderr.
fn step(id: &str, pass: bool, t0: Instant, detail: &str) {
    eprintln!("STEP agent.{id} {} ({} ms) -> {}", if pass { "PASS" } else { "FAIL" }, ms(t0), one_line(detail));
}

fn invalid(msg: impl Into<String>) -> agent_client_protocol::Error {
    agent_client_protocol::Error::new(-32602, msg)
}

fn session_options(sid: &str, conn: &Conn) -> (Value, Value) {
    let sessions = SESSIONS.lock().unwrap();
    let s = sessions.get(sid);
    let mode = s.map(|s| s.mode.clone()).unwrap_or_else(|| "interop-mode-a".into());
    let model = s.map(|s| s.model.clone()).unwrap_or_else(|| "model-a".into());
    let verbose = conn.boolean_options().map(|_| s.map(|s| s.verbose).unwrap_or(false));
    let mut options = config_options(&model, verbose);
    if let Some(s) = s.filter(|s| s.grouped)
        && let Value::Array(list) = &mut options
    {
        list.push(grouped_config_option(&s.effort));
    }
    (modes(&mode), options)
}

/// fixtures.groupedConfigOption: the select effort, its options in the groups fast and deep.
fn grouped_config_option(effort: &str) -> Value {
    json!({ "id": "effort", "name": "Effort", "type": "select", "currentValue": effort,
        "options": [
            { "group": "fast", "name": "Fast", "options": [ { "value": "effort-low", "name": "Low" } ] },
            { "group": "deep", "name": "Deep", "options": [
                { "value": "effort-medium", "name": "Medium" }, { "value": "effort-high", "name": "High" } ] }
        ] })
}

const EFFORT_VALUES: [&str; 3] = ["effort-low", "effort-medium", "effort-high"];

#[derive(Clone)]
struct InteropAgent;

impl ConnectTo<Client> for InteropAgent {
    async fn connect_to(self, client: impl ConnectTo<Agent>) -> Result<(), agent_client_protocol::Error> {
        let conn = Arc::new(Conn::default());
        let c_init = conn.clone();
        let c_new = conn.clone();
        let c_load = conn.clone();
        let c_resume = conn.clone();
        let c_fork = conn.clone();
        let c_cfg = conn.clone();
        let c_prompt = conn.clone();
        let c_ext = conn.clone();
        Agent
            .builder()
            .name("interop-rust-agent")
            .on_receive_request(
                async move |req: InitializeRequest, responder: Responder<InitializeResponse>, _cx| {
                    let caps = to_json(&req.client_capabilities);
                    log(&format!("[agent] initialize clientCapabilities={caps}"));
                    *c_init.caps.lock().unwrap() = Some(caps);
                    let mut methods = vec![auth_method()];
                    if c_init.cap(&["auth", "terminal"]) {
                        methods.push(terminal_auth_method());
                    }
                    let resp: InitializeResponse = typed(json!({
                        "protocolVersion": 1,
                        "agentCapabilities": agent_capabilities(),
                        "authMethods": methods,
                        "agentInfo": { "name": "interop-rust-agent", "version": "1" }
                    }));
                    let _ = responder.respond(resp);
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                async move |req: AuthenticateRequest, responder: Responder<AuthenticateResponse>, _cx| {
                    let t0 = Instant::now();
                    let id = req.method_id.to_string();
                    step("auth.authenticate", id == "interop-auth", t0, &format!("methodId {id}"));
                    let _ = responder.respond(typed(json!({})));
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                async move |_req: LogoutRequest, responder: Responder<LogoutResponse>, _cx| {
                    log("[agent] logout");
                    let _ = responder.respond(typed(json!({})));
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                async move |req: NewSessionRequest, responder: Responder<NewSessionResponse>, _cx| {
                    let id = format!("rust-sess-{}", NEXT_SESSION.fetch_add(1, SeqCst));
                    let cwd = req.cwd.display().to_string();
                    log(&format!("[agent] session/new {id} cwd={cwd}"));
                    SESSIONS.lock().unwrap().insert(id.clone(), Session::new(cwd));
                    let (modes, options) = session_options(&id, &c_new);
                    let _ = responder.respond(typed(json!({ "sessionId": id, "modes": modes, "configOptions": options })));
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                async move |req: LoadSessionRequest, responder: Responder<LoadSessionResponse>, cx: ConnectionTo<Client>| {
                    let sid = req.session_id.to_string();
                    let history = {
                        let sessions = SESSIONS.lock().unwrap();
                        match sessions.get(&sid) {
                            Some(s) if !s.closed => Some(s.history.clone()),
                            _ => None,
                        }
                    };
                    let Some(history) = history else {
                        log(&format!("[agent] session/load {sid}: unknown"));
                        let _ = responder.respond_with_error(invalid(format!("unknown session {sid}")));
                        return Ok(());
                    };
                    log(&format!("[agent] session/load {sid}: replaying {} turn(s)", history.len()));
                    for (prompt, chunks) in history {
                        let _ = cx.send_notification(notification(&sid, json!({
                            "sessionUpdate": "user_message_chunk", "content": { "type": "text", "text": prompt } })));
                        for c in chunks {
                            let _ = cx.send_notification(chunk(&sid, &c));
                        }
                    }
                    let (modes, options) = session_options(&sid, &c_load);
                    let _ = responder.respond(typed(json!({ "modes": modes, "configOptions": options })));
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                async move |req: ResumeSessionRequest, responder: Responder<ResumeSessionResponse>, _cx| {
                    let sid = req.session_id.to_string();
                    let known = SESSIONS.lock().unwrap().get(&sid).is_some_and(|s| !s.closed);
                    log(&format!("[agent] session/resume {sid} known={known}"));
                    if !known {
                        let _ = responder.respond_with_error(invalid(format!("unknown session {sid}")));
                        return Ok(());
                    }
                    let (modes, options) = session_options(&sid, &c_resume);
                    let _ = responder.respond(typed(json!({ "modes": modes, "configOptions": options })));
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                async move |req: ForkSessionRequest, responder: Responder<ForkSessionResponse>, _cx| {
                    let sid = req.session_id.to_string();
                    let id = format!("rust-sess-{}", NEXT_SESSION.fetch_add(1, SeqCst));
                    let forked = {
                        let mut sessions = SESSIONS.lock().unwrap();
                        let history = sessions.get(&sid).filter(|s| !s.closed).map(|s| s.history.clone());
                        history.map(|h| {
                            let mut s = Session::new(req.cwd.display().to_string());
                            s.history = h;
                            sessions.insert(id.clone(), s);
                        })
                    };
                    log(&format!("[agent] session/fork {sid} -> {id} ok={}", forked.is_some()));
                    if forked.is_none() {
                        let _ = responder.respond_with_error(invalid(format!("unknown session {sid}")));
                        return Ok(());
                    }
                    let (modes, options) = session_options(&id, &c_fork);
                    let _ = responder.respond(typed(json!({ "sessionId": id, "modes": modes, "configOptions": options })));
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                async move |req: ListSessionsRequest, responder: Responder<ListSessionsResponse>, _cx| {
                    // Pages of two, so a client that follows nextCursor is exercised.
                    let cwd = req.cwd.map(|c| c.display().to_string());
                    let mut all: Vec<(u64, String, String)> = SESSIONS
                        .lock()
                        .unwrap()
                        .iter()
                        .filter(|(_, s)| !s.closed && cwd.as_ref().is_none_or(|c| *c == s.cwd))
                        .map(|(id, s)| (id.trim_start_matches("rust-sess-").parse().unwrap_or(0), id.clone(), s.cwd.clone()))
                        .collect();
                    all.sort();
                    let start: usize = req.cursor.as_deref().and_then(|c| c.parse().ok()).unwrap_or(0);
                    let page: Vec<Value> = all.iter().skip(start).take(2)
                        .map(|(_, id, cwd)| json!({ "sessionId": id, "cwd": cwd })).collect();
                    let next = if start + 2 < all.len() { Some((start + 2).to_string()) } else { None };
                    log(&format!("[agent] session/list cwd={cwd:?} cursor={:?} -> {} of {}", req.cursor, page.len(), all.len()));
                    let _ = responder.respond(typed(json!({ "sessions": page, "nextCursor": next })));
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                async move |req: CloseSessionRequest, responder: Responder<CloseSessionResponse>, _cx| {
                    let t0 = Instant::now();
                    let sid = req.session_id.to_string();
                    let (known, running) = {
                        let mut sessions = SESSIONS.lock().unwrap();
                        match sessions.get_mut(&sid) {
                            Some(s) => {
                                s.closed = true;
                                let running = s.running;
                                if running > 0 {
                                    s.signal(Stop::Close);
                                }
                                (true, running)
                            }
                            None => (false, 0),
                        }
                    };
                    step("session.close", known && running > 0, t0,
                        &format!("close of {sid}: known={known}, running turns cancelled={running}"));
                    let _ = responder.respond(typed(json!({})));
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                async move |req: DeleteSessionRequest, responder: Responder<DeleteSessionResponse>, _cx| {
                    let sid = req.session_id.to_string();
                    let removed = SESSIONS.lock().unwrap().remove(&sid);
                    if let Some(s) = removed {
                        s.signal(Stop::Close);
                    }
                    log(&format!("[agent] session/delete {sid}"));
                    let _ = responder.respond(typed(json!({})));
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                async move |req: SetSessionModeRequest, responder: Responder<SetSessionModeResponse>, _cx| {
                    let t0 = Instant::now();
                    let sid = req.session_id.to_string();
                    let mode = req.mode_id.to_string();
                    let known_mode = mode == "interop-mode-a" || mode == "interop-mode-b";
                    let known = {
                        let mut sessions = SESSIONS.lock().unwrap();
                        match sessions.get_mut(&sid) {
                            Some(s) if known_mode => {
                                s.mode = mode.clone();
                                true
                            }
                            Some(_) => false,
                            None => false,
                        }
                    };
                    step("mode.set", known && mode == "interop-mode-b", t0, &format!("set_mode {sid} {mode}"));
                    if known {
                        let _ = responder.respond(typed(json!({})));
                    } else {
                        let _ = responder.respond_with_error(invalid(format!("unknown session or mode {sid} {mode}")));
                    }
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                async move |req: SetSessionConfigOptionRequest, responder: Responder<SetSessionConfigOptionResponse>, _cx| {
                    let sid = req.session_id.to_string();
                    let id = req.config_id.to_string();
                    let value = to_json(&req.value);
                    log(&format!("[agent] set_config_option {sid} {id} {value}"));
                    let result = {
                        let mut sessions = SESSIONS.lock().unwrap();
                        match sessions.get_mut(&sid) {
                            None => Err(format!("unknown session {sid}")),
                            Some(s) => match (id.as_str(), &value["value"]) {
                                ("model", Value::String(v)) if v == "model-a" || v == "model-b" => {
                                    s.model = v.clone();
                                    Ok(())
                                }
                                ("effort", Value::String(v)) if s.grouped && EFFORT_VALUES.contains(&v.as_str()) => {
                                    s.effort = v.clone();
                                    step("config.grouped", v == "effort-high", Instant::now(), &format!("effort set to {v}"));
                                    Ok(())
                                }
                                ("verbose", Value::Bool(b)) if c_cfg.boolean_options().is_some() => {
                                    s.verbose = *b;
                                    Ok(())
                                }
                                _ => Err(format!("unknown option or value {id} {value}")),
                            },
                        }
                    };
                    match result {
                        Ok(()) => {
                            let (_, options) = session_options(&sid, &c_cfg);
                            let _ = responder.respond(typed(json!({ "configOptions": options })));
                        }
                        Err(e) => {
                            let _ = responder.respond_with_error(invalid(e));
                        }
                    }
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_notification(
                async move |n: CancelNotification, _cx| {
                    let sid = n.session_id.to_string();
                    let sessions = SESSIONS.lock().unwrap();
                    match sessions.get(&sid) {
                        Some(s) => {
                            log(&format!("[agent] session/cancel {sid} running={}", s.running));
                            s.signal(Stop::Cancel);
                        }
                        None => log(&format!("[agent] session/cancel {sid}: unknown session")),
                    }
                    Ok(())
                },
                agent_client_protocol::on_receive_notification!(),
            )
            .on_receive_request(
                async move |req: PromptRequest, responder: Responder<PromptResponse>, cx: ConnectionTo<Client>| {
                    let conn = c_prompt.clone();
                    let cx2 = cx.clone();
                    // Answered as JSON: #enum stop answers a stopReason the typed StopReason cannot hold.
                    let responder = responder.erase_to_json();
                    cx.spawn(async move {
                        let sid = req.session_id.to_string();
                        let cancellation = responder.cancellation();
                        let result = prompt(&cx2, &conn, req, cancellation).await;
                        match result {
                            Some(Ok(resp)) => {
                                let _ = responder.respond(resp);
                            }
                            Some(Err(e)) => {
                                log(&format!("[agent] prompt on {sid} failed: {}", describe(&e)));
                                let _ = responder.respond_with_error(e);
                            }
                            None => {
                                // #hang: never answer.
                                std::future::pending::<()>().await;
                            }
                        }
                        Ok(())
                    })?;
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_dispatch(
                async move |d: Dispatch, _cx: ConnectionTo<Client>| -> Result<Handled<Dispatch>, agent_client_protocol::Error> {
                    match d {
                        Dispatch::Request(req, responder) if req.method() == EXT_METHOD => {
                            log(&format!("[agent] extension request {} {}", req.method(), req.params()));
                            let _ = responder.respond(json!({ "pong": 1 }));
                            Ok(Handled::Yes)
                        }
                        Dispatch::Notification(n) if n.method().starts_with('_') => {
                            log(&format!("[agent] extension notification {} {}", n.method(), n.params()));
                            *c_ext.last_ext_notification.lock().unwrap() = Some(n.method().to_string());
                            Ok(Handled::Yes)
                        }
                        other => Ok(Handled::No { message: other, retry: false }),
                    }
                },
                agent_client_protocol::on_receive_dispatch!(),
            )
            .connect_to(client)
            .await
    }
}

fn notification(sid: &str, update: Value) -> SessionNotification {
    typed(json!({ "sessionId": sid, "update": update }))
}

fn chunk(sid: &str, text: &str) -> SessionNotification {
    notification(sid, json!({ "sessionUpdate": "agent_message_chunk", "content": { "type": "text", "text": text } }))
}

fn end(reason: &str) -> Value {
    json!({ "stopReason": reason })
}

/// Tracks a running turn for session/cancel and session/close.
struct Turn {
    sid: String,
    rx: watch::Receiver<(u64, Stop)>,
}

impl Turn {
    fn start(sid: &str) -> Result<Turn, agent_client_protocol::Error> {
        let mut sessions = SESSIONS.lock().unwrap();
        match sessions.get_mut(sid) {
            Some(s) if !s.closed => {
                s.running += 1;
                let mut rx = s.stop.subscribe();
                rx.mark_unchanged();
                Ok(Turn { sid: sid.into(), rx })
            }
            Some(_) => Err(invalid(format!("session {sid} is closed"))),
            None => Err(invalid(format!("unknown session {sid}"))),
        }
    }

    /// Waits for the next session/cancel or session/close of the session.
    async fn stopped(&mut self) -> Stop {
        match self.rx.changed().await {
            Ok(()) => self.rx.borrow_and_update().1,
            Err(_) => {
                std::future::pending::<()>().await;
                Stop::Running
            }
        }
    }
}

impl Drop for Turn {
    fn drop(&mut self) {
        if let Some(s) = SESSIONS.lock().unwrap().get_mut(&self.sid) {
            s.running = s.running.saturating_sub(1);
        }
    }
}

/// Runs one prompt turn. `None` means never answer (#hang).
async fn prompt(
    cx: &ConnectionTo<Client>,
    conn: &Conn,
    req: PromptRequest,
    cancellation: agent_client_protocol::RequestCancellation,
) -> Option<Result<Value, agent_client_protocol::Error>> {
    let sid = req.session_id.to_string();
    let text: String = req
        .prompt
        .iter()
        .find_map(|b| if let ContentBlock::Text(t) = b { Some(t.text.clone()) } else { None })
        .unwrap_or_default();
    log(&format!("[agent] session/prompt {sid} {}", one_line(&text)));
    let mut turn = match Turn::start(&sid) {
        Ok(t) => t,
        Err(e) => return Some(Err(e)),
    };
    let send = |t: &str| {
        let _ = cx.send_notification(chunk(&sid, t));
    };
    if !text.starts_with('#') {
        send("echo: ");
        send(&text);
        if let Some(s) = SESSIONS.lock().unwrap().get_mut(&sid) {
            s.history.push((text.clone(), vec!["echo: ".into(), text.clone()]));
        }
        return Some(Ok(end("end_turn")));
    }
    let mut words = text.splitn(3, ' ');
    let name = words.next().unwrap_or("#");
    let arg1 = words.next().unwrap_or("");
    let rest = words.next().unwrap_or("");
    let all_args = text.split_once(' ').map(|x| x.1).unwrap_or("");
    let t0 = Instant::now();
    Some(match (name, arg1) {
        // #permission allow: perm.selected; #permission allow meta: meta.permission (_meta on the
        // request); #permission hold: perm.cancelled.
        ("#permission", "allow") | ("#permission", "hold") if rest.is_empty() || (arg1 == "allow" && rest == "meta") => {
            let hold = arg1 == "hold";
            let with_meta = rest == "meta";
            let mut perm = json!({ "sessionId": sid, "toolCall": permission_tool_call(), "options": permission_options() });
            if with_meta {
                perm["_meta"] = Value::Object(meta());
            }
            let perm: RequestPermissionRequest = typed(perm);
            match cx.send_request(perm).block_task().await {
                Ok(r) => {
                    let r = to_json(&r);
                    let outcome = &r["outcome"];
                    let said = match outcome["outcome"].as_str() {
                        Some("selected") => format!("selected {}", outcome["optionId"].as_str().unwrap_or("?")),
                        Some(o) => o.to_string(),
                        None => format!("unparsed {outcome}"),
                    };
                    if hold {
                        step("perm.cancelled", said == "cancelled", t0, &format!("outcome {said}"));
                    } else if with_meta {
                        step("meta.permission", has_meta(&r), t0,
                            &format!("response _meta {}", r.get("_meta").unwrap_or(&Value::Null)));
                    } else {
                        step("perm.selected", said == "selected allow", t0, &format!("outcome {said}"));
                    }
                    send(&format!("permission: {said}"));
                    Ok(end(if hold { "cancelled" } else { "end_turn" }))
                }
                Err(e) => {
                    step(if hold { "perm.cancelled" } else if with_meta { "meta.permission" } else { "perm.selected" }, false, t0, &describe(&e));
                    send(&format!("permission error {}", code_of(&e)));
                    Ok(end(if hold { "cancelled" } else { "end_turn" }))
                }
            }
        }
        ("#fs", "write") => {
            let (path, content) = rest.split_once(' ').unwrap_or((rest, ""));
            if !conn.cap(&["fs", "writeTextFile"]) {
                step("fs.write", false, t0, "the client did not advertise fs.writeTextFile");
                send("fs write error capability");
                return Some(Ok(end("end_turn")));
            }
            let r = cx.send_request::<WriteTextFileRequest>(typed(json!({ "sessionId": sid, "path": path, "content": content })))
                .block_task().await;
            match r {
                Ok(_) => {
                    step("fs.write", true, t0, "fs/write_text_file answered without error");
                    send("fs write ok");
                }
                Err(e) => {
                    step("fs.write", false, t0, &format!("fs/write_text_file failed: {}", describe(&e)));
                    send(&format!("fs write error {}", code_of(&e)));
                }
            }
            Ok(end("end_turn"))
        }
        ("#fs", "read") | ("#fs", "read-range") | ("#fs", "read-missing") => {
            let mut parts = rest.split(' ');
            let path = parts.next().unwrap_or("");
            let mut params = json!({ "sessionId": sid, "path": path });
            for p in parts {
                if let Some((k, v)) = p.split_once('=')
                    && let Ok(n) = v.parse::<u64>()
                {
                    params[k] = json!(n);
                }
            }
            let id = match arg1 { "read-range" => "fs.read-range", "read-missing" => "fs.read-missing", _ => "fs.read" };
            if !conn.cap(&["fs", "readTextFile"]) {
                step(id, false, t0, "the client did not advertise fs.readTextFile");
                send("fs read error capability");
                return Some(Ok(end("end_turn")));
            }
            match cx.send_request::<ReadTextFileRequest>(typed(params)).block_task().await {
                Ok(r) => {
                    match id {
                        "fs.read-missing" => step(id, false, t0, "fs/read_text_file of a missing file succeeded"),
                        "fs.read-range" => step(id, r.content.trim() == "line2", t0, &format!("content {:?}", r.content)),
                        _ => step(id, r.content == FS_READ_CONTENT, t0, &format!("content {:?}", r.content)),
                    }
                    send(&r.content);
                }
                Err(e) => {
                    step(id, id == "fs.read-missing", t0, &format!("fs/read_text_file failed: {}", describe(&e)));
                    send(&format!("fs read error {}", code_of(&e)));
                }
            }
            Ok(end("end_turn"))
        }
        ("#fs", "read-slow") => {
            let path = rest.split(' ').next().unwrap_or("");
            let sent = cx.send_request::<ReadTextFileRequest>(typed(json!({ "sessionId": sid, "path": path })));
            tokio::time::sleep(Duration::from_millis(200)).await;
            let t1 = Instant::now();
            if let Err(e) = sent.cancel() {
                log(&format!("[agent] $/cancel_request failed: {}", describe(&e)));
            }
            send("cancel-request sent");
            match tokio::time::timeout(Duration::from_secs(5), sent.block_task()).await {
                Ok(Err(e)) => step("cancel-request.agent", code_of(&e) == -32800, t1,
                    &format!("fs/read_text_file ended with {}", describe(&e))),
                Ok(Ok(r)) => step("cancel-request.agent", false, t1, &format!("fs/read_text_file succeeded: {:?}", r.content)),
                Err(_) => step("cancel-request.agent", false, t1, "TIMEOUT: no answer within 5 s of $/cancel_request"),
            }
            Ok(end("end_turn"))
        }
        ("#emit", kind) => {
            let tool_name = rest.split(' ').find_map(|p| p.strip_prefix("name="));
            let Some(update) = emit(kind, tool_name, conn.boolean_options().map(|_| false)) else {
                return Some(Err(invalid(format!("unknown directive: #emit {kind}"))));
            };
            if kind == "tool_call_update" {
                let _ = cx.send_notification(notification(&sid, emit("tool_call", None, None).unwrap()));
            }
            if kind == "unknown" {
                // The typed SessionUpdate cannot carry an unknown variant: send it untyped.
                let raw = UntypedMessage::new("session/update", json!({ "sessionId": sid, "update": update }))
                    .expect("untyped session/update");
                let _ = cx.send_notification(raw);
                send("after-unknown");
            } else {
                let _ = cx.send_notification(notification(&sid, update));
            }
            Ok(end("end_turn"))
        }
        ("#stop", reason) => {
            send("stop");
            match serde_json::from_value::<StopReason>(json!(reason)) {
                Ok(r) => Ok(json!({ "stopReason": r })),
                Err(_) => Err(invalid(format!("unknown stop reason {reason}"))),
            }
        }
        ("#slow", _) => {
            let grace = all_args.split(' ').find_map(|p| p.strip_prefix("grace=")).and_then(|g| g.parse::<u64>().ok()).unwrap_or(0);
            let deadline = tokio::time::sleep(Duration::from_secs(10));
            tokio::pin!(deadline);
            let mut tick = tokio::time::interval(Duration::from_millis(100));
            let why = loop {
                tokio::select! {
                    _ = tick.tick() => send("tick"),
                    w = turn.stopped() => break Some(w),
                    _ = cancellation.cancelled() => break None,
                    _ = &mut deadline => return Some(Ok(end("end_turn"))),
                }
            };
            match why {
                Some(Stop::Cancel) => step("cancel.prompt", true, t0, &format!("session/cancel arrived for {sid}")),
                Some(_) => log(&format!("[agent] #slow on {sid} stopped by session/close")),
                None => step("cancel-request.client", true, t0, "$/cancel_request observed for the prompt"),
            }
            let until = Instant::now() + Duration::from_millis(grace);
            while Instant::now() < until {
                send("tick");
                tokio::time::sleep(Duration::from_millis(100).min(until - Instant::now())).await;
            }
            Ok(end("cancelled"))
        }
        ("#hang", _) => return None,
        // Values the v1 schema does not define: the typed ToolCallStatus, PlanEntryPriority,
        // PlanEntryStatus and StopReason are closed enums, so these go out untyped.
        ("#enum", what @ ("tool_call" | "plan")) => {
            let update = if what == "tool_call" {
                json!({ "sessionUpdate": "tool_call", "toolCallId": "call-enum", "title": "interop enum tool",
                    "kind": "interop_future_kind", "status": "interop_future_status" })
            } else {
                json!({ "sessionUpdate": "plan", "entries": [ { "content": "future entry",
                    "priority": "interop_future_priority", "status": "interop_future_status" } ] })
            };
            let raw = UntypedMessage::new("session/update", json!({ "sessionId": sid, "update": update }))
                .expect("untyped session/update");
            let _ = cx.send_notification(raw);
            send("after-enum");
            Ok(end("end_turn"))
        }
        ("#enum", "stop") => {
            send("stop");
            Ok(end("interop_future_stop"))
        }
        ("#enum", "audience") => {
            // Reached only if the SDK parsed the PromptRequest, i.e. accepted the unknown Role.
            let first = req.prompt.first().map(to_json).unwrap_or(Value::Null);
            let audience: Option<Vec<String>> = first["annotations"]["audience"]
                .as_array()
                .map(|a| a.iter().map(|r| r.as_str().unwrap_or("?").to_string()).collect());
            let ok = audience.as_deref() == Some(&["user".to_string(), "interop_future_role".to_string()][..]);
            step("enum.audience", ok, t0, &format!("audience {audience:?}"));
            send(&format!("audience: {}", audience.map(|a| a.join(",")).unwrap_or_else(|| "none".into())));
            Ok(end("end_turn"))
        }
        ("#config", "grouped") => {
            if let Some(s) = SESSIONS.lock().unwrap().get_mut(&sid) {
                s.grouped = true;
            }
            let (_, options) = session_options(&sid, conn);
            let _ = cx.send_notification(notification(&sid, json!({ "sessionUpdate": "config_option_update", "configOptions": options })));
            Ok(end("end_turn"))
        }
        ("#terminal", "run") | ("#terminal", "kill") => {
            let kill = arg1 == "kill";
            let id = if kill { "term.kill" } else { "term.run" };
            let mut cmd = rest.split(' ').filter(|s| !s.is_empty()).map(String::from);
            let command = cmd.next().unwrap_or_default();
            let args: Vec<String> = cmd.collect();
            if !conn.cap(&["terminal"]) {
                step(id, false, t0, "the client did not advertise terminal");
                send("terminal error capability");
                return Some(Ok(end("end_turn")));
            }
            Ok(match terminal(cx, &sid, &command, args, kill, t0).await {
                Ok(chunk_text) => {
                    send(&chunk_text);
                    end("end_turn")
                }
                Err(e) => {
                    step(id, false, t0, &describe(&e));
                    send(&format!("terminal error {}", code_of(&e)));
                    end("end_turn")
                }
            })
        }
        ("#elicit", mode @ ("form" | "url")) => {
            let need = if mode == "form" { "form" } else { "url" };
            if !conn.cap(&["elicitation", need]) {
                if mode == "form" {
                    step("elicit.form", false, t0, "the client did not advertise elicitation.form");
                }
                send("elicit error capability");
                return Some(Ok(end("end_turn")));
            }
            let req: CreateElicitationRequest = if mode == "form" {
                typed(json!({ "mode": "form", "sessionId": sid, "message": "interop form", "requestedSchema": elicitation_schema() }))
            } else {
                typed(json!({ "mode": "url", "sessionId": sid, "elicitationId": "elic-1",
                    "url": "https://example.invalid/interop", "message": "interop url" }))
            };
            match cx.send_request(req).block_task().await {
                Ok(r) => {
                    let r = to_json(&r);
                    let action = r["action"].as_str().unwrap_or("?").to_string();
                    let content = r.get("content").cloned().unwrap_or(Value::Null);
                    if mode == "form" {
                        step("elicit.form", action == "accept" && content["name"] == "interop", t0, &format!("response {r}"));
                        send(&format!("elicit: {action} {content}"));
                    } else {
                        let done: CompleteElicitationNotification = typed(json!({ "elicitationId": "elic-1" }));
                        let _ = cx.send_notification(done);
                        send("elicit url done");
                    }
                }
                Err(e) => {
                    if mode == "form" {
                        step("elicit.form", false, t0, &describe(&e));
                    }
                    send(&format!("elicit error {}", code_of(&e)));
                }
            }
            Ok(end("end_turn"))
        }
        ("#ext", "request") => {
            let method = rest.split(' ').next().unwrap_or(EXT_METHOD);
            let req = UntypedMessage::new(method, json!({ "n": 1 })).expect("untyped request");
            match cx.send_request(req).block_task().await {
                Ok(r) => {
                    step("ext.agent-request", r == json!({ "pong": 1 }), t0, &format!("result {r}"));
                    send(&format!("ext: {r}"));
                }
                Err(e) => {
                    step("ext.agent-request", false, t0, &describe(&e));
                    send(&format!("ext error {}", code_of(&e)));
                }
            }
            Ok(end("end_turn"))
        }
        ("#ext", "notify") => {
            let method = rest.split(' ').next().unwrap_or(EXT_NOTIFICATION);
            let n = UntypedMessage::new(method, json!({ "n": 1 })).expect("untyped notification");
            let _ = cx.send_notification(n);
            send("ext notified");
            Ok(end("end_turn"))
        }
        ("#ext", "last-notification") => {
            let last = conn.last_ext_notification.lock().unwrap().clone().unwrap_or_else(|| "none".into());
            send(&format!("ext last: {last}"));
            Ok(end("end_turn"))
        }
        ("#meta", _) => {
            let m = req.meta.clone().map(Value::Object).unwrap_or(Value::Null);
            let mut update = json!({ "sessionUpdate": "agent_message_chunk", "content": { "type": "text", "text": "meta" } });
            let mut resp = json!({ "stopReason": "end_turn" });
            if !m.is_null() {
                update["_meta"] = m.clone();
                resp["_meta"] = m;
            }
            let _ = cx.send_notification(notification(&sid, update));
            Ok(resp)
        }
        ("#echo-caps", _) => {
            let caps = conn.caps.lock().unwrap().clone();
            step("init.client-capabilities", caps.is_some(), t0, &format!("clientCapabilities {}", caps.clone().unwrap_or(Value::Null)));
            send(&caps.unwrap_or(Value::Null).to_string());
            Ok(end("end_turn"))
        }
        ("#len", _) => {
            send(&format!("len={}", all_args.chars().count()));
            Ok(end("end_turn"))
        }
        ("#big", n) => match n.parse::<usize>() {
            Ok(n) => {
                send(&"x".repeat(n));
                Ok(end("end_turn"))
            }
            Err(_) => Err(invalid(format!("bad size {n}"))),
        },
        _ => Err(invalid(format!("unknown directive: {name}"))),
    })
}

async fn terminal(
    cx: &ConnectionTo<Client>,
    sid: &str,
    command: &str,
    args: Vec<String>,
    kill: bool,
    t0: Instant,
) -> Result<String, agent_client_protocol::Error> {
    let created = cx
        .send_request::<CreateTerminalRequest>(typed(json!({ "sessionId": sid, "command": command, "args": args })))
        .block_task()
        .await?;
    let tid = created.terminal_id.to_string();
    let ids = json!({ "sessionId": sid, "terminalId": tid });
    if kill {
        tokio::time::sleep(Duration::from_millis(200)).await;
        cx.send_request::<KillTerminalRequest>(typed(ids.clone())).block_task().await?;
        let t1 = Instant::now();
        let waited = tokio::time::timeout(
            Duration::from_secs(5),
            cx.send_request::<WaitForTerminalExitRequest>(typed(ids.clone())).block_task(),
        )
        .await;
        match &waited {
            Ok(Ok(w)) => step("term.kill", true, t1, &format!("wait_for_exit returned {} after the kill", to_json(w))),
            Ok(Err(e)) => step("term.kill", false, t1, &describe(e)),
            Err(_) => step("term.kill", false, t1, "TIMEOUT: wait_for_exit did not return within 5 s of the kill"),
        }
        let _ = cx.send_request::<ReleaseTerminalRequest>(typed(ids)).block_task().await;
        return Ok("terminal killed".into());
    }
    let exit = cx.send_request::<WaitForTerminalExitRequest>(typed(ids.clone())).block_task().await?;
    let out = cx.send_request::<TerminalOutputRequest>(typed(ids.clone())).block_task().await?;
    let _ = cx.send_request::<ReleaseTerminalRequest>(typed(ids)).block_task().await;
    let code = exit.exit_status.exit_code.map(|c| c.to_string()).unwrap_or_else(|| "none".into());
    step("term.run", out.output.contains("hi") && code == "0", t0, &format!("output {:?} exit={code}", out.output));
    Ok(format!("terminal: {} exit={code}", out.output.trim()))
}

fn usage(problem: &str) -> ! {
    eprintln!("agent: {problem}");
    eprintln!("usage: interop-agent --transport stdio | --transport http|ws --port <port>");
    std::process::exit(2);
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .with_writer(std::io::stderr)
        .init();
    let mut transport = None;
    let mut port: u16 = 0;
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        match a.as_str() {
            "--transport" => transport = Some(args.next().unwrap_or_else(|| usage("--transport needs a value"))),
            "--port" => {
                port = args
                    .next()
                    .and_then(|p| p.parse().ok())
                    .unwrap_or_else(|| usage("--port needs a number"))
            }
            other => usage(&format!("unknown argument {other}")),
        }
    }
    match transport.as_deref() {
        Some("stdio") => {
            let r = InteropAgent.connect_to(agent_client_protocol::Stdio::new()).await;
            log(&format!("stdin closed; exiting ({r:?})"));
            std::process::exit(0);
        }
        Some("http") | Some("ws") => {
            let router = agent_client_protocol_http::AcpHttpServer::new(|| InteropAgent).into_router().layer(
                axum::middleware::from_fn(|req: axum::extract::Request, next: axum::middleware::Next| async move {
                    let (head, tail) = {
                        let h = |n: &str| req.headers().get(n).and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
                        (format!("[http] {} {} {:?}", req.method(), req.uri().path(), req.version()),
                         format!("ct=\"{}\" accept=\"{}\" conn=\"{}\" sess=\"{}\" upgrade=\"{}\"",
                            h("content-type"), h("accept"), h("acp-connection-id"), h("acp-session-id"), h("upgrade")))
                    };
                    let resp = next.run(req).await;
                    eprintln!("{head} -> {} {tail}", resp.status().as_u16());
                    resp
                }),
            );
            let listener = tokio::net::TcpListener::bind(("127.0.0.1", port)).await?;
            let bound = listener.local_addr()?.port();
            log(&format!("listening http://127.0.0.1:{bound}/acp (HTTP and WebSocket)"));
            println!("READY {bound}");
            axum::serve(listener, router).await?;
            Ok(())
        }
        Some(t) => usage(&format!("unknown transport {t}")),
        None => usage("--transport is required"),
    }
}
