package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test

/** 1.9.0 B: "잘한 순간 모으기". 누적 숫자 몇 개만 남기고, 오늘 카드를 다시 읽어도 같은 결과가 나와야 한다. */
class GoodMomentsTest {
    private fun kph(v: Float) = v / 3.6f
    private val summary = SegmentSummary(0, 1_000, 0, -6f, 7f, 1, 1, kph(58f), kph(58f), false, SignalTiming.NONE, true)
    private var seq = 0
    private fun card(kind: ReviewKind, at: Long, id: String = "c${seq++}", feedback: Feedback = Feedback.NONE,
                     uncertainty: Set<Uncertainty> = emptySet()) =
        ReviewCard(id, at, kind, kind, summary, feedback = feedback, uncertainty = uncertainty)
    private val good = ReviewKind.LANE_SIGNALLED
    private val missed = ReviewKind.LANE_NO_SIGNAL
    private val bannedWords = listOf("위반", "점수", "순위", "랭킹", "등급", "벌점", "경고")
    private fun update(state: GoodState, today: String, cards: List<ReviewCard>) = updateGoodMoments(state, today, cards)

    // ---- 누적·연속 --------------------------------------------------------------------------

    @Test fun emptyStateStartsAtZero() {
        assertEquals(GoodState(), update(GoodState(), "2026-10-26", emptyList()))
    }

    @Test fun goodMomentsAddUpAndEarlySignalStreakGrows() {
        val s = update(GoodState(), "2026-10-26", listOf(card(good, 1), card(ReviewKind.TURN_SIGNALLED, 2), card(good, 3)))
        assertEquals(3, s.total); assertEquals(3, s.streak); assertEquals(3, s.best)
    }

    @Test fun missedOrLateSignalResetsTheStreakButKeepsTheBestAndTotal() {
        val cards = listOf(card(good, 1), card(good, 2), card(good, 3), card(ReviewKind.LANE_LATE_SIGNAL, 4), card(good, 5))
        val s = update(GoodState(), "2026-10-26", cards)
        assertEquals(4, s.total); assertEquals(1, s.streak); assertEquals(3, s.best)
        val turns = update(GoodState(), "2026-10-26", listOf(card(good, 1), card(ReviewKind.TURN_NO_SIGNAL, 2)))
        assertEquals(0, turns.streak); assertEquals(1, turns.best)
        val lateTurn = update(GoodState(), "2026-10-26", listOf(card(good, 1), card(ReviewKind.TURN_LATE_SIGNAL, 2)))
        assertEquals(0, lateTurn.streak)
    }

    @Test fun cardsAreReadInDrivingOrderNotInListOrder() {
        val shuffled = listOf(card(good, 5), card(missed, 3), card(good, 1), card(good, 2), card(good, 4))
        val s = update(GoodState(), "2026-10-26", shuffled)
        // 시간순: 좋 좋 놓침 좋 좋 → 연속 2, 최고 2
        assertEquals(4, s.total); assertEquals(2, s.streak); assertEquals(2, s.best)
    }

    @Test fun otherKindsDoNotTouchTheStreak() {
        val cards = listOf(card(good, 1), card(ReviewKind.HARD_BRAKE, 2), card(ReviewKind.BLINKER_LEFT_ON, 3),
            card(ReviewKind.UNSURE, 4), card(good, 5))
        val s = update(GoodState(), "2026-10-26", cards)
        assertEquals(2, s.total); assertEquals(2, s.streak)
    }

    @Test fun cardsHiddenFromTheScreenAreNotCounted() {
        val hidden = card(good, 2, uncertainty = setOf(Uncertainty.SIGNAL_GAP))
        val s = update(GoodState(), "2026-10-26", listOf(card(good, 1), hidden))
        assertEquals(1, s.total)
    }

    // ---- 같은 카드 중복 금지 · 아니에요 반영 ---------------------------------------------------------

    @Test fun readingTheSameCardsAgainNeverCountsTwice() {
        val cards = listOf(card(good, 1), card(good, 2))
        val first = update(GoodState(), "2026-10-26", cards)
        val again = update(update(first, "2026-10-26", cards), "2026-10-26", cards)
        assertEquals(first, again)
        // 카드가 하나 늘면 하나만 더해진다.
        val more = update(again, "2026-10-26", cards + card(good, 3))
        assertEquals(3, more.total); assertEquals(3, more.streak)
    }

    @Test fun cardMarkedNotQuiteIsLeftOutAndBroughtBackWhenUndone() {
        val a = card(good, 1, "a"); val b = card(good, 2, "b"); val c = card(good, 3, "c")
        val base = update(GoodState(), "2026-10-26", listOf(a, b, c))
        assertEquals(3, base.total)
        val no = update(base, "2026-10-26", listOf(a, b.copy(feedback = Feedback.NO), c))
        assertEquals(2, no.total); assertEquals(2, no.streak)
        val undone = update(no, "2026-10-26", listOf(a, b.copy(feedback = Feedback.YES), c))
        assertEquals(3, undone.total); assertEquals(3, undone.streak); assertEquals(3, undone.best)
        assertEquals(base, undone)
    }

    @Test fun aMissedCardTheDriverRejectedDoesNotBreakTheStreak() {
        val m = card(missed, 2, "m")
        val cards = listOf(card(good, 1), m, card(good, 3))
        assertEquals(1, update(GoodState(), "2026-10-26", cards).streak)
        assertEquals(2, update(GoodState(), "2026-10-26", listOf(cards[0], m.copy(feedback = Feedback.NO), cards[2])).streak)
    }

    @Test fun bestFollowsTheCardsOfTheDayWhenACardIsRejectedAndRestored() {
        val a = card(good, 1, "a"); val b = card(good, 2, "b"); val m = card(missed, 3, "m")
        val s1 = update(GoodState(), "2026-10-26", listOf(a, b, m))
        assertEquals(2, s1.total); assertEquals(0, s1.streak); assertEquals(2, s1.best)
        val s2 = update(s1, "2026-10-26", listOf(a, b.copy(feedback = Feedback.NO), m))
        assertEquals(1, s2.total); assertEquals(1, s2.best)
        assertEquals(s1, update(s2, "2026-10-26", listOf(a, b, m)))
    }

    @Test fun bestFromEarlierDaysIsKeptWhenTodayIsShorter() {
        val day1 = update(GoodState(), "2026-10-26", (1L..5L).map { card(good, it) })
        val day2 = update(day1, "2026-10-27", listOf(card(missed, 1), card(good, 2)))
        assertEquals(6, day2.total); assertEquals(1, day2.streak); assertEquals(5, day2.best)
    }

    // ---- 날짜 넘김 ----------------------------------------------------------------------------

    @Test fun nextDayContinuesFromThePreviousTotalsAndStreak() {
        val day1 = update(GoodState(), "2026-10-26", listOf(card(good, 1), card(good, 2), card(good, 3)))
        val day2 = update(day1, "2026-10-27", listOf(card(good, 100)))
        assertEquals(4, day2.total); assertEquals(4, day2.streak); assertEquals(4, day2.best)
        // 이튿날 놓침으로 시작하면 연속은 0, 누적·최고는 그대로.
        val day2b = update(day1, "2026-10-27", listOf(card(missed, 100)))
        assertEquals(3, day2b.total); assertEquals(0, day2b.streak); assertEquals(3, day2b.best)
    }

    @Test fun anEmptyNewDayKeepsTheNumbersWithoutCountingAnything() {
        val day1 = update(GoodState(), "2026-10-26", listOf(card(good, 1), card(good, 2)))
        val day2 = update(day1, "2026-10-27", emptyList())
        assertEquals(day1.total, day2.total); assertEquals(day1.streak, day2.streak); assertEquals(day1.best, day2.best)
        // 그 날 안에서 다시 읽어도 그대로(진입값이 현재값으로 한 번만 넘어간다).
        assertEquals(day2, update(day2, "2026-10-27", emptyList()))
        val later = update(day2, "2026-10-27", listOf(card(good, 200)))
        assertEquals(3, later.total)
    }

    @Test fun reRunningTheSameDayAfterFoldingDoesNotDoubleCount() {
        val day1 = update(GoodState(), "2026-10-26", listOf(card(good, 1), card(good, 2)))
        val today = listOf(card(good, 100))
        val day2 = update(day1, "2026-10-27", today)
        assertEquals(day2, update(day2, "2026-10-27", today))
        assertEquals(3, day2.total)
    }

    @Test fun anOlderDayAfterTheStateMovedOnIsIgnored() {
        val day2 = update(update(GoodState(), "2026-10-26", listOf(card(good, 1))), "2026-10-27", listOf(card(good, 2)))
        assertEquals(day2, update(day2, "2026-10-26", listOf(card(good, 1), card(good, 9))))
    }

    @Test fun anAllZeroStateIsNormalisedToTheEmptyState() {
        // 기록 삭제 뒤 빈 카드 목록을 읽어도 날짜만 남은 파일이 생기지 않는다.
        assertEquals(GoodState(), update(GoodState(), "2026-10-27", emptyList()))
        val onlyMissed = update(GoodState(), "2026-10-27", listOf(card(missed, 1)))
        assertEquals(GoodState(), onlyMissed)
    }

    // ---- 카드의 "N번째" -------------------------------------------------------------------------

    @Test fun ordinalsContinueFromTheTotalBeforeToday() {
        val day1 = update(GoodState(), "2026-10-26", listOf(card(good, 1), card(good, 2)))
        val a = card(good, 10, "a"); val m = card(missed, 11, "m"); val b = card(ReviewKind.TURN_SIGNALLED, 12, "b")
        val today = listOf(b, m, a)
        val state = update(day1, "2026-10-27", today)
        assertEquals(mapOf("a" to 3, "b" to 4), goodOrdinals(state, today))
    }

    @Test fun ordinalsSkipCardsMarkedNotQuiteAndRenumberTheRest() {
        val a = card(good, 1, "a"); val b = card(good, 2, "b"); val c = card(good, 3, "c")
        val cards = listOf(a, b.copy(feedback = Feedback.NO), c)
        val state = update(GoodState(), "2026-10-26", cards)
        assertEquals(mapOf("a" to 1, "c" to 2), goodOrdinals(state, cards))
        assertNull(goodOrdinals(state, cards)["b"])
    }

    @Test fun chipTextIsPlainAndMilestonesChangeOnlyTheWording() {
        assertEquals("7번째 잘한 순간", goodMomentChip(7))
        assertEquals("1번째 잘한 순간", goodMomentChip(1))
        for (n in listOf(10, 50, 100, 200, 1000)) {
            assertTrue("$n", isGoodMilestone(n))
            assertEquals("${n}번째 잘한 순간 · 이정표", goodMomentChip(n))
        }
        for (n in listOf(1, 9, 11, 49, 51, 99, 101, 150)) { assertFalse("$n", isGoodMilestone(n)); assertEquals("${n}번째 잘한 순간", goodMomentChip(n)) }
    }

    // ---- 홈 타일 문구 ---------------------------------------------------------------------------

    @Test fun tileTextsFollowTheSpec() {
        assertEquals("모은 잘한 순간", GOOD_TILE_HEADING)
        assertEquals("깜빡이 먼저 켬 3번 연속(최고 7)", goodTileState(GoodState(total = 12, streak = 3, best = 7)))
        assertEquals("깜빡이 먼저 켬 최고 7번 연속", goodTileState(GoodState(total = 12, streak = 0, best = 7)))
        assertEquals("깜빡이를 먼저 켜면 여기에 쌓여요", goodTileState(GoodState()))
        assertEquals("12", goodTileNumber(GoodState(total = 12, streak = 3, best = 7)))
        assertEquals("0", goodTileNumber(GoodState()))
    }

    @Test fun wordingIsCalm() {
        val texts = mutableListOf(GOOD_TILE_HEADING, goodTileState(GoodState()), goodTileState(GoodState(total = 50, streak = 4, best = 9)))
        for (n in listOf(1, 10, 50, 100, 101)) texts += goodMomentChip(n)
        texts.forEach { text -> bannedWords.forEach { assertFalse("$it in $text", text.contains(it)) } }
    }

    // ---- 저장 형식 -------------------------------------------------------------------------------

    @Test fun codecRoundTripsAndHoldsOnlyNumbersAndOneDate() {
        val s = update(update(GoodState(), "2026-10-26", listOf(card(good, 1), card(good, 2))), "2026-10-27", listOf(card(good, 3)))
        val bytes = GoodCodec.encode(s)
        assertEquals(s, GoodCodec.decode(bytes))
        assertEquals(GoodState(), GoodCodec.decode(GoodCodec.encode(GoodState())))
        val text = bytes.toString(Charsets.ISO_8859_1)
        for (forbidden in listOf("wallTime", "latitude", "VIN", "feedback", "c0", "c1")) assertFalse(text.contains(forbidden))
        assertTrue(bytes.size < 50)
    }

    @Test fun codecRejectsTruncationTrailingDataAndImpossibleValues() {
        val bytes = GoodCodec.encode(GoodState("2026-10-26", 0, 0, 0, 2, 2, 2))
        assertTrue(runCatching { GoodCodec.decode(bytes.copyOf(bytes.size - 1)) }.isFailure)
        assertTrue(runCatching { GoodCodec.decode(bytes + 0) }.isFailure)
        assertTrue(runCatching { GoodCodec.encode(GoodState("2026-10-26", 0, 0, 0, -1, 0, 0)) }.isFailure)
        assertTrue(runCatching { GoodCodec.encode(GoodState("not-a-date", 0, 0, 0, 1, 1, 1)) }.isFailure)
        // 연속이 누적보다 크거나 최고가 연속보다 작은 값은 만들어질 수 없다.
        assertTrue(runCatching { GoodCodec.encode(GoodState("2026-10-26", 0, 0, 0, 1, 2, 2)) }.isFailure)
        assertTrue(runCatching { GoodCodec.encode(GoodState("2026-10-26", 0, 0, 0, 3, 3, 2)) }.isFailure)
        val wrongMagic = bytes.copyOf().also { it[0] = 0 }
        assertTrue(runCatching { GoodCodec.decode(wrongMagic) }.isFailure)
    }
}
