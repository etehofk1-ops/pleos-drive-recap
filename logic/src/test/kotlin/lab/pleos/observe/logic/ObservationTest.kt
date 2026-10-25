package lab.pleos.observe.logic

import org.junit.Assert.*
import org.junit.Test

class ObservationTest {
    private val parked = SafetyState(true, false, Gear.PARK, 0f)

    @Test fun unlocksOnlyWithConfirmedParkedInputs() {
        assertFalse(shouldLock(parked))
        assertFalse(shouldLock(parked.copy(speedMps = -0f)))
    }

    @Test fun everyUnknownInputLocksIndependently() {
        listOf(SafetyState(), parked.copy(restrictionsActive = null),
            parked.copy(gear = null), parked.copy(speedMps = null))
            .forEach { assertTrue(shouldLock(it)) }
    }

    @Test fun disconnectedCarLocksEvenWithPreviousParkedValues() {
        assertTrue(shouldLock(parked.copy(carConnected = false)))
    }

    @Test fun activeRestrictionsAloneLock() {
        assertTrue(shouldLock(parked.copy(restrictionsActive = true)))
    }

    @Test fun allNonParkGearsLockEvenAtZeroSpeed() {
        Gear.values().filter { it != Gear.PARK }.forEach {
            assertTrue(shouldLock(parked.copy(gear = it)))
        }
    }

    @Test fun positiveNegativeAndTinyNonzeroSpeedsLock() {
        listOf(0.001f, 5f, -0.001f, -5f, Float.MIN_VALUE).forEach {
            assertTrue(shouldLock(parked.copy(speedMps = it)))
        }
    }

    @Test fun nonFiniteSpeedsLock() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertTrue(shouldLock(parked.copy(speedMps = it)))
        }
    }

    @Test fun lockRemovesValuesTimesAndErrorsRatherThanCoveringThem() {
        val rows = listOf(Observation("창문", true, "VALUE", "TIME", error = "ERROR"))
        assertTrue(observationBody(parked, rows).contains("VALUE"))
        assertEquals("", observationBody(parked.copy(gear = Gear.DRIVE), rows))
        assertEquals("", observationBody(SafetyState(), rows))
    }

    @Test fun distinguishesUnsupportedFromNoValueAndErrors() {
        assertTrue(Observation("좌석", false).display().contains("미지원"))
        val failed = Observation("좌석", true, error = "SecurityException").display()
        assertTrue(failed.contains("미수신"))
        assertTrue(failed.contains("SecurityException"))
        assertFalse(failed.contains("미지원"))
    }

    @Test fun receivedNullStillShowsCallbackTimeWithoutInventingValue() {
        val text = Observation("창문", true, receivedAt = "2026-10-22T10:00:00+09:00").display()
        assertTrue(text.contains("미수신"))
        assertTrue(text.contains("2026-10-22T10:00:00+09:00"))
    }
}
