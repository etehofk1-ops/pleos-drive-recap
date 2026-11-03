package lab.pleos.observe.logic.recap

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** 1.8.0 복기 화면 C안 "한 장씩". 화면에 나가는 문장·칩·막대·점은 전부 여기서 정한다(안드로이드 의존 없음).
 * 기준은 "권장 거리"이고 법적 판단 말은 쓰지 않는다. 핸들 원시값은 [recapEvidence]의 Debug 경로에만 남긴다. */

/** 종류를 6가지 색으로 묶는다. 놓침과 켜 둔 채 주행은 서로 다른 묶음이다. */
enum class MomentGroup { GOOD, MISSED, LATE, LEFT_ON, HARD_BRAKE, OTHER }

fun momentGroup(card: ReviewCard): MomentGroup = when (card.kind) {
    ReviewKind.LANE_SIGNALLED, ReviewKind.TURN_SIGNALLED -> MomentGroup.GOOD
    ReviewKind.LANE_NO_SIGNAL, ReviewKind.TURN_NO_SIGNAL -> MomentGroup.MISSED
    ReviewKind.LANE_LATE_SIGNAL, ReviewKind.TURN_LATE_SIGNAL -> MomentGroup.LATE
    ReviewKind.BLINKER_LEFT_ON -> MomentGroup.LEFT_ON
    ReviewKind.HARD_BRAKE -> MomentGroup.HARD_BRAKE
    else -> MomentGroup.OTHER
}

fun momentGroupLabel(group: MomentGroup): String = when (group) {
    MomentGroup.GOOD -> "잘한 순간"; MomentGroup.MISSED -> "놓친 순간"; MomentGroup.LATE -> "늦게 켬"
    MomentGroup.LEFT_ON -> "켜 둔 채"; MomentGroup.HARD_BRAKE -> "급제동"; MomentGroup.OTHER -> "애매함"
}

private fun captionLabel(card: ReviewCard): String {
    val group = momentGroup(card)
    return if (group == MomentGroup.OTHER && card.kind != ReviewKind.UNSURE) "기타" else momentGroupLabel(group)
}

private fun format1(value: Float) = String.format(Locale.ROOT, "%.1f", value)
private fun seconds1(ms: Long) = format1(ms / 1_000f)

private fun turnVerb(turn: NavigationTurn?) = when (turn) {
    NavigationTurn.LEFT -> "좌회전했어요"; NavigationTurn.RIGHT -> "우회전했어요"; NavigationTurn.UTURN -> "유턴했어요"; null -> "회전했어요"
}
private fun turnVerbGuess(turn: NavigationTurn?) = when (turn) {
    NavigationTurn.LEFT -> "좌회전한 것 같아요"; NavigationTurn.RIGHT -> "우회전한 것 같아요"; NavigationTurn.UTURN -> "유턴한 것 같아요"
    null -> "회전한 것 같아요"
}

private fun hedgedHeadline(card: ReviewCard): String = when (card.candidate) {
    ReviewKind.LANE_NO_SIGNAL -> "깜빡이 없이 차선을 바꾼 것 같아요"
    ReviewKind.LANE_LATE_SIGNAL -> "깜빡이가 늦게 켜진 차선 변경 같아요"
    ReviewKind.LANE_SIGNALLED -> "깜빡이를 켜고 차선을 바꾼 것 같아요"
    ReviewKind.LANE_CHANGE -> "차선을 바꾼 것 같아요"
    ReviewKind.TURN_NO_SIGNAL -> "깜빡이 없이 ${turnVerbGuess(card.navigationTurn)}"
    ReviewKind.TURN_LATE_SIGNAL -> "깜빡이가 늦게 켜진 채 ${turnVerbGuess(card.navigationTurn)}"
    ReviewKind.TURN_SIGNALLED -> "깜빡이를 켜고 ${turnVerbGuess(card.navigationTurn)}"
    ReviewKind.TURN -> turnVerbGuess(card.navigationTurn)
    ReviewKind.HARD_BRAKE -> "급제동 같아요"
    ReviewKind.CURVE -> "굽은 길 같아요"
    else -> "핸들이 움직인 순간이에요"
}

/** 카드 윗부분 큰 제목. 사람 말 한 문장, 화면 폭에 맞게 줄바꿈. [aiLabel]은 AI가 받아들여진 라벨일 때만 넘긴다. */
fun momentHeadline(card: ReviewCard, aiLabel: String? = null): String {
    if (aiLabel != null) return "$aiLabel 순간이에요"
    val s = card.summary
    if (card.kind == ReviewKind.UNSURE) return hedgedHeadline(card)
    return when (card.candidate) {
        ReviewKind.LANE_SIGNALLED, ReviewKind.TURN_SIGNALLED -> {
            val action = if (card.candidate == ReviewKind.LANE_SIGNALLED) "차선을 바꾼 것 같아요" else turnVerb(card.navigationTurn)
            val lead = s.signalLeadMeters
            if (lead == null) "깜빡이를 켜고 $action" else "깜빡이를 켜고 ${roundMeters(lead)}m 뒤에 $action"
        }
        ReviewKind.LANE_NO_SIGNAL -> "깜빡이 없이 차선을 바꾼 것 같아요"
        ReviewKind.TURN_NO_SIGNAL -> "깜빡이 없이 ${turnVerb(card.navigationTurn)}"
        ReviewKind.LANE_LATE_SIGNAL -> "깜빡이를 ${s.lateSignalMs?.let { "${seconds1(it)}초 " } ?: ""}늦게 켠 것 같아요"
        ReviewKind.TURN_LATE_SIGNAL ->
            "핸들이 먼저 돌고 깜빡이가 ${s.lateSignalMs?.let { "${seconds1(it)}초 " } ?: ""}늦었어요"
        ReviewKind.BLINKER_LEFT_ON -> s.blinkerOnMs?.let { "깜빡이를 켠 채 ${(it / 1_000f).roundToInt()}초를 달렸어요" } ?: "깜빡이를 켠 채 달렸어요"
        ReviewKind.HARD_BRAKE -> {
            val from = s.maxSpeedMps?.let { mpsToKph(it).roundToInt() }; val to = s.minSpeedMps?.let { mpsToKph(it).roundToInt() }
            when {
                from == null || to == null -> "급하게 브레이크를 밟았어요"
                from == to -> "${from}km/h에서 급하게 브레이크를 밟았어요"
                else -> "${from}km/h에서 ${to}km/h로 급하게 섰어요"
            }
        }
        ReviewKind.BRAKE_UNVERIFIED -> "브레이크를 밟았어요. 급제동인지는 확인하지 못했어요"
        ReviewKind.LANE_CHANGE -> "차선을 바꾼 것 같아요"
        ReviewKind.TURN -> turnVerbGuess(card.navigationTurn)
        ReviewKind.CURVE -> "굽은 길을 지났어요"
        else -> "핸들이 움직인 순간이에요"
    }
}

fun momentTime(card: ReviewCard, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(card.wallTimeMs))

/** "잘한 순간 · 1 / 7 · 08:12". [index]는 0부터. */
fun momentCaption(card: ReviewCard, index: Int, total: Int, zone: ZoneId = ZoneId.systemDefault()): String =
    "${captionLabel(card)} · ${index + 1} / $total · ${momentTime(card, zone)}"

fun uncertaintyLabel(u: Uncertainty): String = when (u) {
    Uncertainty.SPEED_MISSING -> "속도 미수신 경로"
    Uncertainty.DEMO_SPEED_MISSING -> "속도 미수신 시연"
    Uncertainty.SIGNAL_GAP -> "깜빡이 관측 부족/동시 관측"
    Uncertainty.INPUT_GAP -> "입력 공백"
    Uncertainty.INCOMPLETE -> "끝나기 전 기록 종료"
    Uncertainty.SHAPE_AMBIGUOUS -> "파형·속도만으로 종류를 가르기 어려움"
    Uncertainty.NAV_CONFLICT -> "내비와 핸들 불일치"
    Uncertainty.WEAK_LANE_EVIDENCE -> "깜빡이 놓침을 표시할 조향 근거 부족"
}

private fun meanKph(s: SegmentSummary): Int? =
    if (s.minSpeedMps == null || s.maxSpeedMps == null) null else mpsToKph((s.minSpeedMps + s.maxSpeedMps) / 2f).roundToInt()

private fun isEarlySignal(card: ReviewCard) = card.candidate == ReviewKind.LANE_SIGNALLED || card.candidate == ReviewKind.TURN_SIGNALLED
private fun isBrake(card: ReviewCard) = card.candidate == ReviewKind.HARD_BRAKE || card.candidate == ReviewKind.BRAKE_UNVERIFIED
/** 감속이 이 값보다 크면 측정값 이상으로 보고 숫자 대신 말로만 한다(기존 recapEvidence와 같은 선). */
private const val DECEL_GLITCH = 15f

/** 근거 칩. 사람 말뿐이고 핸들 원시값은 없다. 판정 출처는 [trustBadge]가 따로 맡는다.
 * [numbersShown]이면 같은 숫자를 큰 숫자 칩([momentBigNumbers])이 이미 보여 주므로 칩에서는 뺀다. */
fun momentEvidenceChips(card: ReviewCard, numbersShown: Boolean = false): List<String> {
    val s = card.summary
    val chips = mutableListOf<String>()
    fun speed() { if (!numbersShown) chips += meanKph(s)?.let { "속도 $it km/h" } ?: "속도 미수신" }
    when {
        card.candidate == ReviewKind.HARD_BRAKE -> {
            card.decelerationMps2?.takeIf { !numbersShown }?.let { chips += if (it > DECEL_GLITCH) "감속 큼" else "감속 ${format1(it)}m/s²" }
            chips += "브레이크 작동"
        }
        card.candidate == ReviewKind.BRAKE_UNVERIFIED -> chips += "속도 미수신"
        card.candidate == ReviewKind.BLINKER_LEFT_ON -> {
            speed(); s.blinkerOnMeters?.let { chips += "약 ${roundMeters(it)}m" }
        }
        else -> {
            speed(); if (!numbersShown) chips += "핸들 ${seconds1(s.durationMs)}초"
            when (s.timing) {
                SignalTiming.NONE -> chips += "깜빡이 없음"
                SignalTiming.UNKNOWN -> chips += "깜빡이 관측 부족"
                SignalTiming.DURING -> if (!numbersShown) chips += s.lateSignalMs?.let { "깜빡이 ${seconds1(it)}초 늦음" } ?: "깜빡이 늦음"
                SignalTiming.BEFORE -> {
                    val meters = s.signalLeadMeters; val reference = signalLeadReferenceMeters(s)
                    if (isEarlySignal(card) && meters != null && reference != null) {
                        chips += if (meters >= reference) "권장 거리 이상" else "권장 ${reference.roundToInt()}m보다 짧아요"
                        if (reference == BlinkerThresholds.HIGHWAY_REFERENCE_M) chips += "고속 기준(속도로만 구분)"
                    } else chips += "깜빡이 사전 사용"
                }
            }
        }
    }
    if (s.nearNavigationTurn) chips += "가까운 회전 안내 관측"
    card.uncertainty.forEach { chips += uncertaintyLabel(it) }
    return chips
}

/** 판정 출처 칩 하나. "추정"·"내비 확인"·AI 상태를 지우지 않는다. */
fun trustBadge(card: ReviewCard, decision: AiDecision?, aiEnabled: Boolean): String {
    if (decision?.accepted == true) return if (card.navigation == NavigationLabel.ESTIMATED) "AI 추정" else "AI 추정 · ${navigationLabel(card)}"
    val label = aiCardTitle(card, decision, aiEnabled).removePrefix("${recapTitle(card)} · ")
    return label + if (card.navigation == NavigationLabel.ESTIMATED) " · 추정" else ""
}

enum class BarKind { DISTANCE, TIME }
/** 그림 한 줄: 막대 길이 = [value] / [scaleMax], 점선 눈금 = [reference]. */
data class MomentBar(val kind: BarKind, val value: Float, val reference: Float, val scaleMax: Float,
    val startLabel: String, val valueLabel: String, val referenceLabel: String, val reachedReference: Boolean)

private fun niceMax(value: Float) = ceil(value / 10f) * 10f

fun momentBar(card: ReviewCard): MomentBar? {
    val s = card.summary
    if (isEarlySignal(card)) {
        val meters = s.signalLeadMeters ?: return null
        val reference = signalLeadReferenceMeters(s) ?: return null
        val value = roundMeters(meters).toFloat()
        val place = if (card.candidate == ReviewKind.LANE_SIGNALLED) "차선 진입" else "회전 시작"
        return MomentBar(BarKind.DISTANCE, value, reference, niceMax(max(reference * 8f / 3f, value * 1.25f)),
            "깜빡이 켬", "$place · ${value.roundToInt()}m", "권장 ${reference.roundToInt()}m", meters >= reference)
    }
    if (card.candidate == ReviewKind.BLINKER_LEFT_ON) {
        val ms = s.blinkerOnMs ?: return null
        val value = (ms / 1_000f).roundToInt().toFloat()
        val reference = BlinkerThresholds.LEFT_ON_MIN_MS / 1_000f
        return MomentBar(BarKind.TIME, value, reference, max(40f, niceMax(value * 1.25f)),
            "깜빡이 켬", "${value.roundToInt()}초", "${reference.roundToInt()}초", value >= reference)
    }
    return null
}

/** 막대가 없는 카드의 큰 숫자 칩(2~3개). */
data class BigNumber(val label: String, val value: String, val unit: String)

fun momentBigNumbers(card: ReviewCard): List<BigNumber> {
    val s = card.summary
    val out = mutableListOf<BigNumber>()
    if (card.candidate == ReviewKind.HARD_BRAKE) {
        s.maxSpeedMps?.let { out += BigNumber("시작 속도", mpsToKph(it).roundToInt().toString(), "km/h") }
        s.minSpeedMps?.let { out += BigNumber("끝 속도", mpsToKph(it).roundToInt().toString(), "km/h") }
        card.decelerationMps2?.takeIf { it <= DECEL_GLITCH }?.let { out += BigNumber("감속", format1(it), "m/s²") }
        return out
    }
    if (card.candidate == ReviewKind.BRAKE_UNVERIFIED) return out
    meanKph(s)?.let { out += BigNumber("속도", it.toString(), "km/h") }
    if (card.candidate == ReviewKind.BLINKER_LEFT_ON) {
        s.blinkerOnMeters?.let { out += BigNumber("달린 거리", roundMeters(it).toString(), "m") }
        return out
    }
    out += BigNumber("핸들 지속", seconds1(s.durationMs), "초")
    if (s.timing == SignalTiming.DURING) s.lateSignalMs?.let { out += BigNumber("깜빡이 늦음", seconds1(it), "초") }
    return out
}

/** 이 쪽 카드를 운전한 순서(오래된 것 먼저)로 놓는다. */
fun chronologicalCards(cards: List<ReviewCard>): List<ReviewCard> = cards.sortedBy { it.wallTimeMs }

data class TimelineDot(val cardId: String, val fraction: Float, val group: MomentGroup, val selected: Boolean)

/** "오늘 주행 중 위치": 첫 순간~마지막 순간을 0..1로 편다. 속도는 저장하지 않으므로 곡선은 없다. */
fun timelineDots(cards: List<ReviewCard>, selectedIndex: Int): List<TimelineDot> {
    if (cards.isEmpty()) return emptyList()
    val first = cards.minOf { it.wallTimeMs }; val last = cards.maxOf { it.wallTimeMs }
    val span = (last - first).toFloat()
    return cards.mapIndexed { i, c ->
        TimelineDot(c.id, if (span <= 0f) .5f else (c.wallTimeMs - first) / span, momentGroup(c), i == selectedIndex)
    }
}

data class TimelineTick(val fraction: Float, val label: String)

fun timelineTicks(startMs: Long, endMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<TimelineTick> {
    val format = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    fun label(ms: Long) = format.format(Instant.ofEpochMilli(ms))
    val span = endMs - startMs
    if (span < 60_000L) return listOf(TimelineTick(if (span <= 0) .5f else 0f, label(startMs)))
    val count = if (span >= 3 * 60_000L) 4 else 2
    return (0 until count).map { k -> TimelineTick(k / (count - 1).toFloat(), label(startMs + span * k / (count - 1))) }
}

/** 오늘 한눈에: 종류별로 따로 센다. 켜 둔 채 주행은 놓친 순간에 합치지 않는다. */
data class RecapGlance(val good: Int = 0, val missed: Int = 0, val late: Int = 0, val leftOn: Int = 0, val hardBrake: Int = 0)
data class GlanceRow(val group: MomentGroup, val count: Int)

fun recapGlance(c: ReviewCounts) = RecapGlance(c.good + c.turnGood, c.missing + c.turnMissing, c.late + c.turnLate, c.blinkerOn, c.hardBrake)

fun glanceRows(g: RecapGlance): List<GlanceRow> = listOf(
    GlanceRow(MomentGroup.GOOD, g.good), GlanceRow(MomentGroup.MISSED, g.missed), GlanceRow(MomentGroup.LATE, g.late),
    GlanceRow(MomentGroup.LEFT_ON, g.leftOn), GlanceRow(MomentGroup.HARD_BRAKE, g.hardBrake)).filter { it.count > 0 }

private val WEEKDAYS = listOf("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")

/** 머리 둘째 줄: "10월 25일 토요일 · 주행 34분". 거리는 저장하지 않아 쓰지 않는다. */
fun recapSubtitle(date: String, drivingMs: Long): String {
    val day = runCatching { LocalDate.parse(date) }.getOrNull()
    val dateText = if (day == null) "오늘" else "${day.monthValue}월 ${day.dayOfMonth}일 ${WEEKDAYS[day.dayOfWeek.value - 1]}"
    val driving = when {
        drivingMs <= 0 -> null
        drivingMs < 60_000L -> "주행 ${drivingMs / 1_000}초"
        drivingMs < 3_600_000L -> "주행 ${drivingMs / 60_000L}분"
        else -> { val h = drivingMs / 3_600_000L; val m = drivingMs % 3_600_000L / 60_000L; "주행 ${h}시간" + if (m > 0) " ${m}분" else "" }
    }
    return if (driving == null) dateText else "$dateText · $driving"
}
