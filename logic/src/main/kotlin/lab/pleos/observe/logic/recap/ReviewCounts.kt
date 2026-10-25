package lab.pleos.observe.logic.recap

import java.io.*
import java.time.LocalDate

/** Seven days of rule-only counts. No IDs, timestamps, location or segment evidence. */
data class ReviewCounts(val missing: Int = 0, val late: Int = 0, val good: Int = 0, val hardBrake: Int = 0,
    val turnMissing: Int = 0, val turnLate: Int = 0, val turnGood: Int = 0,
    /** 1.7.0: blinker left on while driving. A missed moment (shown with the "놓침" tile), not a lane/turn count. */
    val blinkerOn: Int = 0)
/** [leadSumM]/[leadCount] (1.9.0): 그날 깜빡이를 먼저 켠 차선 변경·회전 카드의 켬 거리(카드와 같은 5m 반올림 값) 합계와 장수.
 * "지난 7일과 비교"의 평균용이다. 카드 id·시각·위치는 없다. 옛 기록은 0/0(거리 없음)으로 읽는다. */
data class CountDay(val date: String, val counts: ReviewCounts, val leadSumM: Int = 0, val leadCount: Int = 0)
fun reviewCounts(cards: List<ReviewCard>) = ReviewCounts(
    cards.count { it.kind == ReviewKind.LANE_NO_SIGNAL }, cards.count { it.kind == ReviewKind.LANE_LATE_SIGNAL },
    cards.count { it.kind == ReviewKind.LANE_SIGNALLED }, cards.count { it.kind == ReviewKind.HARD_BRAKE },
    cards.count { it.kind == ReviewKind.TURN_NO_SIGNAL }, cards.count { it.kind == ReviewKind.TURN_LATE_SIGNAL },
    cards.count { it.kind == ReviewKind.TURN_SIGNALLED }, cards.count { it.kind == ReviewKind.BLINKER_LEFT_ON })

fun countDay(day: DayRecap): CountDay {
    val leads = day.cards.filter { it.kind == ReviewKind.LANE_SIGNALLED || it.kind == ReviewKind.TURN_SIGNALLED }
        .mapNotNull { card -> card.summary.signalLeadMeters?.let(::roundMeters) }
    return CountDay(day.date, reviewCounts(day.cards), leads.sum(), leads.size)
}

/** Whole-day, rule-only counts. An uncertain hypothesis never inflates a confirmed count. */
fun recapSummary(cards: List<ReviewCard>): String {
    val c = reviewCounts(cards)
    val unsure = visibleReviewCards(cards).count { it.kind == ReviewKind.UNSURE }
    val turns = NavigationTurn.values().mapNotNull { turn ->
        val missing = cards.count { it.kind == ReviewKind.TURN_NO_SIGNAL && it.navigationTurn == turn }
        val late = cards.count { it.kind == ReviewKind.TURN_LATE_SIGNAL && it.navigationTurn == turn }
        if (missing + late == 0) null else "${when (turn) {
            NavigationTurn.LEFT -> "좌회전"; NavigationTurn.RIGHT -> "우회전"; NavigationTurn.UTURN -> "유턴"
        }} 깜빡이 놓침 $missing · 늦게 켬 $late"
    }
    return "오늘 차선 변경 ${c.good + c.missing + c.late}번 · 깜빡이 놓침 ${c.missing} · 늦게 켬 ${c.late} · 급제동 ${c.hardBrake}" +
        (if (turns.isNotEmpty()) " · ${turns.joinToString(" · ")}" else "") +
        (if (c.blinkerOn > 0) " · 켜 둔 채 주행 ${c.blinkerOn}" else "") + (if (unsure > 0) " · 애매함 $unsure" else "")
}
fun retainedCountDate(date: String, today: String): Boolean = runCatching {
    val day = LocalDate.parse(date); val now = LocalDate.parse(today)
    day >= now.minusDays(6) && day <= now
}.getOrDefault(false)
fun relativeCounts(days: List<CountDay>, today: String): List<RecentCounts> = (1..6).mapNotNull { ago ->
    val date = LocalDate.parse(today).minusDays(ago.toLong()).toString()
    days.find { it.date == date }?.let { RecentCounts("D-$ago", it.counts) }
}
object CountCodec {
    private const val V1 = 0x434E5431; private const val V2 = 0x434E5432; private const val V3 = 0x434E5433
    /** 1.9.0: V3 + 켬 거리 합계·장수. 거리가 없는 날은 계속 옛 형식 그대로 쓴다. */
    private const val V4 = 0x434E5434
    fun encode(day: CountDay): ByteArray = ByteArrayOutputStream().use { bytes ->
        LocalDate.parse(day.date)
        require(day.leadCount in 0..100_000 && day.leadSumM in 0..10_000_000 && (day.leadSumM == 0 || day.leadCount > 0))
        DataOutputStream(bytes).use { out ->
            val turns = listOf(day.counts.turnMissing, day.counts.turnLate, day.counts.turnGood)
            val extended = turns.any { it != 0 }
            val withBlinker = day.counts.blinkerOn != 0 // 0x434E5433 (1.7.0); days without it stay byte-identical to 1.6.0
            val withLead = day.leadCount != 0
            out.writeInt(if (withLead) V4 else if (withBlinker) V3 else if (extended) V2 else V1); out.writeUTF(day.date)
            listOf(day.counts.missing, day.counts.late, day.counts.good, day.counts.hardBrake).forEach { require(it in 0..100_000); out.writeInt(it) }
            if (extended || withBlinker || withLead) turns.forEach { require(it in 0..100_000); out.writeInt(it) }
            if (withBlinker || withLead) { require(day.counts.blinkerOn in 0..100_000); out.writeInt(day.counts.blinkerOn) }
            if (withLead) { out.writeInt(day.leadSumM); out.writeInt(day.leadCount) }
        }; bytes.toByteArray()
    }
    fun decode(bytes: ByteArray): CountDay = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val version = input.readInt(); require(version in V1..V4)
        val date = input.readUTF().also { LocalDate.parse(it) }
        val values = List(4) { input.readInt().also { require(it in 0..100_000) } }
        val turns = if (version >= V2) List(3) { input.readInt().also { require(it in 0..100_000) } } else listOf(0, 0, 0)
        val blinker = if (version >= V3) input.readInt().also { require(it in 0..100_000) } else 0
        val leadSum = if (version >= V4) input.readInt().also { require(it in 0..10_000_000) } else 0
        val leadCount = if (version >= V4) input.readInt().also { require(it in 0..100_000 && (leadSum == 0 || it > 0)) } else 0
        require(input.available() == 0)
        CountDay(date, ReviewCounts(values[0], values[1], values[2], values[3], turns[0], turns[1], turns[2], blinker), leadSum, leadCount)
    }
}
