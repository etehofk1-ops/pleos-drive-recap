package lab.pleos.observe.logic.recap

import java.io.*
import java.time.LocalDate

/** Seven days of rule-only counts. No IDs, timestamps, location or segment evidence. */
data class ReviewCounts(val missing: Int = 0, val late: Int = 0, val good: Int = 0, val hardBrake: Int = 0,
    val turnMissing: Int = 0, val turnLate: Int = 0, val turnGood: Int = 0)
data class CountDay(val date: String, val counts: ReviewCounts)
fun reviewCounts(cards: List<ReviewCard>) = ReviewCounts(
    cards.count { it.kind == ReviewKind.LANE_NO_SIGNAL }, cards.count { it.kind == ReviewKind.LANE_LATE_SIGNAL },
    cards.count { it.kind == ReviewKind.LANE_SIGNALLED }, cards.count { it.kind == ReviewKind.HARD_BRAKE },
    cards.count { it.kind == ReviewKind.TURN_NO_SIGNAL }, cards.count { it.kind == ReviewKind.TURN_LATE_SIGNAL },
    cards.count { it.kind == ReviewKind.TURN_SIGNALLED })

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
        (if (turns.isNotEmpty()) " · ${turns.joinToString(" · ")}" else "") + (if (unsure > 0) " · 애매함 $unsure" else "")
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
    fun encode(day: CountDay): ByteArray = ByteArrayOutputStream().use { bytes ->
        LocalDate.parse(day.date)
        DataOutputStream(bytes).use { out ->
            val turns = listOf(day.counts.turnMissing, day.counts.turnLate, day.counts.turnGood)
            val extended = turns.any { it != 0 }
            out.writeInt(if (extended) 0x434E5432 else 0x434E5431); out.writeUTF(day.date)
            listOf(day.counts.missing, day.counts.late, day.counts.good, day.counts.hardBrake).forEach { require(it in 0..100_000); out.writeInt(it) }
            if (extended) turns.forEach { require(it in 0..100_000); out.writeInt(it) }
        }; bytes.toByteArray()
    }
    fun decode(bytes: ByteArray): CountDay = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val version = input.readInt(); require(version in 0x434E5431..0x434E5432)
        val date = input.readUTF().also { LocalDate.parse(it) }
        val values = List(4) { input.readInt().also { require(it in 0..100_000) } }
        val turns = if (version == 0x434E5432) List(3) { input.readInt().also { require(it in 0..100_000) } } else listOf(0, 0, 0)
        require(input.available() == 0)
        CountDay(date, ReviewCounts(values[0], values[1], values[2], values[3], turns[0], turns[1], turns[2]))
    }
}
