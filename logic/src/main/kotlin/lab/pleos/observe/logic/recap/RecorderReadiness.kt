package lab.pleos.observe.logic.recap

/** Usable current state may come from GET or notify. Never require a separate GET-success flag.
 * False brake / OFF indicator / zero angle are received states, not missing values.
 */
fun recorderReadiness(indicator: Indicator?, brake: Boolean?, angle: Float?, unavailable: Set<String> = emptySet()): String {
    val missing = listOf("turnSignal" to "깜빡이", "brakePedal" to "브레이크", "steeringAngle" to "조향")
        .filter { (signal, _) -> when (signal) {
            "turnSignal" -> indicator == null
            "brakePedal" -> brake == null
            else -> angle == null || !angle.isFinite()
        } }
    val names = missing.joinToString(", ") { it.second }
    return when {
        missing.isEmpty() -> "초기 신호 준비됨"
        missing.any { it.first in unavailable } -> "초기 조회 미확인: $names · 정차 후 기록을 다시 시작해 주세요."
        else -> "초기 신호 확인 중: $names"
    }
}
