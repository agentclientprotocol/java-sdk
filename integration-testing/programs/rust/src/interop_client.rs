//! The Rust interop client of the step catalogue (integration-testing/steps.json). Runs the step
//! ids in STEPS (comma separated) in order and prints one `STEP <id> PASS|FAIL (<ms> ms) -> <detail>`
//! line per step, then `RESULT pass=.. fail=.. updates_total=.. upd_<kind>=..`.
//!
//!   STEPS=... interop-client --transport stdio            spawns bash -c "exec $AGENT_CMD" (AcpAgent)
//!   STEPS=... interop-client --transport http --url http://127.0.0.1:<p>/acp
//!   STEPS=... interop-client --transport ws   --url ws://127.0.0.1:<p>/acp   (HttpClient with a ws:// URL)
//!
//! On stdio the agent's stderr is relayed to stdout: lines starting with `STEP ` verbatim, every
//! other line prefixed `agent| `. Exits 0 once RESULT is printed, whatever the step outcomes.
mod common;

use agent_client_protocol::schema::ProtocolVersion;
use agent_client_protocol::schema::v1::*;
use agent_client_protocol::{
    AcpAgent, AcpAgentConfig, Agent, ConnectTo, ConnectionTo, Dispatch, Handled, LineDirection, Responder, UntypedMessage,
};
use common::*;
use serde_json::{Value, json};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::future::Future;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering::SeqCst};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::{Duration, Instant};
use tokio::io::AsyncReadExt;
use tokio::sync::{oneshot, watch};

type AcpError = agent_client_protocol::Error;

// ------------------------------------------------------------------ shared client state

struct Term {
    output: Arc<Mutex<String>>,
    exit: watch::Receiver<Option<(Option<i32>, Option<String>)>>,
    kill: Arc<tokio::sync::Notify>,
}

#[derive(Default)]
struct State {
    /// Every session/update received on any connection: (sessionId, update as JSON).
    updates: Mutex<Vec<(String, Value)>>,
    total: AtomicUsize,
    kinds: Mutex<BTreeMap<String, usize>>,
    /// session/request_permission requests: (sessionId, request as JSON).
    perms: Mutex<Vec<(String, Value)>>,
    /// Sessions whose next permission request is answered by cancelling the turn (perm.cancelled).
    cancel_on_perm: Mutex<HashSet<String>>,
    cancel_sent: Mutex<HashMap<String, Instant>>,
    ext_notifications: Mutex<Vec<(String, Value)>>,
    elicit_complete: Mutex<Vec<String>>,
    terminals: Mutex<HashMap<String, Term>>,
}

static S: LazyLock<State> = LazyLock::new(State::default);
static NEXT_TERMINAL: AtomicUsize = AtomicUsize::new(1);

struct Config {
    transport: String,
    url: Option<String>,
    agent_cmd: Option<String>,
    timeout: Duration,
    dir: PathBuf,
}

static CFG: std::sync::OnceLock<Config> = std::sync::OnceLock::new();

fn cfg() -> &'static Config {
    CFG.get().expect("config")
}

fn dir() -> String {
    cfg().dir.display().to_string()
}

const UPDATE_GRACE: Duration = Duration::from_millis(1000);
const REPLAY_GRACE: Duration = Duration::from_millis(2000);

fn record_update(sid: String, update: Value) {
    let kind = update.get("sessionUpdate").and_then(Value::as_str).unwrap_or("other").to_string();
    S.total.fetch_add(1, SeqCst);
    *S.kinds.lock().unwrap().entry(kind).or_default() += 1;
    S.updates.lock().unwrap().push((sid, update));
}

fn relay(line: &str, dir: LineDirection) {
    if dir == LineDirection::Stderr {
        if line.starts_with("STEP ") {
            println!("{line}");
        } else {
            println!("agent| {line}");
        }
    }
}

// ------------------------------------------------------------------ connections

struct Conn {
    cx: ConnectionTo<Agent>,
    close: Option<oneshot::Sender<()>>,
    join: tokio::task::JoinHandle<Result<(), AcpError>>,
}

impl Conn {
    async fn open() -> Result<Conn, String> {
        let (cx_tx, cx_rx) = oneshot::channel();
        let (close_tx, close_rx) = oneshot::channel();
        let join = if cfg().transport == "stdio" {
            let cmd = cfg().agent_cmd.clone().unwrap_or_default();
            let agent = AcpAgent::new(AcpAgentConfig::new("bash").arg("-c").arg(format!("exec {cmd}"))).with_debug(relay);
            tokio::spawn(run_conn(agent, cx_tx, close_rx))
        } else {
            let url = cfg().url.clone().unwrap_or_default();
            let http = agent_client_protocol_http::HttpClient::with_endpoint(&url).map_err(|e| format!("bad url {url}: {e}"))?;
            tokio::spawn(run_conn(http, cx_tx, close_rx))
        };
        match cx_rx.await {
            Ok(cx) => Ok(Conn { cx, close: Some(close_tx), join }),
            Err(_) => Err(format!("connection failed: {:?}", join.await)),
        }
    }

    /// The SDK's graceful close: return from connect_with (HTTP: DELETE; WS: close; stdio: end the child).
    async fn close(mut self) -> Result<String, String> {
        if let Some(c) = self.close.take() {
            let _ = c.send(());
        }
        let r = tokio::time::timeout(cfg().timeout, self.join).await;
        if cfg().transport == "http" {
            // The SDK sends the DELETE from a detached task (HttpConnection::spawn_close,
            // agent-client-protocol-http client.rs:525) after connect_with has returned, so nothing
            // can await it: give it a moment before the process may exit.
            tokio::time::sleep(Duration::from_millis(500)).await;
        }
        match r {
            Ok(Ok(Ok(()))) => Ok("closed".into()),
            Ok(Ok(Err(e))) => Err(format!("close failed: {}", describe(&e))),
            Ok(Err(e)) => Err(format!("connection task failed: {e}")),
            Err(_) => Err("TIMEOUT: the close did not complete".into()),
        }
    }
}

async fn run_conn<T: ConnectTo<agent_client_protocol::Client> + 'static>(
    transport: T,
    cx_tx: oneshot::Sender<ConnectionTo<Agent>>,
    close_rx: oneshot::Receiver<()>,
) -> Result<(), AcpError> {
    agent_client_protocol::Client
        .builder()
        .name("interop-rust-client")
        .on_receive_notification(
            async move |n: SessionNotification, _cx| {
                record_update(n.session_id.to_string(), to_json(&n.update));
                Ok(())
            },
            agent_client_protocol::on_receive_notification!(),
        )
        .on_receive_request(
            async move |req: RequestPermissionRequest, responder: Responder<RequestPermissionResponse>, cx: ConnectionTo<Agent>| {
                let j = to_json(&req);
                let sid = req.session_id.to_string();
                println!("  permission request on {sid}: {} option(s)", req.options.len());
                S.perms.lock().unwrap().push((sid.clone(), j.clone()));
                if S.cancel_on_perm.lock().unwrap().remove(&sid) {
                    let _ = cx.send_notification::<CancelNotification>(typed(json!({ "sessionId": sid })));
                    S.cancel_sent.lock().unwrap().insert(sid, Instant::now());
                    let _ = responder.respond(typed(json!({ "outcome": { "outcome": "cancelled" } })));
                    return Ok(());
                }
                let options = j["options"].as_array().cloned().unwrap_or_default();
                let chosen = options
                    .iter()
                    .find(|o| o["kind"] == "allow_once")
                    .or(options.first())
                    .and_then(|o| o["optionId"].as_str())
                    .unwrap_or("none")
                    .to_string();
                let mut resp = json!({ "outcome": { "outcome": "selected", "optionId": chosen } });
                if let Some(m) = j.get("_meta") {
                    resp["_meta"] = m.clone();
                }
                let _ = responder.respond(typed(resp));
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |req: WriteTextFileRequest, responder: Responder<WriteTextFileResponse>, _cx| {
                println!("  fs/write_text_file {}", req.path.display());
                match std::fs::write(&req.path, &req.content) {
                    Ok(()) => {
                        let _ = responder.respond(typed(json!({})));
                    }
                    Err(e) => {
                        let _ = responder.respond_with_error(AcpError::new(-32603, format!("write failed: {e}")));
                    }
                }
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |req: ReadTextFileRequest, responder: Responder<ReadTextFileResponse>, cx: ConnectionTo<Agent>| {
                println!("  fs/read_text_file {} line={:?} limit={:?}", req.path.display(), req.line, req.limit);
                if req.path.display().to_string().ends_with("slow.txt") {
                    // cancel-request.agent: wait up to 10 s or until the agent cancels the request.
                    let cancellation = responder.cancellation();
                    cx.spawn(async move {
                        let r = cancellation
                            .run_until_cancelled(async {
                                tokio::time::sleep(Duration::from_secs(10)).await;
                                Ok(typed::<ReadTextFileResponse>(json!({ "content": "slow" })))
                            })
                            .await;
                        println!("  slow fs/read_text_file ended: {}", if r.is_ok() { "completed" } else { "cancelled" });
                        let _ = responder.respond_with_result(r);
                        Ok(())
                    })?;
                    return Ok(());
                }
                match std::fs::read_to_string(&req.path) {
                    Ok(content) => {
                        let content = match (req.line, req.limit) {
                            (None, None) => content,
                            (line, limit) => {
                                let skip = line.map(|l| l.saturating_sub(1) as usize).unwrap_or(0);
                                let take = limit.map(|l| l as usize).unwrap_or(usize::MAX);
                                content.split_inclusive('\n').skip(skip).take(take).collect()
                            }
                        };
                        let _ = responder.respond(typed(json!({ "content": content })));
                    }
                    Err(e) => {
                        let _ = responder.respond_with_error(AcpError::resource_not_found(Some(req.path.display().to_string()))
                            .data(json!(e.to_string())));
                    }
                }
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |req: CreateTerminalRequest, responder: Responder<CreateTerminalResponse>, _cx| {
                println!("  terminal/create {} {:?}", req.command, req.args);
                let mut cmd = tokio::process::Command::new(&req.command);
                cmd.args(&req.args)
                    .stdin(std::process::Stdio::null())
                    .stdout(std::process::Stdio::piped())
                    .stderr(std::process::Stdio::piped())
                    .kill_on_drop(true);
                if let Some(cwd) = &req.cwd {
                    cmd.current_dir(cwd);
                }
                let mut child = match cmd.spawn() {
                    Ok(c) => c,
                    Err(e) => {
                        let _ = responder.respond_with_error(AcpError::new(-32603, format!("spawn failed: {e}")));
                        return Ok(());
                    }
                };
                let output = Arc::new(Mutex::new(String::new()));
                for pipe in [child.stdout.take().map(|p| Box::new(p) as Box<dyn tokio::io::AsyncRead + Unpin + Send>),
                    child.stderr.take().map(|p| Box::new(p) as Box<dyn tokio::io::AsyncRead + Unpin + Send>)]
                .into_iter()
                .flatten()
                {
                    let out = output.clone();
                    tokio::spawn(async move {
                        let mut pipe = pipe;
                        let mut buf = [0u8; 4096];
                        while let Ok(n) = pipe.read(&mut buf).await {
                            if n == 0 {
                                break;
                            }
                            out.lock().unwrap().push_str(&String::from_utf8_lossy(&buf[..n]));
                        }
                    });
                }
                let (exit_tx, exit_rx) = watch::channel(None);
                let kill = Arc::new(tokio::sync::Notify::new());
                let k = kill.clone();
                tokio::spawn(async move {
                    let status = tokio::select! {
                        s = child.wait() => s,
                        _ = k.notified() => {
                            let _ = child.start_kill();
                            child.wait().await
                        }
                    };
                    let exit = match status {
                        Ok(s) => {
                            use std::os::unix::process::ExitStatusExt;
                            (s.code(), s.signal().map(|n| if n == 9 { "SIGKILL".to_string() } else { format!("signal {n}") }))
                        }
                        Err(e) => (None, Some(format!("wait failed: {e}"))),
                    };
                    let _ = exit_tx.send(Some(exit));
                });
                let id = format!("term-{}", NEXT_TERMINAL.fetch_add(1, SeqCst));
                S.terminals.lock().unwrap().insert(id.clone(), Term { output, exit: exit_rx, kill });
                let _ = responder.respond(typed(json!({ "terminalId": id })));
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |req: TerminalOutputRequest, responder: Responder<TerminalOutputResponse>, _cx| {
                let tid = req.terminal_id.to_string();
                let resp = S.terminals.lock().unwrap().get(&tid).map(|t| {
                    let exit = t.exit.borrow().clone();
                    let mut r = json!({ "output": t.output.lock().unwrap().clone(), "truncated": false });
                    if let Some((code, signal)) = exit {
                        r["exitStatus"] = json!({ "exitCode": code, "signal": signal });
                    }
                    r
                });
                let _ = match resp {
                    Some(r) => responder.respond(typed(r)),
                    None => responder.respond_with_error(AcpError::new(-32602, format!("unknown terminal {tid}"))),
                };
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |req: WaitForTerminalExitRequest, responder: Responder<WaitForTerminalExitResponse>, cx: ConnectionTo<Agent>| {
                let tid = req.terminal_id.to_string();
                let rx = S.terminals.lock().unwrap().get(&tid).map(|t| t.exit.clone());
                let Some(mut rx) = rx else {
                    let _ = responder.respond_with_error(AcpError::new(-32602, format!("unknown terminal {tid}")));
                    return Ok(());
                };
                cx.spawn(async move {
                    let exit = match rx.wait_for(Option::is_some).await {
                        Ok(v) => v.clone().unwrap_or((None, None)),
                        Err(_) => (None, Some("lost".into())),
                    };
                    let _ = responder.respond(typed(json!({ "exitCode": exit.0, "signal": exit.1 })));
                    Ok(())
                })?;
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |req: KillTerminalRequest, responder: Responder<KillTerminalResponse>, _cx| {
                let tid = req.terminal_id.to_string();
                println!("  terminal/kill {tid}");
                if let Some(t) = S.terminals.lock().unwrap().get(&tid) {
                    t.kill.notify_one();
                }
                let _ = responder.respond(typed(json!({})));
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |req: ReleaseTerminalRequest, responder: Responder<ReleaseTerminalResponse>, _cx| {
                let tid = req.terminal_id.to_string();
                if let Some(t) = S.terminals.lock().unwrap().remove(&tid) {
                    t.kill.notify_one();
                }
                let _ = responder.respond(typed(json!({})));
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |req: CreateElicitationRequest, responder: Responder<CreateElicitationResponse>, _cx| {
                let j = to_json(&req);
                println!("  elicitation/create mode={}", j["mode"]);
                let resp = if j["mode"] == "form" {
                    json!({ "action": "accept", "content": { "name": "interop" } })
                } else {
                    json!({ "action": "accept" })
                };
                let _ = responder.respond(typed(resp));
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_notification(
            async move |n: CompleteElicitationNotification, _cx| {
                println!("  elicitation/complete {}", n.elicitation_id);
                S.elicit_complete.lock().unwrap().push(n.elicitation_id.to_string());
                Ok(())
            },
            agent_client_protocol::on_receive_notification!(),
        )
        .on_receive_dispatch(
            async move |d: Dispatch, _cx: ConnectionTo<Agent>| -> Result<Handled<Dispatch>, AcpError> {
                match d {
                    Dispatch::Request(req, responder) if req.method() == EXT_METHOD => {
                        println!("  extension request {} {}", req.method(), req.params());
                        let _ = responder.respond(json!({ "pong": 1 }));
                        Ok(Handled::Yes)
                    }
                    Dispatch::Notification(n) if n.method().starts_with('_') => {
                        println!("  extension notification {} {}", n.method(), n.params());
                        S.ext_notifications.lock().unwrap().push((n.method().to_string(), n.params().clone()));
                        Ok(Handled::Yes)
                    }
                    Dispatch::Notification(n) if n.method() == "session/update" => {
                        // A session/update the typed handler could not parse (a future variant).
                        println!("  untyped session/update {}", one_line(&n.params().to_string()));
                        let sid = n.params()["sessionId"].as_str().unwrap_or("").to_string();
                        let mut update = n.params()["update"].clone();
                        update["sessionUpdate"] = json!("other");
                        record_update(sid, update);
                        Ok(Handled::Yes)
                    }
                    other => Ok(Handled::No { message: other, retry: false }),
                }
            },
            agent_client_protocol::on_receive_dispatch!(),
        )
        .connect_with(transport, async move |cx: ConnectionTo<Agent>| {
            let _ = cx_tx.send(cx.clone());
            let _ = close_rx.await;
            Ok(())
        })
        .await
}

// ------------------------------------------------------------------ helpers

trait Ctx<T> {
    fn ctx(self, what: &str) -> Result<T, String>;
}

impl<T> Ctx<T> for Result<T, AcpError> {
    fn ctx(self, what: &str) -> Result<T, String> {
        self.map_err(|e| format!("{what}: {}", describe(&e)))
    }
}

fn ensure(cond: bool, msg: impl Into<String>) -> Result<(), String> {
    if cond { Ok(()) } else { Err(msg.into()) }
}

static MAIN: Mutex<Option<Conn>> = Mutex::new(None);
static INIT: Mutex<Option<Value>> = Mutex::new(None);

fn main_cx() -> Result<ConnectionTo<Agent>, String> {
    MAIN.lock().unwrap().as_ref().map(|c| c.cx.clone()).ok_or_else(|| "no main connection (init.initialize failed)".into())
}

fn init() -> Result<Value, String> {
    INIT.lock().unwrap().clone().ok_or_else(|| "no initialize response".into())
}

fn init_request() -> InitializeRequest {
    typed(json!({
        "protocolVersion": 1,
        "clientCapabilities": client_capabilities(),
        "clientInfo": { "name": "interop-rust-client", "version": "1" }
    }))
}

async fn initialize(cx: &ConnectionTo<Agent>) -> Result<Value, String> {
    let r = cx.send_request(init_request()).block_task().await.ctx("initialize")?;
    let _ = ProtocolVersion::V1;
    Ok(to_json(&r))
}

async fn new_session_in(cx: &ConnectionTo<Agent>, cwd: &str) -> Result<(String, Value), String> {
    let r = cx
        .send_request::<NewSessionRequest>(typed(json!({ "cwd": cwd, "mcpServers": [] })))
        .block_task()
        .await
        .ctx("session/new")?;
    let sid = r.session_id.to_string();
    ensure(!sid.is_empty(), "empty sessionId")?;
    Ok((sid, to_json(&r)))
}

async fn new_session(cx: &ConnectionTo<Agent>) -> Result<String, String> {
    Ok(new_session_in(cx, &dir()).await?.0)
}

fn prompt_request(sid: &str, text: &str, with_meta: bool) -> PromptRequest {
    let mut p = json!({ "sessionId": sid, "prompt": [ { "type": "text", "text": text } ] });
    if with_meta {
        p["_meta"] = Value::Object(meta());
    }
    typed(p)
}

async fn prompt(cx: &ConnectionTo<Agent>, sid: &str, text: &str) -> Result<Value, String> {
    let r = cx.send_request(prompt_request(sid, text, false)).block_task().await.ctx("session/prompt")?;
    Ok(to_json(&r))
}

fn stop_reason(r: &Value) -> String {
    r["stopReason"].as_str().unwrap_or("?").to_string()
}

async fn prompt_end_turn(cx: &ConnectionTo<Agent>, sid: &str, text: &str) -> Result<Value, String> {
    let r = prompt(cx, sid, text).await?;
    ensure(stop_reason(&r) == "end_turn", format!("stopReason {}", stop_reason(&r)))?;
    Ok(r)
}

fn updates(sid: &str) -> Vec<Value> {
    S.updates.lock().unwrap().iter().filter(|(s, _)| s == sid).map(|(_, u)| u.clone()).collect()
}

fn mark() -> usize {
    S.updates.lock().unwrap().len()
}

fn updates_since(sid: &str, from: usize) -> Vec<Value> {
    S.updates.lock().unwrap().iter().skip(from).filter(|(s, _)| s == sid).map(|(_, u)| u.clone()).collect()
}

fn texts(updates: &[Value], kind: &str) -> Vec<String> {
    updates
        .iter()
        .filter(|u| u["sessionUpdate"] == kind)
        .filter_map(|u| u["content"]["text"].as_str().map(String::from))
        .collect()
}

fn agent_text(sid: &str) -> String {
    texts(&updates(sid), "agent_message_chunk").concat()
}

/// Polls `cond` until it holds or `within` elapses.
async fn wait_for(within: Duration, cond: impl Fn() -> bool) -> bool {
    let t0 = Instant::now();
    loop {
        if cond() {
            return true;
        }
        if t0.elapsed() >= within {
            return false;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
}

async fn wait_chunk(sid: &str, within: Duration, pred: impl Fn(&str) -> bool) -> Option<String> {
    let t0 = Instant::now();
    loop {
        if let Some(t) = texts(&updates(sid), "agent_message_chunk").into_iter().find(|t| pred(t)) {
            return Some(t);
        }
        if t0.elapsed() >= within {
            return None;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
}


fn path_present(v: &Value, path: &[&str]) -> bool {
    let mut v = v;
    for p in path {
        match v.get(p) {
            Some(x) => v = x,
            None => return false,
        }
    }
    !v.is_null()
}

async fn send_cancel(cx: &ConnectionTo<Agent>, sid: &str) -> Instant {
    let _ = cx.send_notification::<CancelNotification>(typed(json!({ "sessionId": sid })));
    Instant::now()
}

// ------------------------------------------------------------------ steps

async fn init_initialize() -> Result<String, String> {
    let conn = Conn::open().await?;
    let r = initialize(&conn.cx).await;
    *MAIN.lock().unwrap() = Some(conn);
    let r = r?;
    *INIT.lock().unwrap() = Some(r.clone());
    ensure(r["protocolVersion"] == 1, format!("protocolVersion {}", r["protocolVersion"]))?;
    Ok(format!("protocolVersion=1 agentInfo={}", r["agentInfo"]))
}

async fn init_agent_capabilities() -> Result<String, String> {
    let c = &init()?["agentCapabilities"];
    ensure(c["loadSession"] == true, format!("loadSession {}", c["loadSession"]))?;
    for k in ["list", "resume", "close", "delete"] {
        ensure(path_present(c, &["sessionCapabilities", k]), format!("sessionCapabilities.{k} missing: {c}"))?;
    }
    Ok(one_line(&c.to_string()))
}

async fn echo_caps() -> Result<Value, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    prompt_end_turn(&cx, &sid, "#echo-caps").await?;
    let text = wait_chunk(&sid, UPDATE_GRACE, |t| t.starts_with('{')).await.ok_or("no capabilities chunk")?;
    serde_json::from_str(&text).map_err(|e| format!("chunk is not JSON ({e}): {text}"))
}

async fn init_client_capabilities() -> Result<String, String> {
    let caps = echo_caps().await?;
    ensure(caps["fs"]["readTextFile"] == true && caps["fs"]["writeTextFile"] == true && caps["terminal"] == true,
        format!("echoed {caps}"))?;
    Ok(one_line(&caps.to_string()))
}

async fn init_config_boolean() -> Result<String, String> {
    let caps = echo_caps().await?;
    ensure(path_present(&caps, &["session", "configOptions", "boolean"]), format!("echoed {caps}"))?;
    Ok(one_line(&caps.to_string()))
}

async fn init_auth_methods() -> Result<String, String> {
    let m = init()?["authMethods"].clone();
    ensure(m.as_array().is_some_and(|a| a.iter().any(|x| x["id"] == "interop-auth")), format!("authMethods {m}"))?;
    Ok(one_line(&m.to_string()))
}

async fn init_agent_info() -> Result<String, String> {
    let i = init()?["agentInfo"].clone();
    ensure(i["name"].as_str().is_some_and(|n| n.starts_with("interop-")), format!("agentInfo {i}"))?;
    Ok(i.to_string())
}

async fn auth_authenticate() -> Result<String, String> {
    let cx = main_cx()?;
    let r = cx
        .send_request::<AuthenticateRequest>(typed(json!({ "methodId": "interop-auth" })))
        .block_task()
        .await
        .ctx("authenticate")?;
    Ok(format!("result {}", to_json(&r)))
}

async fn auth_logout() -> Result<String, String> {
    let cx = main_cx()?;
    let r = cx.send_request::<LogoutRequest>(typed(json!({}))).block_task().await.ctx("logout")?;
    Ok(format!("result {}", to_json(&r)))
}

async fn auth_logout_capability() -> Result<String, String> {
    let c = &init()?["agentCapabilities"];
    ensure(path_present(c, &["auth", "logout"]), format!("agentCapabilities {c}"))?;
    Ok("auth.logout present".into())
}

async fn auth_terminal() -> Result<String, String> {
    let m = init()?["authMethods"].clone();
    ensure(
        m.as_array().is_some_and(|a| a.iter().any(|x| x["type"] == "terminal" && x["id"] == "interop-terminal-auth")),
        format!("authMethods {m}"),
    )?;
    Ok(one_line(&m.to_string()))
}

async fn session_new() -> Result<String, String> {
    let sid = new_session(&main_cx()?).await?;
    Ok(format!("sessionId={sid}"))
}

async fn load(cx: &ConnectionTo<Agent>, sid: &str) -> Result<Value, String> {
    let r = cx
        .send_request::<LoadSessionRequest>(typed(json!({ "sessionId": sid, "cwd": dir(), "mcpServers": [] })))
        .block_task()
        .await
        .ctx("session/load")?;
    Ok(to_json(&r))
}

async fn session_load() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    prompt_end_turn(&cx, &sid, "hello load").await?;
    load(&cx, &sid).await?;
    let from = mark();
    prompt_end_turn(&cx, &sid, "after load").await?;
    let ok = wait_for(UPDATE_GRACE, || {
        texts(&updates_since(&sid, from), "agent_message_chunk").iter().any(|t| t.contains("after load"))
    })
    .await;
    ensure(ok, "no agent_message_chunk for the prompt after the load")?;
    Ok(format!("loaded {sid}"))
}

async fn session_load_replay() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    prompt_end_turn(&cx, &sid, "replay me").await?;
    wait_for(UPDATE_GRACE, || agent_text(&sid) == "echo: replay me").await;
    let from = mark();
    load(&cx, &sid).await?;
    let ok = wait_for(REPLAY_GRACE, || {
        let u = updates_since(&sid, from);
        texts(&u, "user_message_chunk").iter().any(|t| t == "replay me")
            && texts(&u, "agent_message_chunk").iter().any(|t| t.contains("replay me"))
    })
    .await;
    let kinds: Vec<String> = updates_since(&sid, from).iter().map(|u| u["sessionUpdate"].to_string()).collect();
    ensure(ok, format!("replay incomplete: {kinds:?}"))?;
    Ok(format!("replayed {} update(s)", kinds.len()))
}

async fn session_resume() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    prompt_end_turn(&cx, &sid, "before resume").await?;
    wait_for(UPDATE_GRACE, || agent_text(&sid) == "echo: before resume").await;
    let from = mark();
    cx.send_request::<ResumeSessionRequest>(typed(json!({ "sessionId": sid, "cwd": dir() })))
        .block_task()
        .await
        .ctx("session/resume")?;
    tokio::time::sleep(UPDATE_GRACE).await;
    let n = updates_since(&sid, from).len();
    ensure(n == 0, format!("{n} update(s) arrived after the resume"))?;
    prompt_end_turn(&cx, &sid, "after resume").await?;
    Ok(format!("resumed {sid}"))
}

async fn session_list() -> Result<String, String> {
    let cx = main_cx()?;
    let cwd = format!("{}/list", dir());
    let _ = std::fs::create_dir_all(&cwd);
    let (sid, _) = new_session_in(&cx, &cwd).await?;
    let mut cursor: Option<String> = None;
    let mut seen = 0;
    for page in 1..=10 {
        let mut req = json!({ "cwd": cwd });
        if let Some(c) = &cursor {
            req["cursor"] = json!(c);
        }
        let r = cx.send_request::<ListSessionsRequest>(typed(req)).block_task().await.ctx("session/list")?;
        let r = to_json(&r);
        let sessions = r["sessions"].as_array().cloned().unwrap_or_default();
        seen += sessions.len();
        if let Some(s) = sessions.iter().find(|s| s["sessionId"] == sid.as_str()) {
            ensure(s["cwd"] == cwd.as_str(), format!("listed with cwd {}", s["cwd"]))?;
            return Ok(format!("found {sid} on page {page}"));
        }
        match r["nextCursor"].as_str() {
            Some(c) => cursor = Some(c.to_string()),
            None => break,
        }
    }
    Err(format!("{sid} not listed ({seen} session(s) seen)"))
}

async fn session_close() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    let slow = cx.send_request(prompt_request(&sid, "#slow", false));
    ensure(wait_chunk(&sid, Duration::from_secs(5), |t| t == "tick").await.is_some(), "no tick")?;
    let r = cx.send_request::<CloseSessionRequest>(typed(json!({ "sessionId": sid }))).block_task().await.ctx("session/close")?;
    let r = to_json(&r);
    ensure(r.as_object().is_some_and(|o| o.keys().all(|k| k == "_meta")), format!("close answered {r}"))?;
    let ended = tokio::time::timeout(Duration::from_secs(5), slow.block_task()).await;
    let how = match ended {
        Err(_) => return Err("TIMEOUT: the #slow prompt did not end within 5 s of the close".into()),
        Ok(Ok(p)) if stop_reason(&to_json(&p)) == "cancelled" => "cancelled".to_string(),
        Ok(Ok(p)) => return Err(format!("the #slow prompt answered {}", stop_reason(&to_json(&p)))),
        Ok(Err(e)) => describe(&e),
    };
    match prompt(&cx, &sid, "after close").await {
        Ok(r) => Err(format!("the prompt after close answered {}", stop_reason(&r))),
        Err(e) => Ok(format!("#slow ended ({how}); prompt after close failed ({e})")),
    }
}

/// On a connection of its own: the Rust HTTP client ends the whole connection when one POST is
/// answered with an HTTP error (an agent that 404s the unknown session id), which must not take
/// the main connection, and every later step, with it.
async fn session_delete() -> Result<String, String> {
    let conn = Conn::open().await?;
    let r = delete_twice(&conn.cx).await;
    let closed = conn.close().await;
    let r = r?;
    closed.map_err(|e| format!("both deletes answered, then {e}"))?;
    Ok(r)
}

async fn delete_twice(cx: &ConnectionTo<Agent>) -> Result<String, String> {
    initialize(cx).await?;
    let sid = new_session(cx).await?;
    cx.send_request::<DeleteSessionRequest>(typed(json!({ "sessionId": sid }))).block_task().await.ctx("session/delete")?;
    cx.send_request::<DeleteSessionRequest>(typed(json!({ "sessionId": "no-such-session" })))
        .block_task()
        .await
        .ctx("session/delete of an unknown id")?;
    Ok("both deletes answered".into())
}

async fn session_multi() -> Result<String, String> {
    let cx = main_cx()?;
    let a = new_session(&cx).await?;
    let b = new_session(&cx).await?;
    let (ra, rb) = tokio::join!(prompt(&cx, &a, "multi A"), prompt(&cx, &b, "multi B"));
    ensure(stop_reason(&ra?) == "end_turn", "A did not end_turn")?;
    ensure(stop_reason(&rb?) == "end_turn", "B did not end_turn")?;
    wait_for(UPDATE_GRACE, || agent_text(&a) == "echo: multi A" && agent_text(&b) == "echo: multi B").await;
    let (ta, tb) = (agent_text(&a), agent_text(&b));
    ensure(ta == "echo: multi A" && tb == "echo: multi B", format!("A={ta:?} B={tb:?}"))?;
    Ok("both sessions answered their own prompt".into())
}

async fn session_fork() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    prompt_end_turn(&cx, &sid, "before fork").await?;
    let r = cx
        .send_request::<ForkSessionRequest>(typed(json!({ "sessionId": sid, "cwd": dir() })))
        .block_task()
        .await
        .ctx("session/fork")?;
    let fork = r.session_id.to_string();
    ensure(fork != sid && !fork.is_empty(), format!("fork returned {fork}"))?;
    prompt_end_turn(&cx, &fork, "in fork").await?;
    Ok(format!("{sid} forked to {fork}"))
}

async fn update_agent_message_chunk() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    prompt_end_turn(&cx, &sid, "hello").await?;
    wait_for(UPDATE_GRACE, || agent_text(&sid) == "echo: hello").await;
    let t = agent_text(&sid);
    ensure(t == "echo: hello", format!("chunks {t:?}"))?;
    Ok("echo: hello".into())
}

/// session/new; prompt `#emit <kind>`; wait for an update of `kind` that satisfies `check`.
async fn emitted(prompt_text: &str, kind: &str, check: impl Fn(&Value) -> bool) -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    let r = prompt(&cx, &sid, prompt_text).await?;
    let t0 = Instant::now();
    loop {
        if let Some(u) = updates(&sid).into_iter().find(|u| u["sessionUpdate"] == kind && check(u)) {
            ensure(stop_reason(&r) == "end_turn", format!("stopReason {}", stop_reason(&r)))?;
            return Ok(one_line(&u.to_string()));
        }
        if t0.elapsed() >= UPDATE_GRACE {
            let got: Vec<Value> = updates(&sid);
            return Err(format!("no matching {kind}; got {}", one_line(&Value::Array(got).to_string())));
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
}

async fn update_tool_call_update() -> Result<String, String> {
    let r = emitted("#emit tool_call_update", "tool_call_update", |u| {
        u["toolCallId"] == "call-1" && u["status"] == "completed" && u["content"][0]["content"]["text"] == "tool output"
    })
    .await?;
    let sids_ok = S.updates.lock().unwrap().iter().rev().any(|(_, u)| u["sessionUpdate"] == "tool_call" && u["toolCallId"] == "call-1");
    ensure(sids_ok, "no tool_call call-1 before the update")?;
    Ok(r)
}

async fn update_unknown() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    prompt_end_turn(&cx, &sid, "#emit unknown").await?;
    ensure(wait_chunk(&sid, UPDATE_GRACE, |t| t == "after-unknown").await.is_some(), "no after-unknown chunk")?;
    let other = updates(&sid).iter().filter(|u| u["sessionUpdate"] == "other").count();
    Ok(format!("after-unknown arrived; the unknown update was {}", if other > 0 { "surfaced" } else { "dropped" }))
}

async fn stop(reason: &str) -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    let r = prompt(&cx, &sid, &format!("#stop {reason}")).await?;
    ensure(stop_reason(&r) == reason, format!("stopReason {}", stop_reason(&r)))?;
    Ok(format!("stopReason {reason}"))
}

async fn mode_set() -> Result<String, String> {
    let cx = main_cx()?;
    let (sid, r) = new_session_in(&cx, &dir()).await?;
    let modes = &r["modes"]["availableModes"];
    ensure(modes.as_array().is_some_and(|a| a.iter().any(|m| m["id"] == "interop-mode-b")), format!("modes {}", r["modes"]))?;
    cx.send_request::<SetSessionModeRequest>(typed(json!({ "sessionId": sid, "modeId": "interop-mode-b" })))
        .block_task()
        .await
        .ctx("session/set_mode")?;
    Ok("interop-mode-b set".into())
}

fn option<'a>(list: &'a Value, id: &str) -> Option<&'a Value> {
    list.as_array()?.iter().find(|o| o["id"] == id)
}

async fn config_on_new() -> Result<String, String> {
    let (_, r) = new_session_in(&main_cx()?, &dir()).await?;
    let model = option(&r["configOptions"], "model").ok_or(format!("configOptions {}", r["configOptions"]))?;
    ensure(model["type"] == "select" && model["currentValue"] == "model-a", format!("model {model}"))?;
    Ok(one_line(&r["configOptions"].to_string()))
}

async fn config_select() -> Result<String, String> {
    let cx = main_cx()?;
    let (sid, new) = new_session_in(&cx, &dir()).await?;
    let r = cx
        .send_request::<SetSessionConfigOptionRequest>(typed(json!({ "sessionId": sid, "configId": "model", "value": "model-b" })))
        .block_task()
        .await
        .ctx("session/set_config_option")?;
    let list = to_json(&r)["configOptions"].clone();
    let model = option(&list, "model").ok_or(format!("configOptions {list}"))?;
    ensure(model["currentValue"] == "model-b", format!("model {model}"))?;
    if let Some(before) = new["configOptions"].as_array() {
        let ids = |l: &Vec<Value>| l.iter().map(|o| o["id"].to_string()).collect::<Vec<_>>();
        let after = list.as_array().cloned().unwrap_or_default();
        ensure(ids(before) == ids(&after), format!("not the complete list: {list}"))?;
    }
    Ok(one_line(&list.to_string()))
}

async fn config_boolean() -> Result<String, String> {
    let cx = main_cx()?;
    let (sid, _) = new_session_in(&cx, &dir()).await?;
    let r = cx
        .send_request::<SetSessionConfigOptionRequest>(typed(
            json!({ "sessionId": sid, "configId": "verbose", "type": "boolean", "value": true }),
        ))
        .block_task()
        .await
        .ctx("session/set_config_option")?;
    let list = to_json(&r)["configOptions"].clone();
    let v = option(&list, "verbose").ok_or(format!("configOptions {list}"))?;
    ensure(v["currentValue"] == true, format!("verbose {v}"))?;
    Ok(one_line(&list.to_string()))
}

async fn perm_selected() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    prompt_end_turn(&cx, &sid, "#permission allow").await?;
    let reqs: Vec<Value> = S.perms.lock().unwrap().iter().filter(|(s, _)| *s == sid).map(|(_, r)| r.clone()).collect();
    ensure(reqs.len() == 1, format!("{} permission request(s)", reqs.len()))?;
    ensure(reqs[0]["options"].as_array().is_some_and(|o| !o.is_empty()), "no options")?;
    ensure(wait_chunk(&sid, UPDATE_GRACE, |t| t == "permission: selected allow").await.is_some(),
        format!("chunks {:?}", agent_text(&sid)))?;
    Ok("permission: selected allow".into())
}

async fn perm_cancelled() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    S.cancel_on_perm.lock().unwrap().insert(sid.clone());
    let r = prompt(&cx, &sid, "#permission hold").await;
    S.cancel_on_perm.lock().unwrap().remove(&sid);
    let r = r?;
    let sent = S.cancel_sent.lock().unwrap().get(&sid).copied().ok_or("no permission request arrived")?;
    let after = sent.elapsed();
    ensure(stop_reason(&r) == "cancelled", format!("stopReason {}", stop_reason(&r)))?;
    ensure(after <= Duration::from_secs(5), format!("cancelled {} ms after the cancel", after.as_millis()))?;
    Ok("stopReason cancelled".into())
}

async fn fs_write() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    let path = format!("{}/fs-write.txt", dir());
    prompt_end_turn(&cx, &sid, &format!("#fs write {path} interop write")).await?;
    ensure(wait_chunk(&sid, UPDATE_GRACE, |t| t == "fs write ok").await.is_some(), format!("chunks {:?}", agent_text(&sid)))?;
    let content = std::fs::read_to_string(&path).map_err(|e| format!("{path}: {e}"))?;
    ensure(content == "interop write", format!("file contains {content:?}"))?;
    Ok("fs write ok".into())
}

async fn fs_read(args: &str, check: impl Fn(&str) -> bool) -> Result<String, String> {
    let cx = main_cx()?;
    let path = format!("{}/fs-read.txt", dir());
    std::fs::write(&path, FS_READ_CONTENT).map_err(|e| e.to_string())?;
    let sid = new_session(&cx).await?;
    prompt_end_turn(&cx, &sid, &format!("#fs read {path}{args}")).await?;
    let t = wait_chunk(&sid, UPDATE_GRACE, &check).await;
    ensure(t.is_some(), format!("chunks {:?}", agent_text(&sid)))?;
    Ok(format!("chunk {:?}", t.unwrap_or_default()))
}

async fn fs_read_missing() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    prompt_end_turn(&cx, &sid, &format!("#fs read {}/no-such-file.txt", dir())).await?;
    let t = wait_chunk(&sid, UPDATE_GRACE, |t| t.starts_with("fs read error")).await;
    ensure(t.is_some(), format!("chunks {:?}", agent_text(&sid)))?;
    Ok(t.unwrap_or_default())
}

async fn chunk_step(text: &str, within: Duration, check: impl Fn(&str) -> bool) -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    let t0 = Instant::now();
    let r = tokio::time::timeout(within, prompt(&cx, &sid, text)).await.map_err(|_| format!("TIMEOUT after {} ms", within.as_millis()))??;
    let _ = t0;
    let t = wait_chunk(&sid, UPDATE_GRACE, &check).await;
    ensure(t.is_some(), format!("stopReason {}; chunks {:?}", stop_reason(&r), one_line(&agent_text(&sid))))?;
    Ok(format!("chunk {}", one_line(&t.unwrap_or_default())))
}

async fn elicit_form() -> Result<String, String> {
    chunk_step("#elicit form", cfg().timeout, |t| {
        t.strip_prefix("elicit: accept ")
            .and_then(|j| serde_json::from_str::<Value>(j).ok())
            .is_some_and(|v| v == json!({ "name": "interop" }))
    })
    .await
}

async fn elicit_complete() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    prompt(&cx, &sid, "#elicit url").await?;
    let ok = wait_for(UPDATE_GRACE, || S.elicit_complete.lock().unwrap().iter().any(|e| e == "elic-1")).await;
    ensure(ok, "no elicitation/complete for elic-1")?;
    Ok("elicitation/complete elic-1".into())
}

async fn cancel_prompt() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    let slow = cx.send_request(prompt_request(&sid, "#slow", false));
    ensure(wait_chunk(&sid, Duration::from_secs(5), |t| t == "tick").await.is_some(), "no tick")?;
    let t = send_cancel(&cx, &sid).await;
    let r = tokio::time::timeout(Duration::from_secs(5), slow.block_task())
        .await
        .map_err(|_| "TIMEOUT: no answer within 5 s of the cancel".to_string())?
        .ctx("session/prompt")?;
    let r = to_json(&r);
    ensure(stop_reason(&r) == "cancelled", format!("stopReason {}", stop_reason(&r)))?;
    Ok(format!("cancelled {} ms after the cancel", t.elapsed().as_millis()))
}

async fn cancel_prompt_while_cancelling() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    let slow = cx.send_request(prompt_request(&sid, "#slow grace=1000", false));
    ensure(wait_chunk(&sid, Duration::from_secs(5), |t| t == "tick").await.is_some(), "no tick")?;
    send_cancel(&cx, &sid).await;
    let during = prompt(&cx, &sid, "during cancel").await;
    let first = slow.block_task().await.ctx("first prompt")?;
    ensure(stop_reason(&to_json(&first)) == "cancelled", format!("first prompt answered {}", stop_reason(&to_json(&first))))?;
    match during {
        Ok(r) => return Err(format!("\"during cancel\" answered {}", stop_reason(&r))),
        Err(e) => ensure(e.contains("error -32600"), format!("\"during cancel\" failed with {e}"))?,
    }
    prompt_end_turn(&cx, &sid, "after cancel").await?;
    Ok("cancelled; during cancel -32600; after cancel end_turn".into())
}

async fn cancel_grace() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    let hang = cx.send_request(prompt_request(&sid, "#hang", false));
    tokio::time::sleep(Duration::from_millis(200)).await;
    let t = send_cancel(&cx, &sid).await;
    let r = tokio::time::timeout(Duration::from_secs(6), hang.block_task())
        .await
        .map_err(|_| "TIMEOUT: no answer within 6 s of the cancel".to_string())?
        .ctx("session/prompt")?;
    let r = to_json(&r);
    ensure(stop_reason(&r) == "cancelled", format!("stopReason {}", stop_reason(&r)))?;
    Ok(format!("cancelled {} ms after the cancel", t.elapsed().as_millis()))
}

async fn cancel_request_client() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    let slow = cx.send_request(prompt_request(&sid, "#slow", false));
    ensure(wait_chunk(&sid, Duration::from_secs(5), |t| t == "tick").await.is_some(), "no tick")?;
    slow.cancel().ctx("$/cancel_request")?;
    let t = Instant::now();
    match tokio::time::timeout(Duration::from_secs(5), slow.block_task()).await {
        Err(_) => Err("TIMEOUT: the prompt did not end within 5 s of $/cancel_request".into()),
        Ok(Err(e)) if code_of(&e) == -32800 => Ok(format!("-32800 after {} ms", t.elapsed().as_millis())),
        Ok(Err(e)) => Err(describe(&e)),
        Ok(Ok(r)) if stop_reason(&to_json(&r)) == "cancelled" => Ok(format!("cancelled after {} ms", t.elapsed().as_millis())),
        Ok(Ok(r)) => Err(format!("stopReason {}", stop_reason(&to_json(&r)))),
    }
}

async fn cancel_request_unknown() -> Result<String, String> {
    let cx = main_cx()?;
    cx.send_cancel_request(999_999i64).ctx("$/cancel_request 999999")?;
    let done = cx.send_request::<NewSessionRequest>(typed(json!({ "cwd": dir(), "mcpServers": [] })));
    let id = done.id().clone();
    let r = done.block_task().await.ctx("session/new")?;
    cx.send_cancel_request(id.clone()).ctx("$/cancel_request of a completed request")?;
    let _ = r;
    let sid = new_session(&cx).await?;
    prompt_end_turn(&cx, &sid, "after cancel-request").await?;
    Ok(format!("ignored: 999999 and completed id {id}"))
}

async fn ext_agent_notification() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    prompt(&cx, &sid, "#ext notify _interop/note").await?;
    let ok = wait_for(UPDATE_GRACE, || {
        S.ext_notifications.lock().unwrap().iter().any(|(m, p)| m == EXT_NOTIFICATION && *p == json!({ "n": 1 }))
    })
    .await;
    ensure(ok, format!("notifications {:?}", S.ext_notifications.lock().unwrap()))?;
    Ok("_interop/note {\"n\":1}".into())
}

async fn ext_client_request() -> Result<String, String> {
    let cx = main_cx()?;
    let req = UntypedMessage::new(EXT_METHOD, json!({ "n": 1 })).map_err(|e| describe(&e))?;
    let r = cx.send_request(req).block_task().await.ctx(EXT_METHOD)?;
    ensure(r == json!({ "pong": 1 }), format!("result {r}"))?;
    Ok(format!("result {r}"))
}

async fn ext_client_notification() -> Result<String, String> {
    let cx = main_cx()?;
    let n = UntypedMessage::new(EXT_NOTIFICATION, json!({ "n": 1 })).map_err(|e| describe(&e))?;
    cx.send_notification(n).ctx(EXT_NOTIFICATION)?;
    let sid = new_session(&cx).await?;
    prompt(&cx, &sid, "#ext last-notification").await?;
    let t = wait_chunk(&sid, UPDATE_GRACE, |t| t == "ext last: _interop/note").await;
    ensure(t.is_some(), format!("chunks {:?}", agent_text(&sid)))?;
    Ok("ext last: _interop/note".into())
}

async fn meta_prompt() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    let r = cx.send_request(prompt_request(&sid, "#meta", true)).block_task().await.ctx("session/prompt")?;
    let r = to_json(&r);
    let ok = wait_for(UPDATE_GRACE, || {
        updates(&sid).iter().any(|u| u["sessionUpdate"] == "agent_message_chunk" && u["content"]["text"] == "meta" && has_meta(u))
    })
    .await;
    ensure(ok, format!("updates {}", one_line(&Value::Array(updates(&sid)).to_string())))?;
    ensure(has_meta(&r), format!("PromptResponse {r}"))?;
    Ok("update and response carry _meta interop=m1".into())
}

async fn meta_permission() -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    prompt(&cx, &sid, "#permission allow meta").await?;
    let req = S.perms.lock().unwrap().iter().find(|(s, _)| *s == sid).map(|(_, r)| r.clone()).ok_or("no permission request")?;
    ensure(has_meta(&req), format!("request _meta {}", req.get("_meta").unwrap_or(&Value::Null)))?;
    Ok("request carried _meta interop=m1".into())
}

async fn error_method_not_found() -> Result<String, String> {
    let cx = main_cx()?;
    let req = UntypedMessage::new("interop/no_such_method", json!({})).map_err(|e| describe(&e))?;
    let r = cx.send_request(req).block_task().await;
    let code = match &r {
        Ok(v) => return Err(format!("answered {v}")),
        Err(e) => code_of(e),
    };
    ensure(code == -32601, format!("code {code}"))?;
    new_session(&cx).await?;
    Ok("-32601, then session/new succeeded".into())
}

async fn big_prompt(n: usize) -> Result<String, String> {
    let text = format!("#len {}", "x".repeat(n));
    let want = format!("len={n}");
    chunk_step(&text, cfg().timeout, |t| t == want).await
}

async fn big_update(n: usize) -> Result<String, String> {
    let cx = main_cx()?;
    let sid = new_session(&cx).await?;
    prompt_end_turn(&cx, &sid, &format!("#big {n}")).await?;
    let ok = wait_for(UPDATE_GRACE, || texts(&updates(&sid), "agent_message_chunk").iter().any(|t| t.len() == n)).await;
    let lens: Vec<usize> = texts(&updates(&sid), "agent_message_chunk").iter().map(String::len).collect();
    ensure(ok, format!("chunk lengths {lens:?}"))?;
    Ok(format!("one chunk of {n} characters"))
}

async fn http_reconnect() -> Result<String, String> {
    let second = Conn::open().await?;
    initialize(&second.cx).await?;
    let sid = new_session(&second.cx).await?;
    prompt_end_turn(&second.cx, &sid, "before reconnect").await?;
    second.close().await?;
    let third = Conn::open().await?;
    initialize(&third.cx).await?;
    load(&third.cx, &sid).await?;
    prompt_end_turn(&third.cx, &sid, "after reconnect").await?;
    third.close().await?;
    Ok(format!("reloaded {sid} on a new connection"))
}

async fn stdio_eof_exit() -> Result<String, String> {
    let cmd = cfg().agent_cmd.clone().unwrap_or_default();
    let agent = AcpAgent::new(AcpAgentConfig::new("bash").arg("-c").arg(format!("exec {cmd}")));
    let (stdin, stdout, stderr, mut child) = agent.spawn_process().map_err(|e| describe(&e))?;
    tokio::spawn(async move {
        use futures_lite_lines::lines;
        lines(stderr, |l| relay(&l, LineDirection::Stderr)).await;
    });
    let streams = agent_client_protocol::ByteStreams::new(stdin, stdout);
    let r = agent_client_protocol::Client
        .builder()
        .name("interop-rust-eof-client")
        .connect_with(streams, async |cx: ConnectionTo<Agent>| cx.send_request(init_request()).block_task().await)
        .await;
    r.ctx("initialize of the second agent")?;
    // connect_with has returned: the transport, and with it the child's stdin, is dropped (EOF).
    let t = Instant::now();
    match tokio::time::timeout(Duration::from_secs(5), child.status()).await {
        Ok(Ok(s)) => Ok(format!("exited {s} {} ms after EOF", t.elapsed().as_millis())),
        Ok(Err(e)) => Err(format!("wait failed: {e}")),
        Err(_) => {
            let _ = child.kill();
            Err("no exit on EOF".into())
        }
    }
}

/// Reads an async_process pipe line by line (the SDK's AcpAgent exposes futures-io streams).
mod futures_lite_lines {
    pub async fn lines(pipe: impl futures::io::AsyncRead + Unpin, f: impl Fn(String)) {
        use futures::io::AsyncBufReadExt;
        use futures::stream::StreamExt;
        let mut l = futures::io::BufReader::new(pipe).lines();
        while let Some(Ok(line)) = l.next().await {
            f(line);
        }
    }
}

async fn conn_close() -> Result<String, String> {
    let conn = MAIN.lock().unwrap().take().ok_or("no main connection")?;
    conn.close().await
}

// ------------------------------------------------------------------ main

static PASS: AtomicUsize = AtomicUsize::new(0);
static FAIL: AtomicUsize = AtomicUsize::new(0);

async fn run<F: Future<Output = Result<String, String>>>(id: &str, f: F) {
    let t0 = Instant::now();
    let r = match tokio::time::timeout(cfg().timeout, f).await {
        Ok(r) => r,
        Err(_) => Err(format!("TIMEOUT after {} ms", cfg().timeout.as_millis())),
    };
    match r {
        Ok(d) => {
            PASS.fetch_add(1, SeqCst);
            println!("STEP {id} PASS ({} ms) -> {}", ms(t0), one_line(&d));
        }
        Err(e) => {
            FAIL.fetch_add(1, SeqCst);
            println!("STEP {id} FAIL ({} ms) -> {}", ms(t0), one_line(&e));
        }
    }
}

async fn step(id: &str) {
    let to = cfg().timeout;
    match id {
        "init.initialize" => run(id, init_initialize()).await,
        "init.agent-capabilities" => run(id, init_agent_capabilities()).await,
        "init.client-capabilities" => run(id, init_client_capabilities()).await,
        "init.auth-methods" => run(id, init_auth_methods()).await,
        "init.agent-info" => run(id, init_agent_info()).await,
        "init.config-boolean" => run(id, init_config_boolean()).await,
        "auth.authenticate" => run(id, auth_authenticate()).await,
        "auth.logout" => run(id, auth_logout()).await,
        "auth.logout-capability" => run(id, auth_logout_capability()).await,
        "auth.terminal" => run(id, auth_terminal()).await,
        "session.new" => run(id, session_new()).await,
        "session.load" => run(id, session_load()).await,
        "session.load-replay" => run(id, session_load_replay()).await,
        "session.resume" => run(id, session_resume()).await,
        "session.list" => run(id, session_list()).await,
        "session.close" => run(id, session_close()).await,
        "session.delete" => run(id, session_delete()).await,
        "session.multi" => run(id, session_multi()).await,
        "session.fork" => run(id, session_fork()).await,
        "update.agent_message_chunk" => run(id, update_agent_message_chunk()).await,
        "update.user_message_chunk" => {
            run(id, emitted("#emit user_message_chunk", "user_message_chunk", |u| u["content"]["text"] == "user-chunk")).await
        }
        "update.agent_thought_chunk" => {
            run(id, emitted("#emit agent_thought_chunk", "agent_thought_chunk", |u| u["content"]["text"] == "thinking")).await
        }
        "update.tool_call" => {
            run(id, emitted("#emit tool_call", "tool_call", |u| {
                // The SDK omits a default status when it re-serializes; absent means pending.
                u["toolCallId"] == "call-1" && u["title"] == "interop tool" && u["kind"] == "read"
                    && (u["status"] == "pending" || u.get("status").is_none())
            }))
            .await
        }
        "update.tool_call_update" => run(id, update_tool_call_update()).await,
        "update.tool_call-name" => run(id, emitted("#emit tool_call name=read_file", "tool_call", |u| u["name"] == "read_file")).await,
        "update.plan" => {
            run(id, emitted("#emit plan", "plan", |u| {
                u["entries"]
                    == json!([
                        { "content": "step one", "priority": "high", "status": "pending" },
                        { "content": "step two", "priority": "low", "status": "completed" }
                    ])
            }))
            .await
        }
        "update.available_commands_update" => {
            run(id, emitted("#emit available_commands_update", "available_commands_update", |u| {
                let c = &u["availableCommands"];
                c.as_array().is_some_and(|a| a.len() == 1) && c[0]["name"] == "interop" && c[0]["input"]["hint"] == "args"
            }))
            .await
        }
        "update.current_mode_update" => {
            run(id, emitted("#emit current_mode_update", "current_mode_update", |u| u["currentModeId"] == "interop-mode-b")).await
        }
        "update.config_option_update" => {
            run(id, emitted("#emit config_option_update", "config_option_update", |u| {
                option(&u["configOptions"], "model").is_some_and(|m| m["currentValue"] == "model-b")
            }))
            .await
        }
        "update.session_info_update" => {
            run(id, emitted("#emit session_info_update", "session_info_update", |u| u["title"] == "interop title")).await
        }
        "update.usage_update" => {
            run(id, emitted("#emit usage_update", "usage_update", |u| {
                u["used"] == 100 && u["size"] == 1000 && u["cost"]["amount"] == 0.01 && u["cost"]["currency"] == "USD"
            }))
            .await
        }
        "update.unknown" => run(id, update_unknown()).await,
        "stop.max_tokens" => run(id, stop("max_tokens")).await,
        "stop.refusal" => run(id, stop("refusal")).await,
        "stop.max_turn_requests" => run(id, stop("max_turn_requests")).await,
        "mode.set" => run(id, mode_set()).await,
        "config.on-new" => run(id, config_on_new()).await,
        "config.select" => run(id, config_select()).await,
        "config.boolean" => run(id, config_boolean()).await,
        "perm.selected" => run(id, perm_selected()).await,
        "perm.cancelled" => run(id, perm_cancelled()).await,
        "fs.write" => run(id, fs_write()).await,
        "fs.read" => run(id, fs_read("", |t| t == FS_READ_CONTENT)).await,
        "fs.read-range" => run(id, fs_read(" line=2 limit=1", |t| t.trim() == "line2")).await,
        "fs.read-missing" => run(id, fs_read_missing()).await,
        "term.run" => run(id, chunk_step("#terminal run echo hi", to, |t| t == "terminal: hi exit=0")).await,
        "term.kill" => run(id, chunk_step("#terminal kill sleep 30", Duration::from_secs(5), |t| t == "terminal killed")).await,
        "elicit.form" => run(id, elicit_form()).await,
        "elicit.complete" => run(id, elicit_complete()).await,
        "cancel.prompt" => run(id, cancel_prompt()).await,
        "cancel.prompt-while-cancelling" => run(id, cancel_prompt_while_cancelling()).await,
        "cancel.grace" => run(id, cancel_grace()).await,
        "cancel-request.client" => run(id, cancel_request_client()).await,
        "cancel-request.agent" => {
            run(id, chunk_step(&format!("#fs read-slow {}/slow.txt", dir()), Duration::from_secs(5), |t| t == "cancel-request sent"))
                .await
        }
        "cancel-request.unknown" => run(id, cancel_request_unknown()).await,
        "ext.agent-request" => {
            run(id, chunk_step("#ext request _interop/ping", to, |t| {
                t.strip_prefix("ext: ").and_then(|j| serde_json::from_str::<Value>(j).ok()) == Some(json!({ "pong": 1 }))
            }))
            .await
        }
        "ext.agent-notification" => run(id, ext_agent_notification()).await,
        "ext.client-request" => run(id, ext_client_request()).await,
        "ext.client-notification" => run(id, ext_client_notification()).await,
        "meta.prompt" => run(id, meta_prompt()).await,
        "meta.permission" => run(id, meta_permission()).await,
        "error.method-not-found" => run(id, error_method_not_found()).await,
        "big.prompt-1m" => run(id, big_prompt(1_048_576)).await,
        "big.update-1m" => run(id, big_update(1_048_576)).await,
        "big.prompt-8m" => run(id, big_prompt(8_388_608)).await,
        "big.update-8m" => run(id, big_update(8_388_608)).await,
        "http.reconnect" => run(id, http_reconnect()).await,
        "stdio.eof-exit" => run(id, stdio_eof_exit()).await,
        "conn.close" => run(id, conn_close()).await,
        _ => {
            FAIL.fetch_add(1, SeqCst);
            println!("STEP {id} FAIL (0 ms) -> unknown-step");
        }
    }
}

fn usage(problem: &str) -> ! {
    eprintln!("client: {problem}");
    eprintln!("usage: STEPS=<ids> interop-client --transport stdio (with AGENT_CMD) | --transport http|ws --url <url>");
    std::process::exit(2);
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .with_writer(std::io::stderr)
        .init();
    let mut transport = None;
    let mut url = None;
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        match a.as_str() {
            "--transport" => transport = Some(args.next().unwrap_or_else(|| usage("--transport needs a value"))),
            "--url" => url = Some(args.next().unwrap_or_else(|| usage("--url needs a value"))),
            other => usage(&format!("unknown argument {other}")),
        }
    }
    let transport = match transport.as_deref() {
        Some(t @ ("stdio" | "http" | "ws")) => t.to_string(),
        _ => usage("--transport stdio|http|ws is required"),
    };
    if transport != "stdio" && url.is_none() {
        usage(&format!("--url is required for {transport}"));
    }
    let agent_cmd = std::env::var("AGENT_CMD").ok();
    if transport == "stdio" && agent_cmd.is_none() {
        usage("AGENT_CMD is required for stdio");
    }
    let steps = std::env::var("STEPS").unwrap_or_default();
    if steps.trim().is_empty() {
        usage("STEPS is required");
    }
    let timeout = Duration::from_millis(std::env::var("STEP_TIMEOUT_MS").ok().and_then(|v| v.parse().ok()).unwrap_or(15_000));
    let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
    let dir = std::env::temp_dir().join(format!("acp-interop-rs-{}-{nanos}", std::process::id()));
    std::fs::create_dir_all(&dir).expect("scratch directory");
    let _ = CFG.set(Config { transport, url, agent_cmd, timeout, dir });
    for id in steps.split(',').map(str::trim).filter(|s| !s.is_empty()) {
        step(id).await;
    }
    // A run without conn.close still ends its connection.
    let left = MAIN.lock().unwrap().take();
    if let Some(c) = left {
        let _ = c.close().await;
    }
    let kinds: Vec<String> = S.kinds.lock().unwrap().iter().map(|(k, v)| format!("upd_{k}={v}")).collect();
    println!(
        "RESULT pass={} fail={} updates_total={} {}",
        PASS.load(SeqCst),
        FAIL.load(SeqCst),
        S.total.load(SeqCst),
        kinds.join(" ")
    );
    use std::io::Write;
    let _ = std::io::stdout().flush();
    std::process::exit(0);
}
