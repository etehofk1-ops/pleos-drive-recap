package lab.pleos.observe.logic

import org.junit.Assert.*
import org.junit.Test

class VehicleLayoutTest {
    private val parked = SafetyState(true, false, Gear.PARK, 0f)
    private val values = VehicleSnapshot.of(mapOf(Seat.ROW_1_LEFT to SeatState(8, true),
        Seat.ROW_1_RIGHT to SeatState(0, false), Seat.ROW_2_LEFT to SeatState(4, false)))
    private fun question() = reduceLab(LabState(parked, true, values, LabScreen.Home, seed = 42), LabAction.Open(ProblemKind.NUMBER)).state

    @Test fun narrowBoundariesAndVerticalActions() {
        listOf(0, 320, 599).forEach {
            val layout = vehicleLayout(it)
            assertEquals(VehicleWidth.NARROW, layout.width); assertTrue(layout.verticalButtons)
            assertFalse(layout.twoPanes); assertEquals(16, layout.marginDp)
        }
    }
    @Test fun mediumBoundariesKeepSingleColumn() {
        listOf(600, 840, 999).forEach {
            val layout = vehicleLayout(it)
            assertEquals(VehicleWidth.MEDIUM, layout.width); assertFalse(layout.twoPanes)
            assertFalse(layout.verticalButtons); assertEquals(24, layout.marginDp)
        }
    }
    @Test fun wideBoundariesUseTwoPanes() {
        listOf(1000, 1280, 2560).forEach { assertEquals(VehicleWidth.WIDE, vehicleLayout(it).width); assertTrue(vehicleLayout(it).twoPanes) }
    }
    @Test(expected = IllegalArgumentException::class) fun negativeWidthRejected() { vehicleLayout(-1) }
    @Test fun collapseReturnsLastTouchedPaneAnchor() {
        val summary = ScrollAnchor("summary", 4, 300); val card = ScrollAnchor("card:3", -12, 900)
        val state = PaneScrollState(summary, card)
        assertEquals(summary, singlePaneAnchor(state)); assertEquals(card, singlePaneAnchor(state.copy(cardsFocused = true)))
    }
    @Test fun codeColorsMeetContrastForAllTextOnEverySurface() {
        listOf(false, true).forEach { night ->
            val p = vehiclePalette(night)
            pleosTextPairs(p).forEach { (text, background) ->
                assertTrue("night=$night contrast=${contrastRatio(text, background)}", contrastRatio(text, background) >= 4.5)
            }
        }
    }
    @Test fun contrastReferenceBlackWhiteAndSymmetry() {
        assertEquals(21.0, contrastRatio(0xff000000.toInt(), 0xffffffff.toInt()), 0.001)
        assertEquals(1.0, contrastRatio(0xff123456.toInt(), 0xff123456.toInt()), 0.001)
        assertEquals(contrastRatio(123, 456), contrastRatio(456, 123), 0.0)
    }
    @Test fun emptyAndPartialCallbacksWaitWithoutTrustingPreviousPark() {
        listOf(SafetyState(), parked.copy(carConnected = false), parked.copy(gear = null),
            parked.copy(speedMps = null), parked.copy(restrictionsActive = null)).forEach {
            assertEquals(RestorationGate.WAIT, restorationGate(it))
        }
        assertEquals(RestorationGate.READY, restorationGate(parked))
    }
    @Test fun anyKnownUnsafeCallbackDiscardsPendingUiImmediately() {
        listOf(SafetyState(restrictionsActive = true), SafetyState(gear = Gear.DRIVE), SafetyState(speedMps = 0.01f),
            SafetyState(speedMps = -0.01f), SafetyState(speedMps = Float.NaN), SafetyState(speedMps = Float.POSITIVE_INFINITY)).forEach {
            assertEquals(RestorationGate.DISCARD, restorationGate(it))
        }
    }
    @Test fun questionAndSeedSurviveRecreationWithIdenticalFreshValues() {
        val checkpoint = question()
        assertEquals(checkpoint, restoreLabCheckpoint(checkpoint, parked, values, false))
    }
    @Test fun cachedParkCannotRevealQuestionOnRecreation() {
        assertNull(restoreLabCheckpoint(question(), SafetyState(), values, true))
        assertNull(restoreLabCheckpoint(question(), parked.copy(gear = Gear.DRIVE), values, true))
    }
    @Test fun incompleteSnapshotWaitsForKnownSeats() {
        assertNull(restoreLabCheckpoint(question(), parked, VehicleSnapshot.EMPTY, false))
        val settled = restoreLabCheckpoint(question(), parked, VehicleSnapshot.EMPTY, true)!!
        assertTrue(settled.screen is LabScreen.Unavailable)
    }
    @Test fun changedSnapshotInvalidatesOldQuestionAfterResize() {
        val old = question()
        val next = restoreLabCheckpoint(old, parked, VehicleSnapshot.of(values.seats + (Seat.ROW_1_LEFT to SeatState(9, true))), true)!!
        assertNotEquals((old.screen as LabScreen.Number).id, (next.screen as LabScreen.Number).id)
        assertEquals("차가 바뀌었어요, 새 문제를 낼게요", next.screen.notice)
    }
    @Test fun hiddenMemoryBaselineSurvivesResizeButNotUnsafeSignal() {
        val checkpoint = LabState(parked, true, values, LabScreen.MemoryHidden(values), seed = 3)
        assertEquals(checkpoint, restoreLabCheckpoint(checkpoint, parked, values, true))
        assertNull(restoreLabCheckpoint(checkpoint, parked.copy(restrictionsActive = true), values, true))
    }
    @Test fun diagnosticsCanRestoreBeforeAllSeatValuesArrive() {
        val checkpoint = LabState(parked, true, values, LabScreen.Diagnostics)
        assertEquals(LabScreen.Diagnostics, restoreLabCheckpoint(checkpoint, parked, VehicleSnapshot.EMPTY, false)!!.screen)
    }
}
