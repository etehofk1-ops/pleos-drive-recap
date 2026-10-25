package lab.pleos.observe.logic.recap

import java.util.Locale
import kotlin.math.roundToInt

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
    ReviewKind.BLINKER_LEFT_ON -> "깜빡이를 켠 채 주행"
    else -> "조향 변화"
} + if (card.kind == ReviewKind.UNSURE) " · 애매함" else ""

internal fun roundMeters(meters: Float) = (meters / BlinkerThresholds.ROUND_M).roundToInt() * BlinkerThresholds.ROUND_M

/** Reference distance for the lead: 100m when the mean speed over the lead interval was >= 80km/h, else 30m.
 * The app cannot tell an expressway apart, so only speed picks it. Null when the card has no lead. */
fun signalLeadReferenceMeters(s: SegmentSummary): Float? {
    val meters = s.signalLeadMeters ?: return null
    val ms = s.signalLeadMs
    val mean = if (ms != null && ms > 0) meters.toDouble() / (ms / 1_000.0) else null
    return if (mean != null && mean >= BlinkerThresholds.HIGHWAY_MIN_MPS) BlinkerThresholds.HIGHWAY_REFERENCE_M else BlinkerThresholds.REFERENCE_M
}

/** Wording for the early-signal cards only. A recommended distance, never a legal judgement. */
fun signalLeadText(card: ReviewCard): String? {
    if (card.candidate != ReviewKind.LANE_SIGNALLED && card.candidate != ReviewKind.TURN_SIGNALLED) return null
    val meters = card.summary.signalLeadMeters ?: return null
    val reference = signalLeadReferenceMeters(card.summary) ?: return null
    val head = "깜빡이를 켜고 약 ${roundMeters(meters)} m 뒤 진입 — "
    val highway = if (reference == BlinkerThresholds.HIGHWAY_REFERENCE_M) " · 평균 80km/h 이상이라 고속 기준으로 봤어요(속도로만 구분)" else ""
    return head + (if (meters >= reference) "충분히 일찍 켰어요" else "권장 거리(30m/고속 100m)보다 짧아요") + highway
}

/** 1.7.0 B wording. */
fun blinkerLeftOnText(card: ReviewCard): String? {
    if (card.candidate != ReviewKind.BLINKER_LEFT_ON) return null
    val ms = card.summary.blinkerOnMs ?: return null
    val meters = card.summary.blinkerOnMeters ?: return null
    return "깜빡이를 켠 채 약 ${(ms / 1_000f).roundToInt()}초(약 ${roundMeters(meters)} m) 달렸어요 — 뒤차가 진로 변경으로 오해할 수 있어요"
}

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
        else if (card.candidate == ReviewKind.BLINKER_LEFT_ON) "$speed, ${blinkerLeftOnText(card) ?: "깜빡이를 켠 채 주행"}"
        else if (card.candidate == ReviewKind.BRAKE_UNVERIFIED) "속도 미수신으로 감소율을 계산할 수 없어요. 급제동으로 단정하지 않아요."
        else "$speed, 핸들 원시 $angle, ${number(s.durationMs / 1_000f)}초, $signal" + (signalLeadText(card)?.let { " · $it" } ?: "")
    val reasons = card.uncertainty.map(::uncertaintyLabel)
    return base + (if (s.nearNavigationTurn) " · 가까운 회전 안내 관측" else "") + if (reasons.isEmpty()) "" else "\n애매한 이유: ${reasons.joinToString(", ")}"
}
