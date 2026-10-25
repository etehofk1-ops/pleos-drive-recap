package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test

class RecapRulesTest {
    private fun lane() = SegmentSummary(5_000, 7_000, 1_000_000, -12f, 9f, -1, 1,
        15f, 15f, false, SignalTiming.NONE, true)
    @Test fun reciprocalShapeWithSpeedAndObservedOffIsMissingSignalEstimate() {
        val card = classifySegment(lane())!!
        assertEquals(ReviewKind.LANE_NO_SIGNAL, card.kind)
        assertEquals(ManeuverDirection.UNKNOWN, card.direction)
    }
    @Test fun signalBeforeIsNormalCandidate() {
        assertEquals(ReviewKind.LANE_SIGNALLED, classifySegment(lane().copy(timing = SignalTiming.BEFORE))!!.kind)
    }
    @Test fun laterSignalHasDelayEvidence() {
        assertEquals(ReviewKind.LANE_LATE_SIGNAL,
            classifySegment(lane().copy(timing = SignalTiming.DURING, lateSignalMs = 500))!!.kind)
    }
    @Test fun simultaneousSignalIsUncertainRatherThanLate() {
        assertEquals(ReviewKind.UNSURE, classifySegment(lane().copy(timing = SignalTiming.DURING, lateSignalMs = 0))!!.kind)
    }
    @Test fun missingSignalObservationDoesNotProveNonuse() {
        val c = classifySegment(lane().copy(signalCoverage = false, timing = SignalTiming.UNKNOWN))!!
        assertEquals(ReviewKind.UNSURE, c.kind)
        assertEquals(ReviewKind.LANE_CHANGE, c.candidate)
        assertFalse(recapTitle(c).contains("깜빡이 없이"))
    }
    @Test fun speedUnknownKeepsHypothesisButMarksUnsure() {
        val c = classifySegment(lane().copy(minSpeedMps = null, maxSpeedMps = null, speedMissing = true))!!
        assertEquals(ReviewKind.UNSURE, c.kind)
        assertEquals(ReviewKind.LANE_NO_SIGNAL, c.candidate)
        assertTrue(c.uncertainty.contains(Uncertainty.SPEED_MISSING))
    }
    @Test fun lowKnownSpeedDoesNotBecomeMissingSpeed() {
        val c = classifySegment(lane().copy(minSpeedMps = 0f, maxSpeedMps = 0f))!!
        assertEquals(ReviewKind.UNSURE, c.kind)
        assertEquals(ReviewKind.STEERING, c.candidate)
    }
    @Test fun laneSpeedBoundaryIsInclusive() {
        assertEquals(ReviewKind.LANE_NO_SIGNAL,
            classifySegment(lane().copy(minSpeedMps = RecapThresholds.LANE_MIN_MPS))!!.kind)
        assertEquals(ReviewKind.UNSURE,
            classifySegment(lane().copy(minSpeedMps = RecapThresholds.LANE_MIN_MPS - 0.001f))!!.kind)
    }
    @Test fun angleAndDurationBoundariesAreInclusive() {
        assertEquals(ReviewKind.LANE_NO_SIGNAL, classifySegment(lane().copy(endMs = 11_000, minAngle = -35f))!!.kind)
        assertEquals(ReviewKind.UNSURE, classifySegment(lane().copy(endMs = 11_001))!!.kind)
        assertNull(classifySegment(lane().copy(endMs = 5_599)))
    }
    @Test fun tinyCounterSteerCannotProveReciprocalPattern() {
        assertEquals(ReviewKind.UNSURE, classifySegment(lane().copy(maxAngle = 5.9f))!!.kind)
    }
    @Test fun repeatedWeavingIsNotOneLaneChange() {
        assertEquals(ReviewKind.UNSURE, classifySegment(lane().copy(reversals = 3))!!.kind)
    }
    @Test fun lowSpeedLargeLongTurnDoesNotBecomeLaneViolation() {
        val c = classifySegment(lane().copy(minAngle = -120f, maxAngle = 0f, reversals = 0,
            minSpeedMps = 4f, maxSpeedMps = 4f, endMs = 9_000))!!
        assertEquals(ReviewKind.TURN, c.kind)
        assertFalse(recapTitle(c).contains("차선 변경"))
    }
    @Test fun sustainedOneWayCurveDoesNotBecomeLaneViolation() {
        assertEquals(ReviewKind.CURVE, classifySegment(lane().copy(minAngle = 0f, maxAngle = 18f, reversals = 0, endMs = 15_000))!!.kind)
    }
    @Test fun verifiedNearbyNavigationCanAssistTurnClassification() {
        assertEquals(ReviewKind.TURN, classifySegment(lane().copy(nearNavigationTurn = true))!!.kind)
    }
    @Test fun incompleteOrGappedSegmentsRemainUnsure() {
        assertEquals(ReviewKind.UNSURE, classifySegment(lane().copy(incomplete = true))!!.kind)
        assertEquals(ReviewKind.UNSURE, classifySegment(lane().copy(inputGap = true))!!.kind)
    }
    @Test fun defaultHasNoLeftRightInterpretation() {
        assertEquals(ManeuverDirection.UNKNOWN, maneuverDirection(-1, SteeringConvention.UNKNOWN))
        assertEquals(ManeuverDirection.LEFT, maneuverDirection(1, SteeringConvention.POSITIVE_LEFT))
        assertEquals(ManeuverDirection.RIGHT, maneuverDirection(1, SteeringConvention.POSITIVE_RIGHT))
    }
    @Test fun missingSpeedTurnAndCurveAreExplicitlyUncertain() {
        val turn = lane().copy(minAngle = -100f, maxAngle = 0f, reversals = 0, speedMissing = true, endMs = 9_000)
        assertEquals(ReviewKind.TURN, classifySegment(turn)!!.candidate)
        assertEquals(ReviewKind.UNSURE, classifySegment(turn)!!.kind)
        val curve = turn.copy(minAngle = -18f, endMs = 15_000)
        assertEquals(ReviewKind.CURVE, classifySegment(curve)!!.candidate)
    }
    @Test fun highSpeedLargeSustainedAngleIsNotAConfidentCurve() {
        val card = classifySegment(lane().copy(minAngle = -120f, maxAngle = 0f, reversals = 0, endMs = 15_000))!!
        assertEquals(ReviewKind.CURVE, card.candidate)
        assertEquals(ReviewKind.UNSURE, card.kind)
    }
}
