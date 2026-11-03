package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test

/** 1.9.0 A: "지난 7일과 비교". 오늘과 지난 날들의 집계(CountDay)만으로 문장을 만든다. 점수·순위·혼내는 말은 없다. */
class WeekComparisonTest {
    private fun kph(v: Float) = v / 3.6f
    private fun summary(leadM: Float? = null) = SegmentSummary(0, 1_000, 0, -6f, 7f, 1, 1, kph(58f), kph(58f), false,
        if (leadM == null) SignalTiming.NONE else SignalTiming.BEFORE, true, signalLeadMeters = leadM)
    private fun card(kind: ReviewKind, id: String, leadM: Float? = null, feedback: Feedback = Feedback.NONE) =
        ReviewCard(id, 0, kind, kind, summary(leadM), feedback = feedback)
    private fun day(date: String, missed: Int = 0, good: Int = 0, leadSum: Int = 0, leadCount: Int = 0) =
        CountDay(date, ReviewCounts(missing = missed, good = good), leadSum, leadCount)
    private val bannedWords = listOf("위반", "점수", "순위", "랭킹", "등급", "벌점", "나빠", "혼났", "경고")

    // ---- 하루 집계에 켬 거리 합계·개수 ---------------------------------------------------------

    @Test fun countDayAddsLeadSumAndCountFromEarlySignalCardsOnly() {
        val cards = listOf(
            card(ReviewKind.LANE_SIGNALLED, "a", 46f), card(ReviewKind.TURN_SIGNALLED, "b", 31f), card(ReviewKind.LANE_SIGNALLED, "c", null),
            card(ReviewKind.LANE_LATE_SIGNAL, "d", 40f), card(ReviewKind.LANE_NO_SIGNAL, "e"))
        val d = countDay(DayRecap("2026-10-26", 60_000, 0, cards))
        assertEquals("2026-10-26", d.date)
        assertEquals(reviewCounts(cards), d.counts)
        // 45m + 30m: 켬 거리는 카드 화면과 같은 5m 단위 반올림 값을 더한다. 늦게 켠 카드의 값은 쓰지 않는다.
        assertEquals(75, d.leadSumM)
        assertEquals(2, d.leadCount)
    }

    @Test fun countDayWithoutAnyLeadHasZeroLead() {
        val d = countDay(DayRecap("2026-10-26", 1_000, 0, listOf(card(ReviewKind.LANE_NO_SIGNAL, "a"))))
        assertEquals(0, d.leadSumM); assertEquals(0, d.leadCount)
    }
    @Test fun rejectedGoodMomentsLeaveBothLeadAverageAndCollection() {
        val date = "2026-11-03"
        val cards = listOf(card(ReviewKind.LANE_SIGNALLED, "a", 100f, Feedback.NO),
            card(ReviewKind.TURN_SIGNALLED, "b", 30f, Feedback.YES), card(ReviewKind.LANE_SIGNALLED, "c", 40f))
        val d = countDay(DayRecap(date, cards = cards))
        assertEquals(70, d.leadSumM); assertEquals(2, d.leadCount)
        assertEquals(1, d.counts.good); assertEquals(1, d.counts.turnGood)
        val collected = updateGoodMoments(GoodState(), date, cards)
        assertEquals(2, collected.total)
        assertEquals(setOf("b", "c"), goodOrdinals(collected, cards).keys)
    }
    @Test fun labelsUseActualOneThreeAndSixDaySamples() {
        for (n in listOf(1, 3, 6)) {
            val past = (1..n).map { day("2026-10-${26 - it}", missed = 2, good = 1, leadSum = 30, leadCount = 1) }
            val c = weekComparison(day("2026-10-26", missed = 1, good = 2, leadSum = 40, leadCount = 1), true, past)
            assertEquals(n, c.baseDays)
            assertTrue(c.rows.all { it.text.contains("최근 ${n}일 평균") })
            assertTrue(c.rows.none { it.text.contains("나아") || it.text.contains("better") })
        }
        assertEquals("주행량은 반영하지 않은 횟수예요", NOTE_UNADJUSTED_COUNTS)
    }

    // ---- 코덱: 옛 기록(실제 1.8.0 이하 바이트)도 읽는다 --------------------------------------------

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    // 1.6.0 이전(0x434E5431) · 1.6.x(0x434E5432, 회전 포함) · 1.7.0~1.8.0(0x434E5433, 켜 둔 채 포함)에서 쓰던 파일 그대로.
    private val v1 = "434e5431000a323032362d31302d323300000001000000020000000300000004"
    private val v2 = "434e5432000a323032362d31302d323400000001000000020000000300000004000000050000000600000007"
    private val v3 = "434e5433000a323032362d31302d32350000000100000002000000030000000400000005000000060000000700000008"

    @Test fun oldCountFilesDecodeWithNoLeadDistance() {
        assertEquals(CountDay("2026-10-23", ReviewCounts(1, 2, 3, 4)), CountCodec.decode(hex(v1)))
        assertEquals(CountDay("2026-10-24", ReviewCounts(1, 2, 3, 4, 5, 6, 7)), CountCodec.decode(hex(v2)))
        val old = CountCodec.decode(hex(v3))
        assertEquals(CountDay("2026-10-25", ReviewCounts(1, 2, 3, 4, 5, 6, 7, 8)), old)
        assertEquals(0, old.leadSumM); assertEquals(0, old.leadCount)
    }

    @Test fun daysWithoutLeadStillEncodeToTheOldBytes() {
        assertArrayEquals(hex(v1), CountCodec.encode(CountDay("2026-10-23", ReviewCounts(1, 2, 3, 4))))
        assertArrayEquals(hex(v2), CountCodec.encode(CountDay("2026-10-24", ReviewCounts(1, 2, 3, 4, 5, 6, 7))))
        assertArrayEquals(hex(v3), CountCodec.encode(CountDay("2026-10-25", ReviewCounts(1, 2, 3, 4, 5, 6, 7, 8))))
    }

    @Test fun leadDistanceRoundTripsInTheNewVersionAndKeepsEveryOldCount() {
        val d = CountDay("2026-10-26", ReviewCounts(1, 2, 3, 4, 5, 6, 7, 8), leadSumM = 245, leadCount = 6)
        val bytes = CountCodec.encode(d)
        assertEquals(d, CountCodec.decode(bytes))
        assertEquals(0x434E5434, java.nio.ByteBuffer.wrap(bytes).int)
        val onlyLead = CountDay("2026-10-26", ReviewCounts(), 30, 1)
        assertEquals(onlyLead, CountCodec.decode(CountCodec.encode(onlyLead)))
        assertTrue(bytes.size < 70)
    }

    @Test fun newCountFilesRejectTruncationTrailingDataAndBadLead() {
        val bytes = CountCodec.encode(CountDay("2026-10-26", ReviewCounts(), 30, 1))
        assertTrue(runCatching { CountCodec.decode(bytes.copyOf(bytes.size - 1)) }.isFailure)
        assertTrue(runCatching { CountCodec.decode(bytes + 0) }.isFailure)
        assertTrue(runCatching { CountCodec.encode(CountDay("2026-10-26", ReviewCounts(), -5, 1)) }.isFailure)
        assertTrue(runCatching { CountCodec.encode(CountDay("2026-10-26", ReviewCounts(), 30, -1)) }.isFailure)
        // 거리 합계가 있는데 개수가 0인 값은 만들 수 없다.
        assertTrue(runCatching { CountCodec.encode(CountDay("2026-10-26", ReviewCounts(), 30, 0)) }.isFailure)
    }

    // ---- 비교 문장 ------------------------------------------------------------------------------

    @Test fun missedMomentsIncludeLateAndTurnsButNotLeftOn() {
        val today = CountDay("2026-10-26", ReviewCounts(missing = 1, late = 1, turnMissing = 1, turnLate = 0, blinkerOn = 5))
        val past = listOf(day("2026-10-25", missed = 3))
        val c = weekComparison(today, true, past)
        assertEquals("놓친 순간 3번 · 최근 1일 평균 3번", c.rows[0].text)
    }

    @Test fun averageUsesOnlyDaysWithRecordsAndOneDecimal() {
        val past = listOf(day("2026-10-25", missed = 3), day("2026-10-24", missed = 2), day("2026-10-22", missed = 2),
            day("2026-10-21", missed = 3), day("2026-10-20", missed = 2))
        val c = weekComparison(day("2026-10-26", missed = 1), true, past)
        assertEquals("놓친 순간 1번 · 최근 5일 평균 2.4번", c.rows[0].text)
        assertTrue(c.rows[0].better)
        assertEquals(5, c.baseDays)
        assertEquals("지난 7일 중 주행한 5일 기준", c.basis)
    }

    @Test fun daysWithoutDrivingAreNotInTheAverage() {
        // 기록 없는 날(10-24, 10-23)은 목록에 없다. 0으로 채워 평균을 깎지 않는다.
        val c = weekComparison(day("2026-10-26", missed = 4), true, listOf(day("2026-10-25", missed = 2), day("2026-10-22", missed = 2)))
        assertEquals("놓친 순간 4번 · 최근 2일 평균 2번", c.rows[0].text)
        assertEquals(2, c.baseDays)
    }

    @Test fun todayIsNeverInsideItsOwnBaseline() {
        val c = weekComparison(day("2026-10-26", missed = 9), true, listOf(day("2026-10-26", missed = 9), day("2026-10-25", missed = 3)))
        assertEquals("놓친 순간 9번 · 최근 1일 평균 3번", c.rows[0].text)
        assertEquals(1, c.baseDays)
    }

    @Test fun onlyTheImprovedSideIsHighlightedAndWorseNumbersStayPlain() {
        val past = listOf(day("2026-10-25", missed = 2, good = 3, leadSum = 120, leadCount = 4))
        val better = weekComparison(day("2026-10-26", missed = 1, good = 5, leadSum = 160, leadCount = 4), true, past)
        assertEquals(listOf(true, true, true), better.rows.map { it.better })
        val worse = weekComparison(day("2026-10-26", missed = 4, good = 1, leadSum = 80, leadCount = 4), true, past)
        assertEquals(listOf(false, false, false), worse.rows.map { it.better })
        val same = weekComparison(day("2026-10-26", missed = 2, good = 3, leadSum = 120, leadCount = 4), true, past)
        assertEquals(listOf(false, false, false), same.rows.map { it.better })
    }

    @Test fun nearlyEqualAfterRoundingIsNotHighlighted() {
        // 평균 2.04 → 화면에는 2. 오늘 2번과 같아 보이므로 강조하지 않는다.
        val past = (1..25).map { day("2026-09-%02d".format(it), missed = if (it == 1) 3 else 2) }
        assertEquals(false, weekComparison(day("2026-10-26", missed = 2), true, past).rows[0].better)
    }

    @Test fun leadDistanceSentenceNamesHowMuchEarlierOnlyWhenEarlier() {
        val past = listOf(day("2026-10-25", leadSum = 112, leadCount = 4), day("2026-10-24", leadSum = 0, leadCount = 0))
        val earlier = weekComparison(day("2026-10-26", leadSum = 120, leadCount = 3), true, past)
        val row = earlier.rows.single { it.text.startsWith("켬 거리") }
        assertEquals("켬 거리 평균 40m · 최근 1일 평균 28m보다 12m 일찍", row.text)
        assertTrue(row.better)
        val shorter = weekComparison(day("2026-10-26", leadSum = 60, leadCount = 3), true, past)
        val plain = shorter.rows.single { it.text.startsWith("켬 거리") }
        assertEquals("켬 거리 평균 20m · 최근 1일 평균 28m", plain.text)
        assertFalse(plain.better)
    }

    @Test fun leadAverageIsPooledOverCardsNotOverDays() {
        // 하루 1장(100m) + 하루 3장(합 90m): 카드 평균 47.5 → 48. 날 평균의 평균(65)이 아니다.
        val past = listOf(day("2026-10-25", leadSum = 100, leadCount = 1), day("2026-10-24", leadSum = 90, leadCount = 3))
        val row = weekComparison(day("2026-10-26", leadSum = 50, leadCount = 1), true, past).rows.single { it.text.startsWith("켬 거리") }
        assertEquals("켬 거리 평균 50m · 최근 2일 평균 48m보다 2m 일찍", row.text)
    }

    @Test fun leadRowIsOmittedWhenEitherSideHasNoDistance() {
        val noToday = weekComparison(day("2026-10-26", missed = 1), true, listOf(day("2026-10-25", leadSum = 90, leadCount = 3)))
        assertTrue(noToday.rows.none { it.text.startsWith("켬 거리") })
        val noPast = weekComparison(day("2026-10-26", leadSum = 90, leadCount = 3), true, listOf(day("2026-10-25", missed = 1)))
        assertTrue(noPast.rows.none { it.text.startsWith("켬 거리") })
    }

    @Test fun rowOrderIsMissedLeadGood() {
        val past = listOf(day("2026-10-25", missed = 2, good = 3, leadSum = 120, leadCount = 4))
        val c = weekComparison(day("2026-10-26", missed = 1, good = 5, leadSum = 160, leadCount = 4), true, past)
        assertTrue(c.rows[0].text.startsWith("놓친 순간")); assertTrue(c.rows[1].text.startsWith("켬 거리")); assertTrue(c.rows[2].text.startsWith("잘한 순간"))
        assertEquals("잘한 순간 5번 · 최근 1일 평균 3번", c.rows[2].text)
    }

    @Test fun goodMomentsCountTurnsToo() {
        val today = CountDay("2026-10-26", ReviewCounts(good = 2, turnGood = 1))
        val c = weekComparison(today, true, listOf(day("2026-10-25", good = 1)))
        assertEquals("잘한 순간 3번 · 최근 1일 평균 1번", c.rows.last().text)
    }

    @Test fun withoutPastDaysOnlyOneCalmLineIsShown() {
        val c = weekComparison(day("2026-10-26", missed = 1, good = 2), true, emptyList())
        assertTrue(c.rows.isEmpty())
        assertEquals("기록이 쌓이면 비교해 드려요", c.note)
        assertEquals("", c.basis)
        val onlyToday = weekComparison(day("2026-10-26"), true, listOf(day("2026-10-26", missed = 3)))
        assertTrue(onlyToday.rows.isEmpty())
    }

    @Test fun withoutDrivingTodayThereIsNothingToCompare() {
        val c = weekComparison(day("2026-10-26"), false, listOf(day("2026-10-25", missed = 3)))
        assertTrue(c.rows.isEmpty())
        assertEquals("오늘 주행 기록이 생기면 비교해 드려요", c.note)
    }

    @Test fun wordingIsCalmAcrossManyCombinations() {
        for (m in listOf(0, 1, 7)) for (g in listOf(0, 2, 9)) for (pm in listOf(0, 3)) for (pg in listOf(0, 4)) {
            val c = weekComparison(day("2026-10-26", missed = m, good = g, leadSum = 90, leadCount = 3), true,
                listOf(day("2026-10-25", missed = pm, good = pg, leadSum = 60, leadCount = 2)))
            (c.rows.map { it.text } + c.note + c.basis).forEach { text -> bannedWords.forEach { assertFalse("$it in $text", text.contains(it)) } }
        }
        val note = weekComparison(day("2026-10-26"), true, emptyList())
        bannedWords.forEach { assertFalse(note.note.contains(it)) }
    }
}
