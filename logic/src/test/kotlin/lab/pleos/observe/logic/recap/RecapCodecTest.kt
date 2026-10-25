package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test

class RecapCodecTest {
    private fun day(): DayRecap {
        val summary = SegmentSummary(0, 2_000, 123456, -12f, 9f, -1, 1, 15f, 15f, false, SignalTiming.NONE, true)
        return DayRecap("2026-10-23", 10_000, 2_000, listOf(classifySegment(summary)!!))
    }
    @Test fun summaryAndFeedbackRoundTripExactly() {
        val day = applyFeedback(day(), day().cards.single().id, Feedback.NO)
        assertEquals(day, RecapCodec.decode(RecapCodec.encode(day)))
    }
    @Test fun unsureNullSpeedAndNullableFieldsRoundTrip() {
        val c = day().cards.single().copy(kind = ReviewKind.UNSURE, uncertainty = setOf(Uncertainty.SPEED_MISSING),
            summary = day().cards.single().summary.copy(minSpeedMps = null, maxSpeedMps = null, speedMissing = true))
        val day = day().copy(cards = listOf(c))
        assertEquals(day, RecapCodec.decode(RecapCodec.encode(day)))
    }
    @Test fun emptyDayRoundTrips() { val day = DayRecap("2026-10-23"); assertEquals(day, RecapCodec.decode(RecapCodec.encode(day))) }
    @Test fun retentionUsesCalendarDatesIncludingMonthAndYearBoundaries() {
        assertTrue(retainedDate("2026-10-23", "2026-10-23")); assertTrue(retainedDate("2026-10-22", "2026-10-23"))
        assertFalse(retainedDate("2026-10-21", "2026-10-23")); assertFalse(retainedDate("2026-10-24", "2026-10-23"))
        assertTrue(retainedDate("2025-12-31", "2026-01-01")); assertFalse(retainedDate("bad", "2026-10-23"))
    }
    @Test fun feedbackIsIdempotentAndMissingIdDoesNotChangeOtherCards() {
        val d = day(); val yes = applyFeedback(d, d.cards.single().id, Feedback.YES)
        assertEquals(yes, applyFeedback(yes, d.cards.single().id, Feedback.YES))
        assertEquals(d, applyFeedback(d, "absent", Feedback.NO))
    }
    @Test fun corruptOrTruncatedDataIsRejected() {
        assertTrue(runCatching { RecapCodec.decode(byteArrayOf(0, 1, 2)) }.isFailure)
        val bytes = RecapCodec.encode(day())
        assertTrue(runCatching { RecapCodec.decode(bytes.copyOf(bytes.size - 1)) }.isFailure)
        assertTrue(runCatching { RecapCodec.decode(bytes + 0) }.isFailure)
    }
    @Test fun codecContainsNoRawEventOrRouteFields() {
        val text = RecapCodec.encode(day()).toString(Charsets.ISO_8859_1)
        listOf("latitude", "longitude", "VIN", "rawEvents", "destination").forEach { assertFalse(text.contains(it)) }
    }
    @Test fun unknownDirectionNeverProducesLeftRightInCardText() {
        val c = day().cards.single()
        assertEquals(ManeuverDirection.UNKNOWN, c.direction)
        assertFalse(recapEvidence(c).contains("좌회전")); assertFalse(recapEvidence(c).contains("우회전"))
        assertTrue(recapTitle(c).contains("추정"))
    }
}
