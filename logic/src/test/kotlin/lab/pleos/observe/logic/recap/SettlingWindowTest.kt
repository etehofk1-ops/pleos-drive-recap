package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test

class SettlingWindowTest {
    private fun poised(): RecapEngineState {
        val m = LateralSummary(0.07,0.12,0.18,1.4,3.5,80.0,-1.0,1.0)
        val body = SegmentSummary(6_000,10_000,6_000,-12f,14f,1,1,20f,20f,false,
            SignalTiming.UNKNOWN,true,lateral=m)
        return RecapEngineState(lastMs=10_000,gear=DriveGear.DRIVE,angle=0f,speed=20f,speedAt=10_000,
            indicator=Indicator.OFF,indicatorKnownSince=0,active=ActiveSegment(body,-1,neutralSince=9_900,neutralMotion=m),
            lateralFilter=LateralFilter(10_000,heldAngle=0f,heldSpeed=20f,acceleration=-0.18,calibrationMs=3_000))
    }
    @Test fun reciprocalSettledGateNeverChangesTheGlobalOrWeakReturnGate() {
        val m=LateralSummary(0.07,0.12,0.18,1.4,3.5,80.0,-1.0,1.0,500)
        val s=poised().active!!.summary.copy(lateral=m)
        assertFalse(returnedHeading(m)); assertTrue(normalizedLaneShape(s))
        assertFalse(normalizedLaneShape(s.copy(lateral=m.copy(settledMs=499))))
        assertFalse(normalizedLaneShape(s.copy(minAngle=-4.99f,maxAngle=4.99f)))
        assertFalse(normalizedLaneShape(s.copy(lateral=m.copy(headingRad=0.08001))))
        assertFalse(normalizedLaneShape(s.copy(lateral=m.copy(headingRad=0.12))))
        assertFalse(normalizedLaneShape(s.copy(lateral=m.copy(absoluteYawRad=0.49))))
        assertTrue(normalizedLaneShape(s.copy(endMs=s.startMs+8_000)))
        assertEquals(ReviewKind.LANE_NO_SIGNAL,classifySegment(s.copy(endMs=s.startMs+8_000,timing=SignalTiming.NONE))!!.kind)
    }
    @Test fun settlementDoesNotForceAnUnreturnedHeadingToZero() {
        val m=LateralSummary(0.1,0.12,0.18,2.0,3.5,80.0,-1.0,1.0)
        assertEquals(0.1,settledLateral(m,m,2_000).headingRad,0.0)
        assertFalse(normalizedLaneShape(poised().active!!.summary.copy(lateral=settledLateral(m,m,2_000))))
    }
    @Test fun versionFivePreservesSettlingEvidenceAndVersionFourStillReads() {
        // Built directly: the engine no longer produces settled cards at >=25km/h (W16c).
        val m=LateralSummary(0.07,0.12,0.18,1.4,3.5,80.0,-1.0,1.0,500)
        val card=classifySegment(poised().active!!.summary.copy(endMs=14_000,timing=SignalTiming.NONE,lateral=m))!!
        assertEquals(ReviewKind.LANE_NO_SIGNAL,card.kind)
        val day=DayRecap("2026-10-24",cards=listOf(card))
        assertEquals(day,RecapCodec.decode(RecapCodec.encode(day)))
        // Version 6 appended the 4-byte window duration after the 8-byte settledMs; the stored bytes are v6.
        val v5=RecapCodec.encode(day).dropLast(4 + 4).toByteArray().apply { this[7]=5 } // + v7 tail (4 absent nullable fields)
        assertEquals(day.copy(cards=listOf(card.copy(summary=card.summary.copy(lateral=card.summary.lateral!!.copy(windowDurationMs=0))))),RecapCodec.decode(v5))
        val v4=RecapCodec.encode(day).dropLast(12 + 4).toByteArray().apply { this[7]=4 }
        assertEquals(day.copy(cards=listOf(card.copy(summary=card.summary.copy(lateral=card.summary.lateral!!.copy(settledMs=0))))),RecapCodec.decode(v4))
    }
}
