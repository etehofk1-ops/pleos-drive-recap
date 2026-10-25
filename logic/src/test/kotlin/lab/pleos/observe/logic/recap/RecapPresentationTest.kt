package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test

class RecapPresentationTest {
    private fun uncertain() = classifySegment(SegmentSummary(0, 2450, 1_792_728_345_678,
        -18f, 12f, -1, 1, kphToMps(25f), kphToMps(35f), false, SignalTiming.NONE, true))!!
    private fun clear(kind: ReviewKind) = uncertain().copy(kind = kind, candidate = kind)
    private fun answer(p: Double, label: String) = JevAnswer(label,
        JEV_LABELS.associateWith { if (it == label) p else (1 - p) / 3 }, "jev-preview", 100)

    @Test fun clearRuleCardsAlwaysSayRuleEvenWhenAiIsOffOrServerUnavailable() {
        for (kind in listOf(ReviewKind.LANE_SIGNALLED, ReviewKind.LANE_NO_SIGNAL, ReviewKind.LANE_LATE_SIGNAL, ReviewKind.HARD_BRAKE)) {
            for (enabled in listOf(true, false)) {
                val text = aiCardTitle(clear(kind), AiDecision(null), enabled)
                assertTrue(text.endsWith("규칙 판정")); assertFalse(text.contains("AI 미사용"))
            }
        }
    }
    @Test fun clearRuleCardCannotBeOverlaidByAnUnrelatedAiDecision() {
        assertTrue(aiCardTitle(clear(ReviewKind.LANE_NO_SIGNAL), AiDecision("교차로 회전", true)).endsWith("규칙 판정"))
    }
    @Test fun pendingIsNotMislabelledAsAnAiFailure() {
        val text = aiCardTitle(uncertain(), null)
        assertTrue(text.contains("애매함")); assertTrue(text.endsWith("AI 확인 중")); assertFalse(text.contains("AI 미사용"))
    }
    @Test fun offAndServerFailureUseAiUnusedOnlyOnEligibleUncertainCards() {
        val card = uncertain()
        assertTrue(aiCardTitle(card, null, false).endsWith("AI 미사용"))
        val failed = assistedDecision(card, true, JevReviewAdapter { null })
        assertFalse(failed.responded); assertTrue(aiCardTitle(card, failed).endsWith("AI 미사용"))
    }
    @Test fun validAbstentionAndLowProbabilityShowAbstentionInsteadOfAiUnused() {
        for (a in listOf(answer(0.99, "판단 보류"), answer(0.59, "차선 변경"))) {
            val decision = adoptJev(uncertain(), a)
            assertFalse(decision.accepted); assertTrue(decision.responded)
            assertTrue(aiCardTitle(uncertain(), decision).endsWith("AI 판단 보류"))
            assertFalse(aiCardTitle(uncertain(), decision).contains("AI 미사용"))
        }
    }
    @Test fun acceptedDecisionSaysAiEstimateAndDisabledToggleWinsOverStaleResponse() {
        val d = adoptJev(uncertain(), answer(0.8, "차선 변경"))
        assertEquals("AI 추정 · 차선 변경", aiCardTitle(uncertain(), d))
        assertTrue(aiCardTitle(uncertain(), d, false).endsWith("AI 미사용"))
    }
    @Test fun ineligibleUncertaintyDoesNotPretendThatAnAiCallFailed() {
        val c = uncertain().copy(candidate = ReviewKind.BRAKE_UNVERIFIED)
        for (enabled in listOf(true, false)) assertTrue(aiCardTitle(c, null, enabled).endsWith("규칙 판정"))
    }
    @Test fun decelerationAboveFifteenIsGuardedWithoutChangingRawEvidenceOrRuleKind() {
        val c = clear(ReviewKind.HARD_BRAKE).copy(decelerationMps2 = 126f)
        val release = recapEvidence(c)
        assertTrue(release.contains("감속 큼(측정값 이상)")); assertFalse(release.contains("126"))
        assertTrue(recapEvidence(c, debug = true).contains("Debug 원시 감속 126.0m/s²"))
        assertEquals(126f, c.decelerationMps2!!, 0f); assertEquals(ReviewKind.HARD_BRAKE, c.kind)
    }
    @Test fun decelerationBoundaryAndRealisticValueStayNumeric() {
        val c = clear(ReviewKind.HARD_BRAKE)
        assertTrue(recapEvidence(c.copy(decelerationMps2 = 15f)).contains("감속 15.0m/s²"))
        assertTrue(recapEvidence(c.copy(decelerationMps2 = 15.01f)).contains("감속 큼(측정값 이상)"))
        assertTrue(recapEvidence(c.copy(decelerationMps2 = 9.3f)).contains("감속 9.3m/s²"))
    }
    @Test fun summaryCountsActualLaneChangesAndExcludesTurnsAndBrakesFromLaneTotal() {
        val cards = listOf(ReviewKind.LANE_SIGNALLED, ReviewKind.LANE_NO_SIGNAL, ReviewKind.LANE_LATE_SIGNAL,
            ReviewKind.TURN, ReviewKind.HARD_BRAKE).map(::clear)
        assertEquals("오늘 차선 변경 3번 · 깜빡이 놓침 1 · 늦게 켬 1 · 급제동 1", recapSummary(cards))
    }
    @Test fun uncertainHypothesisIsSeparateAndWholeDayCountsMayExceedOnePage() {
        // W16c hides LANE_CHANGE candidates (unknown indicator coverage); a speed-less steering hypothesis stays reviewable.
        val cards = List(101) { clear(ReviewKind.LANE_NO_SIGNAL) } + uncertain().let { it.copy(summary = it.summary.copy(speedMissing = true)) }
        assertEquals("오늘 차선 변경 101번 · 깜빡이 놓침 101 · 늦게 켬 0 · 급제동 0 · 애매함 1", recapSummary(cards))
        assertFalse(recapSummary(listOf(uncertain())).contains("애매함")) // hidden high-speed STEERING
        assertEquals("오늘 차선 변경 0번 · 깜빡이 놓침 0 · 늦게 켬 0 · 급제동 0", recapSummary(emptyList()))
    }
}
