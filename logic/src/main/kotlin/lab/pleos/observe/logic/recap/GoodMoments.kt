package lab.pleos.observe.logic.recap

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.LocalDate

/** 1.9.0 B "잘한 순간 모으기". 누적 숫자 몇 개만 남기는 순수 로직(안드로이드 의존 없음).
 *
 * 남기는 것: 누적([GoodState.total]), 깜빡이를 먼저 켠 연속([GoodState.streak]), 최고 연속([GoodState.best]).
 * 오늘 카드를 다시 읽어도 두 번 세지 않도록 "오늘이 시작될 때의 값(start*)"과 그 날짜 하나만 함께 둔다.
 * 오늘의 현재값 = 시작값 위에 오늘 카드를 시간순으로 다시 쌓은 값이라서, 카드 id를 따로 기억하지 않고도
 * 같은 카드를 여러 번 읽거나 "아니에요"를 눌렀다 되돌려도 결과가 같다. 위치·시각·카드 id는 남기지 않는다. */
data class GoodState(
    /** 시작값을 만든 날짜(내부 표시, 화면에 쓰지 않음). 빈 문자열 = 아직 모은 것이 없음. */
    val date: String = "",
    val startTotal: Int = 0, val startStreak: Int = 0, val startBest: Int = 0,
    val total: Int = 0, val streak: Int = 0, val best: Int = 0,
)

private val EARLY_KINDS = setOf(ReviewKind.LANE_SIGNALLED, ReviewKind.TURN_SIGNALLED)
private val BREAKING_KINDS = setOf(ReviewKind.LANE_NO_SIGNAL, ReviewKind.LANE_LATE_SIGNAL,
    ReviewKind.TURN_NO_SIGNAL, ReviewKind.TURN_LATE_SIGNAL)

/** 화면에 보이고, 운전자가 "아니에요"로 빼지 않았고, 연속을 이어가거나 끊는 종류의 카드만 시간순으로. */
private fun streakCards(cards: List<ReviewCard>): List<ReviewCard> =
    chronologicalCards(visibleReviewCards(cards).filter { it.feedback != Feedback.NO && (it.kind in EARLY_KINDS || it.kind in BREAKING_KINDS) })

/** [date]의 카드로 상태를 다시 계산한다. 날짜가 바뀌었으면 어제의 현재값이 오늘의 시작값이 된다.
 * 이미 더 늦은 날로 넘어간 상태에 지난 날 카드가 오면 무시한다. */
fun updateGoodMoments(state: GoodState, date: String, cards: List<ReviewCard>): GoodState {
    val base = when {
        state.date == date -> state
        state.date.isNotEmpty() && state.date > date -> return state
        else -> state.copy(date = date, startTotal = state.total, startStreak = state.streak, startBest = state.best)
    }
    var total = base.startTotal; var streak = base.startStreak; var best = base.startBest
    for (card in streakCards(cards)) {
        if (card.kind in EARLY_KINDS) { total++; streak++; if (streak > best) best = streak } else streak = 0
    }
    val next = base.copy(total = total, streak = streak, best = best)
    // 모은 것이 전혀 없으면 날짜만 남은 상태를 만들지 않는다(기록 삭제 뒤 빈 파일이 생기지 않게).
    return if (next.total == 0 && next.streak == 0 && next.best == 0) GoodState() else next
}

/** 오늘 카드 중 모으기에 들어간 잘한 순간이 "몇 번째"인지. 시작값(어제까지의 누적)에서 이어 센다. */
fun goodOrdinals(state: GoodState, cards: List<ReviewCard>): Map<String, Int> {
    val out = LinkedHashMap<String, Int>(); var n = state.startTotal
    for (card in streakCards(cards)) if (card.kind in EARLY_KINDS) out[card.id] = ++n
    return out
}

/** 10·50·100번째, 그 뒤로는 100의 배수. */
fun isGoodMilestone(n: Int) = n == 10 || n == 50 || (n >= 100 && n % 100 == 0)

/** 잘한 순간 카드의 작은 칩. 이정표만 말끝이 조금 다르다. 팝업·소리 없음. */
fun goodMomentChip(n: Int) = if (isGoodMilestone(n)) "${n}번째 잘한 순간 · 이정표" else "${n}번째 잘한 순간"

const val GOOD_TILE_HEADING = "모은 잘한 순간"
fun goodTileNumber(state: GoodState) = state.total.toString()
fun goodTileState(state: GoodState) =
    if (state.total == 0 && state.best == 0) "깜빡이를 먼저 켜면 여기에 쌓여요"
    // 연속이 끊긴 날 "0번 연속"은 혼내는 말처럼 읽혀서 최고 기록만 보여 준다.
    else if (state.streak == 0) "깜빡이 먼저 켬 최고 ${state.best}번 연속"
    else "깜빡이 먼저 켬 ${state.streak}번 연속(최고 ${state.best})"

/** 앱 안 파일 형식. 숫자 6개와 날짜 하나뿐이다. */
object GoodCodec {
    private const val MAGIC = 0x474F4431 // "GOD1"
    private const val MAX = 100_000_000
    fun encode(state: GoodState): ByteArray = ByteArrayOutputStream().use { bytes ->
        if (state.date.isNotEmpty()) LocalDate.parse(state.date)
        listOf(state.startTotal, state.startStreak, state.startBest, state.total, state.streak, state.best).forEach { require(it in 0..MAX) }
        require(state.streak <= state.total && state.best >= state.streak && state.best <= state.total)
        require(state.startStreak <= state.startTotal && state.startBest >= state.startStreak && state.startBest <= state.startTotal)
        require(state.startTotal <= state.total)
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeUTF(state.date)
            listOf(state.startTotal, state.startStreak, state.startBest, state.total, state.streak, state.best).forEach(out::writeInt)
        }
        bytes.toByteArray()
    }
    fun decode(bytes: ByteArray): GoodState = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == MAGIC) { "Unsupported good-moment schema" }
        val date = input.readUTF().also { if (it.isNotEmpty()) LocalDate.parse(it) }
        val v = List(6) { input.readInt() }
        require(input.available() == 0)
        GoodState(date, v[0], v[1], v[2], v[3], v[4], v[5]).also { encode(it) } // 같은 검사를 한 번 더 통과해야 읽는다.
    }
}
