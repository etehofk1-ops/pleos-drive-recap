package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class AiReviewTest {
    private fun card(): ReviewCard = classifySegment(SegmentSummary(0, 2450, 1_792_728_345_678,
        -18f, 12f, -1, 1, kphToMps(25f), kphToMps(35f), false, SignalTiming.NONE, true))!!
    private fun lowSpeedCard(): ReviewCard = card().let { it.copy(summary = it.summary.copy(minSpeedMps = kphToMps(24f))) }
    private fun answer(p: Double = 0.7, label: String = "차선 변경") = JevAnswer(label,
        JEV_LABELS.associateWith { if (it == label) p else (1 - p) / 3 }, "jev-preview", 100)

    @Test fun onlyUncertainSteeringCallsAdapterAndOriginalEvidenceStaysIntact() {
        val c = card(); assertEquals(ReviewKind.UNSURE, c.kind)
        var calls = 0
        val adapter = JevReviewAdapter { f -> calls++; assertEquals(25f, f.speedKphMin!!, 0.01f); answer() }
        assertTrue(assistedDecision(c, true, adapter).accepted)
        assertEquals(1, calls); assertEquals(c, card()); assertEquals(SignalTiming.NONE, c.summary.timing)
        assertEquals("AI 추정 · 차선 변경", aiCardTitle(c, assistedDecision(c, true, adapter)))
    }
    @Test fun clearCardsNeverInvokeJev() {
        val c = card().copy(kind = ReviewKind.LANE_NO_SIGNAL, candidate = ReviewKind.LANE_NO_SIGNAL)
        assertFalse(assistedDecision(c, true, JevReviewAdapter { error("must not call") }).accepted)
    }
    @Test fun offToggleDoesNotInvokeAndShowsRuleFallback() {
        val c = card(); val d = assistedDecision(c, false, JevReviewAdapter { error("must not call") })
        assertFalse(d.accepted); assertTrue(aiCardTitle(c, d).contains("AI 미사용"))
    }
    @Test fun exactlyPointSixIsAdoptedAndJustBelowRemainsUncertain() {
        assertTrue(adoptJev(card(), answer(0.6)).accepted)
        assertFalse(adoptJev(card(), answer(0.5999)).accepted)
    }
    @Test fun abstentionIsNeverAdoptedEvenWithHighProbability() { assertFalse(adoptJev(card(), answer(0.99, "판단 보류")).accepted) }
    @Test fun malformedAndMismatchedDistributionsAreRejected() {
        val a = answer()
        for (bad in listOf(a.copy(probabilities = emptyMap()), a.copy(probabilities = a.probabilities + ("extra" to 0.0)),
            a.copy(probabilities = a.probabilities + ("차선 변경" to Double.NaN)),
            a.copy(probabilities = a.probabilities + ("차선 변경" to 1.5)), a.copy(label = "굽은 길"),
            a.copy(model = ""), a.copy(latencyMs = -1))) assertFalse(adoptJev(card(), bad).accepted)
    }
    @Test fun networkExceptionNullOrNoServerQuietlyFallsBack() {
        val c = card()
        assertFalse(assistedDecision(c, true, JevReviewAdapter { throw java.io.IOException() }).accepted)
        assertFalse(assistedDecision(c, true, JevReviewAdapter { null }).accepted)
        assertTrue(aiCardTitle(c, null).contains("애매함"))
    }
    @Test fun brakeUncertaintyIsNeverSentToSteeringClassifier() {
        val c = card().copy(candidate = ReviewKind.BRAKE_UNVERIFIED)
        assertNull(classifyFeatures(c)); assertFalse(adoptJev(c, answer()).accepted)
    }
    @Test fun unknownOrUncoveredOffIndicatorIsNotInventedAsNone() {
        val c = card()
        assertNull(classifyFeatures(c.copy(summary = c.summary.copy(timing = SignalTiming.UNKNOWN))))
        assertNull(classifyFeatures(c.copy(summary = c.summary.copy(signalCoverage = false))))
    }
    @Test fun jevTurnDoesNotChangeRuleIndicatorOrNumericEvidence() {
        val c = card(); val before = recapEvidence(c)
        assertTrue(adoptJev(c, answer(0.8, "교차로 회전")).accepted)
        assertEquals(before, recapEvidence(c)); assertTrue(before.contains("깜빡이 없음"))
        assertEquals(ReviewKind.UNSURE, c.kind)
    }
    @Test fun featuresAreScalarSummariesAndConvertMpsToKph() {
        val f = classifyFeatures(card())!!
        assertEquals(2450L, f.durationMs); assertEquals(18f, f.peakAbsRaw, 0f); assertEquals(1, f.signReversals)
        assertEquals(25f, f.speedKphMin!!, 0.01f); assertEquals(35f, f.speedKphMax!!, 0.01f)
        assertEquals("none", f.indicator.state); assertNull(f.indicator.offsetMs); assertEquals("unknown", f.naviHint)
    }
    @Test fun summaryUsesHoursRelativeDaysAndRuleKindsNotAiLabels() {
        // W16c: LANE_CHANGE candidates are hidden; a 24km/h steering hypothesis (legacy path) is still reviewable.
        val c = lowSpeedCard(); val d = DayRecap("2026-10-23", cards = listOf(c))
        val input = summaryFeatures(d, listOf(RecentCounts("D-1", ReviewCounts(1, 2, 3, 4))), ZoneId.of("UTC"))!!
        assertEquals("UNSURE", input.today.single().kind); assertTrue(input.today.single().hourBucket in 0..23)
        assertEquals("mid", input.today.single().speedBand); assertEquals("D-1", input.recentDays.single().date)
    }
    @Test fun missingSpeedIsExplicitUnknownInsteadOfLowOrZero() {
        val c = card().copy(summary = card().summary.copy(minSpeedMps = null, maxSpeedMps = null, speedMissing = true))
        assertNull(classifyFeatures(c)!!.speedKphMin)
        assertEquals("unknown", summaryFeatures(DayRecap("2026-10-23", cards = listOf(c)), emptyList())!!.today.single().speedBand)
    }
    @Test fun overLimitDayFallsBackInsteadOfSilentlyDroppingOldCards() {
        assertNull(summaryFeatures(DayRecap("2026-10-23", cards = List(501) { lowSpeedCard().copy(id = "$it") }), emptyList()))
    }
    @Test fun templateIsAlwaysAvailableAndDoesNotIssueDrivingInstructions() {
        for (hasCards in listOf(true, false)) {
            assertEquals(templateRecap(hasCards), generatedOrTemplate(null, hasCards))
            assertTrue(templateRecap(hasCards).contains("정차")); assertFalse(templateRecap(hasCards).contains("안전합니다"))
        }
    }
    @Test fun invalidGeneratedTextFallsBackAndValidTextCanBeUsed() {
        val valid = GeneratedRecap("오늘 기록을 차분히 살펴봤어요. 정차한 지금 남은 장면을 확인해 보세요.", emptyList(), "deepseek-v4.1-flash")
        assertTrue(validGeneratedRecap(valid)); assertEquals(valid.text, generatedOrTemplate(valid, true))
        for (bad in listOf(valid.copy(text = ""), valid.copy(text = "sk-secret"), valid.copy(model = ""), valid.copy(patterns = List(5) { "패턴" }))) {
            assertFalse(validGeneratedRecap(bad)); assertEquals(templateRecap(true), generatedOrTemplate(bad, true))
        }
    }
    @Test fun indicatorOffsetsAreNullBeforeOrNoneAndPositiveFromSegmentStartWhenLate() {
        for (timing in listOf(SignalTiming.BEFORE, SignalTiming.NONE)) {
            val s = card().summary.copy(timing = timing, lateSignalMs = -1500)
            assertNull(classifyFeatures(card().copy(summary = s))!!.indicator.offsetMs)
        }
        val late = card().summary.copy(timing = SignalTiming.DURING, lateSignalMs = 662)
        assertEquals(IndicatorFeature("late", 662), classifyFeatures(card().copy(summary = late))!!.indicator)
    }
}
