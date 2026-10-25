package lab.pleos.observe.logic

import org.junit.Assert.*
import org.junit.Test

class SignalProbeTest {
    private enum class RawEnum { INTERMITTENT_LEVEL_1 }

    @Test fun numbersAreNotScaledRoundedOrTurnedIntoColors() {
        assertEquals("1234.125", probeRaw(1234.125f))
        assertEquals("-16711681", probeRaw(-16711681))
        assertEquals("87", probeRaw(87))
    }

    @Test fun booleanAndEnumNamesStayRaw() {
        assertEquals("true", probeRaw(true))
        assertEquals("false", probeRaw(false))
        assertEquals("INTERMITTENT_LEVEL_1", probeRaw(RawEnum.INTERMITTENT_LEVEL_1))
    }

    @Test fun actualNullCallbackIsDifferentFromNeverReceived() {
        assertEquals("null", probeRaw(null))
        assertEquals("미수신", probeSampleText(null))
        assertTrue(probeSampleText(ProbeResult("null", "TIME")).contains("null | 수신 TIME"))
    }

    @Test fun getAndNotifyEvidenceRemainIndependent() {
        val get = ProbeResult("0", "GET_TIME")
        val row = ProbeAreaState("ROW_1_LEFT", get = get).copy(notify = ProbeResult("1", "NOTIFY_TIME"))
        assertEquals(get, row.get)
        assertEquals("NOTIFY_TIME", row.notify!!.receivedAt)
        assertNotEquals(row.get!!.raw, row.notify.raw)
    }

    @Test fun requestedLogContractIncludesRawValueAreaPhaseAndTime() {
        val text = probeMessage("wiperState", "notify", "OFF", "FRONT_WINDSHIELD", "TIME", "received", debug = true)
        assertEquals("event=probe.wiperState.notify value=OFF area=FRONT_WINDSHIELD receivedAt=TIME status=received", text)
    }

    @Test fun releasePreservesTheExistingEventOnlyLogPolicy() {
        assertEquals("event=probe.wiperState.notify",
            probeMessage("wiperState", "notify", "OFF", "FRONT_WINDSHIELD", "TIME", "received", debug = false))
    }

    @Test fun missingSdkMethodsDoNotPretendToBeReceivedCallbacks() {
        val row = ProbeSignalState("chargeSwitch", "Charge Switch", "Boolean", notifyMissing = true,
            areas = listOf(ProbeAreaState("GLOBAL")))
        assertNull(row.areas.single().notify)
        assertTrue(row.notifyMissing)
    }
}
