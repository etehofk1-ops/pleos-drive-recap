package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test

class RecorderReadinessTest {
    @Test fun noReceivedValuesRemainPendingWithoutInventingDefaults() {
        assertEquals("초기 신호 확인 중: 깜빡이, 브레이크, 조향", recorderReadiness(null, null, null))
    }
    @Test fun offFalseAndNeutralAreKnownStatesWithoutAGetSuccessFlag() {
        assertEquals("초기 신호 준비됨", recorderReadiness(Indicator.OFF, false, 0f))
    }
    @Test fun notifyCompletesReadinessAfterInitialGetWasUnavailable() {
        val failed = setOf("turnSignal")
        assertTrue(recorderReadiness(null, false, 0f, failed).startsWith("초기 조회 미확인: 깜빡이"))
        for (value in Indicator.entries) assertEquals("초기 신호 준비됨", recorderReadiness(value, false, 0f, failed))
    }
    @Test fun laterNotifyErrorOrNullReturnsToMissingUntilRecovered() {
        assertEquals("초기 신호 준비됨", recorderReadiness(Indicator.LEFT, true, 9f))
        assertEquals("초기 신호 확인 중: 깜빡이", recorderReadiness(null, true, 9f))
        assertEquals("초기 신호 준비됨", recorderReadiness(Indicator.RIGHT, true, 9f))
    }
    @Test fun oldFailureForARecoveredSignalDoesNotMislabelAnotherPendingSignal() {
        assertEquals("초기 신호 확인 중: 브레이크", recorderReadiness(Indicator.OFF, null, 0f, setOf("turnSignal")))
    }
    @Test fun brakeAndAngleRecoveryAreTreatedTheSameAsTurnSignalRecovery() {
        val failed = setOf("turnSignal", "brakePedal", "steeringAngle")
        assertEquals("초기 신호 준비됨", recorderReadiness(Indicator.OFF, false, 0f, failed))
        assertTrue(recorderReadiness(Indicator.OFF, null, 0f, failed).contains("브레이크"))
        assertTrue(recorderReadiness(Indicator.OFF, false, null, failed).contains("조향"))
    }
    @Test fun nonfiniteSteeringNeverBecomesReady() {
        for (angle in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
            assertEquals("초기 신호 확인 중: 조향", recorderReadiness(Indicator.OFF, false, angle))
    }
    @Test fun engineNotificationStateFeedsReadinessWithoutInitialGetMarkers() {
        var state = RecapEngineState()
        for (input in listOf(DriveInput.Signal(0, 0, Indicator.OFF), DriveInput.Brake(1, 1, false), DriveInput.Steering(2, 2, 0f)))
            state = recapStep(state, input).state
        assertEquals("초기 신호 준비됨", recorderReadiness(state.indicator, state.brake, state.angle))
        state = recapStep(state, DriveInput.Signal(3, 3, null)).state
        assertEquals("초기 신호 확인 중: 깜빡이", recorderReadiness(state.indicator, state.brake, state.angle))
    }
}
