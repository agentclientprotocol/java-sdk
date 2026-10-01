package interop

import kotlin.system.exitProcess

/**
 * The Kotlin interop programs of the cross-SDK suite: `kt-peer agent ...` and `kt-peer client ...`
 * (launch/agent.sh and launch/client.sh). Contract: integration-testing/README.md, "Contracts";
 * step catalogue: integration-testing/steps.json.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "agent" -> AgentMain.run(args.drop(1))
        "client" -> ClientMain.run(args.drop(1))
        else -> usage("kt-peer", "first argument must be agent or client", "kt-peer agent|client <args>")
    }
}

fun usage(who: String, problem: String, usage: String): Nothing {
    System.err.println("$who: $problem")
    System.err.println("usage: $usage")
    exitProcess(2)
}

/** Parses `--transport <t>` plus `--port <n>` or `--url <u>`; anything else is a usage error. */
class Args(args: List<String>, who: String, usage: String, allowed: Set<String>) {
    val values = HashMap<String, String>()

    init {
        var i = 0
        while (i < args.size) {
            val key = args[i]
            if (!key.startsWith("--") || key.removePrefix("--") !in allowed) usage(who, "unknown argument $key", usage)
            if (i + 1 >= args.size) usage(who, "$key needs a value", usage)
            values[key.removePrefix("--")] = args[i + 1]
            i += 2
        }
    }

    operator fun get(name: String): String? = values[name]
}
