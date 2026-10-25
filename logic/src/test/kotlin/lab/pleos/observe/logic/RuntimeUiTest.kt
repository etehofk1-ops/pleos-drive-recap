package lab.pleos.observe.logic

import org.junit.Assert.*
import org.junit.Test

class RuntimeUiTest {
    private val route = RecapUiRoute(true, 100, PaneScrollState(ScrollAnchor("summary", 3, 40),
        ScrollAnchor("card:120", -8, 900), true), 1_000)

    @Test fun sameInstanceResumeWaitsThenRestoresAllPanes() {
        val waiting = restoreRecapRoute(route, RestorationGate.WAIT, 2_000)
        assertNull(waiting.restore); assertEquals(route, waiting.pending)
        val ready = restoreRecapRoute(waiting.pending, RestorationGate.READY, 3_000)
        assertEquals(route, ready.restore); assertNull(ready.pending)
    }
    @Test fun processRestorePauseAndResaveDuringWaitDoesNotLoseRouteOrRenewAge() {
        var saved = route.copy() // safe Bundle fields only
        repeat(3) { saved = restoreRecapRoute(saved, RestorationGate.WAIT, 2_000L + it).pending!! }
        assertEquals(1_000, saved.capturedAtMs)
        assertEquals(route, restoreRecapRoute(saved, RestorationGate.READY, 4_000).restore)
    }
    @Test fun unsafeThenParkNeverRevivesDiscardedRoute() {
        val discard = restoreRecapRoute(route, RestorationGate.DISCARD, 2_000)
        assertEquals(UiRestoration(), discard)
        assertNull(restoreRecapRoute(discard.pending, RestorationGate.READY, 3_000).restore)
    }
    @Test fun expiryBoundaryAndClockRollback() {
        assertEquals(route, restoreRecapRoute(route, RestorationGate.READY, 1_000 + UI_RESTORE_TTL_MS).restore)
        assertNull(restoreRecapRoute(route, RestorationGate.WAIT, 1_001 + UI_RESTORE_TTL_MS).pending)
        assertNull(restoreRecapRoute(route, RestorationGate.READY, 999).restore)
    }
    @Test fun absentRouteStaysHomeForEveryGate() {
        RestorationGate.entries.forEach { assertEquals(UiRestoration(), restoreRecapRoute(null, it, 2_000)) }
    }
    @Test fun savedRouteNeverCopiesTextBasedTagsOrDiagnosticInput() {
        val dirty = route.copy(scroll = route.scroll.copy(primary = ScrollAnchor("text:raw input", 3, 40),
            cards = ScrollAnchor("card:12345-678-steer", -8, 900)))
        val safe = persistedRecapRoute(dirty)
        assertNull(safe.scroll.primary.key); assertEquals(40, safe.scroll.primary.yDp)
        assertEquals(dirty.scroll.cards, safe.scroll.cards); assertTrue(safe.scroll.cardsFocused)
        assertEquals("summary", persistedRecapRoute(route).scroll.primary.key)
    }
    @Test fun latePageOrPreviousInstanceResponseCannotOverwriteCurrentCards() {
        assertTrue(currentRecapResponse(2, 2, 100, 100))
        assertFalse(currentRecapResponse(1, 2, 100, 100))
        assertFalse(currentRecapResponse(2, 2, 0, 100))
    }
    @Test fun pagesKeepHundredBoundaryAndNoEmptyPage() {
        assertEquals(0, recapPageOffset(-2)); assertEquals(100, recapPageOffset(199))
        assertEquals(100, nextRecapPage(0, 100, 120))
        assertNull(nextRecapPage(100, 20, 120)); assertNull(nextRecapPage(0, 0, 120))
    }
    @Test fun hundredCardsBuildViewportOnlyOneTileAtATime() {
        val tops = (0 until 100).map { it * 400 }; val bottoms = tops.map { it + 400 }
        val window = visibleRecapRows(tops, bottoms, 0, 900)
        assertEquals(0..3, window)
        assertEquals(0, nextRecapRow(window, tops.indices.toSet()))
        assertEquals(1, nextRecapRow(window, tops.indices.toSet() - 0))
        assertNull(nextRecapRow(window, (4 until 100).toSet()))
    }
    @Test fun deepScrollIncludesTargetAndBoundsWithOverscan() {
        val tops = (0 until 20).map { it * 400 }; val bottoms = tops.map { it + 400 }
        assertEquals(16..19, visibleRecapRows(tops, bottoms, 7_000, 8_000))
        assertTrue(visibleRecapRows(tops, bottoms, -100, -1).isEmpty())
        assertTrue(visibleRecapRows(emptyList(), emptyList(), 0, 100).isEmpty())
    }
    @Test fun debugUiLogsOnlyOneSamplePerFiveSeconds() {
        assertTrue(shouldLogUiProperty(true, 10, null))
        assertFalse(shouldLogUiProperty(true, 5_009, 10))
        assertTrue(shouldLogUiProperty(true, 5_010, 10))
        assertTrue(shouldLogUiProperty(true, 1, 10))
    }
    @Test fun releaseDoesNotLogRawSignalsEvenWithoutSampleHistory() {
        assertFalse(shouldLogUiProperty(false, 10_000, null))
        listOf("steeringAngle", "speed", "gear", "gearSelection", "ux", "turnSignal", "brakePedal",
            "heartbeat", "service.onCreate", "navi.tbt").forEach {
            assertFalse(it, shouldLogDriveEvent(it, false)); assertTrue(shouldLogDriveEvent(it, true))
        }
    }
    @Test fun releaseRetainsDecisionAndSummaryEventsOnly() {
        listOf("recap.card", "recap.segment.start", "recap.motion.ux.speed", "recap.summary.ready").forEach {
            assertTrue(it, shouldLogDriveEvent(it, false))
        }
        assertFalse(shouldLogDriveEvent("recap.raw", false))
    }
}
