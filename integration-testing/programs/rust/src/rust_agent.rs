//! Interop agent on the Rust SDK's Streamable HTTP server (axum). Each prompt streams two updates;
//! a prompt containing "permission" first asks the client. Env: PORT. Prints "READY <port>" on
//! stdout once bound, and one [http] line per request.
use agent_client_protocol::schema::v1::*;
use agent_client_protocol::{Agent, Client, ConnectTo, ConnectionTo};
use agent_client_protocol_http::AcpHttpServer;

#[derive(Clone)]
struct Echo;

impl ConnectTo<Client> for Echo {
    async fn connect_to(self, client: impl ConnectTo<Agent>) -> Result<(), agent_client_protocol::Error> {
        Agent.builder().name("rust-interop-agent")
            .on_receive_request(async move |req: InitializeRequest, responder, _cx| {
                eprintln!("[agent] initialize");
                responder.respond(InitializeResponse::new(req.protocol_version)
                    .agent_capabilities(AgentCapabilities::new().load_session(true)))
            }, agent_client_protocol::on_receive_request!())
            .on_receive_request(async move |_req: NewSessionRequest, responder, _cx| {
                eprintln!("[agent] session/new");
                responder.respond(NewSessionResponse::new(SessionId::new("rust-session-1")))
            }, agent_client_protocol::on_receive_request!())
            .on_receive_request(async move |req: LoadSessionRequest, responder, cx: ConnectionTo<Client>| {
                eprintln!("[agent] session/load {}", req.session_id);
                cx.send_notification(SessionNotification::new(req.session_id.clone(),
                    SessionUpdate::AgentMessageChunk(ContentChunk::new("replayed history".to_string().into()))))?;
                responder.respond(LoadSessionResponse::new())
            }, agent_client_protocol::on_receive_request!())
            .on_receive_request(async move |req: PromptRequest, responder, cx: ConnectionTo<Client>| {
                eprintln!("[agent] session/prompt");
                let cx2 = cx.clone();
                cx.spawn(async move {
                    let sid = req.session_id.clone();
                    let text = req.prompt.iter().filter_map(|b| if let ContentBlock::Text(t) = b { Some(t.text.clone()) } else { None }).collect::<Vec<_>>().join(" ");
                    if text.contains("permission") {
                        let perm = RequestPermissionRequest::new(sid.clone(),
                            ToolCallUpdate::new("tc-1", ToolCallUpdateFields::new().title("Run thing").kind(ToolKind::Execute).status(ToolCallStatus::Pending)),
                            vec![PermissionOption::new("allow_once", "Allow once", PermissionOptionKind::AllowOnce),
                                 PermissionOption::new("reject_once", "Reject once", PermissionOptionKind::RejectOnce)]);
                        let r = cx2.send_request(perm).block_task().await;
                        eprintln!("[agent] permission result: {r:?}");
                        let out = match r { Ok(resp) => format!("permission outcome: {:?}", resp.outcome), Err(e) => format!("permission error: {e:?}") };
                        cx2.send_notification(SessionNotification::new(sid.clone(), SessionUpdate::AgentMessageChunk(ContentChunk::new(out.into()))))?;
                    }
                    cx2.send_notification(SessionNotification::new(sid.clone(), SessionUpdate::AgentMessageChunk(ContentChunk::new("echo: ".to_string().into()))))?;
                    cx2.send_notification(SessionNotification::new(sid.clone(), SessionUpdate::AgentMessageChunk(ContentChunk::new(text.into()))))?;
                    responder.respond(PromptResponse::new(StopReason::EndTurn))
                })?;
                Ok(())
            }, agent_client_protocol::on_receive_request!())
            .on_receive_notification(async move |_n: CancelNotification, _cx| Ok(()), agent_client_protocol::on_receive_notification!())
            .connect_to(client).await
    }
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt().with_env_filter(tracing_subscriber::EnvFilter::from_default_env()).with_writer(std::io::stderr).init();
    let port: u16 = std::env::var("PORT").unwrap_or("0".into()).parse()?;
    let router = AcpHttpServer::new(|| Echo).into_router()
        .layer(axum::middleware::from_fn(|req: axum::extract::Request, next: axum::middleware::Next| async move {
            let line = format!("[http] {} {} {:?} ct={:?} accept={:?} conn={:?} sess={:?}", req.method(), req.uri(), req.version(),
                req.headers().get("content-type"), req.headers().get("accept"), req.headers().get("acp-connection-id"), req.headers().get("acp-session-id"));
            let resp = next.run(req).await;
            eprintln!("{line} -> {} ct={:?}", resp.status(), resp.headers().get("content-type"));
            resp
        }));
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", port)).await?;
    eprintln!("listening http://127.0.0.1:{port}/acp");
    println!("READY {port}");
    axum::serve(listener, router).await?;
    Ok(())
}
