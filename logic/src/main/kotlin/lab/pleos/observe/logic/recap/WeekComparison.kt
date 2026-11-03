package lab.pleos.observe.logic.recap

import java.util.Locale
import kotlin.math.roundToInt

/** 1.9.0 A "지난 7일과 비교". 오늘 집계와 남아 있는 지난 날들의 집계(CountDay)만으로 문장을 만든다(안드로이드 의존 없음).
 *
 * 원칙: 점수·순위·남과의 비교 없음. [ComparisonRow.better]는 기존 강조용 플래그이며 운전 개선의 근거가 아니다.
 * 개수 보존 기간(7일)은 그대로라서 지난 날은 오늘을 뺀 최대 6일이다. 기록이 없는 날은 0으로 채우지 않고 평균에서 뺀다. */
data class ComparisonRow(val text: String, val better: Boolean)

/** [rows]가 비면 [note] 한 줄만 보인다. [basis]는 평균에 쓴 날 수("지난 7일 중 주행한 3일 기준"). */
data class WeekComparison(val rows: List<ComparisonRow> = emptyList(), val note: String = "", val basis: String = "", val baseDays: Int = 0)

const val NOTE_NO_PAST = "기록이 쌓이면 비교해 드려요"
const val NOTE_NO_TODAY = "오늘 주행 기록이 생기면 비교해 드려요"
const val NOTE_UNADJUSTED_COUNTS = "주행량은 반영하지 않은 횟수예요"

private fun missedOf(c: ReviewCounts) = c.missing + c.late + c.turnMissing + c.turnLate
private fun goodOf(c: ReviewCounts) = c.good + c.turnGood

/** 소수 첫째 자리까지 반올림한 평균. 정수면 소수점을 쓰지 않는다("2번", "2.4번"). */
private fun average1(total: Int, days: Int): Double = (total * 10.0 / days).roundToInt() / 10.0
private fun format(value: Double) =
    if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)

fun weekComparison(today: CountDay, todayDrove: Boolean, past: List<CountDay>): WeekComparison {
    val base = past.filter { it.date != today.date }
    if (!todayDrove && base.isEmpty()) return WeekComparison(note = NOTE_NO_PAST)
    if (!todayDrove) return WeekComparison(note = NOTE_NO_TODAY)
    if (base.isEmpty()) return WeekComparison(note = NOTE_NO_PAST)
    val days = base.size
    val rows = mutableListOf<ComparisonRow>()

    val missed = missedOf(today.counts); val missedAvg = average1(base.sumOf { missedOf(it.counts) }, days)
    rows += ComparisonRow("놓친 순간 ${missed}번 · 최근 ${days}일 평균 ${format(missedAvg)}번", missed < missedAvg)

    val leadCards = base.sumOf { it.leadCount }
    val leadDays = base.count { it.leadCount > 0 }
    if (today.leadCount > 0 && leadCards > 0) {
        val mine = (today.leadSumM.toDouble() / today.leadCount).roundToInt()
        val before = (base.sumOf { it.leadSumM }.toDouble() / leadCards).roundToInt()
        rows += if (mine > before) ComparisonRow("켬 거리 평균 ${mine}m · 최근 ${leadDays}일 평균 ${before}m보다 ${mine - before}m 일찍", true)
            else ComparisonRow("켬 거리 평균 ${mine}m · 최근 ${leadDays}일 평균 ${before}m", false)
    }

    val good = goodOf(today.counts); val goodAvg = average1(base.sumOf { goodOf(it.counts) }, days)
    rows += ComparisonRow("잘한 순간 ${good}번 · 최근 ${days}일 평균 ${format(goodAvg)}번", good > goodAvg)
    return WeekComparison(rows, basis = "지난 7일 중 주행한 ${days}일 기준", baseDays = days)
}
