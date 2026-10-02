package interop

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.protocol.invoke
import com.agentclientprotocol.rpc.JsonRpcErrorResponse
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.rpc.RequestId
import com.agentclientprotocol.transport.StdioTransport
import com.agentclientprotocol.transport.Transport
import com.agentclientprotocol.transport.WebSocketTransport
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess
import io.ktor.client.engine.cio.CIO as ClientCIO

/**
 * The Kotlin interop client: runs the step ids in `STEPS` in order and prints one `STEP` line per
 * step, then `RESULT`. `--transport stdio` spawns `bash -c "exec $AGENT_CMD"` (the sample's
 * createProcessStdioTransport pattern) and relays its stderr; `--transport ws --url <url>` connects
 * through Ktor.
 */
object ClientMain {
    private const val USAGE = "STEPS=<ids> kt-peer client --transport stdio (with AGENT_CMD) | --transport ws --url <url>"

    val stepTimeoutMs: Long = System.getenv("STEP_TIMEOUT_MS")?.toLongOrNull() ?: 15_000
    const val UPDATE_GRACE_MS = 1_000L
    const val REPLAY_GRACE_MS = 2_000L

    lateinit var transport: String
    var url: String? = null
    lateinit var dir: Path
    var pass = 0
    var fail = 0
    val updatesTotal = AtomicInteger()
    val updatesByKind = ConcurrentHashMap<String, AtomicInteger>()

    /** The main connection, opened by init.initialize and closed by conn.close. */
    var main: Conn? = null
    var agentInfo: com.agentclientprotocol.agent.AgentInfo? = null

    fun run(argv: List<String>) {
        val args = Args(argv, "client", USAGE, setOf("transport", "url"))
        transport = args["transport"] ?: usage("client", "--transport stdio|ws is required", USAGE)
        url = args["url"]
        when (transport) {
            "stdio" -> if (System.getenv("AGENT_CMD").isNullOrBlank()) usage("client", "AGENT_CMD is required for stdio", USAGE)
            "ws" -> if (url == null) usage("client", "--url is required for ws", USAGE)
            "http" -> usage("client", "the Kotlin SDK has no Streamable HTTP transport", USAGE)
            else -> usage("client", "--transport stdio|ws is required", USAGE)
        }
        val steps = System.getenv("STEPS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (steps.isNullOrEmpty()) usage("client", "STEPS is required", USAGE)
        dir = Files.createTempDirectory("acp-interop-kt-")
        runBlocking(Dispatchers.Default) {
            for (id in steps) runStep(id)
        }
        val sb = StringBuilder("RESULT pass=$pass fail=$fail updates_total=${updatesTotal.get()}")
        updatesByKind.toSortedMap().forEach { (k, v) -> sb.append(" upd_").append(k).append('=').append(v.get()) }
        println(sb)
        System.out.flush()
        main?.kill()
        exitProcess(0)
    }

    class StepFailure(message: String) : Exception(message)

    fun check(ok: Boolean, failure: () -> String) {
        if (!ok) throw StepFailure(failure())
    }

    private suspend fun runStep(id: String) {
        val body = Steps.all[id]
        if (body == null) {
            fail++
            println(stepLine(id, false, 0, "unknown-step"))
            return
        }
        val t0 = System.nanoTime()
        val (ok, detail) = try {
            true to withTimeout(stepTimeoutMs) { body() }
        } catch (e: TimeoutCancellationException) {
            false to "TIMEOUT after $stepTimeoutMs ms: $e"
        } catch (e: StepFailure) {
            false to (e.message ?: "failed")
        } catch (e: Throwable) {
            false to describe(e)
        }
        if (ok) pass++ else fail++
        println(stepLine(id, ok, (System.nanoTime() - t0) / 1_000_000, detail))
        System.out.flush()
    }

    fun describe(e: Throwable): String {
        val code = errorCode(e)
        return if (code != null) "error $code: ${e.message}" else e.toString()
    }

    fun main(): Conn = main ?: throw StepFailure("no main connection: init.initialize did not run first")

    fun record(conn: Conn, sid: String, u: SessionUpdate) {
        conn.updates.computeIfAbsent(sid) { CopyOnWriteArrayList() }.add(u)
        updatesTotal.incrementAndGet()
        updatesByKind.computeIfAbsent(kindOf(u)) { AtomicInteger() }.incrementAndGet()
        println("  update $sid: ${abbreviate(u.toString(), 200)}")
    }
}

/** One client connection: the SDK [Client] over a (tapped) transport, and what it received. */
class Conn private constructor(
    val scope: CoroutineScope,
    val protocol: Protocol,
    val tap: TapTransport,
    private val closer: suspend () -> Unit,
    private val killer: () -> Unit,
) {
    val client = Client(protocol)
    val updates = ConcurrentHashMap<String, CopyOnWriteArrayList<SessionUpdate>>()
    val permissions = ConcurrentHashMap<String, AtomicInteger>()
    val permissionMeta = ConcurrentHashMap<String, JsonElement>()
    val holdCancel: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val cancelledAt = ConcurrentHashMap<String, Long>()
    val sessions = ConcurrentHashMap<String, ClientSession>()
    val extNotifications = CopyOnWriteArrayList<JsonElement?>()
    val elicitationsCompleted = ConcurrentHashMap<String, Long>()
    private val terminals = ConcurrentHashMap<String, Term>()

    init {
        protocol.setRequestHandlerRaw(Fixtures.extRequestMethod) { Fixtures.extResult }
        protocol.setNotificationHandlerRaw(Fixtures.extNotificationMethod) { extNotifications += it.params }
    }

    companion object {
        suspend fun open(): Conn {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return when (ClientMain.transport) {
                "ws" -> {
                    val http = HttpClient(ClientCIO) {
                        install(WebSockets) { maxFrameSize = Long.MAX_VALUE }
                    }
                    // What acp-ktor-client's acpProtocolOnClientWebSocket does, with the transport tapped.
                    val wss = http.webSocketSession(urlString = ClientMain.url!!)
                    val tap = TapTransport(WebSocketTransport(parentScope = wss, wss = wss))
                    val protocol = Protocol(wss, tap, ProtocolOptions(protocolDebugName = "kotlin-client"))
                    Conn(scope, protocol, tap, closer = {
                        val closed = CompletableDeferred<Unit>()
                        tap.onClose { closed.complete(Unit) }
                        protocol.close()
                        withTimeoutOrNull(5_000) { closed.await() }
                        http.close()
                    }, killer = { http.close() })
                }
                else -> {
                    val (process, transport) = spawn(scope)
                    val tap = TapTransport(transport)
                    val protocol = Protocol(scope, tap, ProtocolOptions(protocolDebugName = "kotlin-client"))
                    Conn(scope, protocol, tap, closer = {
                        // End the child: EOF on its stdin, then wait for it to exit.
                        runCatching { process.outputStream.close() }
                        val exited = runInterruptible(Dispatchers.IO) { process.waitFor(5, TimeUnit.SECONDS) }
                        protocol.close()
                        if (!exited) {
                            destroy(process)
                            throw ClientMain.StepFailure("the agent did not exit within 5 s of EOF")
                        }
                    }, killer = { destroy(process) })
                }
            }
        }

        fun destroy(p: Process) {
            p.descendants().forEach { it.destroyForcibly() }
            p.destroyForcibly()
        }

        /**
         * `bash -c "exec $AGENT_CMD"` with this process's environment, wired like the Kotlin sample's
         * createProcessStdioTransport (ProcessTransportUtils.kt), plus the stderr relay.
         */
        fun spawn(scope: CoroutineScope): Pair<Process, Transport> {
            val process = ProcessBuilder("bash", "-c", "exec " + System.getenv("AGENT_CMD"))
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
            Thread({
                process.errorStream.bufferedReader().forEachLine { line ->
                    println(if (line.startsWith("STEP ")) line else "agent| $line")
                }
            }, "agent-stderr-relay").apply { isDaemon = true }.start()
            val stdout = process.inputStream.bufferedReader(Charsets.UTF_8)
            val stdin = process.outputStream.bufferedWriter(Charsets.UTF_8)
            val transport = StdioTransport(
                parentScope = scope,
                ioDispatcher = Dispatchers.IO,
                input = flow { while (true) emit(stdout.readLine() ?: break) },
                output = { line ->
                    stdin.write(line)
                    stdin.write("\n")
                    stdin.flush()
                },
                name = "client",
            )
            return process to transport
        }
    }

    suspend fun initialize(): com.agentclientprotocol.agent.AgentInfo {
        protocol.start()
        return client.initialize(
            ClientInfo(
                protocolVersion = 1,
                capabilities = ClientCapabilities(
                    fs = FileSystemCapability(readTextFile = true, writeTextFile = true),
                    terminal = true,
                    elicitation = ElicitationCapabilities(form = ElicitationFormCapabilities(), url = ElicitationUrlCapabilities()),
                    // Not expressible with the Kotlin model: auth.terminal (AuthCapabilities has no
                    // terminal field) and session.configOptions.boolean (no session field).
                ),
                implementation = Implementation("interop-kotlin-client", "1"),
            )
        )
    }

    suspend fun close() = closer()

    fun kill() = runCatching { killer() }

    fun params(cwd: String = ClientMain.dir.absolutePathString()) = SessionCreationParameters(cwd, emptyList())

    private val factory = com.agentclientprotocol.client.ClientOperationsFactory { sid, _ -> Ops(sid.value) }

    suspend fun newSession(cwd: String = ClientMain.dir.absolutePathString()): ClientSession =
        client.newSession(params(cwd), factory).also { sessions[it.sessionId.value] = it }

    suspend fun loadSession(sid: String): ClientSession =
        client.loadSession(SessionId(sid), params(), factory).also { sessions[sid] = it }

    suspend fun resumeSession(sid: String): ClientSession =
        client.resumeSession(SessionId(sid), params(), factory).also { sessions[sid] = it }

    suspend fun forkSession(sid: String): ClientSession =
        client.forkSession(SessionId(sid), params(), factory).also { sessions[it.sessionId.value] = it }

    /** A prompt through the SDK's ClientSession.prompt flow; updates are recorded as they arrive. */
    suspend fun prompt(
        session: ClientSession,
        text: String,
        meta: JsonElement? = null,
        onUpdate: suspend (SessionUpdate) -> Unit = {},
    ): PromptResponse {
        var response: PromptResponse? = null
        session.prompt(listOf(ContentBlock.Text(text)), meta).collect { e ->
            when (e) {
                is Event.SessionUpdateEvent -> {
                    ClientMain.record(this, session.sessionId.value, e.update)
                    onUpdate(e.update)
                }
                is Event.PromptResponseEvent -> response = e.response
            }
        }
        return response ?: throw ClientMain.StepFailure("the prompt flow ended without a response")
    }

    fun updates(sid: String): List<SessionUpdate> = updates[sid] ?: emptyList()

    fun chunks(sid: String): List<String> =
        updates(sid).filterIsInstance<SessionUpdate.AgentMessageChunk>().mapNotNull { textOf(it.content) }

    private class Term(val process: Process, val output: StringBuffer, val reader: Thread)

    /** What the client does for the agent, per session. */
    inner class Ops(private val sid: String) : ClientSessionOperations {
        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: JsonElement?,
        ): RequestPermissionResponse {
            this@Conn.permissions.computeIfAbsent(sid) { AtomicInteger() }.incrementAndGet()
            if (_meta != null) permissionMeta[sid] = _meta
            println("  permission request $sid: ${permissions.map { it.optionId.value }} _meta=$_meta")
            if (sid in holdCancel) {
                // perm.cancelled: cancel the turn while the request is pending, then answer cancelled.
                cancelledAt[sid] = System.nanoTime()
                sessions[sid]?.cancel()
                return RequestPermissionResponse(RequestPermissionOutcome.Cancelled, _meta)
            }
            val chosen = permissions.firstOrNull { it.kind == PermissionOptionKind.ALLOW_ONCE } ?: permissions.first()
            // The request's _meta goes back on the response (meta.permission).
            return RequestPermissionResponse(RequestPermissionOutcome.Selected(chosen.optionId), _meta)
        }

        override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
            ClientMain.record(this@Conn, sid, notification)
        }

        override suspend fun fsReadTextFile(path: String, line: UInt?, limit: UInt?, _meta: JsonElement?): ReadTextFileResponse {
            println("  fs/read_text_file $path line=$line limit=$limit")
            if (path.endsWith("slow.txt")) {
                // cancel-request.agent: wait until the agent's $/cancel_request cancels this handler.
                delay(10_000)
            }
            val file = Path.of(path)
            if (!Files.isRegularFile(file)) throw JsonRpcException(-32002, "Resource not found: $path")
            val content = withContext(Dispatchers.IO) { file.readText() }
            if (line == null && limit == null) return ReadTextFileResponse(content)
            val lines = content.split('\n').let { if (content.endsWith("\n")) it.dropLast(1) else it }
            val from = ((line ?: 1u).toInt() - 1).coerceAtLeast(0)
            val selected = lines.drop(from).let { if (limit != null) it.take(limit.toInt()) else it }
            return ReadTextFileResponse(selected.joinToString("") { it + "\n" })
        }

        override suspend fun fsWriteTextFile(path: String, content: String, _meta: JsonElement?): WriteTextFileResponse {
            println("  fs/write_text_file $path")
            withContext(Dispatchers.IO) { Path.of(path).writeText(content) }
            return WriteTextFileResponse()
        }

        override suspend fun terminalCreate(
            command: String,
            args: List<String>,
            cwd: String?,
            env: List<EnvVariable>,
            outputByteLimit: ULong?,
            _meta: JsonElement?,
        ): CreateTerminalResponse {
            println("  terminal/create $command $args")
            val pb = ProcessBuilder(listOf(command) + args).redirectErrorStream(true)
            if (cwd != null) pb.directory(File(cwd))
            env.forEach { pb.environment()[it.name] = it.value }
            val process = withContext(Dispatchers.IO) { pb.start() }
            val output = StringBuffer()
            val reader = Thread({
                runCatching {
                    process.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            output.append(buf, 0, n)
                        }
                    }
                }
            }, "terminal-output").apply { isDaemon = true; start() }
            val id = "term-${process.pid()}"
            terminals[id] = Term(process, output, reader)
            return CreateTerminalResponse(id)
        }

        private fun term(id: String) = terminals[id] ?: throw JsonRpcException(-32002, "Terminal not found: $id")

        override suspend fun terminalOutput(terminalId: String, _meta: JsonElement?): TerminalOutputResponse {
            val t = term(terminalId)
            val status = if (t.process.isAlive) null else {
                withContext(Dispatchers.IO) { t.reader.join(1_000) }
                TerminalExitStatus(exitCode = t.process.exitValue().toUInt())
            }
            return TerminalOutputResponse(t.output.toString(), truncated = false, exitStatus = status)
        }

        override suspend fun terminalWaitForExit(terminalId: String, _meta: JsonElement?): WaitForTerminalExitResponse {
            val t = term(terminalId)
            val code = runInterruptible(Dispatchers.IO) { t.process.waitFor() }
            withContext(Dispatchers.IO) { t.reader.join(1_000) }
            // A process killed by a signal reports 128+n on the JVM.
            return if (code > 128) WaitForTerminalExitResponse(signal = "SIG${code - 128}")
            else WaitForTerminalExitResponse(exitCode = code.toUInt())
        }

        override suspend fun terminalKill(terminalId: String, _meta: JsonElement?): KillTerminalCommandResponse {
            val t = term(terminalId)
            t.process.descendants().forEach { it.destroy() }
            t.process.destroy()
            return KillTerminalCommandResponse()
        }

        override suspend fun terminalRelease(terminalId: String, _meta: JsonElement?): ReleaseTerminalResponse {
            terminals.remove(terminalId)?.let { t ->
                if (t.process.isAlive) {
                    t.process.descendants().forEach { it.destroyForcibly() }
                    t.process.destroyForcibly()
                }
            }
            return ReleaseTerminalResponse()
        }

        override suspend fun createElicitation(request: CreateElicitationRequest): CreateElicitationResponse {
            println("  elicitation/create ${request.mode}")
            return when (request.mode) {
                is ElicitationMode.Form -> CreateElicitationResponse(
                    ElicitationAction.Accept(mapOf("name" to ElicitationContentValue.StringValue("interop")))
                )
                is ElicitationMode.Url -> CreateElicitationResponse(ElicitationAction.Accept(null))
            }
        }

        override suspend fun completeElicitation(notification: CompleteElicitationNotification) {
            println("  elicitation/complete ${notification.elicitationId}")
            elicitationsCompleted[notification.elicitationId.value] = System.nanoTime()
        }
    }
}

/** The step catalogue, client side. Each body returns its PASS detail or throws. */
object Steps {
    private val c = ClientMain
    private fun check(ok: Boolean, failure: () -> String) = c.check(ok, failure)
    private fun main() = c.main()
    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    /** Wait up to [graceMs] for a condition on updates, which may trail the response. */
    private suspend fun await(graceMs: Long = ClientMain.UPDATE_GRACE_MS, failure: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + graceMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) throw ClientMain.StepFailure(failure())
            delay(20)
        }
    }

    private fun endTurn(r: PromptResponse, what: String = "stopReason") =
        check(r.stopReason == StopReason.END_TURN) { "$what ${r.stopReason}" }

    /** A new session and one prompt; returns the session id and the response. */
    private suspend fun promptNew(text: String, meta: JsonElement? = null): Pair<String, PromptResponse> {
        val conn = main()
        val s = conn.newSession()
        return s.sessionId.value to conn.prompt(s, text, meta)
    }

    private inline fun <reified T : SessionUpdate> first(sid: String): T? = main().updates(sid).filterIsInstance<T>().firstOrNull()

    private suspend fun expectChunk(sid: String, text: String) =
        await(failure = { "no chunk ${JsonPrimitive(abbreviate(text)).toString()} in ${abbreviate(main().chunks(sid).toString())}" }) {
            main().chunks(sid).contains(text)
        }

    private suspend fun echoedCaps(): JsonObject {
        val (sid, r) = promptNew("#echo-caps")
        endTurn(r)
        await(failure = { "no capabilities chunk" }) { main().chunks(sid).isNotEmpty() }
        val chunk = main().chunks(sid).joinToString("")
        return try {
            Json.parseToJsonElement(chunk).jsonObject
        } catch (e: Exception) {
            throw ClientMain.StepFailure("the chunk is not a JSON object: ${abbreviate(chunk)}")
        }
    }

    private fun JsonObject.at(vararg path: String): JsonElement? {
        var e: JsonElement? = this
        for (p in path) e = (e as? JsonObject)?.get(p)
        return e
    }

    private fun info() = ClientMain.agentInfo ?: throw ClientMain.StepFailure("no initialize response: init.initialize did not run first")

    /** Starts a prompt in the background; [firstTick] completes on the first "tick" chunk. */
    private class Running(val job: Deferred<PromptResponse>, val firstTick: CompletableDeferred<Unit>)

    private fun CoroutineScope.startSlow(session: ClientSession, text: String = "#slow"): Running {
        val tick = CompletableDeferred<Unit>()
        val job = async {
            main().prompt(session, text) { u ->
                if (u is SessionUpdate.AgentMessageChunk && textOf(u.content) == "tick") tick.complete(Unit)
            }
        }
        return Running(job, tick)
    }

    private fun stop(reason: String, expected: StopReason): suspend () -> String = {
        val (_, r) = promptNew("#stop $reason")
        check(r.stopReason == expected) { "stopReason ${r.stopReason}" }
        "stopReason $reason"
    }

    private fun bigPrompt(n: Int): suspend () -> String = {
        val (sid, r) = promptNew("#len " + "x".repeat(n))
        endTurn(r)
        expectChunk(sid, "len=$n")
        "the agent counted $n characters"
    }

    private fun bigUpdate(n: Int): suspend () -> String = {
        val (sid, r) = promptNew("#big $n")
        endTurn(r)
        await(failure = { "no chunk of $n characters; chunk sizes ${main().chunks(sid).map { it.length }}" }) {
            main().chunks(sid).any { it.length == n && it.all { ch -> ch == 'x' } }
        }
        "one agent_message_chunk of $n characters"
    }

    private fun emitStep(kind: String, verify: suspend (String, PromptResponse) -> String): suspend () -> String = {
        val (sid, r) = promptNew("#emit $kind")
        verify(sid, r)
    }

    val all: Map<String, suspend () -> String> = linkedMapOf(
        "init.initialize" to {
            val conn = Conn.open()
            ClientMain.main = conn
            val info = conn.initialize()
            ClientMain.agentInfo = info
            check(info.protocolVersion == 1) { "protocolVersion ${info.protocolVersion}" }
            "protocolVersion=1 agentInfo=${info.implementation}"
        },
        "init.agent-capabilities" to {
            val caps = info().capabilities
            val sc = caps.sessionCapabilities
            check(caps.loadSession) { "loadSession is not true" }
            check(sc.list != null && sc.resume != null && sc.close != null && sc.delete != null) {
                "sessionCapabilities $sc lacks list, resume, close or delete"
            }
            "loadSession; sessionCapabilities list, resume, close, delete"
        },
        "init.client-capabilities" to {
            val caps = echoedCaps()
            check(caps.at("fs", "readTextFile") == JsonPrimitive(true) && caps.at("fs", "writeTextFile") == JsonPrimitive(true)
                && caps.at("terminal") == JsonPrimitive(true)) { "echoed capabilities ${compact(caps)}" }
            "the agent echoed fs.readTextFile, fs.writeTextFile and terminal"
        },
        "init.auth-methods" to {
            val ids = info().authMethods.map { it.id.value }
            check(Fixtures.AUTH_METHOD in ids) { "authMethods $ids" }
            "authMethods $ids"
        },
        "init.agent-info" to {
            val name = info().implementation?.name
            check(name?.startsWith("interop-") == true) { "agentInfo.name $name" }
            "agentInfo.name $name"
        },
        "init.config-boolean" to {
            val caps = echoedCaps()
            check(caps.at("session", "configOptions", "boolean") != null) {
                "the echoed capabilities have no session.configOptions.boolean: the Kotlin ClientCapabilities cannot advertise it (${compact(caps)})"
            }
            "session.configOptions.boolean echoed"
        },
        "auth.authenticate" to {
            main().client.authenticate(AuthMethodId(Fixtures.AUTH_METHOD))
            "authenticate answered"
        },
        "auth.logout" to {
            main().client.logout()
            "logout answered"
        },
        "auth.logout-capability" to {
            check(info().capabilities.auth.logout != null) { "agentCapabilities.auth.logout is absent" }
            "agentCapabilities.auth.logout present"
        },
        "auth.terminal" to {
            val terminal = info().authMethods.filterIsInstance<AuthMethod.TerminalAuth>()
            check(terminal.any { it.id.value == Fixtures.TERMINAL_AUTH_METHOD }) {
                "no terminal auth method in ${info().authMethods.map { it.id.value }}: the Kotlin AuthCapabilities cannot advertise auth.terminal"
            }
            "terminal auth method listed"
        },
        "session.new" to {
            val s = main().newSession()
            check(s.sessionId.value.isNotEmpty()) { "empty sessionId" }
            "sessionId=${s.sessionId}"
        },
        "session.load" to {
            val conn = main()
            val s = conn.newSession()
            val sid = s.sessionId.value
            conn.prompt(s, "hello load")
            val loaded = conn.loadSession(sid)
            val before = conn.chunks(sid).size
            endTurn(conn.prompt(loaded, "after load"), "after load: stopReason")
            await(failure = { "no agent_message_chunk after the load" }) { conn.chunks(sid).size > before }
            "loaded $sid; prompt after load end_turn"
        },
        "session.load-replay" to {
            val conn = main()
            val s = conn.newSession()
            val sid = s.sessionId.value
            conn.prompt(s, "replay me")
            val from = conn.updates(sid).size
            conn.loadSession(sid)
            delay(ClientMain.REPLAY_GRACE_MS)
            val replayed = conn.updates(sid).drop(from)
            val user = replayed.filterIsInstance<SessionUpdate.UserMessageChunk>().any { textOf(it.content) == "replay me" }
            val agent = replayed.filterIsInstance<SessionUpdate.AgentMessageChunk>().any { textOf(it.content)?.contains("replay me") == true }
            check(user && agent) { "replayed ${replayed.map(::kindOf)}: user_message_chunk=$user agent_message_chunk=$agent" }
            "replay: ${replayed.size} updates"
        },
        "session.resume" to {
            val conn = main()
            val s = conn.newSession()
            val sid = s.sessionId.value
            conn.prompt(s, "before resume")
            val from = conn.updates(sid).size
            val resumed = conn.resumeSession(sid)
            delay(ClientMain.UPDATE_GRACE_MS)
            val extra = conn.updates(sid).drop(from)
            check(extra.isEmpty()) { "resume replayed ${extra.map(::kindOf)}" }
            endTurn(conn.prompt(resumed, "after resume"), "after resume: stopReason")
            "resumed $sid without replay"
        },
        "session.list" to {
            val conn = main()
            val cwd = ClientMain.dir.resolve("list").also { Files.createDirectories(it) }.absolutePathString()
            val s = conn.newSession(cwd)
            val listed = conn.client.listSessions(cwd = cwd).toList()
            check(listed.any { it.sessionId == s.sessionId && it.cwd == cwd }) { "listed ${listed.map { "${it.sessionId}@${it.cwd}" }}" }
            "listed ${listed.size} session(s) for $cwd"
        },
        "session.close" to {
            val conn = main()
            val s = conn.newSession()
            coroutineScope {
                val run = startSlow(s)
                run.firstTick.await()
                s.close()
                val closedAt = System.nanoTime()
                val outcome = withTimeoutOrNull(5_000) {
                    try {
                        "stopReason ${run.job.await().stopReason}"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "error ${ClientMain.describe(e)}"
                    }
                }
                check(outcome != null) { "the #slow prompt did not end within 5 s of the close" }
                val after = try {
                    conn.prompt(s, "after close")
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
                check(after != null) { "the prompt after close succeeded" }
                "close answered; #slow ended ${ms(closedAt)} ms after it ($outcome); prompt after close: ${ClientMain.describe(after!!)}"
            }
        },
        "session.delete" to {
            val conn = main()
            val s = conn.newSession()
            conn.client.deleteSession(s.sessionId)
            conn.client.deleteSession(SessionId("no-such-session"))
            "deleted ${s.sessionId} and no-such-session"
        },
        "session.multi" to {
            val conn = main()
            val a = conn.newSession()
            val b = conn.newSession()
            coroutineScope {
                val ra = async { conn.prompt(a, "multi A") }
                val rb = async { conn.prompt(b, "multi B") }
                endTurn(ra.await(), "multi A: stopReason")
                endTurn(rb.await(), "multi B: stopReason")
            }
            await(failure = { "chunks A ${conn.chunks(a.sessionId.value)} B ${conn.chunks(b.sessionId.value)}" }) {
                conn.chunks(a.sessionId.value).joinToString("") == "echo: multi A" &&
                    conn.chunks(b.sessionId.value).joinToString("") == "echo: multi B"
            }
            "both end_turn, each session got its own chunks"
        },
        "session.fork" to {
            val conn = main()
            val s = conn.newSession()
            conn.prompt(s, "before fork")
            val forked = conn.forkSession(s.sessionId.value)
            check(forked.sessionId != s.sessionId) { "the fork returned the original sessionId" }
            endTurn(conn.prompt(forked, "in fork"), "in fork: stopReason")
            "forked ${s.sessionId} into ${forked.sessionId}"
        },
        "update.agent_message_chunk" to {
            val (sid, r) = promptNew("hello")
            endTurn(r)
            await(failure = { "chunks ${main().chunks(sid)} do not spell \"echo: hello\"" }) { main().chunks(sid).joinToString("") == "echo: hello" }
            "end_turn; chunks spell \"echo: hello\""
        },
        "update.user_message_chunk" to emitStep("user_message_chunk") { sid, r ->
            endTurn(r)
            await(failure = { "no user_message_chunk \"user-chunk\"" }) {
                main().updates(sid).filterIsInstance<SessionUpdate.UserMessageChunk>().any { textOf(it.content) == "user-chunk" }
            }
            "user_message_chunk \"user-chunk\""
        },
        "update.agent_thought_chunk" to emitStep("agent_thought_chunk") { sid, r ->
            endTurn(r)
            await(failure = { "no agent_thought_chunk \"thinking\"" }) {
                main().updates(sid).filterIsInstance<SessionUpdate.AgentThoughtChunk>().any { textOf(it.content) == "thinking" }
            }
            "agent_thought_chunk \"thinking\""
        },
        "update.tool_call" to emitStep("tool_call") { sid, _ ->
            await(failure = { "no tool_call call-1 in ${main().updates(sid)}" }) {
                main().updates(sid).filterIsInstance<SessionUpdate.ToolCall>().any {
                    it.toolCallId.value == "call-1" && it.title == "interop tool" && it.kind == ToolKind.READ && it.status == ToolCallStatus.PENDING
                }
            }
            "tool_call call-1 \"interop tool\" read pending"
        },
        "update.tool_call_update" to emitStep("tool_call_update") { sid, _ ->
            await(failure = { "no tool_call then tool_call_update call-1 completed \"tool output\" in ${main().updates(sid)}" }) {
                val us = main().updates(sid)
                val call = us.indexOfFirst { it is SessionUpdate.ToolCall && it.toolCallId.value == "call-1" }
                val upd = us.indexOfFirst {
                    it is SessionUpdate.ToolCallUpdate && it.toolCallId.value == "call-1" && it.status == ToolCallStatus.COMPLETED &&
                        it.content.orEmpty().any { c -> c is ToolCallContent.Content && textOf(c.content) == "tool output" }
                }
                call >= 0 && upd > call
            }
            "tool_call then tool_call_update completed \"tool output\""
        },
        "update.tool_call-name" to emitStep("tool_call name=read_file") { sid, _ ->
            await(failure = { "no tool_call" }) { first<SessionUpdate.ToolCall>(sid) != null }
            throw ClientMain.StepFailure("tool_call received, but the Kotlin v1 SessionUpdate.ToolCall has no name field to read")
        },
        "update.plan" to emitStep("plan") { sid, _ ->
            val want = listOf(
                Triple("step one", PlanEntryPriority.HIGH, PlanEntryStatus.PENDING),
                Triple("step two", PlanEntryPriority.LOW, PlanEntryStatus.COMPLETED),
            )
            await(failure = { "no plan with the fixture entries in ${main().updates(sid)}" }) {
                main().updates(sid).filterIsInstance<SessionUpdate.PlanUpdate>().any { p ->
                    p.entries.map { Triple(it.content, it.priority, it.status) } == want
                }
            }
            "plan with the two fixture entries"
        },
        "update.available_commands_update" to emitStep("available_commands_update") { sid, _ ->
            await(failure = { "no available_commands_update with command interop (hint args) in ${main().updates(sid)}" }) {
                main().updates(sid).filterIsInstance<SessionUpdate.AvailableCommandsUpdate>().any { u ->
                    u.availableCommands.size == 1 && u.availableCommands[0].name == "interop" &&
                        (u.availableCommands[0].input as? AvailableCommandInput.Unstructured)?.hint == "args"
                }
            }
            "available_commands_update: interop (hint args)"
        },
        "update.current_mode_update" to emitStep("current_mode_update") { sid, _ ->
            await(failure = { "no current_mode_update interop-mode-b" }) {
                main().updates(sid).filterIsInstance<SessionUpdate.CurrentModeUpdate>().any { it.currentModeId.value == Fixtures.MODE_B }
            }
            "current_mode_update interop-mode-b"
        },
        "update.config_option_update" to emitStep("config_option_update") { sid, _ ->
            await(failure = { "no config_option_update with model at model-b in ${main().updates(sid)}" }) {
                main().updates(sid).filterIsInstance<SessionUpdate.ConfigOptionUpdate>().any { u ->
                    u.configOptions.any { it.id.value == "model" && (it as? SessionConfigOption.Select)?.currentValue?.value == "model-b" }
                }
            }
            "config_option_update: model at model-b"
        },
        "update.session_info_update" to emitStep("session_info_update") { sid, _ ->
            await(failure = { "no session_info_update \"interop title\" in ${main().updates(sid)}" }) {
                main().updates(sid).filterIsInstance<SessionUpdate.SessionInfoUpdate>().any { it.title == "interop title" }
            }
            "session_info_update \"interop title\""
        },
        "update.usage_update" to emitStep("usage_update") { sid, _ ->
            await(failure = { "no usage_update 100/1000 0.01 USD in ${main().updates(sid)}" }) {
                main().updates(sid).filterIsInstance<SessionUpdate.UsageUpdate>().any {
                    it.used == 100L && it.size == 1000L && it.cost?.amount == 0.01 && it.cost?.currency == "USD"
                }
            }
            "usage_update used 100 size 1000 cost 0.01 USD"
        },
        "update.unknown" to emitStep("unknown") { sid, r ->
            endTurn(r)
            expectChunk(sid, "after-unknown")
            val kept = main().updates(sid).filterIsInstance<SessionUpdate.UnknownSessionUpdate>().map { it.sessionUpdateType }
            "\"after-unknown\" arrived and end_turn; unknown update surfaced as $kept"
        },
        "stop.max_tokens" to stop("max_tokens", StopReason.MAX_TOKENS),
        "stop.refusal" to stop("refusal", StopReason.REFUSAL),
        "stop.max_turn_requests" to stop("max_turn_requests", StopReason.MAX_TURN_REQUESTS),
        "mode.set" to {
            val conn = main()
            val s = conn.newSession()
            check(s.availableModes.any { it.id.value == Fixtures.MODE_B }) { "session/new returned modes ${s.availableModes.map { it.id }}" }
            s.setMode(SessionModeId(Fixtures.MODE_B))
            "set_mode interop-mode-b answered"
        },
        "config.on-new" to {
            val s = main().newSession()
            check(s.configOptionsSupported) { "session/new returned no configOptions" }
            val model = s.configOptions.value.firstOrNull { it.id.value == "model" } as? SessionConfigOption.Select
            check(model?.currentValue?.value == "model-a") { "configOptions ${s.configOptions.value}" }
            "select option model at model-a"
        },
        "config.select" to {
            val s = main().newSession()
            val r = s.setConfigOption(SessionConfigId("model"), SessionConfigOptionValue.StringValue("model-b"))
            val model = r.configOptions.firstOrNull { it.id.value == "model" } as? SessionConfigOption.Select
            check(model?.currentValue?.value == "model-b") { "response configOptions ${r.configOptions}" }
            "response lists ${r.configOptions.size} option(s), model at model-b"
        },
        "config.boolean" to {
            val s = main().newSession()
            val r = s.setConfigOption(SessionConfigId("verbose"), SessionConfigOptionValue.BoolValue(true))
            val verbose = r.configOptions.firstOrNull { it.id.value == "verbose" } as? SessionConfigOption.BooleanOption
            check(verbose?.currentValue == true) { "response configOptions ${r.configOptions}" }
            "verbose at true"
        },
        "perm.selected" to {
            val (sid, r) = promptNew("#permission allow")
            endTurn(r)
            expectChunk(sid, "permission: selected allow")
            val asked = main().permissions[sid]?.get() ?: 0
            check(asked == 1) { "$asked permission requests, expected 1" }
            "one permission request; selected allow; end_turn"
        },
        "perm.cancelled" to {
            val conn = main()
            val s = conn.newSession()
            val sid = s.sessionId.value
            conn.holdCancel += sid
            val r = conn.prompt(s, "#permission hold")
            val cancelledAt = conn.cancelledAt[sid] ?: throw ClientMain.StepFailure("no permission request arrived")
            val after = ms(cancelledAt)
            check(r.stopReason == StopReason.CANCELLED) { "stopReason ${r.stopReason}" }
            check(after <= 5_000) { "cancelled $after ms after the cancel" }
            "stopReason cancelled $after ms after session/cancel"
        },
        "fs.write" to {
            val file = ClientMain.dir.resolve("fs-write.txt")
            val (sid, r) = promptNew("#fs write ${file.absolutePathString()} interop write")
            endTurn(r)
            expectChunk(sid, "fs write ok")
            check(Files.exists(file)) { "$file was not written" }
            val content = file.readText()
            check(content == "interop write") { "file content \"$content\"" }
            "written through the client: \"interop write\""
        },
        "fs.read" to {
            val file = ClientMain.dir.resolve("fs-read.txt").also { it.writeText(Fixtures.FS_READ_CONTENT) }
            val (sid, r) = promptNew("#fs read ${file.absolutePathString()}")
            endTurn(r)
            expectChunk(sid, Fixtures.FS_READ_CONTENT)
            "the agent read the fixture content"
        },
        "fs.read-range" to {
            val file = ClientMain.dir.resolve("fs-read.txt").also { it.writeText(Fixtures.FS_READ_CONTENT) }
            val (sid, r) = promptNew("#fs read-range ${file.absolutePathString()} line=2 limit=1")
            endTurn(r)
            await(failure = { "no chunk \"line2\" in ${main().chunks(sid)}" }) { main().chunks(sid).any { it.trim() == "line2" } }
            "line=2 limit=1 read \"line2\""
        },
        "fs.read-missing" to {
            val (sid, r) = promptNew("#fs read-missing ${ClientMain.dir.resolve("no-such-file.txt").absolutePathString()}")
            endTurn(r)
            await(failure = { "no chunk starting \"fs read error\" in ${main().chunks(sid)}" }) {
                main().chunks(sid).any { it.startsWith("fs read error") }
            }
            "the agent got an error: ${main().chunks(sid).first { it.startsWith("fs read error") }}"
        },
        "term.run" to {
            val (sid, r) = promptNew("#terminal run echo hi")
            endTurn(r)
            expectChunk(sid, "terminal: hi exit=0")
            "terminal: hi exit=0"
        },
        "term.kill" to {
            val t0 = System.nanoTime()
            val (sid, r) = promptNew("#terminal kill sleep 30")
            endTurn(r)
            expectChunk(sid, "terminal killed")
            check(ms(t0) <= 5_000) { "terminal killed after ${ms(t0)} ms" }
            "terminal killed within ${ms(t0)} ms"
        },
        "elicit.form" to {
            val (sid, r) = promptNew("#elicit form")
            endTurn(r)
            await(failure = { "no matching elicit chunk in ${main().chunks(sid)}" }) {
                main().chunks(sid).any { ch ->
                    ch.startsWith("elicit: accept ") && runCatching {
                        Json.parseToJsonElement(ch.removePrefix("elicit: accept ")) == buildJsonObject { put("name", "interop") }
                    }.getOrDefault(false)
                }
            }
            "elicit: accept {\"name\":\"interop\"}"
        },
        "elicit.complete" to {
            val conn = main()
            val s = conn.newSession()
            conn.elicitationsCompleted.remove("elic-1")
            endTurn(conn.prompt(s, "#elicit url"))
            await(failure = { "no elicitation/complete for elic-1" }) { conn.elicitationsCompleted.containsKey("elic-1") }
            "elicitation/complete elic-1 arrived"
        },
        "cancel.prompt" to {
            val conn = main()
            val s = conn.newSession()
            coroutineScope {
                val run = startSlow(s)
                run.firstTick.await()
                s.cancel()
                val at = System.nanoTime()
                val r = withTimeoutOrNull(5_000) { run.job.await() }
                    ?: throw ClientMain.StepFailure("no response within 5 s of session/cancel")
                check(r.stopReason == StopReason.CANCELLED) { "stopReason ${r.stopReason}" }
                "stopReason cancelled ${ms(at)} ms after session/cancel"
            }
        },
        "cancel.prompt-while-cancelling" to {
            val conn = main()
            val s = conn.newSession()
            val sid = s.sessionId
            coroutineScope {
                val run = startSlow(s, "#slow grace=1000")
                run.firstTick.await()
                s.cancel()
                // ClientSession allows one prompt at a time, so the overlapping prompt goes out raw.
                val during = try {
                    AcpMethod.AgentMethods.V1.SessionPrompt(conn.protocol, PromptRequest(sid, listOf(ContentBlock.Text("during cancel"))))
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
                val first = run.job.await()
                check(first.stopReason == StopReason.CANCELLED) { "first prompt stopReason ${first.stopReason}" }
                check(during != null && errorCode(during) == -32600) {
                    "\"during cancel\" ${if (during == null) "succeeded" else "failed with ${ClientMain.describe(during)}"}"
                }
                endTurn(conn.prompt(s, "after cancel"), "after cancel: stopReason")
                "cancelled; \"during cancel\" -32600; \"after cancel\" end_turn"
            }
        },
        "cancel.grace" to {
            val conn = main()
            val s = conn.newSession()
            coroutineScope {
                val run = async { conn.prompt(s, "#hang") }
                delay(200)
                s.cancel()
                val at = System.nanoTime()
                val r = withTimeoutOrNull(6_000) { run.await() }
                    ?: throw ClientMain.StepFailure("no response within 6 s of session/cancel")
                check(r.stopReason == StopReason.CANCELLED) { "stopReason ${r.stopReason}" }
                "stopReason cancelled ${ms(at)} ms after session/cancel"
            }
        },
        "cancel-request.client" to {
            val conn = main()
            val s = conn.newSession()
            coroutineScope {
                val run = startSlow(s)
                run.firstTick.await()
                val id = conn.tap.lastOutgoingId("session/prompt") ?: throw ClientMain.StepFailure("no session/prompt id seen")
                // Cancelling the call is how the Kotlin SDK sends $/cancel_request for it.
                val at = System.nanoTime()
                run.job.cancel()
                val resp = withTimeoutOrNull(5_000) { conn.tap.response(id).await() }
                val sent = conn.tap.sentNotifications.any {
                    it.method.name == "\$/cancel_request" && (it.params as? JsonObject)?.get("requestId")?.toString() == id.toString()
                }
                check(sent) { "the SDK sent no \$/cancel_request for request $id" }
                check(resp != null) { "no response to the prompt within 5 s of \$/cancel_request" }
                val outcome = when (resp) {
                    is JsonRpcErrorResponse -> "error ${resp.error.code}"
                    is JsonRpcSuccessResponse -> "stopReason ${(resp.result as? JsonObject)?.get("stopReason")?.jsonPrimitive?.contentOrNull}"
                    else -> "?"
                }
                check(outcome == "error -32800" || outcome == "stopReason cancelled") { "the prompt ended with $outcome" }
                "\$/cancel_request for $id; the prompt ended ${ms(at)} ms later with $outcome"
            }
        },
        "cancel-request.agent" to {
            val t0 = System.nanoTime()
            val (sid, r) = promptNew("#fs read-slow ${ClientMain.dir.resolve("slow.txt").absolutePathString()}")
            endTurn(r)
            expectChunk(sid, "cancel-request sent")
            check(ms(t0) <= 5_000) { "end_turn after ${ms(t0)} ms" }
            "\"cancel-request sent\" and end_turn in ${ms(t0)} ms"
        },
        "cancel-request.unknown" to {
            val conn = main()
            val done = conn.tap.completedOutgoing.lastOrNull() ?: throw ClientMain.StepFailure("no completed request to name")
            val ids = listOf<RequestId>(RequestId.create(999999), done)
            for (id in ids) {
                AcpMethod.MetaMethods.CancelRequest(conn.protocol, CancelRequestNotification(id))
            }
            val s = conn.newSession()
            endTurn(conn.prompt(s, "after cancel-request"))
            val errors = conn.tap.errorsFor(ids + RequestId.Null)
            check(errors.isEmpty()) { "error responses: ${errors.map { "${it.id}: ${it.error.code}" }}" }
            "\$/cancel_request for 999999 and completed request $done: no error; prompt end_turn"
        },
        "ext.agent-request" to {
            val (sid, r) = promptNew("#ext request ${Fixtures.EXT_METHOD}")
            endTurn(r)
            await(failure = { "no ext chunk in ${main().chunks(sid)}" }) {
                main().chunks(sid).any { it.startsWith("ext: ") && runCatching { Json.parseToJsonElement(it.removePrefix("ext: ")) == Fixtures.extResult }.getOrDefault(false) }
            }
            "ext: {\"pong\":1}"
        },
        "ext.agent-notification" to {
            val conn = main()
            conn.extNotifications.clear()
            val (_, r) = promptNew("#ext notify ${Fixtures.EXT_NOTIFICATION}")
            endTurn(r)
            await(failure = { "no ${Fixtures.EXT_NOTIFICATION} notification (got ${conn.extNotifications})" }) {
                conn.extNotifications.any { it == Fixtures.extParams }
            }
            "${Fixtures.EXT_NOTIFICATION} arrived with {\"n\":1}"
        },
        "ext.client-request" to {
            val result = main().protocol.sendRequestRaw(MethodName(Fixtures.EXT_METHOD), Fixtures.extParams)
            check(result == Fixtures.extResult) { "result ${compact(result)}" }
            "result ${compact(result)}"
        },
        "ext.client-notification" to {
            main().protocol.sendNotificationRaw(Fixtures.extNotificationMethod, Fixtures.extParams)
            val (sid, r) = promptNew("#ext last-notification")
            endTurn(r)
            expectChunk(sid, "ext last: ${Fixtures.EXT_NOTIFICATION}")
            "ext last: ${Fixtures.EXT_NOTIFICATION}"
        },
        "meta.prompt" to {
            val (sid, r) = promptNew("#meta", Fixtures.meta)
            await(failure = { "no \"meta\" chunk" }) { main().chunks(sid).contains("meta") }
            val chunk = main().updates(sid).filterIsInstance<SessionUpdate.AgentMessageChunk>().first { textOf(it.content) == "meta" }
            check(Fixtures.hasMeta(chunk._meta)) { "the \"meta\" chunk's _meta is ${chunk._meta}" }
            check(Fixtures.hasMeta(r._meta)) { "the PromptResponse _meta is ${r._meta}" }
            "update and PromptResponse _meta interop == m1"
        },
        "meta.permission" to {
            val (sid, r) = promptNew("#permission allow meta")
            endTurn(r)
            val meta = main().permissionMeta[sid]
            check(Fixtures.hasMeta(meta)) { "the permission request carried _meta $meta" }
            "the permission request carried _meta interop == m1"
        },
        "error.method-not-found" to {
            val conn = main()
            val code = try {
                conn.protocol.sendRequestRaw(MethodName("interop/no_such_method"), JsonObject(emptyMap()))
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorCode(e) ?: throw ClientMain.StepFailure("failed without a JSON-RPC code: $e")
            }
            check(code == -32601) { "interop/no_such_method answered ${code ?: "with a result"}" }
            conn.newSession()
            "-32601, then session/new succeeded"
        },
        "big.prompt-1m" to bigPrompt(1_048_576),
        "big.update-1m" to bigUpdate(1_048_576),
        "big.prompt-8m" to bigPrompt(8_388_608),
        "big.update-8m" to bigUpdate(8_388_608),
        "http.reconnect" to {
            throw ClientMain.StepFailure("http.reconnect does not apply to ${ClientMain.transport}")
        },
        "stdio.eof-exit" to {
            check(ClientMain.transport == "stdio") { "stdio.eof-exit does not apply to ${ClientMain.transport}" }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val (process, transport) = Conn.spawn(scope)
            try {
                val protocol = Protocol(scope, transport)
                val client = Client(protocol)
                protocol.start()
                client.initialize(ClientInfo(protocolVersion = 1))
                process.outputStream.close()
                val at = System.nanoTime()
                val exited = runInterruptible(Dispatchers.IO) { process.waitFor(5, TimeUnit.SECONDS) }
                check(exited) { "no exit on EOF" }
                "the second agent exited ${ms(at)} ms after EOF (exit ${process.exitValue()})"
            } finally {
                Conn.destroy(process)
                scope.cancel()
            }
        },
        "conn.close" to {
            main().close()
            "closed"
        },
    )
}
