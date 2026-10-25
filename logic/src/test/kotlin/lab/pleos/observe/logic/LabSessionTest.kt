package lab.pleos.observe.logic

import org.junit.Assert.*
import org.junit.Test

class LabSessionTest {
    private val safe = SafetyState(true, false, Gear.PARK, 0f)
    private val original = VehicleSnapshot.of(mapOf(
        Seat.ROW_1_LEFT to SeatState(8, true), Seat.ROW_1_RIGHT to SeatState(6, false),
        Seat.ROW_2_LEFT to SeatState(0, false), Seat.ROW_2_RIGHT to SeatState(0, true)))

    private fun step(state: LabState, action: LabAction) = reduceLab(state, action).state
    private fun home(): LabState = step(step(LabState(seed = 42), LabAction.Safety(safe, true)), LabAction.Snapshot(original))
    private fun changed(window: Int = 9, light: Boolean = true) =
        VehicleSnapshot.of(original.seats + (Seat.ROW_1_LEFT to SeatState(window, light)))

    private fun hidden(): LabState {
        var state = step(home(), LabAction.Open(ProblemKind.CHANGE))
        state = step(state, LabAction.Remember)
        val reading = state.screen as LabScreen.MemoryReading
        return step(state, LabAction.FreshRead(reading.id, original))
    }

    @Test fun startsLockedAndCannotOpenBeforeSafetyArrives() {
        val state = LabState()
        assertEquals(state, step(state, LabAction.Open(ProblemKind.NUMBER)))
        assertEquals(state, step(state, LabAction.Snapshot(original)))
        assertEquals(LabScreen.Home, step(state, LabAction.Safety(safe, true)).screen)
    }

    @Test fun numberChangeInvalidatesEvenIfAnswerCountDoesNotChange() {
        val initial = step(home(), LabAction.Open(ProblemKind.NUMBER))
        val old = initial.screen as LabScreen.Number
        val transition = reduceLab(initial, LabAction.Snapshot(changed()))
        val next = transition.state.screen as LabScreen.Number
        assertNotEquals(old.id, next.id)
        assertEquals(old.problem.answer, next.problem.answer)
        assertEquals("차가 바뀌었어요, 새 문제를 낼게요", next.notice)
        assertTrue(transition.events.any { it.name == "quiz.invalidated" })
        assertEquals(transition.state, step(transition.state, LabAction.NumberAnswer(old.id, old.problem.answer)))
    }

    @Test fun seatQuestionAlsoInvalidatesOnDataOrEligibilityChange() {
        val initial = step(home(), LabAction.Open(ProblemKind.SEAT))
        assertTrue(initial.screen is LabScreen.SeatQuestion)
        val next = reduceLab(initial, LabAction.Snapshot(changed()))
        assertTrue(next.events.any { it.name == "quiz.invalidated" })
        val lost = VehicleSnapshot.of(original.seats - Seat.ROW_1_LEFT - Seat.ROW_1_RIGHT)
        assertTrue(step(initial, LabAction.Snapshot(lost)).screen is LabScreen.Unavailable)
    }

    @Test fun sameValuesDoNotRegenerateAndChangeBackDoesNotReviveOldQuestion() {
        val initial = step(home(), LabAction.Open(ProblemKind.NUMBER))
        assertEquals(initial, step(initial, LabAction.Snapshot(VehicleSnapshot.of(original.seats))))
        val updated = step(step(initial, LabAction.Snapshot(changed())), LabAction.Snapshot(original))
        assertNotEquals((initial.screen as LabScreen.Number).id, (updated.screen as LabScreen.Number).id)
    }

    @Test fun numberAndSeatCorrectWrongAndInvalidOptionsAreHandled() {
        val number = step(home(), LabAction.Open(ProblemKind.NUMBER))
        val nq = number.screen as LabScreen.Number
        assertEquals(number, step(number, LabAction.NumberAnswer(nq.id, 100)))
        assertTrue((step(number, LabAction.NumberAnswer(nq.id, nq.problem.answer)).screen as LabScreen.Result).correct)
        assertFalse((step(number, LabAction.NumberAnswer(nq.id, nq.problem.options.first { it != nq.problem.answer })).screen as LabScreen.Result).correct)
        val seat = step(home(), LabAction.Open(ProblemKind.SEAT))
        val sq = seat.screen as LabScreen.SeatQuestion
        assertTrue((step(seat, LabAction.SeatAnswer(sq.id, sq.problem.answer)).screen as LabScreen.Result).correct)
        assertFalse((step(seat, LabAction.SeatAnswer(sq.id, Seat.values().first { it != sq.problem.answer })).screen as LabScreen.Result).correct)
    }

    @Test fun unavailableStateExplainsAndRecoversOnRealStateChange() {
        val uniform = VehicleSnapshot.of(Seat.values().associateWith { SeatState(0, false) })
        val state = step(step(home(), LabAction.Snapshot(uniform)), LabAction.Open(ProblemKind.SEAT))
        assertTrue((state.screen as LabScreen.Unavailable).reason.contains("창문이나 독서등을 하나 바꿔 보세요"))
        assertTrue(step(state, LabAction.Snapshot(original)).screen is LabScreen.SeatQuestion)
        val missing = step(step(home(), LabAction.Snapshot(VehicleSnapshot.EMPTY)), LabAction.Open(ProblemKind.NUMBER))
        assertTrue((missing.screen as LabScreen.Unavailable).reason.contains("좌석이 부족"))
    }

    @Test fun rememberRequestsFreshReadInsteadOfCapturingCachedValues() {
        val ready = step(home(), LabAction.Open(ProblemKind.CHANGE))
        val request = reduceLab(ready, LabAction.Remember)
        val screen = request.state.screen as LabScreen.MemoryReading
        assertNull(screen.baseline)
        assertEquals(screen.id, request.refresh!!.id)
        val remembered = step(request.state, LabAction.FreshRead(screen.id, changed()))
        assertEquals(changed(), (remembered.screen as LabScreen.MemoryHidden).baseline)
    }

    @Test fun hiddenWaitAllowsChangesWithoutInvalidatingAndChecksFreshSnapshot() {
        val state = hidden()
        val updated = step(state, LabAction.Snapshot(changed()))
        assertEquals(state.screen, updated.screen)
        val request = reduceLab(updated, LabAction.Changed)
        assertNotNull(request.refresh)
        assertTrue(request.state.screen is LabScreen.MemoryReading)
        val compared = step(request.state, LabAction.FreshRead(request.refresh!!.id, changed()))
        assertTrue(compared.screen is LabScreen.ChangeQuestion)
    }

    @Test fun zeroAndMultipleChangesKeepOriginalBaselineForRetry() {
        val initial = hidden()
        fun compare(state: LabState, fresh: VehicleSnapshot): LabState {
            val request = reduceLab(state, LabAction.Changed)
            return step(request.state, LabAction.FreshRead(request.refresh!!.id, fresh))
        }
        val zero = compare(initial, original).screen as LabScreen.MemoryHidden
        assertEquals("아직 안 바뀌었어요", zero.notice)
        assertEquals(original, zero.baseline)
        val multiple = compare(initial, changed(light = false))
        assertEquals("하나만 바꿔 주세요", (multiple.screen as LabScreen.MemoryHidden).notice)
        assertEquals(original, multiple.screen.baseline)
        assertTrue(compare(multiple, changed()).screen is LabScreen.ChangeQuestion)
    }

    @Test fun missingOrFailedFreshReadNeverBecomesAnAnswer() {
        val request = reduceLab(hidden(), LabAction.Changed)
        val missing = VehicleSnapshot.of(original.seats - Seat.ROW_1_LEFT)
        val rejected = step(request.state, LabAction.FreshRead(request.refresh!!.id, missing))
        assertTrue((rejected.screen as LabScreen.MemoryHidden).notice!!.contains("확인할 수 없어요"))
        val failed = step(request.state, LabAction.FreshRead(request.refresh.id, null))
        assertTrue((failed.screen as LabScreen.MemoryHidden).notice!!.contains("다시 읽지 못했어요"))
    }

    @Test fun changeAnswerUsesFourValidOptionsAndRejectsStaleClicks() {
        val request = reduceLab(hidden(), LabAction.Changed)
        val state = step(request.state, LabAction.FreshRead(request.refresh!!.id, changed()))
        val question = state.screen as LabScreen.ChangeQuestion
        assertEquals(state, step(state, LabAction.ChangeAnswer(question.id - 1, question.problem.answer)))
        val correct = reduceLab(state, LabAction.ChangeAnswer(question.id, question.problem.answer))
        assertTrue((correct.state.screen as LabScreen.Result).correct)
        assertEquals(listOf(LabEvent("quiz.change.answered", true)), correct.events)
        val wrong = question.problem.options.first { it != question.problem.answer }
        assertFalse((step(state, LabAction.ChangeAnswer(question.id, wrong)).screen as LabScreen.Result).correct)
    }

    @Test fun everyProgressScreenAndBaselineAreDiscardedForEveryLockCause() {
        val request = reduceLab(hidden(), LabAction.Changed)
        val compared = step(request.state, LabAction.FreshRead(request.refresh!!.id, changed()))
        val question = compared.screen as LabScreen.ChangeQuestion
        val result = step(compared, LabAction.ChangeAnswer(question.id, question.problem.answer))
        val screens = listOf(home(), step(home(), LabAction.Open(ProblemKind.NUMBER)),
            step(home(), LabAction.Open(ProblemKind.SEAT)), hidden(), request.state, compared, result,
            step(home(), LabAction.Diagnostics))
        val unsafe = listOf(safe.copy(carConnected = false), safe.copy(restrictionsActive = true),
            safe.copy(restrictionsActive = null), safe.copy(gear = Gear.DRIVE), safe.copy(gear = null),
            safe.copy(speedMps = 1f), safe.copy(speedMps = -1f), safe.copy(speedMps = null))
        for (state in screens) for (safety in unsafe) {
            val locked = step(state, LabAction.Safety(safety, true))
            assertEquals(LabScreen.Locked, locked.screen)
            assertEquals(VehicleSnapshot.EMPTY, locked.current)
            assertEquals(LabScreen.Home, step(locked, LabAction.Safety(safe, true)).screen)
        }
    }

    @Test fun pauseAndLateFreshReadCannotRestoreDiscardedGame() {
        val request = reduceLab(hidden(), LabAction.Changed)
        val paused = step(request.state, LabAction.Safety(safe, false))
        assertEquals(LabScreen.Locked, paused.screen)
        assertEquals(paused, step(paused, LabAction.FreshRead(request.refresh!!.id, changed())))
        val resumed = step(paused, LabAction.Safety(safe, true))
        assertEquals(LabScreen.Home, resumed.screen)
        assertEquals(resumed, step(resumed, LabAction.FreshRead(request.refresh.id, changed())))
    }

    @Test fun newerReadRequestRejectsOldResponseEvenAfterUnlock() {
        val first = reduceLab(hidden(), LabAction.Changed)
        var state = step(first.state, LabAction.Safety(safe, false))
        state = step(state, LabAction.Safety(safe, true))
        state = step(state, LabAction.Snapshot(original))
        state = step(state, LabAction.Open(ProblemKind.CHANGE))
        val second = reduceLab(state, LabAction.Remember)
        assertNotEquals(first.refresh!!.id, second.refresh!!.id)
        assertEquals(second.state, step(second.state, LabAction.FreshRead(first.refresh.id, changed())))
    }

    @Test fun homeAndDiagnosticsDropMemoryBaseline() {
        assertEquals(LabScreen.Home, step(hidden(), LabAction.Home).screen)
        assertEquals(LabScreen.Diagnostics, step(hidden(), LabAction.Diagnostics).screen)
    }
}
