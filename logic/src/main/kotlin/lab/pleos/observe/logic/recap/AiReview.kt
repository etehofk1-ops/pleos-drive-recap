package lab.pleos.observe.logic.recap

import kotlin.math.abs
import kotlin.math.max
import java.time.Instant
import java.time.ZoneId

val JEV_LABELS = listOf("차선 변경", "교차로 회전", "굽은 길", "판단 보류")
data class IndicatorFeature(val state: String, val offsetMs: Long?)
data class ClassifyFeatures(val durationMs: Long, val peakAbsRaw: Float, val signReversals: Int,
    val speedKphMin: Float?, val speedKphMax: Float?, val indicator: IndicatorFeature, val naviHint: String)
data class JevAnswer(val label: String, val probabilities: Map<String, Double>, val model: String, val latencyMs: Long)
data class AiDecision(val label: String?, val accepted: Boolean = false, val responded: Boolean = false)
data class SummaryItem(val kind: String, val hourBucket: Int, val speedBand: String, val indicator: String)
data class RecentCounts(val date: String, val counts: ReviewCounts)
data class SummaryFeatures(val today: List<SummaryItem>, val recentDays: List<RecentCounts>)
data class GeneratedRecap(val text: String, val patterns: List<String>, val model: String)
fun interface JevReviewAdapter { fun review(input: ClassifyFeatures): JevAnswer? }

fun classifyFeatures(card: ReviewCard): ClassifyFeatures? {
    val s = card.summary
    if (card.navigation == NavigationLabel.CONFLICT || card.navigation == NavigationLabel.CONFIRMED ||
        card.kind != ReviewKind.UNSURE || card.candidate in setOf(ReviewKind.HARD_BRAKE, ReviewKind.BRAKE_UNVERIFIED) ||
        s.firstSign == 0 || s.timing == SignalTiming.UNKNOWN || (s.timing == SignalTiming.NONE && !s.signalCoverage) ||
        !s.minAngle.isFinite() || !s.maxAngle.isFinite()) return null
    // Unknown indicator reception is never converted into an invented "none".
    val indicator = indicatorFeature(s)
    return ClassifyFeatures(s.durationMs, max(abs(s.minAngle), abs(s.maxAngle)), s.reversals,
        s.minSpeedMps?.let(::mpsToKph), s.maxSpeedMps?.let(::mpsToKph), indicator,
        if (s.nearNavigationTurn) "turn_ahead" else "unknown")
}

fun assistedDecision(card: ReviewCard, enabled: Boolean, adapter: JevReviewAdapter): AiDecision {
    if (!enabled) return AiDecision(null)
    val input = classifyFeatures(card) ?: return AiDecision(null)
    return adoptJev(card, runCatching { adapter.review(input) }.getOrNull())
}

fun generatedOrTemplate(result: GeneratedRecap?, hasCards: Boolean): String =
    if (validGeneratedRecap(result)) result!!.text else templateRecap(hasCards)

fun indicatorFeature(s: SegmentSummary) = IndicatorFeature(when (s.timing) {
    SignalTiming.BEFORE -> "before"; SignalTiming.DURING -> "late"; SignalTiming.NONE -> "none"; else -> "unknown"
}, if (s.timing == SignalTiming.DURING) s.lateSignalMs else null)

/** Maximum option probability is an adoption threshold, NEVER probability of correctness.
 * Keep original rule/card/numeric evidence intact; this is a display overlay only.
 */
fun adoptJev(card: ReviewCard, answer: JevAnswer?): AiDecision {
    if (classifyFeatures(card) == null || answer == null || answer.model.isBlank() || answer.latencyMs < 0) return AiDecision(null)
    val p = answer.probabilities
    if (p.keys != JEV_LABELS.toSet() || p.values.any { !it.isFinite() || it !in 0.0..1.0 } || abs(p.values.sum() - 1.0) > 0.02)
        return AiDecision(null)
    val highest = p.values.maxOrNull() ?: return AiDecision(null)
    if (answer.label !in JEV_LABELS || p[answer.label] != highest) return AiDecision(null)
    if (highest < 0.6 || answer.label == "판단 보류") return AiDecision(null, responded = true)
    return AiDecision(answer.label, accepted = true, responded = true)
}

fun aiCardTitle(card: ReviewCard, decision: AiDecision?, aiEnabled: Boolean = true): String = (when {
    card.navigation != NavigationLabel.ESTIMATED -> "${recapTitle(card)} · ${navigationLabel(card)}"
    card.kind != ReviewKind.UNSURE -> "${recapTitle(card)} · 규칙 판정"
    classifyFeatures(card) == null -> "${recapTitle(card)} · 규칙 판정"
    !aiEnabled -> "${recapTitle(card)} · AI 미사용"
    decision?.accepted == true -> "AI 추정 · ${decision.label}"
    decision == null -> "${recapTitle(card)} · AI 확인 중"
    decision.responded -> "${recapTitle(card)} · AI 판단 보류"
    else -> "${recapTitle(card)} · AI 미사용"
}) + if (card.demoNavigation && card.navigation in setOf(NavigationLabel.ESTIMATED, NavigationLabel.CONFLICT)) " · 모의 내비" else ""

fun summaryFeatures(day: DayRecap, recent: List<RecentCounts>, zone: ZoneId = ZoneId.systemDefault()): SummaryFeatures? =
    if (visibleReviewCards(day.cards).size > 500) null else SummaryFeatures(
    // Full day's rule cards, not a paginated view. Exact times/IDs never leave the device.
    visibleReviewCards(day.cards).map { c ->
        val speed = c.summary.maxSpeedMps?.let(::mpsToKph)
        SummaryItem(c.kind.name, Instant.ofEpochMilli(c.wallTimeMs).atZone(zone).hour,
            when { speed == null -> "unknown"; speed < 30f -> "low"; speed < 60f -> "mid"; else -> "high" },
            indicatorFeature(c.summary).state)
    }, recent)

fun templateRecap(hasCards: Boolean): String = if (hasCards)
    "오늘 기록에서 짚어볼 순간을 모았어요. 정차한 지금 깜빡이 사용과 감속 구간을 차분히 살펴보세요."
else "오늘은 기록된 복기 후보가 없어요. 다음 기록도 정차한 뒤 천천히 살펴보세요."

fun validGeneratedRecap(result: GeneratedRecap?): Boolean = result != null && result.text.length in 8..700 &&
    result.text.any { it in '가'..'힣' } && result.model.length in 1..100 && result.patterns.size <= 4 &&
    result.patterns.all { it.length in 1..160 } && !result.text.contains(Regex("(?i)Bearer|sk[-_]|api[_ -]?key"))
