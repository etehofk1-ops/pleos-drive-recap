package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class DelayedLaneWindowTest {
    @Test fun constantSteeringOffsetDoesNotLatchAnOpenSegmentAndMemoryIsBounded() {
        var state=DelayedLaneState()
        val cards=mutableListOf<ReviewCard>()
        for(t in 0L..100_000 step 100) {
            val step=delayedLaneStep(state,DriveInput.Tick(t,t),9f,20f,DriveGear.DRIVE)
            state=step.state; cards+=step.cards
            assertTrue(state.points.size<=160); assertTrue(state.signals.size<=256)
        }
        val finish=delayedLaneStep(state,DriveInput.Finish(100_050,100_050),9f,20f,DriveGear.DRIVE)
        cards+=finish.cards
        assertTrue(cards.isEmpty()); assertTrue(finish.state.points.isEmpty())
    }
    @Test fun unknownMotionRestartsTheWarmupInsteadOfBridgingAnInputGap() {
        var state=DelayedLaneState()
        for(t in 0L..10_000 step 100) state=delayedLaneStep(state,DriveInput.Tick(t,t),0f,20f,DriveGear.DRIVE).state
        state=delayedLaneStep(state,DriveInput.Steering(10_100,10_100,null),null,20f,DriveGear.DRIVE).state
        assertTrue(state.points.isEmpty()); assertNull(state.firstMs)
        state=delayedLaneStep(state,DriveInput.Steering(10_200,10_200,0f),0f,20f,DriveGear.DRIVE).state
        assertEquals(10_200L,state.firstMs)
    }
    @Test fun signalTransitionHistoryHasAHardCapacity() {
        var state=DelayedLaneState()
        for(t in 0L..5_000 step 10) {
            state=delayedLaneStep(state,DriveInput.Signal(t,t,if(t%20==0L) Indicator.OFF else Indicator.LEFT),0f,20f,DriveGear.DRIVE).state
            assertTrue(state.signals.size<=256)
        }
        assertEquals(256,state.signals.size)
    }
    private fun flush(lat:Double,peak:Double,timing:SignalTiming=SignalTiming.NONE): ReviewCard {
        val m=LateralSummary(0.01,peak*PI/180,0.1,0.0,lat,80.0,windowDurationMs=4_000)
        val s=SegmentSummary(4_500,8_500,4_500,-8f,8f,1,1,20f,20f,false,SignalTiming.UNKNOWN,true,lateral=m)
        val proof=LaneSignalProof(timing==SignalTiming.BEFORE,false,if(timing==SignalTiming.DURING) 5_000 else null,null,true)
        val group=LaneWindowGroup(4_500,8_500,4_500,s,1.0,proof)
        return delayedLaneStep(DelayedLaneState(firstMs=500,nextMs=10_100,feedAt=10_000,pending=group),
            DriveInput.Finish(10_000,10_000),0f,20f,DriveGear.DRIVE).cards.single()
    }
    @Test fun weakMissingIndicatorEvidenceStaysHiddenAndCannotBePromotedByNavigation() {
        val weak=flush(2.2,1.3)
        assertEquals(ReviewKind.UNSURE,weak.kind); assertEquals(ReviewKind.STEERING,weak.candidate)
        assertTrue(visibleReviewCards(listOf(weak)).isEmpty())
        val nav=(3_000L..10_000 step 1_000).map{NavigationSnapshot(it,true,"TURN_STRAIGHT",200.0)}
        assertEquals(weak,fuseNavigation(weak,nav))
    }
    @Test fun strongEvidencePreservesBeforeLateAndNoneMeanings() {
        assertEquals(ReviewKind.LANE_NO_SIGNAL,flush(4.0,2.0).kind)
        assertEquals(ReviewKind.LANE_SIGNALLED,flush(2.2,2.0,SignalTiming.BEFORE).kind)
        val late=flush(2.2,2.0,SignalTiming.DURING)
        assertEquals(ReviewKind.LANE_LATE_SIGNAL,late.kind); assertEquals(500L,late.summary.lateSignalMs)
    }
    @Test fun windowEvidenceSurvivesVersionSixSummaryRoundTrip() {
        val day=DayRecap("2026-10-25",cards=listOf(flush(4.0,2.0)))
        assertEquals(day,RecapCodec.decode(RecapCodec.encode(day)))
    }
    @Test fun nonmaximumSuppressionDoesNotMergeATransitiveChain() {
        val c=flush(4.0,2.0)
        fun window(start:Long,end:Long,score:Double)=LaneWindowGroup(start,end,start,
            c.summary.copy(startMs=start,endMs=end),score,LaneSignalProof(false,false,null,null,true))
        val selected=selectLaneWindows(listOf(window(0,4_000,3.0),window(3_000,7_000,1.0),window(6_000,10_000,2.0)))
        assertEquals(listOf(0L,6_000L),selected.map{it.startMs})
    }
    @Test fun selectedEventsHaveSeparateSignalLookbacks() {
        val history=listOf(LaneSignal(0,Indicator.OFF),LaneSignal(2_000,Indicator.LEFT),
            LaneSignal(5_000,Indicator.OFF),LaneSignal(12_500,Indicator.RIGHT))
        assertTrue(signalProof(history,4_000,6_000).leftBefore)
        val none=signalProof(history,9_000,11_000)
        assertTrue(none.covered); assertFalse(none.leftBefore); assertNull(none.leftDuring)
        val late=signalProof(history,12_000,15_000)
        assertFalse(late.rightBefore); assertEquals(12_500L,late.rightDuring)
    }
    @Test fun blinkerOffExactlyOnTheThreeSecondGraceEdge() {
        val start = 20_000L
        fun proof(offAt: Long) = signalProof(listOf(LaneSignal(0, Indicator.OFF), LaneSignal(5_000, Indicator.LEFT),
            LaneSignal(offAt, Indicator.OFF)), start, start + 3_000)
        val tooLate = proof(start - RecapThresholds.SIGNAL_LOOKBACK_MS - 1) // Off received 3001ms before the window
        assertFalse(tooLate.leftBefore); assertTrue(tooLate.covered); assertNull(tooLate.leftDuring)
        assertTrue(proof(start - RecapThresholds.SIGNAL_LOOKBACK_MS).leftBefore) // exactly 3000ms: still "signalled"
        assertTrue(proof(start - 2_999).leftBefore)
    }
    @Test fun incompleteLaneSignalEvidenceIsNotAVisibleWarning() {
        val good=flush(4.0,2.0)
        assertTrue(visibleReviewCards(listOf(good.copy(kind=ReviewKind.UNSURE,candidate=ReviewKind.LANE_CHANGE,
            uncertainty=setOf(Uncertainty.SIGNAL_GAP)))).isEmpty())
        assertTrue(visibleReviewCards(listOf(good.copy(uncertainty=setOf(Uncertainty.SIGNAL_GAP)))).isEmpty())
    }

    // ---- W16c end-point baseline geometry (mirrors spikes/w16c/lanewin.py) ----
    private fun drive(events: List<Pair<Long, Int>>, endMs: Long, kph: Float = 60f, offsetDeg: Float = 0f,
        curve: (Long) -> Float = { 0f }, finish: Boolean = true): List<ReviewCard> {
        var state = DelayedLaneState(); val cards = mutableListOf<ReviewCard>()
        for (t in 0L..endMs step 100) {
            val a = offsetDeg + curve(t) + events.sumOf { (start, dir) -> LaneWave.angleDeg(t - start, kph, dir).toDouble() }.toFloat()
            val step = delayedLaneStep(state, DriveInput.Tick(t, t), a, kph / 3.6f, DriveGear.DRIVE)
            state = step.state; cards += step.cards
        }
        if (finish) cards += delayedLaneStep(state, DriveInput.Finish(endMs + 50, endMs + 50), 0f, kph / 3.6f, DriveGear.DRIVE).cards
        return cards
    }
    @Test fun aSteeringZeroOffsetDoesNotMatterBecauseTheBaselineIsTheWindowEndPoints() {
        val cards = drive(listOf(12_000L to 1), 30_000, offsetDeg = 4f)
        assertEquals(1, cards.size); assertTrue(cards.single().summary.startMs in 12_000L..12_700L)
    }
    @Test fun aWindowIsConfirmedAboutNineSecondsAfterItEnds() {
        var state = DelayedLaneState(); val cards = mutableListOf<ReviewCard>(); var firstAt = -1L
        for (t in 0L..30_000 step 100) {
            val a = LaneWave.angleDeg(t - 12_000, 60f)
            val step = delayedLaneStep(state, DriveInput.Tick(t, t), a, 60f / 3.6f, DriveGear.DRIVE)
            state = step.state; if (step.cards.isNotEmpty() && firstAt < 0) firstAt = t; cards += step.cards
        }
        assertEquals(1, cards.size)
        val end = cards.single().summary.endMs
        assertTrue("confirmed at $firstAt for end $end", firstAt - end in 500L..10_000L)
    }
    @Test fun twoLaneChangesThreeSecondsApartAreNotMergedByNonmaximumSuppression() {
        val cards = drive(listOf(12_000L to 1, 20_000L to -1), 36_000)
        assertEquals(2, cards.size)
        assertTrue(cards[0].summary.endMs <= cards[1].summary.startMs)
    }
    @Test fun aHeldCurveEntryAndExitIsRejectedByTheEndPointCheck() {
        val ramp = { t: Long -> if (t < 12_000 || t > 22_000) 0f else if (t < 14_500) (t - 12_000) / 2_500f * 6f else if (t > 19_500) (22_000 - t) / 2_500f * 6f else 6f }
        assertTrue(drive(emptyList(), 40_000, curve = ramp).isEmpty())
    }
    @Test fun straightDrivingWithSmallHandNoiseIsNotALaneChange() {
        val noise = { t: Long -> (kotlin.math.sin(t / 700.0) * 0.4 + kotlin.math.sin(t / 311.0) * 0.2).toFloat() }
        assertTrue(drive(emptyList(), 60_000, curve = noise).isEmpty())
    }
    @Test fun anEventTooCloseToTheStartOfRecordingIsExcludedByTheThreeSecondWarmup() {
        assertTrue(drive(listOf(500L to 1), 20_000).isEmpty())
    }
    @Test fun finishUsesTheEndPointSamplesThatExistInsteadOfZeroPadding() {
        // Ends 0.2s after the wave: only 2 of the 5 end-point samples exist.
        val cards = drive(listOf(10_000L to 1), 10_000 + LaneWave.PERIOD_MS + 200)
        assertEquals(1, cards.size)
    }
    @Test fun slowDrivingBelowThirtyKphIsNotAWindowLane() {
        assertTrue(drive(listOf(12_000L to 1), 30_000, kph = 27f).isEmpty())
    }
    @Test fun candidateAndMemoryStayBoundedOnALongNoisyDrive() {
        var state = DelayedLaneState()
        for (t in 0L..300_000 step 100) {
            val a = (kotlin.math.sin(t / 1_700.0) * 7).toFloat() // repeated weaving: candidates may pile up
            state = delayedLaneStep(state, DriveInput.Tick(t, t), a, 20f, DriveGear.DRIVE).state
            assertTrue(state.points.size <= LaneWindowPolicy.CAPACITY)
            assertTrue(state.candidates.size <= LaneWindowPolicy.CANDIDATE_CAPACITY)
        }
    }
    @Test fun steeringAngleIsAStepHeldWhenNoSteeringCallbackArrives() {
        var state = DelayedLaneState()
        // Angle changes only reach the grid through Tick inputs here (no steering callback), so the 10Hz grid holds them as steps.
        val updates = mapOf(0L to 0f, 350L to 10f, 700L to -10f)
        var angle = 0f
        for (t in 0L..1_000 step 50) {
            updates[t]?.let { angle = it }
            state = delayedLaneStep(state, DriveInput.Tick(t, t), angle, 20f, DriveGear.DRIVE).state
        }
        val byTime = state.points.associate { it.atMs to it.angle }
        assertEquals(0f, byTime.getValue(300L), 0f)
        assertEquals(10f, byTime.getValue(400L), 0f); assertEquals(10f, byTime.getValue(600L), 0f)
        assertEquals(-10f, byTime.getValue(800L), 0f)
        assertEquals(20.0 * 10 * PI / 180 / (LaneWindowPolicy.RATIO * LaneWindowPolicy.WHEELBASE_M),
            state.points.first { it.atMs == 500L }.yaw, 1e-12)
    }

    // ---- 1.9.4: 1Hz steering with 3s skip -> linear ramp over the last <=1s ----
    /** lanewin.py throttle(): 1s ticks, 0.1 degree quantisation, skip when the same value was delivered <=3s ago. */
    private fun throttled(angleAt: (Long) -> Float, endMs: Long): List<Pair<Long, Float>> {
        val out = mutableListOf<Pair<Long, Float>>(); var last: Pair<Long, Float>? = null
        for (t in 0L..endMs step 1_000) {
            val v = Math.round(angleAt(t) / 0.1f) * 0.1f
            if (last == null || v != last.second || t - last.first > 3_000) { out += t to v; last = t to v }
        }
        return out
    }
    private fun feed(callbacks: List<Pair<Long, Float>>, endMs: Long, asSteering: Boolean, kph: Float = 60f,
        stepEvery: Long = 100): Pair<DelayedLaneState, List<ReviewCard>> {
        var state = DelayedLaneState(); val cards = mutableListOf<ReviewCard>(); var angle = 0f
        val byTime = callbacks.toMap()
        for (t in 0L..endMs step stepEvery) {
            val cb = byTime[t]; if (cb != null) angle = cb
            val input: DriveInput = if (cb != null && asSteering) DriveInput.Steering(t, t, cb) else DriveInput.Tick(t, t)
            val step = delayedLaneStep(state, input, angle, kph / 3.6f, DriveGear.DRIVE)
            state = step.state; cards += step.cards
        }
        cards += delayedLaneStep(state, DriveInput.Finish(endMs + 50, endMs + 50), angle, kph / 3.6f, DriveGear.DRIVE).cards
        return state to cards
    }
    @Test fun aLaneChangeSampledAtOneHertzIsFoundWithTheRampButNotWithHeldSteps() {
        // A quick 3.0s / 2.8m lane change at 100 km/h: held 1Hz steps lose it, the ramp keeps its shape (found by a
        // sweep of speed, width, period and phase; most gentler synthetic waves are found either way).
        val wave = { t: Long -> LaneWave.angleDeg(t - 12_000, 100f, 1, 3_000, 2.8) }
        val callbacks = throttled(wave, 30_000)
        val held = feed(callbacks, 30_000, asSteering = false, kph = 100f).second
        val ramp = feed(callbacks, 30_000, asSteering = true, kph = 100f).second
        assertTrue("held steps found ${held.size}", held.isEmpty())
        assertEquals(1, ramp.size)
        assertTrue(ramp.single().summary.startMs in 11_500L..13_500L)
    }
    @Test fun silentSecondsKeepTheValueAndOnlyTheLastSecondBeforeTheCallbackBecomesARamp() {
        // Callback 0deg at 5s, nothing for 4s (value unchanged), then 8deg at 9s: 8.0..9.0s ramps, 5.0..8.0s stays flat.
        val (state, _) = feed(listOf(0L to 0f, 5_000L to 0f, 9_000L to 8f), 9_500, asSteering = true)
        val byTime = state.points.associate { it.atMs to it.angle }
        for (t in 5_000L..8_000L step 100) assertEquals("t=$t", 0f, byTime.getValue(t), 0f)
        assertEquals(0.8f, byTime.getValue(8_100L), 1e-4f)
        assertEquals(4f, byTime.getValue(8_500L), 1e-4f)
        assertEquals(8f, byTime.getValue(9_000L), 1e-4f)
        assertEquals(8f, byTime.getValue(9_400L), 0f)
        assertEquals(9_000L, state.lastAngleMs)
    }
    @Test fun aCallbackBurstOnlyRampsOverItsOwnGap() {
        // Previous callback 300ms ago: the ramp starts there, not one second back.
        val (state, _) = feed(listOf(0L to 0f, 4_000L to 2f, 4_300L to 5f), 4_600, asSteering = true)
        val byTime = state.points.associate { it.atMs to it.angle }
        assertEquals(2f, byTime.getValue(4_000L), 0f)
        assertEquals(3f, byTime.getValue(4_100L), 1e-4f); assertEquals(4f, byTime.getValue(4_200L), 1e-4f)
        assertEquals(5f, byTime.getValue(4_300L), 1e-4f)
    }
    @Test fun denseSteeringInputGivesTheSameCardsWithOrWithoutTheRamp() {
        val wave = { t: Long -> LaneWave.angleDeg(t - 12_000, 60f) }
        val callbacks = (0L..30_000 step 100).map { it to wave(it) }
        val held = feed(callbacks, 30_000, asSteering = false)
        val ramp = feed(callbacks, 30_000, asSteering = true)
        assertEquals(1, held.second.size)
        // Cards are confirmed one second later with the ramp, so wall-clock fields may differ; the event itself may not.
        fun key(c: ReviewCard) = listOf(c.kind, c.summary.startMs, c.summary.endMs, c.summary.lateral, c.summary.timing)
        assertEquals(held.second.map(::key), ramp.second.map(::key))
        assertEquals(held.first.points, ramp.first.points)
    }
    @Test fun theRampedTailIsNeverScannedBeforeItCanStillChange() {
        // The newest ten grid points may still be rewritten, so the scan stays at least 10 starts behind the 1.9.3 position.
        val (state, _) = feed(listOf(0L to 0f), 20_000, asSteering = true)
        val last = state.points.last().seq
        assertTrue(state.nextScan <= last - (LaneWindowPolicy.MAX_SAMPLES + LaneWindowPolicy.EDGE - 1 + LaneWindowPolicy.RAMP_POINTS) + LaneWindowPolicy.SCAN_STEP)
    }
}
