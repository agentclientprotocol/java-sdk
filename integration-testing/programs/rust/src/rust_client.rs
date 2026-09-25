//! Interop client on the Rust SDK's Streamable HTTP client. Usage: rust-client <url>. Prints one
//! "STEP <name> PASS|FAIL" line per step and a RESULT line with pass/fail counts and the number
//! of session updates each step received. Dropping the connection sends the DELETE.
use agent_client_protocol::schema::ProtocolVersion;
use agent_client_protocol::schema::v1::*;
use agent_client_protocol::{Agent, ConnectionTo};
use agent_client_protocol_http::HttpClient;
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, Ordering::SeqCst};
use std::time::Instant;

static UPDATES: AtomicUsize = AtomicUsize::new(0);
static PASS: AtomicUsize = AtomicUsize::new(0);
static FAIL: AtomicUsize = AtomicUsize::new(0);
static PER_STEP: Mutex<Vec<(String, usize)>> = Mutex::new(Vec::new());

fn begin() -> Instant {
    UPDATES.store(0, SeqCst);
    Instant::now()
}

fn record(name: &str, t: Instant, r: Result<String, String>) {
    let ms = t.elapsed().as_millis();
    match r {
        Ok(d) => {
            PASS.fetch_add(1, SeqCst);
            println!("STEP {name} PASS ({ms} ms) -> {d}");
        }
        Err(e) => {
            FAIL.fetch_add(1, SeqCst);
            println!("STEP {name} FAIL ({ms} ms) -> {e}");
        }
    }
    let key: String = name.chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '_' }).collect();
    PER_STEP.lock().unwrap().push((format!("{key}_updates"), UPDATES.load(SeqCst)));
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt().with_env_filter(tracing_subscriber::EnvFilter::from_default_env()).with_writer(std::io::stderr).init();
    let url = std::env::args().nth(1).expect("usage: rust-client <url>");
    let http = HttpClient::with_endpoint(&url)?;
    let t0 = Instant::now();
    let res = agent_client_protocol::Client.builder()
        .on_receive_notification(async move |n: SessionNotification, _cx| {
            UPDATES.fetch_add(1, SeqCst);
            println!("  update: {:?}", n.update);
            Ok(())
        }, agent_client_protocol::on_receive_notification!())
        .on_receive_request(async move |req: RequestPermissionRequest, responder, _cx| {
            println!("  permission request received, options={}", req.options.len());
            responder.respond(RequestPermissionResponse::new(RequestPermissionOutcome::Selected(
                SelectedPermissionOutcome::new(req.options[0].option_id.clone()))))
        }, agent_client_protocol::on_receive_request!())
        .connect_with(http, |c: ConnectionTo<Agent>| async move {
            let t = begin();
            let r = c.send_request(InitializeRequest::new(ProtocolVersion::V1)).block_task().await;
            record("initialize", t, r.map(|i| format!("loadSession={}", i.agent_capabilities.load_session)).map_err(|e| format!("{e:?}")));

            let t = begin();
            let r = c.send_request(NewSessionRequest::new(std::path::PathBuf::from("/tmp"))).block_task().await;
            let sid = r.as_ref().map(|s| s.session_id.clone()).unwrap_or_else(|_| SessionId::new("missing"));
            record("session/new", t, r.map(|s| format!("{}", s.session_id)).map_err(|e| format!("{e:?}")));

            for (name, text) in [("prompt1", "hello one"), ("prompt2", "hello two"), ("prompt3-permission", "please ask permission")] {
                let t = begin();
                let r = c.send_request(PromptRequest::new(sid.clone(), vec![ContentBlock::Text(TextContent::new(text.to_string()))])).block_task().await;
                record(name, t, r.map(|p| format!("stop={:?}", p.stop_reason)).map_err(|e| format!("{e:?}")));
            }

            let t = begin();
            let r = c.send_request(LoadSessionRequest::new(sid.clone(), std::path::PathBuf::from("/tmp"))).block_task().await;
            record("session/load", t, r.map(|_| "loaded".to_string()).map_err(|e| format!("{e:?}")));

            let t = begin();
            let r = c.send_request(PromptRequest::new(sid.clone(), vec![ContentBlock::Text(TextContent::new("after load".to_string()))])).block_task().await;
            record("prompt-after-load", t, r.map(|p| format!("stop={:?}", p.stop_reason)).map_err(|e| format!("{e:?}")));
            Ok(())
        }).await;
    println!("connect_with result: {res:?} total {} ms", t0.elapsed().as_millis());
    let per: Vec<String> = PER_STEP.lock().unwrap().iter().map(|(k, v)| format!("{k}={v}")).collect();
    println!("RESULT pass={} fail={} {}", PASS.load(SeqCst), FAIL.load(SeqCst), per.join(" "));
    // Give the transport time to send its DELETE before the runtime shuts down.
    tokio::time::sleep(std::time::Duration::from_millis(1000)).await;
    Ok(())
}
