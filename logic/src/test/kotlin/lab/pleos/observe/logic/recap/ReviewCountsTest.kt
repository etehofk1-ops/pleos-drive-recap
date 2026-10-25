package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test

class ReviewCountsTest {
    private fun card(kind: ReviewKind) = ReviewCard("x", 0, kind, kind,
        SegmentSummary(0, 1000, 0, -9f, 12f, 1, 1, 15f, 15f, false, SignalTiming.NONE, true))
    @Test fun countsUseOnlyClearRuleKinds() {
        val cards = listOf(card(ReviewKind.LANE_NO_SIGNAL), card(ReviewKind.LANE_LATE_SIGNAL), card(ReviewKind.LANE_SIGNALLED),
            card(ReviewKind.HARD_BRAKE), card(ReviewKind.UNSURE).copy(candidate = ReviewKind.LANE_NO_SIGNAL), card(ReviewKind.TURN))
        assertEquals(ReviewCounts(1, 1, 1, 1), reviewCounts(cards))
    }
    @Test fun maximumDecelerationUpsertsCountOnlyOneEpisode() {
        val c = card(ReviewKind.HARD_BRAKE)
        val merged = mergeReviewCards(listOf(c), listOf(c.copy(decelerationMps2 = 4f)))
        assertEquals(1, reviewCounts(merged).hardBrake)
    }
    @Test fun sevenDayRetentionAndTwoDayRawRetentionStayDifferent() {
        assertTrue(retainedCountDate("2026-10-17", "2026-10-23")); assertFalse(retainedDate("2026-10-17", "2026-10-23"))
        assertFalse(retainedCountDate("2026-10-16", "2026-10-23")); assertFalse(retainedCountDate("2026-10-24", "2026-10-23"))
        assertTrue(retainedCountDate("2025-12-26", "2026-01-01")); assertFalse(retainedCountDate("bad", "2026-01-01"))
    }
    @Test fun relativeDaysExcludeTodayAndUnobservedDatesWithoutInventingZeros() {
        val days = listOf(CountDay("2026-10-23", ReviewCounts()), CountDay("2026-10-22", ReviewCounts(2)),
            CountDay("2026-10-17", ReviewCounts(3)), CountDay("2026-10-16", ReviewCounts(4)))
        assertEquals(listOf("D-1", "D-6"), relativeCounts(days, "2026-10-23").map { it.date })
        assertEquals(2, relativeCounts(days, "2026-10-23")[0].counts.missing)
    }
    @Test fun fourCountsAndDateRoundTripWithoutSegmentFields() {
        val d = CountDay("2026-10-23", ReviewCounts(1, 2, 3, 4)); val bytes = CountCodec.encode(d)
        assertEquals(d, CountCodec.decode(bytes))
        val text = bytes.toString(Charsets.ISO_8859_1)
        for (forbidden in listOf("wallTime", "angle", "VIN", "latitude", "feedback", "rawEvents")) assertFalse(text.contains(forbidden))
        assertTrue(bytes.size < 50)
    }
    @Test fun countCodecRejectsTruncationTrailingDataAndInvalidCounts() {
        val bytes = CountCodec.encode(CountDay("2026-10-23", ReviewCounts()))
        assertTrue(runCatching { CountCodec.decode(bytes.copyOf(bytes.size - 1)) }.isFailure)
        assertTrue(runCatching { CountCodec.decode(bytes + 0) }.isFailure)
        assertTrue(runCatching { CountCodec.encode(CountDay("2026-10-23", ReviewCounts(-1))) }.isFailure)
    }
}
