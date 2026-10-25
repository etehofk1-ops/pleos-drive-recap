package lab.pleos.observe.logic.recap

import java.util.Locale

fun recapTitle(card: ReviewCard): String = when (card.candidate) {
    ReviewKind.LANE_NO_SIGNAL -> "깜빡이 없이 차선 변경(추정)"
    ReviewKind.LANE_LATE_SIGNAL -> "늦은 깜빡이로 차선 변경(추정)"
    ReviewKind.LANE_SIGNALLED -> (if (card.kind == ReviewKind.UNSURE) "" else "좋아요 · ") + "깜빡이를 사용한 차선 변경(추정)"
    ReviewKind.LANE_CHANGE -> "차선 변경 후보"
    ReviewKind.TURN -> "회전 후보"
    ReviewKind.TURN_NO_SIGNAL -> "깜빡이 없이 ${turnTitle(card)}"
    ReviewKind.TURN_LATE_SIGNAL -> "늦은 깜빡이로 ${turnTitle(card)}"
    ReviewKind.TURN_SIGNALLED -> (if (card.kind == ReviewKind.UNSURE) "" else "좋아요 · ") + "깜빡이를 사용한 ${turnTitle(card)}"
    ReviewKind.CURVE -> "굽은 길 후보"
    ReviewKind.HARD_BRAKE -> "급제동(추정)"
    ReviewKind.BRAKE_UNVERIFIED -> "브레이크 작동 · 급제동 확인 불가"
    else -> "조향 변화"
} + if (card.kind == ReviewKind.UNSURE) " · 애매함" else ""

private fun turnTitle(card: ReviewCard) = when (card.navigationTurn) {
    NavigationTurn.LEFT -> "좌회전"; NavigationTurn.RIGHT -> "우회전"; NavigationTurn.UTURN -> "유턴"; null -> "회전"
}

fun recapEvidence(card: ReviewCard, debug: Boolean = false): String {
    fun number(v: Float) = String.format(Locale.ROOT, "%.1f", v)
    val s = card.summary
    val speed = if (s.minSpeedMps == null || s.maxSpeedMps == null) "속도 미수신" else
        "속도 ${number(mpsToKph(s.minSpeedMps))}~${number(mpsToKph(s.maxSpeedMps))}km/h"
    val angle = if (s.firstSign < 0) "${number(s.minAngle)}→${number(s.maxAngle)}" else "${number(s.maxAngle)}→${number(s.minAngle)}"
    val signal = when (s.timing) { SignalTiming.BEFORE -> "깜빡이 사전 사용"; SignalTiming.DURING -> "깜빡이 시작 후 ${s.lateSignalMs ?: 0}ms"
        SignalTiming.NONE -> "관측 구간 깜빡이 없음"; SignalTiming.UNKNOWN -> "깜빡이 관측 부족" }
    // Display guard only: retain the measured evidence and rule result without clamping it.
    val deceleration = card.decelerationMps2 ?: 0f
    val braking = if (deceleration > 15f) "감속 큼(측정값 이상)" +
        if (debug) " · Debug 원시 감속 ${number(deceleration)}m/s²" else ""
        else "감속 ${number(deceleration)}m/s²"
    val base = if (card.candidate == ReviewKind.HARD_BRAKE) "$speed, $braking + 브레이크 작동"
        else if (card.candidate == ReviewKind.BRAKE_UNVERIFIED) "속도 미수신으로 감소율을 계산할 수 없어요. 급제동으로 단정하지 않아요."
        else "$speed, 핸들 원시 $angle, ${number(s.durationMs / 1_000f)}초, $signal"
    val reasons = card.uncertainty.map { when (it) {
        Uncertainty.SPEED_MISSING -> "속도 미수신 경로"
        Uncertainty.DEMO_SPEED_MISSING -> "속도 미수신 시연"
        Uncertainty.SIGNAL_GAP -> "깜빡이 관측 부족/동시 관측"
        Uncertainty.INPUT_GAP -> "입력 공백"
        Uncertainty.INCOMPLETE -> "끝나기 전 기록 종료"
        Uncertainty.SHAPE_AMBIGUOUS -> "파형·속도만으로 종류를 가르기 어려움"
        Uncertainty.NAV_CONFLICT -> "내비와 핸들 불일치"
        Uncertainty.WEAK_LANE_EVIDENCE -> "깜빡이 놓침을 표시할 조향 근거 부족"
    } }
    return base + (if (s.nearNavigationTurn) " · 가까운 회전 안내 관측" else "") + if (reasons.isEmpty()) "" else "\n애매한 이유: ${reasons.joinToString(", ")}"
}
