package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class NavigationFusionTest {
    private fun lane(speed: Float = 60f, timing: SignalTiming = SignalTiming.NONE): ReviewCard = classifySegment(
        SegmentSummary(10_000, 12_500, 1_000_000, -14f, 11f, -1, 1, speed / 3.6f, speed / 3.6f,
            false, timing, true, if (timing == SignalTiming.DURING) 600 else null))!!
    private fun turn(timing: SignalTiming = SignalTiming.NONE, right: Boolean = true): ReviewCard = classifySegment(
        lane().summary.copy(endMs = 14_000, minAngle = 0f, maxAngle = 90f, reversals = 0,
            minSpeedMps = 12f / 3.6f, maxSpeedMps = 12f / 3.6f, timing = timing,
            leftSignal = DirectionalTiming(if (right) SignalTiming.NONE else timing, if (timing == SignalTiming.DURING) 600 else null),
            rightSignal = DirectionalTiming(if (right) timing else SignalTiming.NONE, if (timing == SignalTiming.DURING) 600 else null)))!!
    private fun nav(action: String? = "TURN_RIGHT", distance: Double? = 25.0, at: Long = 10_000, guiding: Boolean? = true,
        epoch: Long = 1) = NavigationSnapshot(at, guiding, action, distance, epoch = epoch)
    private fun straight() = listOf(nav("TURN_STRAIGHT", 0.0, 9_500), nav("TURN_STRAIGHT", 0.0, 12_000))

    @Test fun noNavigationInactiveUnknownAndEmptyTbtPreserveExistingRule() {
        val c = lane()
        for (input in listOf(emptyList(), listOf(nav(guiding = false)), listOf(nav(guiding = null)), listOf(nav(null, null))))
            assertEquals(c, fuseNavigation(c, input))
        assertEquals("추정", navigationLabel(c))
    }
    @Test fun guidedLargeSlowRightTurnHasSeparateMissingCard() {
        val c = fuseNavigation(turn(), listOf(nav()))
        assertEquals(ReviewKind.TURN_NO_SIGNAL, c.kind); assertEquals(NavigationTurn.RIGHT, c.navigationTurn)
        assertEquals(NavigationLabel.CONFIRMED, c.navigation); assertEquals("깜빡이 없이 우회전", recapTitle(c))
        assertTrue(visibleReviewCards(listOf(c)).contains(c)); assertTrue(aiCardTitle(c, null).contains("내비 확인"))
        assertTrue(recapSummary(listOf(c)).contains("우회전 깜빡이 놓침 1"))
    }
    @Test fun correctRightIndicatorProducesGoodTurn() {
        val c = fuseNavigation(turn(SignalTiming.BEFORE), listOf(nav()))
        assertEquals(ReviewKind.TURN_SIGNALLED, c.kind); assertTrue(recapTitle(c).contains("좋아요"))
    }
    @Test fun wrongSideIndicatorNeverCountsAsGoodTurn() {
        val raw = turn(SignalTiming.BEFORE, right = false)
        assertEquals(SignalTiming.BEFORE, raw.summary.timing)
        val c = fuseNavigation(raw, listOf(nav()))
        assertEquals(ReviewKind.TURN_NO_SIGNAL, c.kind); assertEquals(SignalTiming.NONE, c.summary.timing)
    }
    @Test fun uturnUsesLeftSignalEvidenceRegardlessOfAngleSign() {
        val c = fuseNavigation(turn(right = false), listOf(nav("TURN_UTURN")))
        assertEquals(NavigationTurn.UTURN, c.navigationTurn); assertEquals(ReviewKind.TURN_NO_SIGNAL, c.kind)
        assertTrue(recapTitle(c).contains("유턴"))
    }
    @Test fun leftSharpAndRightSharpUseOnlyExactSdkNames() {
        assertEquals(NavigationTurn.LEFT, navigationTurn("TURN_SHARP_L"))
        assertEquals(NavigationTurn.RIGHT, navigationTurn("TURN_SHARP_R"))
        assertNull(navigationTurn("UTURN")); assertNull(navigationTurn("TURN_KEEP_R")); assertNull(navigationTurn("999"))
    }
    @Test fun lateTurnUsesDirectionalOffset() {
        val c = fuseNavigation(turn(SignalTiming.DURING), listOf(nav()))
        assertEquals(ReviewKind.TURN_LATE_SIGNAL, c.kind); assertEquals(600L, c.summary.lateSignalMs)
    }
    @Test fun missingDirectionalObservationNeverClaimsNoSignal() {
        val c = turn().let { it.copy(summary = it.summary.copy(rightSignal = null)) }
        val fused = fuseNavigation(c, listOf(nav()))
        assertEquals(ReviewKind.UNSURE, fused.kind); assertTrue(Uncertainty.SIGNAL_GAP in fused.uncertainty)
        assertNull(classifyFeatures(fused))
    }
    @Test fun distanceBoundaryIsInclusiveAndNegativeNonfiniteUnverifiedAreNotMetres() {
        assertEquals(NavigationLabel.CONFIRMED, fuseNavigation(turn(), listOf(nav(distance = 50.0))).navigation)
        for (d in listOf(50.001, -1.0, Double.NaN, Double.POSITIVE_INFINITY, null))
            assertEquals(turn(), fuseNavigation(turn(), listOf(nav(distance = d))))
    }
    @Test fun stalePreWindowAndPostWindowAreIgnored() {
        for (at in listOf(6_999L, 17_001L)) assertEquals(turn(), fuseNavigation(turn(), listOf(nav(at = at))))
        assertEquals(NavigationLabel.CONFIRMED, fuseNavigation(turn(), listOf(nav(at = 7_000))).navigation)
        assertEquals(NavigationLabel.CONFIRMED, fuseNavigation(turn(), listOf(nav(at = 17_000))).navigation)
    }
    @Test fun recordedPassageInsideSegmentCanConfirmButTypeChangeDoesNotInventPassage() {
        assertEquals(NavigationLabel.CONFIRMED, fuseNavigation(turn(), listOf(nav(distance = null).copy(passageAtMs = 12_000))).navigation)
        assertEquals(turn(), fuseNavigation(turn(), listOf(nav(distance = null))))
        assertEquals(turn(), fuseNavigation(turn(), listOf(nav(distance = null).copy(passageAtMs = 9_999))))
    }
    @Test fun smallFastSteeringConflictsAndJevCannotOverride() {
        val c = fuseNavigation(lane(), listOf(nav()))
        assertEquals(ReviewKind.UNSURE, c.kind); assertTrue(Uncertainty.NAV_CONFLICT in c.uncertainty)
        assertEquals(NavigationLabel.CONFLICT, c.navigation); assertNull(classifyFeatures(c))
        assertTrue(recapEvidence(c).contains("내비와 핸들 불일치"))
    }
    @Test fun clearGuidancePromotesBoundarySpeedLaneButNotBelowMargin() {
        val c = lane(25f)
        assertEquals(ReviewKind.UNSURE, c.kind)
        assertEquals(ReviewKind.LANE_NO_SIGNAL, fuseNavigation(c, straight()).kind)
        assertEquals(NavigationLabel.ASSISTED, fuseNavigation(c, straight()).navigation)
        assertEquals(lane(24.9f), fuseNavigation(lane(24.9f), straight()))
    }
    @Test fun unknownTbtAndMissingDistanceOnFarTurnNeverMeanNoNearbyTurn() {
        for (input in listOf(listOf(nav("TURN_UNKNOWN", 200.0)),
            listOf(nav("TURN_LEFT", null, 9_500), nav("TURN_LEFT", null, 12_000)), emptyList()))
            assertEquals(lane(25f), fuseNavigation(lane(25f), input))
    }
    @Test fun staleOrGappedGuidanceCannotPromoteLane() {
        assertEquals(lane(25f), fuseNavigation(lane(25f), listOf(nav("TURN_STRAIGHT", 0.0, 6_999))))
        val long = lane(25f).let { it.copy(summary = it.summary.copy(endMs = 16_000)) }
        assertEquals(long, fuseNavigation(long, listOf(nav("TURN_STRAIGHT", 0.0, 9_000), nav("TURN_STRAIGHT", 0.0, 16_000))))
    }
    @Test fun exitAndMergeAreLaneCardsWithExplicitZoneLabel() {
        for (name in listOf("TURN_EXIT_IC_R", "TURN_ENTER_IC_L", "TURN_DEVIDE_MERGE")) {
            val c = fuseNavigation(lane(), listOf(nav(name)))
            assertEquals(ReviewKind.LANE_NO_SIGNAL, c.kind); assertEquals(NavigationLabel.MERGE_EXIT, c.navigation)
            assertTrue(aiCardTitle(c, null).contains("합류/진출 구간"))
        }
    }
    @Test fun farTurnCanAssistOnlyWhenCoveredAndItsDistanceIsVerified() {
        val events = listOf(nav("TURN_LEFT", 51.0, 9_500), nav("TURN_LEFT", 51.0, 12_000))
        assertEquals(NavigationLabel.ASSISTED, fuseNavigation(lane(), events).navigation)
    }
    @Test fun multipleInstructionsDoNotHideNearTurnBehindStraight() {
        val n = nav("TURN_STRAIGHT", 0.0).copy(following = listOf(NavigationCue("TURN_LEFT", 30.0)))
        assertEquals(NavigationLabel.CONFLICT, fuseNavigation(lane(), listOf(n)).navigation)
    }
    @Test fun opposingNearActionsAreAmbiguous() {
        val n = nav().copy(following = listOf(NavigationCue("TURN_LEFT", 30.0)))
        assertEquals(NavigationLabel.CONFLICT, fuseNavigation(turn(), listOf(n)).navigation)
    }
    @Test fun cancellationRerouteAndDifferentRouteEpochBlockMixing() {
        for (n in listOf(nav(at = 13_000, guiding = false), nav(at = 13_000, guiding = null), nav(at = 13_000, epoch = 2)))
            assertEquals(turn(), fuseNavigation(turn(), listOf(nav(), n)))
    }
    @Test fun inputGapIncompleteMissingSpeedAndSignalGapCannotBePromotedToCertain() {
        for (flag in listOf(Uncertainty.INPUT_GAP, Uncertainty.INCOMPLETE, Uncertainty.SIGNAL_GAP, Uncertainty.SPEED_MISSING)) {
            val c = lane(25f).copy(uncertainty = setOf(Uncertainty.SHAPE_AMBIGUOUS, flag))
            assertEquals(c, fuseNavigation(c, straight()))
        }
    }
    @Test fun speedAngleAndDurationTurnBoundariesAreInclusive() {
        val c = turn().let { it.copy(summary = it.summary.copy(endMs = 12_500, maxAngle = 55f, minSpeedMps = 25f / 3.6f, maxSpeedMps = 25f / 3.6f)) }
        assertEquals(NavigationLabel.CONFIRMED, fuseNavigation(c, listOf(nav())).navigation)
        assertEquals(NavigationLabel.CONFLICT, fuseNavigation(c.copy(summary = c.summary.copy(maxAngle = 54.99f)), listOf(nav())).navigation)
    }
    @Test fun delayedTbtIsAcceptedBeforeDeadlineAndNotAfterDecision() {
        var step = navigationFusionStep(NavigationFusionState(), 14_700, listOf(turn()))
        assertTrue(step.cards.isEmpty())
        step = navigationFusionStep(step.state, 16_000, snapshot = nav(at = 16_000))
        assertTrue(step.cards.isEmpty())
        step = navigationFusionStep(step.state, 17_700)
        assertEquals(NavigationLabel.CONFIRMED, step.cards.single().navigation)
        assertTrue(navigationFusionStep(step.state, 20_000, snapshot = nav(at = 20_000)).cards.isEmpty())
    }
    @Test fun noTbtAndFinishStillEmitOriginalRulesAndDiscardMemory() {
        val pending = navigationFusionStep(NavigationFusionState(), 13_200, listOf(lane()))
        val done = navigationFusionStep(pending.state, 14_000, finish = true)
        assertEquals(listOf(lane()), done.cards); assertEquals(NavigationFusionState(), done.state)
    }
    @Test fun bufferIsBoundedAndBrakeIsNeverDelayedOrReclassified() {
        val observations = (0L..300L).map { nav(at = it * 1_000) }
        val step = navigationFusionStep(NavigationFusionState(observations), 300_000)
        assertTrue(step.state.observations.size <= 67)
        val brake = lane().copy(kind = ReviewKind.HARD_BRAKE, candidate = ReviewKind.HARD_BRAKE, summary = lane().summary.copy(firstSign = 0))
        assertEquals(listOf(brake), navigationFusionStep(NavigationFusionState(), 1, listOf(brake)).cards)
    }
    @Test fun navVerdictCodecAndWeeklyCountsRoundTripWithoutRawNavigation() {
        val c = fuseNavigation(turn(), listOf(nav()))
        val d = DayRecap("2026-10-23", cards = listOf(c))
        assertEquals(d, RecapCodec.decode(RecapCodec.encode(d)))
        val text = RecapCodec.encode(d).toString(Charsets.ISO_8859_1)
        for (word in listOf("TURN_RIGHT", "distanceMeters", "latitude", "longitude", "epoch", "description")) assertFalse(text.contains(word))
        val counts = reviewCounts(d.cards); assertEquals(1, counts.turnMissing); assertEquals(0, counts.missing)
        val cd = CountDay(d.date, counts); assertEquals(cd, CountCodec.decode(CountCodec.encode(cd)))
        assertEquals("TURN_NO_SIGNAL", summaryFeatures(d, emptyList())!!.today.single().kind)
    }
    @Test fun versionOneRecapStillDecodesWithoutInventedNavigation() {
        val bytes = ByteArrayOutputStream().also { b -> DataOutputStream(b).use { out ->
            out.writeInt(0x52454350); out.writeInt(1); out.writeUTF("2026-10-23"); out.writeLong(0); out.writeLong(0); out.writeInt(0)
        } }.toByteArray()
        assertEquals(DayRecap("2026-10-23"), RecapCodec.decode(bytes))
    }
    private fun recordedTurn(signal: Indicator?, late: Boolean = false, jitter: Boolean = false): ReviewCard {
        var state = RecapEngineState()
        val cards = mutableListOf<ReviewCard>()
        fun put(input: DriveInput) { val step = recapStep(state, input); state = step.state; cards += step.cards }
        put(DriveInput.Gear(0, 1_000_000, DriveGear.DRIVE))
        put(DriveInput.Signal(0, 1_000_000, Indicator.OFF))
        put(DriveInput.Steering(0, 1_000_000, 0f))
        for (t in 0L..10_000L step 100) {
            put(DriveInput.Speed(t, 1_000_000 + t, 12f / 3.6f))
            if (t == 3_000L && signal != null && !late && !jitter) put(DriveInput.Signal(t, 1_000_000 + t, signal))
            if (t == 5_000L) {
                put(DriveInput.Steering(t, 1_000_000 + t, -80f))
                if (jitter) put(DriveInput.Signal(t - 2, 1_000_000 + t - 2, signal))
            }
            if (t == 5_600L && late) put(DriveInput.Signal(t, 1_000_000 + t, signal))
            if (t == 8_500L) put(DriveInput.Steering(t, 1_000_000 + t, 0f))
            put(DriveInput.Tick(t, 1_000_000 + t))
        }
        return cards.single()
    }
    @Test fun realReducerDirectionalFeaturesReachFusionWithoutAssumingSteeringSign() {
        val snapshot = nav(at = 5_000)
        val good = fuseNavigation(recordedTurn(Indicator.RIGHT), listOf(snapshot))
        assertEquals(ReviewKind.TURN_SIGNALLED, good.kind)
        assertEquals(ReviewKind.TURN_NO_SIGNAL, fuseNavigation(recordedTurn(Indicator.LEFT), listOf(snapshot)).kind)
        assertEquals(ReviewKind.TURN_NO_SIGNAL, fuseNavigation(recordedTurn(null), listOf(snapshot)).kind)
        val late = fuseNavigation(recordedTurn(Indicator.RIGHT, late = true), listOf(snapshot))
        assertEquals(ReviewKind.TURN_LATE_SIGNAL, late.kind); assertEquals(600L, late.summary.lateSignalMs)
    }
    @Test fun reorderedBeforeSignalRetainsDirectionalEvidence() {
        val good = fuseNavigation(recordedTurn(Indicator.RIGHT, jitter = true), listOf(nav(at = 5_000)))
        assertEquals(ReviewKind.TURN_SIGNALLED, good.kind)
    }
    @Test fun changingNavigationDecisionAndTurnCountsInvalidatesSummaryCache() {
        val c = fuseNavigation(turn(), listOf(nav()))
        val d = DayRecap("2026-10-23", cards = listOf(c))
        val features = summaryFeatures(d, listOf(RecentCounts("D-1", ReviewCounts(turnMissing = 1))))!!
        val key = summaryCacheKey(d, features)
        assertNotEquals(key, summaryCacheKey(d.copy(cards = listOf(c.copy(navigation = NavigationLabel.ESTIMATED))), features))
        assertNotEquals(key, summaryCacheKey(d, features.copy(recentDays = listOf(RecentCounts("D-1", ReviewCounts(turnMissing = 2))))))
    }
    @Test fun populatedVersionOneFilePreservesOldCardFeedbackAndEvidence() {
        val card = lane().copy(feedback = Feedback.YES)
        val bytes = ByteArrayOutputStream().also { b -> DataOutputStream(b).use { out ->
            out.writeInt(0x52454350); out.writeInt(1); out.writeUTF("2026-10-23"); out.writeLong(5000); out.writeLong(0); out.writeInt(1)
            out.writeUTF(card.id); out.writeLong(card.wallTimeMs); out.writeUTF(card.kind.name); out.writeUTF(card.candidate.name)
            out.writeUTF(card.direction.name); out.writeUTF(card.feedback.name); out.writeInt(0); out.writeBoolean(false)
            val s = card.summary
            out.writeLong(s.startMs); out.writeLong(s.endMs); out.writeLong(s.wallStartMs)
            out.writeFloat(s.minAngle); out.writeFloat(s.maxAngle); out.writeInt(s.firstSign); out.writeInt(s.reversals)
            out.writeBoolean(true); out.writeFloat(s.minSpeedMps!!); out.writeBoolean(true); out.writeFloat(s.maxSpeedMps!!)
            out.writeBoolean(false); out.writeUTF(s.timing.name); out.writeBoolean(s.signalCoverage)
            out.writeBoolean(false); out.writeBoolean(false); out.writeBoolean(false); out.writeBoolean(false)
        } }.toByteArray()
        val expected = DayRecap("2026-10-23", 5000, cards = listOf(card))
        assertEquals(expected, RecapCodec.decode(bytes))
        assertEquals(expected, RecapCodec.decode(RecapCodec.encode(RecapCodec.decode(bytes))))
    }
}
