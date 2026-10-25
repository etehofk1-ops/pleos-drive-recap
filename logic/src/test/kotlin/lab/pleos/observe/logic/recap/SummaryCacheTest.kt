package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test

class SummaryCacheTest {
    private val day = DayRecap("2026-10-23", cards = listOf(ReviewCard("one", 1792728345678L, ReviewKind.UNSURE,
        ReviewKind.STEERING, SegmentSummary(0, 2450, 1792728345678L, -18f, 12f, -1, 1,
            kphToMps(24f), kphToMps(35f), false, SignalTiming.NONE, true))))
    private val answer = GeneratedRecap("오늘 기록을 차분히 살펴봤어요. 정차한 지금 남은 장면을 확인해 보세요.", emptyList(), "fixture-model")
    private fun key(d: DayRecap = day, recent: List<RecentCounts> = emptyList()) = summaryCacheKey(d, summaryFeatures(d, recent)!!)!!

    @Test fun unchangedCardsKeepCacheAcrossFeedbackTimersAndListReordering() {
        val second = day.cards.single().copy(id = "two")
        val d = day.copy(cards = day.cards + second)
        assertEquals(key(d), key(d.copy(drivingMs = 99999, cards = d.cards.reversed().map { it.copy(feedback = Feedback.YES) })))
    }
    @Test fun changedEvidenceDateCountsAndCardsInvalidateKey() {
        assertNotEquals(key(), key(day.copy(date = "2026-10-24")))
        assertNotEquals(key(), key(day.copy(cards = day.cards.map { it.copy(summary = it.summary.copy(maxAngle = 20f)) })))
        assertNotEquals(key(), key(recent = listOf(RecentCounts("D-1", ReviewCounts(missing = 2)))))
        assertNotEquals(key(), key(day.copy(cards = day.cards + day.cards.single().copy(id = "two"))))
    }
    @Test fun localCacheRestoresWithoutNetworkAndOnlyForMatchingKey() {
        val state = SummaryPreparation(); val cached = SummaryCacheEntry(key(), answer)
        state.select(key(), cached); assertEquals(answer, state.entry?.result); assertNull(state.begin())
        val other = key(day.copy(date = "2026-10-24")); state.select(other, cached)
        assertNull(state.entry); assertNotNull(state.begin())
    }
    @Test fun pendingShowsTemplateUntilCompletionAndDoesNotDuplicateRequest() {
        val state = SummaryPreparation(); state.select(key()); val ticket = state.begin()!!
        assertTrue(state.pending); assertNull(state.entry); assertNull(state.begin())
        assertEquals(answer, state.finish(ticket, answer)?.result); assertFalse(state.pending); assertNull(state.begin())
    }
    @Test fun failureUsesTemplateAndRequiresExplicitRetry() {
        val state = SummaryPreparation(); state.select(key()); state.finish(state.begin()!!, null)
        assertFalse(state.pending); assertNull(state.entry); assertNull(state.begin())
        assertNotNull(state.begin(force = true)); assertTrue(state.pending)
    }
    @Test fun deleteOptOutOrNewDriveDiscardsLateResponseEvenIfSameCardsReturn() {
        val state = SummaryPreparation(); state.select(key()); val old = state.begin()!!
        state.reset(); state.select(key()); val fresh = state.begin()!!
        assertNull(state.finish(old, answer)); assertTrue(state.pending)
        assertEquals(answer, state.finish(fresh, answer)?.result)
    }
    @Test fun newCardsAndManualRetryPreventOldReplyOverwritingCurrentResult() {
        val state = SummaryPreparation(); state.select(key()); val old = state.begin()!!
        state.select(key(day.copy(cards = day.cards + day.cards.single().copy(id = "two"))))
        val current = state.begin()!!; assertNull(state.finish(old, answer))
        val retry = state.begin(force = true)!!; assertNull(state.finish(current, answer))
        assertNotNull(state.finish(retry, answer))
    }
    @Test fun corruptCacheAndInvalidModelTextAreRejected() {
        val cached = SummaryCacheEntry(key(), answer)
        assertEquals(cached, SummaryCacheCodec.decode(SummaryCacheCodec.encode(cached)))
        for (bytes in listOf(byteArrayOf(), SummaryCacheCodec.encode(cached) + byteArrayOf(1), ByteArray(20000))) {
            assertTrue(runCatching { SummaryCacheCodec.decode(bytes) }.isFailure)
        }
        val state = SummaryPreparation(); state.select(key()); assertNull(state.finish(state.begin()!!, answer.copy(text = "")))
    }
    @Test fun emptyOrOverLimitCardListDoesNotCreateCacheIdentity() {
        val empty = day.copy(cards = emptyList()); assertNull(summaryCacheKey(empty, summaryFeatures(empty, emptyList())!!))
        assertNull(summaryCacheKey(day.copy(cards = List(501) { day.cards.single().copy(id = "$it") }), SummaryFeatures(emptyList(), emptyList())))
    }
}
