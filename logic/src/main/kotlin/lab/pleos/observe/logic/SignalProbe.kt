package lab.pleos.observe.logic

/** Diagnostic values only. Never enters a quiz snapshot or the game reducer. */
data class ProbeResult(val raw: String, val receivedAt: String, val error: String? = null)

data class ProbeAreaState(
    val area: String,
    val get: ProbeResult? = null,
    val notify: ProbeResult? = null,
    val getStatus: String = "대기",
    val subscription: String = "대기",
)

data class ProbeSignalState(
    val id: String,
    val title: String,
    val rawType: String,
    val capMissing: Boolean = false,
    val notifyMissing: Boolean = false,
    val capability: ProbeResult? = null,
    val areas: List<ProbeAreaState> = emptyList(),
)

/** No rounding, scale conversion, Boolean translation, ARGB or enum ID interpretation. */
fun probeRaw(value: Any?): String = when (value) {
    null -> "null"
    is Enum<*> -> value.name
    else -> value.toString()
}

fun probeSampleText(value: ProbeResult?): String = if (value == null) "미수신" else
    "${value.raw} | 수신 ${value.receivedAt}" + (value.error?.let { " | 오류 $it" } ?: "")

/** Raw probe evidence in Debug, preserving the existing Release event-only policy. */
fun probeMessage(signal: String, phase: String, value: String, area: String, at: String, status: String, debug: Boolean): String =
    "event=probe.$signal.$phase" + if (debug) " value=$value area=$area receivedAt=$at status=$status" else ""
