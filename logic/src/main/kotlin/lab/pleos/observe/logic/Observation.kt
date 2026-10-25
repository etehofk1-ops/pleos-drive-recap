package lab.pleos.observe.logic

enum class Gear(val label: String) {
    PARK("P"), REVERSE("R"), NEUTRAL("N"), DRIVE("D"), OTHER("기타")
}

data class SafetyState(
    val carConnected: Boolean = false,
    val restrictionsActive: Boolean? = null,
    val gear: Gear? = null,
    val speedMps: Float? = null,
)

/** Unknown, disconnected, non-finite and reverse speed all fail closed. */
fun lockReasons(state: SafetyState): List<String> = buildList {
    if (!state.carConnected) add("Car 서비스 연결 안 됨")
    when (state.restrictionsActive) {
        null -> add("주행 제한 미수신")
        true -> add("주행 제한 활성")
        false -> Unit
    }
    when (state.gear) {
        null -> add("기어 미수신")
        Gear.PARK -> Unit
        else -> add("기어 P 아님")
    }
    val speed = state.speedMps
    when {
        speed == null -> add("속도 미수신")
        !speed.isFinite() -> add("속도 유효하지 않음")
        speed != 0f -> add("차량 이동 중")
    }
}

fun shouldLock(state: SafetyState): Boolean = lockReasons(state).isNotEmpty()

data class Observation(
    val label: String,
    val supported: Boolean? = null,
    val value: String? = null,
    val receivedAt: String? = null,
    val capability: String? = null,
    val error: String? = null,
    val id: String = "",
) {
    fun display(): String {
        val reading = when {
            supported == false -> "미지원"
            value == null -> "미수신"
            else -> value
        }
        return buildString {
            append("$label: $reading")
            append("\n  수신 시각: ${receivedAt ?: "—"}")
            capability?.let { append("\n  지원: $it") }
            error?.let { append("\n  오류: $it") }
        }
    }
}

/** Locked output contains no observation text, including timestamps and errors. */
fun observationBody(state: SafetyState, rows: List<Observation>): String =
    if (shouldLock(state)) "" else rows.joinToString("\n\n") { it.display() }
