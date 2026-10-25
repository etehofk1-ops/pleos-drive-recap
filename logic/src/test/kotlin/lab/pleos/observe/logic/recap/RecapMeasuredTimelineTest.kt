package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test

/** Synthetic timestamps reproduce the sequences Claude reported; not a live-AVD assertion. */
class RecapMeasuredTimelineTest {
    private class Feed {
        var state = RecapEngineState()
        var cards = emptyList<ReviewCard>()
        private var nextTick = 100L
        private var speed = kphToMps(60f)
        fun put(input: DriveInput) {
            val step = recapStep(state, input); state = step.state; cards = mergeReviewCards(cards, step.cards)
        }
        fun until(t: Long) {
            while (nextTick < t) {
                put(DriveInput.Speed(nextTick, 1_000_000 + nextTick, speed))
                put(DriveInput.Tick(nextTick, 1_000_000 + nextTick))
                nextTick += 100
            }
            if (nextTick == t) nextTick += 100
        }
        fun ready(initialOffGet: Boolean = true) {
            put(DriveInput.Gear(0, 1_000_000, DriveGear.DRIVE))
            if (initialOffGet) put(DriveInput.Signal(0, 1_000_000, Indicator.OFF))
            put(DriveInput.Brake(0, 1_000_000, false))
            put(DriveInput.Steering(0, 1_000_000, 0f))
            put(DriveInput.Speed(0, 1_000_000, speed))
        }
        fun signal(t: Long, v: Indicator?) { until(t); put(DriveInput.Signal(t, 1_000_000 + t, v)) }
        fun angle(t: Long, v: Float?) { until(t); put(DriveInput.Steering(t, 1_000_000 + t, v)) }
        fun brake(t: Long, v: Boolean?) { until(t); put(DriveInput.Brake(t, 1_000_000 + t, v)) }
        fun speed(t: Long, kph: Float) { until(t); speed = kphToMps(kph); put(DriveInput.Speed(t, 1_000_000 + t, speed)) }
        fun tick(t: Long) { until(t); put(DriveInput.Tick(t, 1_000_000 + t)) }
        /** One physical 60km/h lane change (LaneWave, 4.5s). Windows are confirmed ~9s later or at Finish. */
        fun wave(start: Long, direction: Int = 1, onStep: (Long) -> Unit = {}) {
            // A speed packet accompanies every steering packet (angles on the tick grid would starve until()).
            for (e in 0L..LaneWave.PERIOD_MS step 100) {
                onStep(e); speed(start + e, 60f); put(DriveInput.Steering(start + e, 1_000_000 + start + e, LaneWave.angleDeg(e, 60f, direction)))
            }
            speed(start + LaneWave.PERIOD_MS + 100, 60f); put(DriveInput.Steering(start + LaneWave.PERIOD_MS + 100, 1_000_000 + start + LaneWave.PERIOD_MS + 100, 0f))
        }
        fun end(t: Long) { tick(t); put(DriveInput.Finish(t + 50, 1_000_000 + t + 50)) }
    }

    @Test fun measuredThreeManeuversStaySeparateAndHaveNoUncertainty() {
        val f = Feed(); f.ready()
        f.signal(5_000, Indicator.LEFT)
        f.wave(7_000) { e -> if (e == 3_500L) f.signal(10_500, Indicator.OFF) } // signals must stay chronological
        f.wave(15_000, -1)
        f.wave(22_000) { e -> if (e == 900L) f.signal(22_900, Indicator.LEFT) }
        f.signal(27_500, Indicator.OFF)
        f.end(31_000)
        assertEquals(listOf(ReviewKind.LANE_SIGNALLED, ReviewKind.LANE_NO_SIGNAL, ReviewKind.LANE_LATE_SIGNAL), f.cards.map { it.kind })
        // The reported start is the onset inside each window (first sample above 20% of the peak residual).
        listOf(7_000L, 15_000L, 22_000L).zip(f.cards.map { it.summary.startMs }).forEach { (want, got) ->
            assertTrue("start $got vs $want", got in want..want + 700)
        }
        assertEquals(SignalTiming.BEFORE, f.cards[0].summary.timing)
        assertNull(f.cards[0].summary.lateSignalMs)
        assertEquals(22_900L - f.cards[2].summary.startMs, f.cards[2].summary.lateSignalMs)
        assertTrue(f.cards.all { it.uncertainty.isEmpty() })
    }
    @Test fun offGetWithoutAnyNotifyIsEnoughToProveAbsence() {
        val tracker = InitialReadTracker(); assertTrue(tracker.complete(tracker.begin(), true))
        val f = Feed(); f.ready(initialOffGet = tracker.known)
        f.wave(5_000, -1); f.end(11_000)
        assertEquals(ReviewKind.LANE_NO_SIGNAL, f.cards.single().kind)
    }
    @Test fun missingInitialGetAndNoNotifyStillCannotInventOff() {
        val f = Feed(); f.ready(false)
        f.wave(5_000, -1); f.end(11_000)
        assertEquals(ReviewKind.UNSURE, f.cards.single().kind)
    }
    @Test fun lateGetCannotOverwriteNewerNotifyAndRetryCanSeedCurrentState() {
        val tracker = InitialReadTracker(); val old = tracker.begin()
        tracker.notified(true)
        assertFalse(tracker.complete(old, true))
        assertTrue(tracker.complete(tracker.begin(), true))
        assertTrue(tracker.known)
    }
    @Test fun getFailureDoesNotEraseKnownNotifyButRestartHasNoBorrowedState() {
        val tracker = InitialReadTracker(); tracker.notified(true)
        assertFalse(tracker.complete(tracker.begin(), false)); assertTrue(tracker.known)
        assertFalse(InitialReadTracker().known)
        tracker.notified(false); assertFalse(tracker.known)
    }
    @Test fun twoMillisecondCrossSignalJitterKeepsOriginalLateSignalTime() {
        val f = Feed(); f.ready()
        f.wave(5_000) { e -> if (e == 1_000L) { f.angle(6_000, LaneWave.angleDeg(1_000, 60f)); f.tick(6_002)
            f.put(DriveInput.Signal(6_000, 1_006_000, Indicator.LEFT)) } }
        f.end(12_000)
        assertEquals(ReviewKind.LANE_LATE_SIGNAL, f.cards.single().kind)
        assertEquals(6_000L - f.cards.single().summary.startMs, f.cards.single().summary.lateSignalMs)
    }
    @Test fun threeSecondHeldAngleDoesNotBecomeAnInputGap() {
        val f = Feed(); f.ready(); f.angle(5_000, 12f); f.angle(8_000, 0f)
        f.angle(8_350, -9f); f.angle(9_200, 0f); f.end(12_000)
        // 3s one-sided hold + brief countersteer is not a lane geometry (heading never returns).
        assertTrue(f.cards.none { it.kind in setOf(ReviewKind.LANE_NO_SIGNAL, ReviewKind.LANE_SIGNALLED, ReviewKind.LANE_LATE_SIGNAL) })
        assertTrue(f.cards.none { it.summary.inputGap })
    }
    @Test fun preSteeringSignalDeliveredTwoMillisecondsAfterAngleIsStillBefore() {
        val f = Feed(); f.ready()
        f.wave(5_000) { e -> if (e == 0L) { f.angle(5_000, 0f); f.put(DriveInput.Signal(4_998, 1_004_998, Indicator.LEFT)) } }
        f.end(11_000)
        assertEquals(ReviewKind.LANE_SIGNALLED, f.cards.single().kind)
        assertEquals(SignalTiming.BEFORE, f.cards.single().summary.timing)
        assertNull(f.cards.single().summary.lateSignalMs)
    }
    @Test fun heldCurveUsesLastValueAndExplicitFailureStaysOutOfLaneCards() {
        val f = Feed(); f.ready(); f.angle(5_000, 18f); f.angle(14_000, 0f); f.end(16_000)
        assertTrue(f.cards.isEmpty()) // a held curve has no return and no opposite lobe
        assertTrue(visibleReviewCards(f.cards).isEmpty())
        val broken = Feed(); broken.ready(); broken.angle(5_000, 12f); broken.angle(5_800, null); broken.end(9_000)
        assertTrue(broken.cards.isEmpty())
    }
    @Test fun signalAfterNeutralEndDoesNotLeakIntoThePreviousManeuver() {
        val f = Feed(); f.ready(); f.wave(5_000)
        f.signal(9_800, Indicator.LEFT); f.end(12_000)
        assertEquals(ReviewKind.LANE_NO_SIGNAL, f.cards.single().kind)
    }
    @Test fun fortyOneMillisecondBrakePulseCatchesLaterDeceleration() {
        val f = Feed(); f.ready(); f.brake(5_000, true); f.brake(5_041, false)
        f.speed(5_400, 40f); f.speed(5_800, 0f)
        assertEquals(ReviewKind.HARD_BRAKE, f.cards.single().kind)
        assertTrue(f.cards.single().decelerationMps2!! >= RecapThresholds.BRAKE_MIN_DECEL)
    }
    @Test fun brakeCanAssociateARecentDecelerationBeforeThePress() {
        val f = Feed(); f.ready(); f.speed(5_000, 40f)
        assertTrue(f.cards.isEmpty()); f.brake(5_500, true)
        assertEquals(ReviewKind.HARD_BRAKE, f.cards.single().kind)
    }
    @Test fun decelerationOutsideThePostBrakeWindowIsNotAssociated() {
        val f = Feed(); f.ready(); f.brake(5_000, true); f.brake(5_041, false); f.speed(6_542, 0f)
        assertTrue(f.cards.isEmpty())
    }
    @Test fun oldDecelerationOutsideThePreBrakeWindowIsNotAssociated() {
        val f = Feed(); f.ready(); f.speed(5_000, 40f); f.brake(6_501, true)
        assertTrue(f.cards.isEmpty())
    }
    @Test fun strongerDecelerationUpdatesOneCardAndKeepsFeedback() {
        val f = Feed(); f.ready(); f.brake(5_000, true); f.brake(5_041, false)
        f.speed(5_400, 50f); f.tick(5_650); val first = f.cards.single()
        f.cards = listOf(first.copy(feedback = Feedback.YES))
        f.speed(5_800, 0f); f.tick(6_200)
        assertEquals(first.id, f.cards.single().id)
        assertTrue(f.cards.single().decelerationMps2!! > first.decelerationMps2!!)
        assertEquals(Feedback.YES, f.cards.single().feedback)
    }
    @Test fun repeatedShortPedalPulsesRemainOneBrakingEpisode() {
        val f = Feed(); f.ready(); f.brake(5_000, true); f.brake(5_041, false); f.speed(5_200, 50f)
        val firstId = f.cards.single().id
        f.brake(5_300, true); f.brake(5_341, false); f.speed(5_500, 0f)
        assertEquals(firstId, f.cards.single().id)
    }
    @Test fun noPedalOrSlowDecelerationDoesNotCreateHardBrake() {
        val noPedal = Feed(); noPedal.ready(); noPedal.speed(5_000, 0f); assertTrue(noPedal.cards.isEmpty())
        val slow = Feed(); slow.ready(); slow.brake(5_000, true); slow.speed(5_500, 59f); assertTrue(slow.cards.isEmpty())
    }
    @Test fun twentyMillisecondSpeedUpdatesStillMeasureDecelerationOverFourHundredMilliseconds() {
        var state = RecapEngineState()
        var cards = emptyList<ReviewCard>()
        fun put(input: DriveInput) { val s = recapStep(state, input); state = s.state; cards = mergeReviewCards(cards, s.cards) }
        put(DriveInput.Gear(0, 0, DriveGear.DRIVE)); put(DriveInput.Speed(0, 0, 20f))
        put(DriveInput.Brake(0, 0, true)); put(DriveInput.Brake(41, 41, false))
        // Feed future packets after the short pulse; all adjacent packet intervals are <400ms.
        for (t in 60L..600L step 20) put(DriveInput.Speed(t, t, 20f - t / 100f))
        assertEquals(ReviewKind.HARD_BRAKE, cards.single().kind)
        assertEquals(10f, cards.single().decelerationMps2!!, 0.001f)
    }
}
