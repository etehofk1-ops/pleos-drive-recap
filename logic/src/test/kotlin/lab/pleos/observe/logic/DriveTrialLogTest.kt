package lab.pleos.observe.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveTrialLogTest {
    @Test fun callbackLogKeepsRawValueAndMonotonicTime() {
        assertEquals("event=drive.steeringAngle value=-12.125 t=12345 source=notify n=2 run=test",
            driveTrialMessage("steeringAngle", "-12.125", 12345L, true, "source=notify n=2 run=test"))
    }

    @Test fun unavailableValueIsNotZeroOrFalse() {
        assertEquals("event=drive.brakePedal value=null t=5 source=notify",
            driveTrialMessage("brakePedal", "null", 5L, true, "source=notify"))
    }

    @Test fun serviceLifeAndCallbackSourceRemainDistinct() {
        assertEquals("event=drive.service.onDestroy value=called t=99",
            driveTrialMessage("service.onDestroy", "called", 99L, true))
        assertEquals("event=drive.gear value=8 t=100 source=get",
            driveTrialMessage("gear", "8", 100L, true, "source=get"))
    }

    @Test fun releaseRetainsExistingEventOnlyPolicy() {
        assertEquals("event=drive.steeringAngle",
            driveTrialMessage("steeringAngle", "-12.125", 12345L, false, "source=notify n=2 run=test"))
    }
}
