package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

/** 1.8.0 C안 "한 장씩": 제목·근거 칩·막대·위치 점·종류별 집계 문구는 전부 이 순수 함수에서 정한다. */
class RecapCardViewTest {
    private fun kph(v: Float) = v / 3.6f
    private fun summary(
        timing: SignalTiming = SignalTiming.NONE, min: Float? = kph(56f), max: Float? = kph(60f),
        durationMs: Long = 5_100, lateMs: Long? = null, leadM: Float? = null, leadMs: Long? = null,
        onMs: Long? = null, onM: Float? = null, minAngle: Float = -6f, maxAngle: Float = 7f,
    ) = SegmentSummary(1_000, 1_000 + durationMs, 0, minAngle, maxAngle, 1, 1, min, max, min == null,
        timing, true, lateSignalMs = lateMs, signalLeadMeters = leadM, signalLeadMs = leadMs,
        blinkerOnMs = onMs, blinkerOnMeters = onM)
    private fun card(kind: ReviewKind, s: SegmentSummary = summary(), id: String = "c-$kind", wall: Long = 0,
        candidate: ReviewKind = kind, turn: NavigationTurn? = null, decel: Float? = null,
        uncertainty: Set<Uncertainty> = emptySet(), nav: NavigationLabel = NavigationLabel.ESTIMATED) =
        ReviewCard(id, wall, kind, candidate, s, uncertainty = uncertainty, decelerationMps2 = decel,
            navigationTurn = turn, navigation = nav)
    private fun lead(m: Float, kmh: Float = 58f) = summary(SignalTiming.BEFORE, kph(kmh), kph(kmh), leadM = m, leadMs = (m / kph(kmh) * 1000).toLong())

    // ---- 종류와 제목 ------------------------------------------------------------------------

    @Test fun headlineSaysWhatHappenedInPlainKorean() {
        assertEquals("깜빡이를 켜고 45m 뒤에 차선을 바꿨어요", momentHeadline(card(ReviewKind.LANE_SIGNALLED, lead(46f))))
        assertEquals("깜빡이를 켜고 45m 뒤에 우회전했어요",
            momentHeadline(card(ReviewKind.TURN_SIGNALLED, lead(46f), turn = NavigationTurn.RIGHT)))
        assertEquals("깜빡이를 켜고 차선을 바꿨어요", momentHeadline(card(ReviewKind.LANE_SIGNALLED, summary(SignalTiming.BEFORE))))
        assertEquals("깜빡이 없이 차선을 바꿨어요", momentHeadline(card(ReviewKind.LANE_NO_SIGNAL)))
        assertEquals("깜빡이 없이 좌회전했어요", momentHeadline(card(ReviewKind.TURN_NO_SIGNAL, turn = NavigationTurn.LEFT)))
        assertEquals("깜빡이 없이 유턴했어요", momentHeadline(card(ReviewKind.TURN_NO_SIGNAL, turn = NavigationTurn.UTURN)))
        assertEquals("핸들이 먼저 돌고 깜빡이가 0.5초 늦었어요",
            momentHeadline(card(ReviewKind.LANE_LATE_SIGNAL, summary(SignalTiming.DURING, lateMs = 520))))
        assertEquals("핸들이 먼저 돌고 깜빡이가 늦었어요", momentHeadline(card(ReviewKind.LANE_LATE_SIGNAL, summary(SignalTiming.DURING))))
        assertEquals("깜빡이를 켠 채 23초를 달렸어요",
            momentHeadline(card(ReviewKind.BLINKER_LEFT_ON, summary(onMs = 23_400, onM = 310f))))
        assertEquals("60km/h에서 12km/h로 급하게 섰어요",
            momentHeadline(card(ReviewKind.HARD_BRAKE, summary(min = kph(12f), max = kph(60f)), decel = 7.9f)))
    }

    @Test fun headlineHedgesWhenTheRuleIsUnsureAndNeverClaimsMoreThanItKnows() {
        val unsure = card(ReviewKind.UNSURE, candidate = ReviewKind.LANE_NO_SIGNAL)
        assertEquals("깜빡이 없이 차선을 바꾼 것 같아요", momentHeadline(unsure))
        assertTrue(momentHeadline(card(ReviewKind.UNSURE, candidate = ReviewKind.HARD_BRAKE)).endsWith("같아요"))
        assertEquals("브레이크를 밟았어요. 급제동인지는 확인하지 못했어요", momentHeadline(card(ReviewKind.BRAKE_UNVERIFIED)))
        assertEquals("핸들이 움직인 순간이에요", momentHeadline(card(ReviewKind.STEERING)))
        assertEquals("급하게 브레이크를 밟았어요", momentHeadline(card(ReviewKind.HARD_BRAKE, summary(min = null, max = null))))
    }

    @Test fun aiLabelReplacesTheHeadlineOnlyThroughTheLabelArgument() {
        val unsure = card(ReviewKind.UNSURE, candidate = ReviewKind.LANE_NO_SIGNAL)
        assertEquals("교차로 회전 순간이에요", momentHeadline(unsure, aiLabel = "교차로 회전"))
        assertEquals("깜빡이 없이 차선을 바꾼 것 같아요", momentHeadline(unsure, aiLabel = null))
    }

    @Test fun groupsMatchTheSixColorsAndKeepMissedApartFromLeftOn() {
        assertEquals(MomentGroup.GOOD, momentGroup(card(ReviewKind.LANE_SIGNALLED)))
        assertEquals(MomentGroup.GOOD, momentGroup(card(ReviewKind.TURN_SIGNALLED)))
        assertEquals(MomentGroup.MISSED, momentGroup(card(ReviewKind.LANE_NO_SIGNAL)))
        assertEquals(MomentGroup.MISSED, momentGroup(card(ReviewKind.TURN_NO_SIGNAL)))
        assertEquals(MomentGroup.LATE, momentGroup(card(ReviewKind.LANE_LATE_SIGNAL)))
        assertEquals(MomentGroup.LATE, momentGroup(card(ReviewKind.TURN_LATE_SIGNAL)))
        assertEquals(MomentGroup.LEFT_ON, momentGroup(card(ReviewKind.BLINKER_LEFT_ON)))
        assertEquals(MomentGroup.HARD_BRAKE, momentGroup(card(ReviewKind.HARD_BRAKE)))
        listOf(ReviewKind.UNSURE, ReviewKind.STEERING, ReviewKind.BRAKE_UNVERIFIED, ReviewKind.TURN, ReviewKind.CURVE)
            .forEach { assertEquals(it.name, MomentGroup.OTHER, momentGroup(card(it))) }
        assertEquals(listOf("잘한 순간", "놓친 순간", "늦게 켬", "켜 둔 채", "급제동", "애매함"), MomentGroup.values().map(::momentGroupLabel))
    }

    @Test fun captionHasKindPositionAndClockTime() {
        val c = card(ReviewKind.LANE_SIGNALLED, wall = 1_761_379_920_000L) // 2025-10-25 08:12:00 UTC
        assertEquals("잘한 순간 · 1 / 7 · 08:12", momentCaption(c, 0, 7, ZoneOffset.UTC))
        assertEquals("애매함 · 3 / 5 · 08:12", momentCaption(card(ReviewKind.UNSURE, wall = 1_761_379_920_000L), 2, 5, ZoneOffset.UTC))
    }

    // ---- 근거 칩 ------------------------------------------------------------------------------

    @Test fun chipsReadAsHumanSentencesWithoutRawSteeringValues() {
        val chips = momentEvidenceChips(card(ReviewKind.LANE_NO_SIGNAL, summary(min = kph(58f), max = kph(58f))))
        assertEquals(listOf("속도 58 km/h", "핸들 5.1초", "깜빡이 없음"), chips)
        for (kind in ReviewKind.values()) {
            val text = momentEvidenceChips(card(kind, summary(SignalTiming.BEFORE, leadM = 40f, leadMs = 2_000, onMs = 20_000, onM = 200f), decel = 126f)).joinToString(" ")
            assertFalse(kind.name + " 원시", text.contains("원시"))
            assertFalse(kind.name + " 화살표", text.contains("→"))
            assertFalse(kind.name + " 7.0", text.contains("7.0")); assertFalse(kind.name + " -6.0", text.contains("-6.0"))
            assertFalse(kind.name + " 위반", text.contains("위반"))
        }
    }

    @Test fun earlySignalChipsUseRecommendedDistanceNotLegalWords() {
        val enough = momentEvidenceChips(card(ReviewKind.LANE_SIGNALLED, lead(46f))).joinToString(" | ")
        assertTrue(enough, enough.contains("권장 거리 이상"))
        val short = momentEvidenceChips(card(ReviewKind.LANE_SIGNALLED, lead(20f))).joinToString(" | ")
        assertTrue(short, short.contains("권장 30m보다 짧아요"))
        val fast = momentEvidenceChips(card(ReviewKind.LANE_SIGNALLED, lead(80f, kmh = 90f))).joinToString(" | ")
        assertTrue(fast, fast.contains("권장 100m보다 짧아요") && fast.contains("고속 기준"))
        assertFalse((enough + short + fast).contains("위반"))
    }

    @Test fun leftOnAndBrakeChipsCarryTheirOwnNumbers() {
        val left = momentEvidenceChips(card(ReviewKind.BLINKER_LEFT_ON, summary(onMs = 23_000, onM = 312f)))
        assertTrue(left.toString(), left.contains("약 310m") && left.any { it.startsWith("속도") })
        val brake = momentEvidenceChips(card(ReviewKind.HARD_BRAKE, summary(min = kph(12f), max = kph(60f)), decel = 7.9f))
        assertTrue(brake.toString(), brake.contains("감속 7.9m/s²") && brake.contains("브레이크 작동"))
        // 측정값 이상 구간은 숫자 대신 말로: 원시 값은 Debug 쪽 recapEvidence(debug=true)에만 남는다
        val glitch = momentEvidenceChips(card(ReviewKind.HARD_BRAKE, decel = 126f))
        assertTrue(glitch.toString(), glitch.contains("감속 큼") && glitch.none { it.contains("126") })
        assertTrue(recapEvidence(card(ReviewKind.HARD_BRAKE, decel = 126f), debug = true).contains("Debug 원시 감속 126.0m/s²"))
    }

    @Test fun chipsSkipNumbersThatABigNumberChipAlreadyShows() {
        val miss = card(ReviewKind.LANE_NO_SIGNAL, summary(min = kph(58f), max = kph(58f)))
        assertEquals(listOf("깜빡이 없음"), momentEvidenceChips(miss, numbersShown = true))
        assertEquals(listOf("브레이크 작동"), momentEvidenceChips(card(ReviewKind.HARD_BRAKE, decel = 7.9f), numbersShown = true))
        assertEquals(emptyList<String>(), momentEvidenceChips(card(ReviewKind.LANE_LATE_SIGNAL, summary(SignalTiming.DURING, lateMs = 500)), numbersShown = true))
        assertEquals(momentEvidenceChips(miss), momentEvidenceChips(miss, numbersShown = false))
    }

    @Test fun missingSpeedAndUncertaintyBecomeChips() {
        val chips = momentEvidenceChips(card(ReviewKind.UNSURE, summary(min = null, max = null),
            uncertainty = setOf(Uncertainty.SPEED_MISSING, Uncertainty.NAV_CONFLICT)))
        assertTrue(chips.toString(), chips.contains("속도 미수신") && chips.contains("속도 미수신 경로") && chips.contains("내비와 핸들 불일치"))
    }

    @Test fun trustBadgeKeepsTheSourceOfTheJudgement() {
        assertEquals("규칙 판정 · 추정", trustBadge(card(ReviewKind.LANE_NO_SIGNAL), null, true))
        assertEquals("내비 확인", trustBadge(card(ReviewKind.TURN_NO_SIGNAL, nav = NavigationLabel.CONFIRMED), null, true))
        val unsure = card(ReviewKind.UNSURE, candidate = ReviewKind.LANE_NO_SIGNAL)
        assertEquals("AI 추정", trustBadge(unsure, AiDecision("차선 변경", accepted = true, responded = true), true))
        assertEquals("AI 미사용 · 추정", trustBadge(unsure, null, false))
    }

    // ---- 그림 한 줄 ------------------------------------------------------------------------------

    @Test fun distanceBarUsesRecommendedThirtyMetersAndHundredOnTheHighway() {
        val bar = momentBar(card(ReviewKind.LANE_SIGNALLED, lead(46f)))!!
        assertEquals(BarKind.DISTANCE, bar.kind)
        assertEquals(45f, bar.value, 0f); assertEquals(30f, bar.reference, 0f)
        assertTrue(bar.reachedReference); assertTrue(bar.scaleMax >= 45f && bar.scaleMax >= 80f)
        assertEquals("깜빡이 켬", bar.startLabel); assertEquals("차선 진입 · 45m", bar.valueLabel); assertEquals("권장 30m", bar.referenceLabel)
        val turn = momentBar(card(ReviewKind.TURN_SIGNALLED, lead(46f), turn = NavigationTurn.LEFT))!!
        assertEquals("회전 시작 · 45m", turn.valueLabel)
        val fast = momentBar(card(ReviewKind.LANE_SIGNALLED, lead(80f, kmh = 90f)))!!
        assertEquals(100f, fast.reference, 0f); assertFalse(fast.reachedReference); assertEquals("권장 100m", fast.referenceLabel)
        assertTrue(fast.scaleMax > 100f)
        assertNull(momentBar(card(ReviewKind.LANE_SIGNALLED, summary(SignalTiming.BEFORE))))
    }

    @Test fun leftOnBarShowsSecondsAgainstFifteenSeconds() {
        val bar = momentBar(card(ReviewKind.BLINKER_LEFT_ON, summary(onMs = 23_400, onM = 310f)))!!
        assertEquals(BarKind.TIME, bar.kind)
        assertEquals(23f, bar.value, 0f); assertEquals(15f, bar.reference, 0f)
        assertEquals("깜빡이 켬", bar.startLabel); assertEquals("23초", bar.valueLabel); assertEquals("15초", bar.referenceLabel)
        assertTrue(bar.scaleMax >= 40f && bar.scaleMax >= bar.value)
        assertNull(momentBar(card(ReviewKind.BLINKER_LEFT_ON)))
    }

    @Test fun otherKindsDrawNoBar() {
        listOf(ReviewKind.LANE_NO_SIGNAL, ReviewKind.LANE_LATE_SIGNAL, ReviewKind.HARD_BRAKE, ReviewKind.UNSURE, ReviewKind.TURN_NO_SIGNAL)
            .forEach { assertNull(it.name, momentBar(card(it))) }
    }

    @Test fun bigNumbersFallBackToTwoOrThreeChipsWhenThereIsNoBar() {
        val miss = momentBigNumbers(card(ReviewKind.LANE_NO_SIGNAL, summary(min = kph(58f), max = kph(58f))))
        assertEquals(listOf("58" to "km/h", "5.1" to "초"), miss.map { it.value to it.unit })
        assertEquals(listOf("속도", "핸들 지속"), miss.map { it.label })
        val late = momentBigNumbers(card(ReviewKind.LANE_LATE_SIGNAL, summary(SignalTiming.DURING, lateMs = 1_200)))
        assertEquals("1.2" to "초", late.last().value to late.last().unit)
        assertEquals("깜빡이 늦음", late.last().label)
        val brake = momentBigNumbers(card(ReviewKind.HARD_BRAKE, summary(min = kph(12f), max = kph(60f)), decel = 7.9f))
        assertEquals(listOf("60", "12"), brake.take(2).map { it.value })
        assertTrue(brake.size in 2..3)
        assertTrue(momentBigNumbers(card(ReviewKind.BLINKER_LEFT_ON)).isEmpty() || momentBar(card(ReviewKind.BLINKER_LEFT_ON)) == null)
    }

    // ---- 오늘 주행 중 위치 -----------------------------------------------------------------------

    @Test fun cardsAreShownInDrivingOrder() {
        val a = card(ReviewKind.LANE_NO_SIGNAL, id = "a", wall = 300); val b = card(ReviewKind.LANE_NO_SIGNAL, id = "b", wall = 100)
        val c = card(ReviewKind.LANE_NO_SIGNAL, id = "c", wall = 200)
        assertEquals(listOf("b", "c", "a"), chronologicalCards(listOf(a, b, c)).map { it.id })
    }

    @Test fun timelineSpreadsMomentsFromFirstToLastAndMarksTheCurrentOne() {
        val cards = listOf(
            card(ReviewKind.LANE_SIGNALLED, id = "a", wall = 0), card(ReviewKind.LANE_NO_SIGNAL, id = "b", wall = 300_000),
            card(ReviewKind.BLINKER_LEFT_ON, id = "c", wall = 1_200_000))
        val dots = timelineDots(cards, selectedIndex = 1)
        assertEquals(listOf(0f, .25f, 1f), dots.map { it.fraction })
        assertEquals(listOf(false, true, false), dots.map { it.selected })
        assertEquals(listOf(MomentGroup.GOOD, MomentGroup.MISSED, MomentGroup.LEFT_ON), dots.map { it.group })
        assertEquals(listOf("a", "b", "c"), dots.map { it.cardId })
    }

    @Test fun oneMomentSitsInTheMiddleAndNoMomentGivesNoDots() {
        assertEquals(listOf(.5f), timelineDots(listOf(card(ReviewKind.LANE_NO_SIGNAL)), 0).map { it.fraction })
        assertTrue(timelineDots(emptyList(), 0).isEmpty())
    }

    @Test fun timelineTicksAreClockTimesAndNeverMoreThanFour() {
        val start = 1_761_379_800_000L // 08:10 UTC
        val ticks = timelineTicks(start, start + 30 * 60_000L, ZoneOffset.UTC)
        assertEquals(listOf("08:10", "08:20", "08:30", "08:40"), ticks.map { it.label })
        assertEquals(listOf(0f, 1f / 3, 2f / 3, 1f), ticks.map { it.fraction }.map { Math.round(it * 3f) / 3f })
        assertEquals(listOf("08:10"), timelineTicks(start, start, ZoneOffset.UTC).map { it.label })
        assertTrue(timelineTicks(start, start + 1_000, ZoneOffset.UTC).size <= 4)
    }

    // ---- 오늘 한눈에: 놓침과 켜 둔 채를 따로 센다 --------------------------------------------------

    @Test fun glanceCountsSeparateMissedFromLeftOnAndAddTurnsToTheirOwnRow() {
        val glance = recapGlance(ReviewCounts(missing = 1, late = 1, good = 3, hardBrake = 1, turnMissing = 2, turnLate = 1, turnGood = 1, blinkerOn = 4))
        assertEquals(RecapGlance(good = 4, missed = 3, late = 2, leftOn = 4, hardBrake = 1), glance)
        assertEquals(listOf("잘한 순간" to 4, "놓친 순간" to 3, "늦게 켬" to 2, "켜 둔 채" to 4, "급제동" to 1),
            glanceRows(glance).map { momentGroupLabel(it.group) to it.count })
    }

    @Test fun glanceHidesZeroRowsAndLeftOnNeverInflatesMissed() {
        val rows = glanceRows(recapGlance(ReviewCounts(good = 2, blinkerOn = 1)))
        assertEquals(listOf(MomentGroup.GOOD, MomentGroup.LEFT_ON), rows.map { it.group })
        assertEquals(0, recapGlance(ReviewCounts(blinkerOn = 5)).missed)
        assertTrue(glanceRows(recapGlance(ReviewCounts())).isEmpty())
    }

    // ---- 머리 -----------------------------------------------------------------------------------

    @Test fun headerShowsDateAndDrivingTimeInHumanUnits() {
        assertEquals("10월 25일 일요일 · 주행 34분", recapSubtitle("2026-10-25", 34 * 60_000L))
        assertEquals("10월 25일 일요일 · 주행 45초", recapSubtitle("2026-10-25", 45_000L))
        assertEquals("10월 25일 일요일 · 주행 1시간 5분", recapSubtitle("2026-10-25", 65 * 60_000L))
        assertEquals("10월 25일 일요일", recapSubtitle("2026-10-25", 0))
        assertEquals("10월 24일 토요일", recapSubtitle("2026-10-24", 0)) // 시안의 요일 표기는 예시일 뿐, 실제 달력을 따른다
        assertEquals("오늘", recapSubtitle("not-a-date", 0))
    }

    // ---- 금지어 ----------------------------------------------------------------------------------

    @Test fun noWordingEverCallsAMomentAViolation() {
        for (kind in ReviewKind.values()) for (candidate in ReviewKind.values()) {
            val c = card(kind, lead(10f), candidate = candidate, decel = 8f)
            val all = listOf(momentHeadline(c), momentCaption(c, 0, 3, ZoneOffset.UTC), trustBadge(c, null, true)) +
                momentEvidenceChips(c) + listOfNotNull(momentBar(c)).flatMap { listOf(it.startLabel, it.valueLabel, it.referenceLabel) } +
                momentBigNumbers(c).flatMap { listOf(it.label, it.value, it.unit) }
            assertFalse("$kind/$candidate", all.any { it.contains("위반") })
        }
    }
}
