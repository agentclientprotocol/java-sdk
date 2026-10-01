package interop

import com.agentclientprotocol.model.*
import com.agentclientprotocol.rpc.*
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** steps.json fixtures, hard-coded as the contract asks. */
object Fixtures {
    const val AUTH_METHOD = "interop-auth"
    const val TERMINAL_AUTH_METHOD = "interop-terminal-auth"
    const val MODE_A = "interop-mode-a"
    const val MODE_B = "interop-mode-b"
    const val FS_READ_CONTENT = "line1\nline2\nline3\n"
    const val EXT_METHOD = "_interop/ping"
    const val EXT_NOTIFICATION = "_interop/note"
    val extParams: JsonObject = buildJsonObject { put("n", 1) }
    val extResult: JsonObject = buildJsonObject { put("pong", 1) }
    val meta: JsonObject = buildJsonObject { put("interop", "m1") }

    val permissionOptions = listOf(
        PermissionOption(PermissionOptionId("allow"), "Allow", PermissionOptionKind.ALLOW_ONCE),
        PermissionOption(PermissionOptionId("reject"), "Reject", PermissionOptionKind.REJECT_ONCE),
    )
    val permissionToolCall = SessionUpdate.ToolCallUpdate(
        toolCallId = ToolCallId("perm-1"),
        title = "interop permission",
        kind = ToolKind.EDIT,
        status = ToolCallStatus.PENDING,
    )

    val modes = listOf(SessionMode(SessionModeId(MODE_A), "Mode A"), SessionMode(SessionModeId(MODE_B), "Mode B"))

    fun modelOption(current: String): SessionConfigOption = SessionConfigOption.select(
        id = "model",
        name = "Model",
        currentValue = current,
        options = SessionConfigSelectOptions.Flat(
            listOf(
                SessionConfigSelectOption(SessionConfigValueId("model-a"), "Model A"),
                SessionConfigSelectOption(SessionConfigValueId("model-b"), "Model B"),
            )
        ),
    )

    fun verboseOption(current: Boolean): SessionConfigOption =
        SessionConfigOption.boolean(id = "verbose", name = "Verbose", currentValue = current)

    /** The fixture `_meta` value, `interop == m1`, on any JSON element. */
    fun hasMeta(meta: JsonElement?): Boolean =
        (meta as? JsonObject)?.get("interop")?.let { (it as? JsonPrimitive)?.contentOrNull } == "m1"

    /** Extension method objects: only their names matter to the raw send/handler API. */
    val extRequestMethod = AcpMethod.AcpRequestResponseMethod(
        EXT_METHOD, AuthenticateRequest.serializer(), AuthenticateResponse.serializer()
    )
    val extNotificationMethod = AcpMethod.AcpNotificationMethod(EXT_NOTIFICATION, CancelRequestNotification.serializer())

    fun notificationMethod(name: String) = AcpMethod.AcpNotificationMethod(name, CancelRequestNotification.serializer())
}

/** One STEP line: `STEP <id> PASS|FAIL (<ms> ms) -> <detail>`, on one line. */
fun stepLine(id: String, ok: Boolean, ms: Long, detail: String): String =
    "STEP $id ${if (ok) "PASS" else "FAIL"} ($ms ms) -> ${detail.replace('\n', ' ').replace('\r', ' ')}"

fun abbreviate(s: String, max: Int = 300): String = if (s.length > max) s.substring(0, max) + "... (${s.length} chars)" else s

fun compact(e: JsonElement): String = Json.encodeToString(JsonElement.serializer(), e)

/**
 * A transport that records what crosses it, delegating everything to the real transport.
 *
 * It lets a program check what the SDK put on the wire where the typed API hides it: the request
 * id of an in-flight prompt, the `$/cancel_request` the SDK sent when a call was cancelled, and the
 * response (`-32800`, `stopReason`) the counterpart sent for it after the local call gave up.
 */
class TapTransport(private val delegate: Transport) : Transport by delegate {
    private val lastOutgoing = ConcurrentHashMap<String, RequestId>()
    private val responses = ConcurrentHashMap<RequestId, CompletableDeferred<JsonRpcResponse>>()
    val sentNotifications = CopyOnWriteArrayList<JsonRpcNotification>()
    val completedOutgoing = CopyOnWriteArrayList<RequestId>()
    private val outgoingIds = ConcurrentHashMap.newKeySet<RequestId>()

    init {
        delegate.onFrame { observe(it, incoming = true) }
    }

    override fun send(frame: TransportFrame) {
        observe(frame, incoming = false)
        delegate.send(frame)
    }

    private fun observe(frame: TransportFrame, incoming: Boolean) {
        val entries = when (frame) {
            is TransportFrame.Batch -> frame.entries
            is TransportFrame.Entry -> listOf(frame)
        }
        for (entry in entries) {
            val message = (entry as? TransportFrame.Single)?.message ?: continue
            when {
                !incoming && message is JsonRpcRequest -> {
                    lastOutgoing[message.method.name] = message.id
                    outgoingIds += message.id
                }
                !incoming && message is JsonRpcNotification -> sentNotifications += message
                incoming && message is JsonRpcResponse -> {
                    if (message.id in outgoingIds) completedOutgoing += message.id
                    responses.computeIfAbsent(message.id) { CompletableDeferred() }.complete(message)
                }
            }
        }
    }

    /** The id of the last request sent with this method. */
    fun lastOutgoingId(method: String): RequestId? = lastOutgoing[method]

    /** The counterpart's response to request [id], whenever it arrives (or arrived). */
    fun response(id: RequestId): Deferred<JsonRpcResponse> = responses.computeIfAbsent(id) { CompletableDeferred() }

    fun responseIfAny(id: RequestId): JsonRpcResponse? = responses[id]?.takeIf { it.isCompleted }?.getCompleted()

    /** Error responses received for any of [ids] (including null). */
    fun errorsFor(ids: Collection<RequestId>): List<JsonRpcErrorResponse> =
        ids.mapNotNull { responseIfAny(it) as? JsonRpcErrorResponse }
}

/** `upd_<kind>` names: the wire `sessionUpdate` value, `other` for unknown kinds. */
fun kindOf(u: SessionUpdate): String = when (u) {
    is SessionUpdate.UserMessageChunk -> "user_message_chunk"
    is SessionUpdate.AgentMessageChunk -> "agent_message_chunk"
    is SessionUpdate.AgentThoughtChunk -> "agent_thought_chunk"
    is SessionUpdate.ToolCall -> "tool_call"
    is SessionUpdate.ToolCallUpdate -> "tool_call_update"
    is SessionUpdate.PlanUpdate -> "plan"
    is SessionUpdate.AvailableCommandsUpdate -> "available_commands_update"
    is SessionUpdate.CurrentModeUpdate -> "current_mode_update"
    is SessionUpdate.ConfigOptionUpdate -> "config_option_update"
    is SessionUpdate.SessionInfoUpdate -> "session_info_update"
    is SessionUpdate.UsageUpdate -> "usage_update"
    else -> "other"
}

fun textOf(c: ContentBlock): String? = (c as? ContentBlock.Text)?.text
