package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test

class DrivingStateTest {
    private val parked = DrivingSignals(DriveGear.PARK, DriveGear.PARK, false, 0f, 0)

    @Test fun selectedDriveWinsOverStuckCurrentPark() {
        val d = drivingDecision(parked.copy(selectedGear = DriveGear.DRIVE), 0)
        assertEquals(DriveGear.DRIVE, d.gear)
        assertEquals(listOf(DrivingSource.GEAR_SELECTION), d.sources)
    }
    @Test fun uxStartsRecordingEvenWhenBothGearValuesAreStuckInPark() {
        val d = drivingDecision(parked.copy(requiresDo = true), 0)
        assertEquals(DriveGear.DRIVE, d.gear)
        assertEquals(listOf(DrivingSource.UX_REQUIRES_DO), d.sources)
    }
    @Test fun actualSpeedStartsRecordingDespiteBothParkAndUxFalse() {
        val d = drivingDecision(parked.copy(speedMps = kphToMps(60f)), 0)
        assertEquals(DriveGear.DRIVE, d.gear)
        assertEquals(listOf(DrivingSource.SPEED), d.sources)
    }
    @Test fun sourcesAreOrderedAndNoParkEvidenceOverridesMotion() {
        val d = drivingDecision(parked.copy(selectedGear = DriveGear.DRIVE, requiresDo = true, speedMps = 16f), 0)
        assertEquals(listOf(DrivingSource.GEAR_SELECTION, DrivingSource.UX_REQUIRES_DO, DrivingSource.SPEED), d.sources)
    }
    @Test fun currentGearIsOnlyFallbackAndDoesNotVetoKnownSelection() {
        assertEquals(DriveGear.PARK, drivingDecision(parked.copy(currentGear = DriveGear.DRIVE), 0).gear)
        val fallback = drivingDecision(parked.copy(selectedGear = DriveGear.UNKNOWN, currentGear = DriveGear.DRIVE), 0)
        assertEquals(listOf(DrivingSource.CURRENT_GEAR_FALLBACK), fallback.sources)
    }
    @Test fun parkRequiresAllStoppedAndReceivedZero() {
        assertEquals(DriveGear.PARK, drivingDecision(parked, 0).gear)
        assertEquals(DriveGear.UNKNOWN, drivingDecision(parked.copy(requiresDo = null), 0).gear)
        assertEquals(DriveGear.UNKNOWN, drivingDecision(parked.copy(speedMps = null), 0).gear)
        assertEquals(DriveGear.UNKNOWN, drivingDecision(parked.copy(selectedGear = DriveGear.UNKNOWN, currentGear = DriveGear.UNKNOWN), 0).gear)
    }
    @Test fun staleFutureAndNonfiniteSpeedCannotProveParkingOrMotion() {
        assertEquals(DriveGear.UNKNOWN, drivingDecision(parked, RecapThresholds.SPEED_FRESH_MS + 1).gear)
        assertEquals(DriveGear.UNKNOWN, drivingDecision(parked, -1).gear)
        for (v in listOf(Float.NaN, Float.POSITIVE_INFINITY))
            assertEquals(DriveGear.UNKNOWN, drivingDecision(parked.copy(speedMps = v), 0).gear)
    }
    @Test fun reverseAndTinyPositiveSpeedAreNotParked() {
        assertEquals(DriveGear.DRIVE, drivingDecision(parked.copy(speedMps = -1f), 0).gear)
        assertEquals(DriveGear.DRIVE, drivingDecision(parked.copy(speedMps = 0.001f), 0).gear)
        assertEquals(DriveGear.DRIVE, drivingDecision(parked.copy(selectedGear = DriveGear.OTHER), 0).gear)
    }
    @Test fun disconnectClearsAllEvidenceInsteadOfInventingPark() {
        assertEquals(DriveGear.UNKNOWN, drivingDecision(DrivingSignals(), 0).gear)
    }
    @Test fun mpsConversionMatchesThresholdAndCardText() {
        assertEquals(60f, mpsToKph(kphToMps(60f)), 0.001f)
        assertEquals(30f, mpsToKph(RecapThresholds.LANE_MIN_MPS), 0.001f)
        val s = SegmentSummary(0, 2000, 0, -12f, 9f, -1, 1, 15f, 15f, false, SignalTiming.NONE, true)
        val c = classifySegment(s)!!
        assertEquals(ReviewKind.LANE_NO_SIGNAL, c.kind)
        assertTrue(recapEvidence(c).contains("54.0~54.0km/h"))
        assertEquals(ReviewKind.UNSURE, classifySegment(s.copy(minSpeedMps = 3f, maxSpeedMps = 3f))!!.kind)
        val good = classifySegment(s.copy(timing = SignalTiming.BEFORE))!!
        assertTrue(recapTitle(good).startsWith("좋아요"))
        assertFalse(recapTitle(good.copy(kind = ReviewKind.UNSURE)).startsWith("좋아요"))
    }
    @Test fun stuckCurrentGearWithUxAndSpeedFeedsRealLaneEventThenParks() {
        var signals = parked
        var state = RecapEngineState()
        val cards = mutableListOf<ReviewCard>()
        fun put(input: DriveInput) { val step = recapStep(state, input); state = step.state; cards += step.cards }
        fun motion(t: Long) { put(DriveInput.Gear(t, t, drivingDecision(signals, t).gear)) }
        motion(0); put(DriveInput.Signal(0, 0, Indicator.OFF))
        put(DriveInput.Steering(0, 0, 0f)); put(DriveInput.Speed(0, 0, 0f))
        signals = signals.copy(requiresDo = true, speedMps = 15f, speedAtMs = 4000)
        motion(4000)
        val wave = (0L..LaneWave.PERIOD_MS step 100).associate { e -> (8_000L + e) to LaneWave.angleDeg(e, 54f) }
        for (t in 4_000L..14_000L step 50) {
            signals = signals.copy(speedAtMs = t); motion(t)
            put(DriveInput.Speed(t, t, 15f)); wave[t]?.let { put(DriveInput.Steering(t, t, it)) }; put(DriveInput.Tick(t,t))
        }
        assertTrue(cards.isEmpty()) // W16c: the delayed window is still being confirmed
        signals = signals.copy(requiresDo = false, speedMps = 0f, speedAtMs = 14_500)
        val step = recapStep(state, DriveInput.Gear(14_500, 14_500, drivingDecision(signals, 14_500).gear))
        assertTrue(step.parked)
        assertEquals(ReviewKind.LANE_NO_SIGNAL, step.cards.single().kind) // parking flushes the confirmed window
    }
    @Test fun normalTurnIsNotAReviewCardButAmbiguousTurnIsKept() {
        val s = SegmentSummary(0, 3000, 0, 0f, 120f, 1, 0, 3f, 3f, false, SignalTiming.BEFORE, true)
        val normal = classifySegment(s)!!
        assertEquals(ReviewKind.TURN, normal.kind)
        assertTrue(visibleReviewCards(listOf(normal)).isEmpty())
        val uncertain = classifySegment(s.copy(speedMissing = true))!!
        assertEquals(listOf(uncertain), visibleReviewCards(listOf(uncertain)))
    }
}
