package lab.pleos.observe.logic

import org.junit.Assert.*
import org.junit.Test

class QuizTest {
    private fun snapshot(windows: List<Int> = listOf(8, 5, 0, 0), lights: List<Boolean> = listOf(true, false, false, true)) =
        VehicleSnapshot.of(Seat.values().mapIndexed { i, seat -> seat to SeatState(windows[i], lights[i]) }.toMap())

    @Test fun numberUsesFiveInclusiveAndCountsLightsIndependently() {
        val question = numberProblem(snapshot(listOf(4, 5, 10, 0)), 15)!!
        assertEquals(4, question.answer)
        assertEquals(4, question.options.distinct().size)
        assertTrue(question.answer in question.options)
        assertTrue(question.options.all { it in 0..8 })
        assertTrue(question.explanation.contains("1열 오른쪽"))
        assertTrue(question.explanation.contains("2열 왼쪽"))
    }

    @Test fun numberOptionsReproduceWithSeedAndVaryAcrossSeeds() {
        val source = snapshot()
        assertEquals(numberProblem(source, 37), numberProblem(source, 37))
        assertTrue((0..30).map { numberProblem(source, it)!!.options }.toSet().size > 1)
    }

    @Test fun allNineNumberAnswersHaveFourDistinctOptions() {
        for (answer in 0..8) {
            val lights = List(4) { it < minOf(answer, 4) }
            val windows = List(4) { if (it < maxOf(0, answer - 4)) 5 else 0 }
            for (seed in 0..15) {
                val question = numberProblem(snapshot(windows, lights), seed)!!
                assertEquals(answer, question.answer)
                assertEquals(4, question.options.toSet().size)
                assertTrue(answer in question.options)
            }
        }
    }

    @Test fun notEnoughSeatsCannotGenerateAnyProblem() {
        val one = VehicleSnapshot.of(mapOf(Seat.ROW_1_LEFT to SeatState(5, true)))
        assertNull(numberProblem(one, 1))
        assertNull(seatProblem(one, 1))
        assertFalse(canRemember(one))
    }

    @Test fun seatCluesPointToExactlyOneAnswerAcrossManyStates() {
        var generated = 0
        val levels = listOf(0, 5, 10)
        for (a in levels) for (b in levels) for (c in levels) for (d in levels) for (mask in 0..15) {
            val source = snapshot(listOf(a, b, c, d), List(4) { mask and (1 shl it) != 0 })
            val question = seatProblem(source, mask) ?: continue
            ++generated
            assertTrue(question.clues.size in 2..3)
            val matches = question.clues.fold(source.seats.keys.toSet()) { seats, clue -> seats intersect clue.matches }
            assertEquals(setOf(question.answer), matches)
            assertTrue(question.answer in source.seats)
            assertEquals(question, seatProblem(source, mask))
        }
        assertTrue("Expected real usable states", generated > 200)
    }

    @Test fun uniformStatesCannotProduceFakeUniqueSeat() {
        listOf(false, true).forEach { lit ->
            assertNull(seatProblem(snapshot(List(4) { 5 }, List(4) { lit }), 2))
        }
    }

    @Test fun threeSeatProblemsNeverSelectTheExcludedFourthSeat() {
        val source = VehicleSnapshot.of(snapshot().seats - Seat.ROW_2_RIGHT)
        var generated = 0
        for (seed in 0..20) {
            val question = seatProblem(source, seed) ?: continue
            ++generated
            assertTrue(question.answer in source.seats)
            assertTrue(question.clues.all { it.matches.all(source.seats::containsKey) })
            assertEquals(setOf(question.answer), question.clues.map { it.matches }.reduce { a, b -> a intersect b })
        }
        assertTrue(generated > 0)
        assertNull(seatProblem(VehicleSnapshot.of(source.seats.filterKeys { it.row == 1 }), 1))
    }

    @Test fun rowClueUsesWindowTotalsAndNeverUnknownSeats() {
        val source = snapshot(listOf(8, 8, 0, 0), listOf(true, false, false, true))
        val question = seatProblem(source, 42)!!
        assertEquals(setOf(question.answer), question.clues.map { it.matches }.reduce(Set<Seat>::intersect))
        assertTrue(question.clues.any { it.text.isNotBlank() })
    }

    @Test fun snapshotParserAcceptsOnlyFourVerifiedPairsAndKnownValueRanges() {
        val rows = Seat.values().flatMap { seat -> listOf(
            Observation(seat.label, true, "5", id = "window.${seat.name}"),
            Observation(seat.label, true, "ON", id = "light.${seat.name}")) }
        assertEquals(4, snapshotFromObservations(rows).seats.size)
        val invalid = rows.map {
            when (it.id) {
                "window.ROW_1_LEFT" -> it.copy(value = "11")
                "light.ROW_1_RIGHT" -> it.copy(supported = false)
                "light.ROW_2_LEFT" -> it.copy(value = "DAYTIME_RUNNING")
                "window.ROW_2_RIGHT" -> it.copy(value = null)
                else -> it
            }
        } + Observation("unverified", true, "ON", id = "light.ROW_3_LEFT")
        assertTrue(snapshotFromObservations(invalid).seats.isEmpty())
    }

    @Test fun snapshotIgnoresReceiveTimesButRejectsErrorsAndMissingPair() {
        val rows = listOf(Observation("w", true, "0", id = "window.ROW_1_LEFT"),
            Observation("l", true, "OFF", id = "light.ROW_1_LEFT"))
        assertEquals(snapshotFromObservations(rows), snapshotFromObservations(rows.map { it.copy(receivedAt = "later") }))
        assertTrue(snapshotFromObservations(rows.map { it.copy(error = "failed") }).seats.isEmpty())
        assertTrue(snapshotFromObservations(rows.take(1)).seats.isEmpty())
    }

    @Test fun snapshotCopiesInputMap() {
        val mutable = mutableMapOf(Seat.ROW_1_LEFT to SeatState(0, false))
        val copy = VehicleSnapshot.of(mutable)
        mutable.clear()
        assertEquals(1, copy.seats.size)
    }

    @Test fun differenceCountsPropertiesAndUsesOneRawUnit() {
        val before = snapshot(List(4) { 0 }, List(4) { false })
        assertEquals(0, changes(before, before)!!.size)
        val after = snapshot(listOf(1, 0, 0, 0), listOf(true, false, false, false))
        assertEquals(setOf(SignalKey(Seat.ROW_1_LEFT, SignalKind.WINDOW), SignalKey(Seat.ROW_1_LEFT, SignalKind.LIGHT)), changes(before, after)!!.toSet())
        val reverse = snapshot(listOf(10, 0, 0, 0), List(4) { false })
        assertEquals(1, changes(reverse, before)!!.size)
    }

    @Test fun comparisonRefusesMissingBaselineSeatAndIgnoresNewlyAvailableSeats() {
        val before = snapshot()
        assertNull(changes(before, VehicleSnapshot.of(before.seats - Seat.ROW_1_LEFT)))
        val partial = VehicleSnapshot.of(before.seats - Seat.ROW_2_RIGHT)
        assertEquals(emptyList<SignalKey>(), changes(partial, before))
    }

    @Test fun changeQuestionHasFourDistinctSeededSeatKindOptions() {
        val before = snapshot()
        val after = snapshot(listOf(9, 5, 0, 0))
        for (seed in 0..20) {
            val question = changeProblem(before, after, seed)!!
            assertEquals(SignalKey(Seat.ROW_1_LEFT, SignalKind.WINDOW), question.answer)
            assertEquals(4, question.options.distinct().size)
            assertTrue(question.answer in question.options)
            assertEquals(question, changeProblem(before, after, seed))
            assertTrue(question.explanation.contains("열림 정도"))
        }
        assertNull(changeProblem(before, before, 1))
        assertNull(changeProblem(before, snapshot(listOf(9, 6, 0, 0)), 1))
    }

    @Test fun twoRememberedSeatsStillProvideFourValidOptions() {
        val before = VehicleSnapshot.of(snapshot().seats.filterKeys { it.row == 1 })
        val after = VehicleSnapshot.of(before.seats + (Seat.ROW_1_LEFT to SeatState(9, true)))
        val question = changeProblem(before, after, 2)!!
        assertEquals(4, question.options.toSet().size)
        assertTrue(question.options.all { it.seat.row == 1 })
    }

    @Test fun releaseLogCannotContainValuesTimesErrorsOrSnapshot() {
        val message = eventMessage("quiz.answer", false, debug = false, details = "window=9 time=secret error=oops")
        assertEquals("event=quiz.answer correct=false", message)
        assertEquals("event=window.notify", eventMessage("window.notify", debug = false, details = "value=5"))
        assertTrue(eventMessage("window.notify", debug = true, details = "value=5").contains("value=5"))
    }
}
