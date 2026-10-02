package interop

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.agent.client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.protocol.invoke
import com.agentclientprotocol.protocol.jsonRpcInvalidParams
import com.agentclientprotocol.protocol.jsonRpcMethodNotFound
import com.agentclientprotocol.protocol.jsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcErrorResponse
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.transport.StdioTransport
import com.agentclientprotocol.transport.WebSocketTransport
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpVersion
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * The Kotlin interop agent: `agent --transport stdio` or `agent --transport ws --port <port>`.
 * Kotlin has no Streamable HTTP, so `http` is a usage error. Behaviour is driven by the prompt
 * text (steps.json "directives"); `STEP agent.<id>` lines go to stderr.
 */
object AgentMain {
    private const val USAGE = "kt-peer agent --transport stdio | --transport ws --port <port>"

    fun run(argv: List<String>) {
        val args = Args(argv, "agent", USAGE, setOf("transport", "port"))
        when (args["transport"]) {
            "stdio" -> stdio()
            "ws" -> ws(args["port"]?.toIntOrNull() ?: usage("agent", "--port <port> is required for ws", USAGE))
            "http" -> usage("agent", "the Kotlin SDK has no Streamable HTTP transport", USAGE)
            else -> usage("agent", "--transport stdio|ws is required", USAGE)
        }
    }

    /** Newline-delimited JSON-RPC on stdin/stdout; nothing else on stdout; exit at EOF. */
    private fun stdio() {
        val protocolOut = PrintStream(FileOutputStream(FileDescriptor.out), false, Charsets.UTF_8)
        System.setOut(System.err) // anything that prints by mistake goes to stderr, not into the stream
        val reader = System.`in`.bufferedReader(Charsets.UTF_8)
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val transport = TapTransport(
                StdioTransport(
                    parentScope = scope,
                    ioDispatcher = Dispatchers.IO,
                    input = flow {
                        while (true) emit(reader.readLine() ?: break)
                    },
                    output = { line ->
                        protocolOut.print(line)
                        protocolOut.print('\n')
                        protocolOut.flush()
                    },
                    name = "agent",
                )
            )
            val closed = CompletableDeferred<Unit>()
            transport.onClose { closed.complete(Unit) }
            val protocol = Protocol(scope, transport, ProtocolOptions(protocolDebugName = "kotlin-agent"))
            AgentConnection(protocol, transport)
            protocol.start()
            closed.await()
        }
        System.err.println("agent: stdin closed, exiting")
        exitProcess(0)
    }

    /** One WebSocket endpoint, GET /acp with Upgrade; one [AgentConnection] per socket. */
    private fun ws(port: Int) {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            install(WebSockets) {
                maxFrameSize = Long.MAX_VALUE
            }
            routing {
                // What acp-ktor-server's acpProtocolOnServerWebSocket does, with the transport tapped
                // and the handler returning when the socket closes.
                webSocket("/acp") {
                    System.err.println("[http] GET /acp ${call.request.httpVersion} -> 101 upgrade=\"websocket\"")
                    val transport = TapTransport(WebSocketTransport(parentScope = this, wss = this))
                    val closed = CompletableDeferred<Unit>()
                    transport.onClose { closed.complete(Unit) }
                    val protocol = Protocol(this, transport, ProtocolOptions(protocolDebugName = "kotlin-agent"))
                    AgentConnection(protocol, transport)
                    protocol.start()
                    closed.await()
                }
            }
        }.start(wait = false)
        val bound = runBlocking { server.engine.resolvedConnectors().first().port }
        println("READY $bound")
        System.out.flush()
        Thread.currentThread().join()
    }
}

/** Sessions outlive connections (load and resume on a new socket), so they are per process. */
class SessionState(val id: String, val cwd: String) {
    /** Earlier turns: the prompt text and the agent_message_chunk texts sent for it. */
    val history = CopyOnWriteArrayList<Pair<String, List<String>>>()
    @Volatile var model = "model-a"
    @Volatile var verbose = false
    @Volatile var mode = Fixtures.MODE_A
}

object Sessions {
    private val counter = AtomicInteger()
    val all = ConcurrentHashMap<String, SessionState>()

    fun create(cwd: String): SessionState {
        val s = SessionState("kt-session-${ProcessHandle.current().pid()}-${counter.incrementAndGet()}", cwd)
        all[s.id] = s
        return s
    }
}

fun agentStep(id: String, ok: Boolean, t0: Long, detail: String) {
    System.err.println(stepLine("agent.$id", ok, (System.nanoTime() - t0) / 1_000_000, detail))
    System.err.flush()
}

/** The agent side of one connection: the SDK's v1 [Agent] over [protocol], plus extension handlers. */
class AgentConnection(val protocol: Protocol, val tap: TapTransport) : AgentSupport {
    /** The client capabilities exactly as received: the typed ClientCapabilities drops what it does not model. */
    @Volatile var rawCaps: JsonObject? = null
    @Volatile var lastExtNotification: String? = null

    init {
        Agent(protocol, this)
        protocol.setRequestHandlerRaw(Fixtures.extRequestMethod) { Fixtures.extResult }
        protocol.setNotificationHandlerRaw(Fixtures.extNotificationMethod) { lastExtNotification = it.method.name }
    }

    fun clientCap(vararg path: String): Boolean {
        var e: JsonElement? = rawCaps
        for (p in path) e = (e as? JsonObject)?.get(p)
        return e != null && e !is JsonNull && (e as? JsonPrimitive)?.booleanOrNull != false
    }

    val booleanConfig: Boolean get() = clientCap("session", "configOptions", "boolean")

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        val params = currentCoroutineContext().jsonRpcRequest.params as? JsonObject
        rawCaps = params?.get("clientCapabilities") as? JsonObject
        val methods = mutableListOf<AuthMethod>(
            AuthMethod.AgentAuth(AuthMethodId(Fixtures.AUTH_METHOD), "Interop auth", "Accepts any authenticate call")
        )
        if (clientCap("auth", "terminal")) {
            methods += AuthMethod.TerminalAuth(
                AuthMethodId(Fixtures.TERMINAL_AUTH_METHOD), "Interop terminal auth", args = listOf("--login")
            )
        }
        return AgentInfo(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = AgentCapabilities(
                loadSession = true,
                promptCapabilities = PromptCapabilities(audio = false, image = false, embeddedContext = false),
                mcpCapabilities = McpCapabilities(http = false, sse = false),
                sessionCapabilities = SessionCapabilities(
                    list = SessionListCapabilities(),
                    resume = SessionResumeCapabilities(),
                    close = SessionCloseCapabilities(),
                    delete = SessionDeleteCapabilities(),
                ),
                auth = AgentAuthCapabilities(logout = LogoutCapabilities()),
            ),
            authMethods = methods,
            implementation = Implementation("interop-kotlin-agent", "1"),
        )
    }

    override suspend fun authenticate(methodId: AuthMethodId, _meta: JsonElement?): AuthenticateResponse {
        val t0 = System.nanoTime()
        val ok = methodId.value == Fixtures.AUTH_METHOD
        agentStep("auth.authenticate", ok, t0, "methodId ${methodId.value}")
        if (!ok) jsonRpcInvalidParams("unknown auth method ${methodId.value}")
        return AuthenticateResponse()
    }

    override suspend fun logout(_meta: JsonElement?): LogoutResponse = LogoutResponse()

    // Not implemented by this agent. The SDK registers these handlers anyway and its defaults throw
    // NotImplementedError, which goes out as -32603; answer -32601 as for any unknown method.
    override suspend fun listProviders(_meta: JsonElement?): ListProvidersResponse =
        jsonRpcMethodNotFound("Method not supported: providers/list")

    override suspend fun authStatus(_meta: JsonElement?): AuthStatusResponse =
        jsonRpcMethodNotFound("Method not supported: auth/status")

    override suspend fun listSessions(cwd: String?, additionalDirectories: List<String>?, _meta: JsonElement?): Sequence<SessionInfo> =
        Sessions.all.values.filter { cwd == null || it.cwd == cwd }.map { SessionInfo(SessionId(it.id), it.cwd) }.asSequence()

    override suspend fun deleteSession(sessionId: SessionId, _meta: JsonElement?): DeleteSessionResponse {
        Sessions.all.remove(sessionId.value)
        return DeleteSessionResponse()
    }

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession =
        InteropSession(this, Sessions.create(sessionParameters.cwd))

    override suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession {
        val state = Sessions.all[sessionId.value] ?: jsonRpcInvalidParams("unknown session ${sessionId.value}")
        // Replay the history before the load response, as the spec describes. The session is not
        // registered with the SDK yet, so the updates go out through the protocol directly.
        for ((prompt, chunks) in state.history) {
            notify(state.id, SessionUpdate.UserMessageChunk(ContentBlock.Text(prompt)))
            for (c in chunks) notify(state.id, SessionUpdate.AgentMessageChunk(ContentBlock.Text(c)))
        }
        return InteropSession(this, state)
    }

    override suspend fun resumeSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession {
        val state = Sessions.all[sessionId.value] ?: jsonRpcInvalidParams("unknown session ${sessionId.value}")
        return InteropSession(this, state)
    }

    override suspend fun forkSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession {
        val from = Sessions.all[sessionId.value] ?: jsonRpcInvalidParams("unknown session ${sessionId.value}")
        val forked = Sessions.create(sessionParameters.cwd)
        forked.history.addAll(from.history)
        forked.model = from.model
        forked.mode = from.mode
        return InteropSession(this, forked)
    }

    private fun notify(sessionId: String, update: SessionUpdate) {
        AcpMethod.ClientMethods.V1.SessionUpdate(protocol, SessionNotification(SessionId(sessionId), update))
    }
}

/** One session as served on one connection. */
class InteropSession(private val conn: AgentConnection, private val state: SessionState) : AgentSession {
    override val sessionId = SessionId(state.id)

    /** The directive of the running turn, and its job, for cancel and close. */
    @Volatile private var running: String? = null
    @Volatile private var runningJob: Job? = null
    @Volatile private var closing = false

    override val availableModes: List<SessionMode> get() = Fixtures.modes
    override val defaultMode: SessionModeId get() = SessionModeId(state.mode)

    override suspend fun setMode(modeId: SessionModeId, _meta: JsonElement?): SetSessionModeResponse {
        val t0 = System.nanoTime()
        val known = Fixtures.modes.any { it.id == modeId }
        agentStep("mode.set", known && modeId.value == Fixtures.MODE_B, t0, "set_mode ${modeId.value} on ${state.id}")
        if (!known) jsonRpcInvalidParams("unknown mode ${modeId.value}")
        state.mode = modeId.value
        return SetSessionModeResponse()
    }

    override val configOptions: List<SessionConfigOption>
        get() = buildList {
            add(Fixtures.modelOption(state.model))
            if (conn.booleanConfig) add(Fixtures.verboseOption(state.verbose))
        }

    override suspend fun setConfigOption(configId: SessionConfigId, value: SessionConfigOptionValue, _meta: JsonElement?): SetSessionConfigOptionResponse {
        when {
            configId.value == "model" && value is SessionConfigOptionValue.StringValue && value.value in setOf("model-a", "model-b") ->
                state.model = value.value
            configId.value == "verbose" && conn.booleanConfig && value is SessionConfigOptionValue.BoolValue ->
                state.verbose = value.value
            else -> jsonRpcInvalidParams("unknown config option or value: ${configId.value} = $value")
        }
        return SetSessionConfigOptionResponse(configOptions)
    }

    override suspend fun cancel() {
        if (running?.startsWith("#slow") == true) {
            agentStep("cancel.prompt", true, System.nanoTime(), "session/cancel arrived for ${state.id} during #slow")
        }
    }

    override suspend fun close(_meta: JsonElement?): CloseSessionResponse {
        val t0 = System.nanoTime()
        closing = true
        val job = runningJob
        val directive = running
        val wasRunning = job != null && job.isActive
        job?.cancel(CancellationException("session closed"))
        job?.join()
        Sessions.all.remove(state.id)
        agentStep("session.close", wasRunning, t0,
            if (wasRunning) "closed ${state.id}; its running turn ($directive) was cancelled" else "closed ${state.id}; no turn was running")
        return CloseSessionResponse()
    }

    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
        val text = content.firstOrNull()?.let(::textOf) ?: ""
        running = text.substringBefore(' ').takeIf { text.startsWith("#") } ?: "plain"
        runningJob = currentCoroutineContext()[Job]
        try {
            if (!text.startsWith("#")) {
                plain(text)
            } else {
                directive(text, _meta)
            }
        } finally {
            running = null
            runningJob = null
        }
    }

    private suspend fun FlowCollector<Event>.chunk(text: String, meta: JsonElement? = null) =
        emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(text), _meta = meta)))

    private suspend fun FlowCollector<Event>.update(u: SessionUpdate) = emit(Event.SessionUpdateEvent(u))

    private suspend fun FlowCollector<Event>.done(reason: StopReason = StopReason.END_TURN, meta: JsonElement? = null) =
        emit(Event.PromptResponseEvent(PromptResponse(reason, _meta = meta)))

    private suspend fun FlowCollector<Event>.plain(text: String) {
        val chunks = listOf("echo: ", text)
        for (c in chunks) chunk(c)
        state.history += text to chunks
        done()
    }

    private suspend fun FlowCollector<Event>.directive(text: String, meta: JsonElement?) {
        val name = text.substring(1).substringBefore(' ')
        val rest = text.substringAfter(' ', "")
        val args = if (rest.isEmpty()) emptyList() else rest.split(' ')
        when (name) {
            "permission" -> permission(args.firstOrNull() ?: "allow")
            "fs" -> fs(args, rest)
            "emit" -> emitKind(args)
            "stop" -> {
                chunk("stop")
                done(stopReason(args.firstOrNull() ?: ""))
            }
            "slow" -> slow()
            "hang" -> awaitCancellation()
            "terminal" -> terminal(args)
            "elicit" -> elicit(args.firstOrNull() ?: "")
            "ext" -> ext(args)
            "meta" -> {
                chunk("meta", meta)
                done(meta = meta)
            }
            "echo-caps" -> {
                val t0 = System.nanoTime()
                val caps = conn.rawCaps
                agentStep("init.client-capabilities", caps != null, t0,
                    if (caps != null) "initialize carried clientCapabilities" else "initialize carried no clientCapabilities")
                chunk(compact(caps ?: JsonObject(emptyMap())))
                done()
            }
            "len" -> {
                chunk("len=${text.removePrefix("#len ").length}")
                done()
            }
            "big" -> {
                chunk("x".repeat(args.firstOrNull()?.toIntOrNull() ?: 0))
                done()
            }
            else -> throw JsonRpcException(-32602, "unknown directive: #$name")
        }
    }

    private fun stopReason(s: String): StopReason = when (s) {
        "max_tokens" -> StopReason.MAX_TOKENS
        "refusal" -> StopReason.REFUSAL
        "max_turn_requests" -> StopReason.MAX_TURN_REQUESTS
        "cancelled" -> StopReason.CANCELLED
        "end_turn" -> StopReason.END_TURN
        else -> throw JsonRpcException(-32602, "unknown stop reason: $s")
    }

    private suspend fun FlowCollector<Event>.permission(mode: String) {
        val t0 = System.nanoTime()
        val client = currentCoroutineContext().client
        val step = if (mode == "hold") "perm.cancelled" else "perm.selected"
        try {
            val r = client.requestPermissions(Fixtures.permissionToolCall, Fixtures.permissionOptions, Fixtures.meta)
            val said = when (val o = r.outcome) {
                is RequestPermissionOutcome.Selected -> "selected ${o.optionId.value}"
                RequestPermissionOutcome.Cancelled -> "cancelled"
            }
            if (mode == "hold") {
                agentStep(step, said == "cancelled", t0, "outcome $said")
            } else {
                agentStep(step, said == "selected allow", t0, "outcome $said")
            }
            // meta.permission shares "#permission allow" with perm.selected, so its line is only printed
            // when the response carries _meta at all (a client that echoes the request's _meta).
            if (r._meta != null) {
                agentStep("meta.permission", Fixtures.hasMeta(r._meta), t0, "response _meta ${r._meta}")
            }
            chunk("permission: $said")
            done(if (said == "cancelled") StopReason.CANCELLED else StopReason.END_TURN)
        } catch (ce: CancellationException) {
            // session/cancel: the SDK cancels the whole turn, the pending permission request included,
            // so the outcome never reaches this code. Check what the client answered on the wire.
            withContext(NonCancellable) {
                val id = tap().lastOutgoingId("session/request_permission")
                val resp = id?.let { withTimeoutOrNull(3_000) { tap().response(it).await() } }
                val outcome = ((resp as? JsonRpcSuccessResponse)?.result as? JsonObject)
                    ?.get("outcome")?.jsonObject?.get("outcome")?.jsonPrimitive?.contentOrNull
                val detail = when (resp) {
                    null -> "no response to the permission request"
                    is JsonRpcErrorResponse -> "permission request answered with error ${resp.error.code}"
                    else -> "turn cancelled; the client answered the permission request with outcome $outcome"
                }
                agentStep(step, mode == "hold" && outcome == "cancelled", t0, detail)
            }
            throw ce
        }
    }

    private fun tap() = conn.tap

    private suspend fun FlowCollector<Event>.fs(args: List<String>, rest: String) {
        val t0 = System.nanoTime()
        val client = currentCoroutineContext().client
        when (args.firstOrNull()) {
            "write" -> {
                val path = args.getOrNull(1) ?: ""
                val content = rest.removePrefix("write ").substringAfter(' ', "")
                if (!conn.clientCap("fs", "writeTextFile")) {
                    agentStep("fs.write", false, t0, "the client did not advertise fs.writeTextFile")
                    chunk("fs write error capability")
                    return done()
                }
                try {
                    client.fsWriteTextFile(path, content)
                    agentStep("fs.write", true, t0, "fs/write_text_file answered without error")
                    chunk("fs write ok")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    agentStep("fs.write", false, t0, "fs/write_text_file failed: ${errorCode(e)} $e")
                    chunk("fs write error ${errorCode(e)}")
                }
                done()
            }
            "read" -> {
                val path = args.getOrNull(1) ?: ""
                val line = args.firstOrNull { it.startsWith("line=") }?.removePrefix("line=")?.toUIntOrNull()
                val limit = args.firstOrNull { it.startsWith("limit=") }?.removePrefix("limit=")?.toUIntOrNull()
                val step = when {
                    line != null || limit != null -> "fs.read-range"
                    path.endsWith("no-such-file.txt") -> "fs.read-missing"
                    else -> "fs.read"
                }
                if (!conn.clientCap("fs", "readTextFile")) {
                    agentStep(step, false, t0, "the client did not advertise fs.readTextFile")
                    chunk("fs read error capability")
                    return done()
                }
                try {
                    val content = client.fsReadTextFile(path, line, limit).content
                    when (step) {
                        "fs.read" -> agentStep(step, content == Fixtures.FS_READ_CONTENT, t0, "content ${JsonPrimitive(content).toString()}")
                        "fs.read-range" -> agentStep(step, content.trim() == "line2", t0, "content ${JsonPrimitive(content).toString()}")
                        else -> agentStep(step, false, t0, "reading a missing file succeeded")
                    }
                    chunk(content)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val code = errorCode(e)
                    agentStep(step, step == "fs.read-missing" && code != null, t0, "fs/read_text_file failed: $code $e")
                    chunk("fs read error $code")
                }
                done()
            }
            "read-slow" -> {
                val path = args.getOrNull(1) ?: ""
                if (!conn.clientCap("fs", "readTextFile")) {
                    agentStep("cancel-request.agent", false, t0, "the client did not advertise fs.readTextFile")
                    chunk("fs read error capability")
                    return done()
                }
                // Cancelling the call is how the SDK sends $/cancel_request; it then waits up to its
                // graceful timeout for the client's answer. Check that answer on the wire.
                coroutineScope {
                    val call = async { client.fsReadTextFile(path) }
                    delay(200)
                    call.cancel()
                    call.join()
                }
                val id = tap().lastOutgoingId("fs/read_text_file")
                val sentCancel = tap().sentNotifications.any {
                    it.method.name == "\$/cancel_request" && (it.params as? JsonObject)?.get("requestId")?.toString() == id?.toString()
                }
                val resp = id?.let { withTimeoutOrNull(5_000) { tap().response(it).await() } }
                val code = (resp as? JsonRpcErrorResponse)?.error?.code
                agentStep("cancel-request.agent", sentCancel && code == -32800, t0,
                    "\$/cancel_request sent=$sentCancel; fs/read_text_file " +
                        when (resp) {
                            null -> "never answered"
                            is JsonRpcErrorResponse -> "ended with error ${resp.error.code} ${resp.error.message}"
                            else -> "answered with a result"
                        })
                chunk("cancel-request sent")
                done()
            }
            else -> throw JsonRpcException(-32602, "unknown directive: #fs ${args.firstOrNull()}")
        }
    }

    private suspend fun FlowCollector<Event>.emitKind(args: List<String>) {
        val kind = args.firstOrNull() ?: ""
        val name = args.firstOrNull { it.startsWith("name=") }?.removePrefix("name=")
        when (kind) {
            "user_message_chunk" -> update(SessionUpdate.UserMessageChunk(ContentBlock.Text("user-chunk")))
            "agent_thought_chunk" -> update(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("thinking")))
            "tool_call" -> update(toolCall(name))
            "tool_call_update" -> {
                update(toolCall(name))
                update(
                    SessionUpdate.ToolCallUpdate(
                        toolCallId = ToolCallId("call-1"),
                        status = ToolCallStatus.COMPLETED,
                        content = listOf(ToolCallContent.Content(ContentBlock.Text("tool output"))),
                    )
                )
            }
            "plan" -> update(
                SessionUpdate.PlanUpdate(
                    listOf(
                        PlanEntry("step one", PlanEntryPriority.HIGH, PlanEntryStatus.PENDING),
                        PlanEntry("step two", PlanEntryPriority.LOW, PlanEntryStatus.COMPLETED),
                    )
                )
            )
            "available_commands_update" -> update(
                SessionUpdate.AvailableCommandsUpdate(
                    listOf(AvailableCommand("interop", "interop command", AvailableCommandInput.Unstructured("args")))
                )
            )
            "current_mode_update" -> update(SessionUpdate.CurrentModeUpdate(SessionModeId(Fixtures.MODE_B)))
            "config_option_update" -> update(
                SessionUpdate.ConfigOptionUpdate(
                    buildList {
                        add(Fixtures.modelOption("model-b"))
                        if (conn.booleanConfig) add(Fixtures.verboseOption(state.verbose))
                    }
                )
            )
            "session_info_update" -> update(SessionUpdate.SessionInfoUpdate(title = "interop title"))
            "usage_update" -> update(SessionUpdate.UsageUpdate(100, 1000, Cost(0.01, "USD")))
            "unknown" -> {
                // The SDK keeps unknown variants as UnknownSessionUpdate and serializes them back as is.
                update(
                    SessionUpdate.UnknownSessionUpdate(
                        sessionUpdateType = "interop_future_update",
                        rawJson = buildJsonObject { put("payload", buildJsonObject { put("x", 1) }) },
                    )
                )
                chunk("after-unknown")
            }
            else -> throw JsonRpcException(-32602, "unknown directive: #emit $kind")
        }
        done()
    }

    /**
     * The fixture tool call. The v1 ToolCall has no `name` field, so with name= the same tool_call goes
     * out through the SDK's forward-compatible UnknownSessionUpdate, which serializes its raw JSON.
     */
    private fun toolCall(name: String?): SessionUpdate =
        if (name == null) {
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId("call-1"),
                title = "interop tool",
                kind = ToolKind.READ,
                status = ToolCallStatus.PENDING,
            )
        } else {
            SessionUpdate.UnknownSessionUpdate(
                sessionUpdateType = "tool_call",
                rawJson = buildJsonObject {
                    put("toolCallId", "call-1")
                    put("title", "interop tool")
                    put("kind", "read")
                    put("status", "pending")
                    put("name", name)
                },
            )
        }

    private suspend fun FlowCollector<Event>.slow() {
        val t0 = System.nanoTime()
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                chunk("tick")
                delay(100)
            }
            done()
        } catch (ce: CancellationException) {
            if (!closing && byCounterpart(ce)) {
                agentStep("cancel-request.client", true, t0, "the prompt's handler was cancelled by \$/cancel_request")
            }
            throw ce
        }
    }

    /** A cancellation that the SDK raised for the counterpart's `$/cancel_request`. */
    private fun byCounterpart(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(10).any {
            it.javaClass.simpleName == "IncomingRequestCancelledException" || it.message?.contains("by the counterpart") == true
        }

    private suspend fun FlowCollector<Event>.terminal(args: List<String>) {
        val t0 = System.nanoTime()
        val client = currentCoroutineContext().client
        val sub = args.firstOrNull() ?: ""
        val step = if (sub == "kill") "term.kill" else "term.run"
        if (!conn.clientCap("terminal")) {
            agentStep(step, false, t0, "the client did not advertise terminal")
            chunk("terminal error capability")
            return done()
        }
        val cmd = args.getOrNull(1) ?: ""
        val cmdArgs = args.drop(2)
        try {
            val id = client.terminalCreate(cmd, cmdArgs, cwd = null).terminalId
            if (sub == "kill") {
                delay(200)
                client.terminalKill(id)
                val killedAt = System.nanoTime()
                val exit = withTimeoutOrNull(5_000) { client.terminalWaitForExit(id) }
                client.terminalRelease(id)
                val ms = (System.nanoTime() - killedAt) / 1_000_000
                agentStep(step, exit != null, t0,
                    if (exit != null) "wait_for_exit returned $ms ms after the kill (exitCode=${exit.exitCode} signal=${exit.signal})"
                    else "wait_for_exit did not return within 5 s of the kill")
                chunk("terminal killed")
            } else {
                val exit = client.terminalWaitForExit(id)
                val out = client.terminalOutput(id)
                client.terminalRelease(id)
                val code = exit.exitCode?.toInt()
                agentStep(step, out.output.contains("hi") && code == 0, t0, "output ${JsonPrimitive(out.output).toString()} exit=$code")
                chunk("terminal: ${out.output.trim()} exit=$code")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            agentStep(step, false, t0, "terminal call failed: ${errorCode(e)} $e")
            chunk("terminal error ${errorCode(e)}")
        }
        done()
    }

    private suspend fun FlowCollector<Event>.elicit(mode: String) {
        val t0 = System.nanoTime()
        val client = currentCoroutineContext().client
        when (mode) {
            "form" -> {
                if (!conn.clientCap("elicitation")) {
                    agentStep("elicit.form", false, t0, "the client did not advertise elicitation")
                    chunk("elicit error capability")
                    return done()
                }
                val r = client.createElicitation(
                    CreateElicitationRequest(
                        scope = ElicitationScope.Session(sessionId),
                        mode = ElicitationMode.Form(
                            ElicitationSchema(
                                properties = mapOf("name" to ElicitationPropertySchema.StringProperty()),
                                required = listOf("name"),
                            )
                        ),
                        message = "interop form",
                    )
                )
                val (action, content) = when (val a = r.action) {
                    is ElicitationAction.Accept -> "accept" to JsonObject(
                        (a.content ?: emptyMap()).mapValues { (_, v) -> contentJson(v) }
                    )
                    ElicitationAction.Decline -> "decline" to JsonObject(emptyMap())
                    ElicitationAction.Cancel -> "cancel" to JsonObject(emptyMap())
                }
                val name = (content["name"] as? JsonPrimitive)?.contentOrNull
                agentStep("elicit.form", action == "accept" && name == "interop", t0, "action $action content ${compact(content)}")
                chunk("elicit: $action ${compact(content)}")
            }
            "url" -> {
                if (!conn.clientCap("elicitation", "url")) {
                    chunk("elicit error capability")
                    return done()
                }
                client.createElicitation(
                    CreateElicitationRequest(
                        scope = ElicitationScope.Session(sessionId),
                        mode = ElicitationMode.Url(ElicitationId("elic-1"), "https://example.invalid/interop"),
                        message = "interop url",
                    )
                )
                client.completeElicitation(CompleteElicitationNotification(ElicitationId("elic-1")))
                chunk("elicit url done")
            }
            else -> throw JsonRpcException(-32602, "unknown directive: #elicit $mode")
        }
        done()
    }

    private fun contentJson(v: ElicitationContentValue): JsonElement = when (v) {
        is ElicitationContentValue.StringValue -> JsonPrimitive(v.value)
        is ElicitationContentValue.IntegerValue -> JsonPrimitive(v.value)
        is ElicitationContentValue.NumberValue -> JsonPrimitive(v.value)
        is ElicitationContentValue.BooleanValue -> JsonPrimitive(v.value)
        is ElicitationContentValue.StringArrayValue -> JsonArray(v.value.map { JsonPrimitive(it) })
    }

    private suspend fun FlowCollector<Event>.ext(args: List<String>) {
        val t0 = System.nanoTime()
        when (args.firstOrNull()) {
            "request" -> {
                val method = args.getOrNull(1) ?: Fixtures.EXT_METHOD
                try {
                    val result = conn.protocol.sendRequestRaw(MethodName(method), Fixtures.extParams)
                    agentStep("ext.agent-request", result == Fixtures.extResult, t0, "result ${compact(result)}")
                    chunk("ext: ${compact(result)}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    agentStep("ext.agent-request", false, t0, "$method failed: ${errorCode(e)} $e")
                    chunk("ext error ${errorCode(e)}")
                }
            }
            "notify" -> {
                conn.protocol.sendNotificationRaw(Fixtures.notificationMethod(args.getOrNull(1) ?: Fixtures.EXT_NOTIFICATION), Fixtures.extParams)
                chunk("ext notified")
            }
            "last-notification" -> chunk("ext last: ${conn.lastExtNotification ?: "none"}")
            else -> throw JsonRpcException(-32602, "unknown directive: #ext ${args.firstOrNull()}")
        }
        done()
    }
}

/** The JSON-RPC error code behind an SDK exception, when there is one. */
fun errorCode(e: Throwable): Int? = when (e) {
    is JsonRpcException -> e.code
    is com.agentclientprotocol.protocol.AcpRequestCancelledException -> -32800
    is com.agentclientprotocol.protocol.AcpExpectedError -> -32602
    is kotlinx.serialization.SerializationException -> -32700
    else -> generateSequence(e.cause) { it.cause }.take(5).firstNotNullOfOrNull { (it as? JsonRpcException)?.code }
}
