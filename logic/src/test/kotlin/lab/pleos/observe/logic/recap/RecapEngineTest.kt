package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test

class RecapEngineTest {
    private class Feed(val speed: Float? = 15f, val policy: RecapPolicy = RecapPolicy()) {
        var state = RecapEngineState()
        val cards = mutableListOf<ReviewCard>()
        var driveMs = 0L
        var unknownMs = 0L
        var heldSpeed = speed
        var nextTick = 0L
        fun put(input: DriveInput): RecapStep = recapStep(state, input, policy).also {
            state = it.state
            val merged = mergeReviewCards(cards, it.cards); cards.clear(); cards += merged
            driveMs += it.drivingMs; unknownMs += it.speedUnknownDrivingMs
        }
        fun advance(t: Long) {
            while (nextTick < t) {
                put(DriveInput.Speed(nextTick, 1_000_000 + nextTick, heldSpeed))
                put(DriveInput.Tick(nextTick, 1_000_000 + nextTick)); nextTick += 50
            }
            if (nextTick == t) nextTick += 50
        }
        fun speed(t: Long, v: Float? = heldSpeed): RecapStep { advance(t); heldSpeed = v; return put(DriveInput.Speed(t, 1_000_000 + t, v)) }
        fun angle(t: Long, v: Float?) { speed(t); put(DriveInput.Steering(t, 1_000_000 + t, v)) }
        fun signal(t: Long, v: Indicator?): RecapStep { advance(t); return put(DriveInput.Signal(t, 1_000_000 + t, v)) }
        fun gear(t: Long, v: DriveGear): RecapStep { advance(t); return put(DriveInput.Gear(t, 1_000_000 + t, v)) }
        fun brake(t: Long, v: Boolean?): RecapStep { advance(t); return put(DriveInput.Brake(t, 1_000_000 + t, v)) }
        fun tick(t: Long) { speed(t); put(DriveInput.Tick(t, 1_000_000 + t)) }
        /** W16c: lane windows are confirmed ~9s later, or at Finish. */
        fun end(t: Long) { tick(t); put(DriveInput.Finish(t + 50, 1_000_000 + t + 50)) }
        fun ready() { gear(0, DriveGear.PARK); signal(0, Indicator.OFF); angle(0, 0f); speed(4_000); gear(4_000, DriveGear.DRIVE) }
        /** One physical lane change (LaneWave) and a Finish, because windows are confirmed ~9s late. */
        fun wave(start: Long, direction: Int = 1) {
            val kph = (heldSpeed?.takeIf { it > 1f } ?: 15f) * 3.6f
            for (e in 0L..LaneWave.PERIOD_MS step 100) angle(start + e, LaneWave.angleDeg(e, kph, direction))
            angle(start + LaneWave.PERIOD_MS + 100, 0f)
        }
        /** Default start leaves the 3s warm-up after the first grid sample (DRIVE at 4s). */
        fun lane(start: Long = 8_500) { wave(start); end(start + 6_000) }
    }
    @Test fun oneLaneChangeProducesOneCardOnlyAfterTheDelayedWindowIsConfirmed() {
        val f = Feed(); f.ready(); f.wave(8_500)
        assertTrue(f.cards.isEmpty()) // the end-point mean and later starts are still pending
        f.end(14_500)
        assertEquals(1, f.cards.size)
        assertEquals(ReviewKind.LANE_NO_SIGNAL, f.cards.single().kind)
        assertTrue(f.cards.single().summary.durationMs in 2_500L..LaneWindowPolicy.MAX_SAMPLES * 100L)
        assertNull(f.state.active)
    }
    @Test fun signalHeldLongBeforeStartCountsAsUsed() {
        val f = Feed(); f.ready(); f.signal(4_100, Indicator.LEFT); f.lane(10_000)
        assertEquals(ReviewKind.LANE_SIGNALLED, f.cards.single().kind)
    }
    @Test fun signalOffWithinLookbackStillCountsAsUsed() {
        val f = Feed(); f.ready(); f.signal(6_000, Indicator.LEFT); f.signal(6_800, Indicator.OFF); f.lane()
        assertEquals(ReviewKind.LANE_SIGNALLED, f.cards.single().kind)
    }
    @Test fun olderSignalDoesNotExcuseLaterUnsignalledMove() {
        val f = Feed(); f.ready(); f.signal(4_100, Indicator.LEFT); f.signal(4_200, Indicator.OFF); f.lane(8_000)
        assertEquals(ReviewKind.LANE_NO_SIGNAL, f.cards.single().kind)
    }
    @Test fun signalAfterSteeringIsLate() {
        val f = Feed(); f.ready()
        for (e in 0L..LaneWave.PERIOD_MS step 100) {
            if (e == 1_000L) f.signal(8_500 + e, Indicator.LEFT)
            f.angle(8_500 + e, LaneWave.angleDeg(e, 54f))
        }
        f.angle(13_100, 0f); f.end(14_500)
        assertEquals(ReviewKind.LANE_LATE_SIGNAL, f.cards.single().kind)
        assertEquals(9_500L - f.cards.single().summary.startMs, f.cards.single().summary.lateSignalMs)
    }
    @Test fun unknownConventionAcceptsEitherSignalWithoutDirectionClaim() {
        val f = Feed(); f.ready(); f.signal(4_500, Indicator.RIGHT); f.lane()
        assertEquals(ReviewKind.LANE_SIGNALLED, f.cards.single().kind)
        assertEquals(ManeuverDirection.UNKNOWN, f.cards.single().direction)
    }
    @Test fun knownConventionRejectsWrongSideSignal() {
        val f = Feed(policy = RecapPolicy(convention = SteeringConvention.POSITIVE_RIGHT))
        f.ready(); f.signal(4_500, Indicator.LEFT); f.lane()
        assertEquals(ReviewKind.LANE_NO_SIGNAL, f.cards.single().kind)
    }
    @Test fun noIndicatorReceptionDoesNotInventOff() {
        val f = Feed(); f.ready(); f.signal(4_100, null); f.lane()
        assertEquals(ReviewKind.UNSURE, f.cards.single().kind)
        assertEquals(ReviewKind.LANE_CHANGE, f.cards.single().candidate)
    }
    @Test fun tooShortObservationWindowCannotProveMissingSignal() {
        val f = Feed(); f.angle(0, 0f); f.gear(4_000, DriveGear.DRIVE); f.signal(7_000, Indicator.OFF); f.lane()
        assertEquals(ReviewKind.UNSURE, f.cards.single().kind)
    }
    @Test fun receivedZeroDoesNotCreateSteeringEventsButEffectiveDriveTimeContinues() {
        val f = Feed(0f); f.ready(); f.lane()
        assertTrue(f.cards.isEmpty())
        // 5b: selected D/UX can establish recording DRIVE even while waiting at zero speed.
        assertTrue(f.driveMs > 0)
    }
    @Test fun missingSpeedFallbackIsExplicitlyUnsure() {
        val f = Feed(null); f.ready(); f.lane()
        assertEquals(ReviewKind.UNSURE, f.cards.single().kind)
        assertEquals(ReviewKind.LANE_NO_SIGNAL, f.cards.single().candidate)
        assertTrue(f.unknownMs > 0)
    }
    @Test fun strictPolicyDisablesMissingSpeedFallback() {
        val f = Feed(null, RecapPolicy(allowMissingSpeed = false)); f.ready(); f.lane()
        assertTrue(f.cards.isEmpty())
    }
    @Test fun demoModeDoesNotTurnReceivedZeroIntoRealMotionEvidence() {
        val f = Feed(0f, RecapPolicy(demoMissingSpeed = true)); f.ready(); f.lane()
        val c = f.cards.single()
        assertEquals(ReviewKind.UNSURE, c.kind)
        assertTrue(c.uncertainty.contains(Uncertainty.DEMO_SPEED_MISSING))
        assertNull(c.summary.maxSpeedMps)
    }
    @Test fun parkTransitionDuringAnUnfinishedManeuverInventsNoCard() {
        // W16c: a half-finished window has no end-point baseline; it is dropped, never guessed.
        val f = Feed(); f.ready(); f.angle(5_000, 12f); f.angle(5_700, -9f)
        val parked = f.gear(6_000, DriveGear.PARK)
        assertTrue(parked.parked)
        assertTrue(f.cards.isEmpty())
    }
    @Test fun neutralAndParkWithinTwoPointFiveSecondsCannotBecomeALaneCard() {
        val f = Feed(); f.ready(); f.angle(5_000, 12f); f.angle(5_700, -9f); f.angle(6_500, 0f)
        f.gear(6_600, DriveGear.PARK)
        assertTrue(f.cards.isEmpty()) // shorter than the 2.5s minimum window
    }
    @Test fun neutralNoiseAndParkInputProduceNoEvents() {
        val f = Feed(); f.gear(0, DriveGear.PARK); f.angle(1_000, 2f); f.angle(2_000, -5f); f.tick(3_000)
        assertTrue(f.cards.isEmpty())
    }
    @Test fun staleAndNonfiniteInputsCannotBecomeConfidentEvents() {
        val f = Feed(); f.ready(); f.angle(5_000, 12f); f.tick(5_500)
        f.put(DriveInput.Steering(5_800, 1_005_800, Float.NaN))
        assertTrue(f.cards.isEmpty()) // a lost angle restarts the window warm-up; no card is invented
        val s = f.state
        f.put(DriveInput.Steering(2_000, 1_002_000, 40f))
        // The stale angle is not applied; only the delayed-lane window is restarted (never bridged).
        assertEquals(s.copy(delayedLane = f.state.delayedLane), f.state)
        assertTrue(f.state.delayedLane.points.isEmpty())
    }
    @Test fun hardBrakeNeedsSpeedDropAndPressedPedal() {
        val f = Feed(); f.ready(); f.brake(4_900, true)
        for (t in 5_000L..5_600 step 100) f.speed(t, 15f - (t - 5_000) / 100f)
        assertEquals(ReviewKind.HARD_BRAKE, f.cards.single().kind)
        assertEquals(10f, f.cards.single().decelerationMps2!!, 0.001f)
    }
    @Test fun speedDropWithoutBrakeIsNotHarshBrake() {
        val f = Feed(); f.ready(); f.speed(5_000, 15f); f.speed(5_500, 10f)
        assertTrue(f.cards.isEmpty())
    }
    @Test fun brakeCooldownPreventsRepeatedCardsFromOneStop() {
        val f = Feed(); f.ready(); f.brake(4_900, true); f.speed(5_000, 20f); f.speed(5_500, 15f); f.speed(6_000, 10f)
        assertEquals(1, f.cards.size)
    }
    @Test fun missingSpeedBrakeDoesNotFabricateDeceleration() {
        val f = Feed(null); f.ready(); f.brake(5_000, true); f.brake(5_500, false)
        assertEquals(ReviewKind.UNSURE, f.cards.single().kind)
        assertEquals(ReviewKind.BRAKE_UNVERIFIED, f.cards.single().candidate)
        assertNull(f.cards.single().decelerationMps2)
    }
    @Test fun freshNearTurnHintChangesClassificationButDistantOrStaleHintDoesNot() {
        fun withHint(hint: TurnHint): ReviewKind {
            val f = Feed(); f.ready(); f.advance(8_000); f.put(DriveInput.Navigation(8_000, 1_008_000, hint)); f.lane(); return f.cards.single().kind
        }
        assertEquals(ReviewKind.TURN, withHint(TurnHint(true, 50f, 8_000)))
        assertEquals(ReviewKind.LANE_NO_SIGNAL, withHint(TurnHint(true, 5_000f, 8_000)))
        assertEquals(ReviewKind.LANE_NO_SIGNAL, withHint(TurnHint(true, 50f, 0)))
        assertEquals(ReviewKind.LANE_NO_SIGNAL, withHint(TurnHint(null, 50f, 8_000)))
    }
    @Test fun finishedStreamCannotLeavePendingSegment() {
        val f = Feed(); f.ready(); f.angle(5_000, 15f); f.tick(5_500)
        f.put(DriveInput.Finish(6_000, 1_006_000))
        assertNull(f.state.active)
        assertTrue(f.state.delayedLane.points.isEmpty()); assertTrue(f.state.delayedLane.candidates.isEmpty())
        assertTrue(f.cards.isEmpty())
    }
    @Test fun outOfOrderBlinkerInvalidatesAbsenceEvidence() {
        val f = Feed(); f.ready()
        for (e in 0L..2_000 step 100) f.angle(8_500 + e, LaneWave.angleDeg(e, 54f))
        f.signal(8_700, Indicator.LEFT) // Beyond the 250ms cross-dispatch tolerance: genuine late arrival.
        for (e in 2_100L..LaneWave.PERIOD_MS step 100) f.angle(8_500 + e, LaneWave.angleDeg(e, 54f))
        f.angle(13_100, 0f); f.end(14_500)
        // The window restarts, so the lane is never reported as a confident "no indicator".
        assertTrue(f.cards.none { it.kind == ReviewKind.LANE_NO_SIGNAL || it.kind == ReviewKind.LANE_SIGNALLED })
    }
    @Test fun overflowDoesNotCreateInfiniteDecelerationEvidence() {
        val f = Feed(); f.ready(); f.brake(4_900, true); f.speed(5_000, Float.MAX_VALUE); f.speed(5_500, 0f)
        assertTrue(f.cards.isEmpty())
    }
}
