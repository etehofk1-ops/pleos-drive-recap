package lab.pleos.observe.logic.recap

import lab.pleos.observe.logic.*
import org.junit.Assert.*
import org.junit.Test

/** 1.7.0 B: 깜빡이를 켜 둔 채 주행(30km/h 이상 15초 이상 누적). */
class BlinkerLeftOnTest {
    private class Feed(val speed: Float? = 15f, val policy: RecapPolicy = RecapPolicy()) {
        var state = RecapEngineState()
        val cards = mutableListOf<ReviewCard>()
        var heldSpeed = speed
        var nextTick = 0L
        fun put(input: DriveInput): RecapStep = recapStep(state, input, policy).also {
            state = it.state
            val merged = mergeReviewCards(cards, it.cards); cards.clear(); cards += merged
        }
        fun advance(t: Long) {
            while (nextTick < t) {
                put(DriveInput.Speed(nextTick, 1_000_000 + nextTick, heldSpeed))
                put(DriveInput.Tick(nextTick, 1_000_000 + nextTick)); nextTick += 50
            }
            if (nextTick == t) nextTick += 50
        }
        fun speed(t: Long, v: Float? = heldSpeed): RecapStep { advance(t); heldSpeed = v; return put(DriveInput.Speed(t, 1_000_000 + t, v)) }
        fun angle(t: Long, v: Float?) { speed(t); put(DriveInput.Steering(t, 1_000_000 + t, v)) }
        fun signal(t: Long, v: Indicator?): RecapStep { advance(t); return put(DriveInput.Signal(t, 1_000_000 + t, v)) }
        fun gear(t: Long, v: DriveGear): RecapStep { advance(t); return put(DriveInput.Gear(t, 1_000_000 + t, v)) }
        fun tick(t: Long) { speed(t); put(DriveInput.Tick(t, 1_000_000 + t)) }
        fun end(t: Long) { tick(t); put(DriveInput.Finish(t + 50, 1_000_000 + t + 50)) }
        fun ready() { gear(0, DriveGear.PARK); signal(0, Indicator.OFF); angle(0, 0f); speed(4_000); gear(4_000, DriveGear.DRIVE) }
        fun wave(start: Long, direction: Int = 1) {
            val kph = (heldSpeed?.takeIf { it > 1f } ?: 15f) * 3.6f
            for (e in 0L..LaneWave.PERIOD_MS step 100) angle(start + e, LaneWave.angleDeg(e, kph, direction))
            angle(start + LaneWave.PERIOD_MS + 100, 0f)
        }
        val blinker get() = cards.filter { it.kind == ReviewKind.BLINKER_LEFT_ON }
    }

    /** ON at [onAt], OFF at [offAt], then enough time (and a Finish) for every pending verdict. */
    private fun run(onAt: Long, offAt: Long, speed: Float? = 15f, side: Indicator = Indicator.LEFT, policy: RecapPolicy = RecapPolicy()): Feed {
        val f = Feed(speed, policy); f.ready(); f.signal(onAt, side); f.signal(offAt, Indicator.OFF); f.end(offAt + 20_000); return f
    }

    @Test fun fifteenSecondBoundary() {
        assertTrue(run(5_000, 19_900).blinker.isEmpty()) // 14.9s
        val on = run(5_000, 20_100).blinker // 15.1s
        assertEquals(1, on.size)
        val c = on.single()
        assertEquals(ReviewKind.BLINKER_LEFT_ON, c.candidate); assertEquals(ManeuverDirection.LEFT, c.direction)
        assertEquals(15_100.0, c.summary.blinkerOnMs!!.toDouble(), 150.0)
        assertEquals(15f * 15.1f, c.summary.blinkerOnMeters!!, 6f)
        assertEquals(5_000L, c.summary.startMs); assertEquals(20_100L, c.summary.endMs)
        assertTrue(c.uncertainty.isEmpty())
    }
    @Test fun rightSideCountsToo() {
        val c = run(5_000, 21_000, side = Indicator.RIGHT).blinker.single()
        assertEquals(ManeuverDirection.RIGHT, c.direction)
    }
    @Test fun thirtyKphBoundary() {
        assertEquals(1, run(5_000, 21_000, kphToMps(30f)).blinker.size)
        assertTrue(run(5_000, 21_000, kphToMps(29.9f)).blinker.isEmpty())
    }
    @Test fun onlyTheTimeAtOrAboveThirtyKphAccumulates() {
        fun mixed(offAt: Long): Feed {
            val f = Feed(); f.ready(); f.signal(5_000, Indicator.LEFT)
            f.speed(15_000, 5f); f.speed(20_000, 15f); f.signal(offAt, Indicator.OFF); f.end(offAt + 20_000); return f
        }
        val c = mixed(26_000).blinker.single() // 10s + 6s fast, 5s slow
        assertEquals(16_000.0, c.summary.blinkerOnMs!!.toDouble(), 200.0)
        assertTrue(mixed(24_000).blinker.isEmpty()) // 10s + 4s
    }
    @Test fun shortOffBetweenFlashesIsBridgedButALongerOffSplitsTheRun() {
        fun gapped(offLenMs: Long): Feed {
            val f = Feed(); f.ready(); f.signal(5_000, Indicator.LEFT); f.signal(12_000, Indicator.OFF)
            f.signal(12_000 + offLenMs, Indicator.LEFT); f.signal(21_500 + offLenMs, Indicator.OFF); f.end(45_000); return f
        }
        val bridged = gapped(1_000).blinker.single() // 7s + 9.5s over one continuous run
        assertEquals(5_000L, bridged.summary.startMs); assertEquals(16_500.0, bridged.summary.blinkerOnMs!!.toDouble(), 200.0)
        assertTrue(gapped(4_000).blinker.isEmpty()) // 7s and 9.5s are two runs, neither reaches 15s
    }
    @Test fun oneCardPerRunAndTwoQualifyingRunsGiveTwoCards() {
        val f = Feed(); f.ready(); f.signal(5_000, Indicator.LEFT); f.signal(22_000, Indicator.OFF)
        f.signal(30_000, Indicator.RIGHT); f.signal(48_000, Indicator.OFF); f.end(70_000)
        assertEquals(2, f.blinker.size)
        assertEquals(2, f.blinker.map { it.id }.distinct().size)
        assertEquals(listOf(ManeuverDirection.LEFT, ManeuverDirection.RIGHT), f.blinker.sortedBy { it.summary.startMs }.map { it.direction })
    }
    @Test fun runStillOnAtTheEndOfTheRecordingIsConfirmedByFinish() {
        val f = Feed(); f.ready(); f.signal(5_000, Indicator.LEFT); f.end(25_000)
        assertEquals(1, f.blinker.size)
        assertTrue(f.blinker.single().summary.blinkerOnMs!! >= 19_000)
    }
    @Test fun noCardIsEmittedWhileTheBlinkerIsStillOn() {
        val f = Feed(); f.ready(); f.signal(5_000, Indicator.LEFT); f.tick(40_000)
        assertTrue(f.blinker.isEmpty()) // 꺼지거나 기록이 끝나야 확정
    }
    @Test fun switchingToTheOtherSideEndsTheRun() {
        val f = Feed(); f.ready(); f.signal(5_000, Indicator.LEFT); f.signal(22_000, Indicator.RIGHT); f.signal(24_000, Indicator.OFF); f.end(50_000)
        assertEquals(listOf(ManeuverDirection.LEFT), f.blinker.map { it.direction })
    }

    // ---- 변경하려고 켠 깜빡이를 끝나고 안 끈 경우만 ----
    @Test fun blinkerKeptOnlyForTheManeuverItselfIsNotACard() {
        val f = Feed(); f.ready(); f.signal(4_100, Indicator.LEFT); f.wave(10_000)
        f.tick(27_000); f.signal(27_000, Indicator.OFF); f.end(50_000)
        val lane = f.cards.single { it.kind == ReviewKind.LANE_SIGNALLED }
        assertTrue(f.blinker.isEmpty()) // 4.1s~27s = 22.9s 켰지만 차선 변경이 끝난 뒤는 12~13초뿐
        assertTrue(lane.summary.endMs in 12_000L..16_000L)
    }
    @Test fun leftOnAfterTheManeuverCountsFromTheEndOfThatCard() {
        val f = Feed(); f.ready(); f.signal(4_100, Indicator.LEFT); f.wave(10_000)
        f.tick(36_000); f.signal(36_000, Indicator.OFF); f.end(60_000)
        val lane = f.cards.single { it.kind == ReviewKind.LANE_SIGNALLED }
        val c = f.blinker.single()
        assertTrue(c.summary.startMs >= lane.summary.endMs)
        assertEquals((36_000 - lane.summary.endMs).toDouble(), c.summary.blinkerOnMs!!.toDouble(), 300.0)
        assertTrue(c.summary.blinkerOnMs!! in 15_000L..30_000L)
    }
    @Test fun aSlowManeuverCardArrivingAfterTheBlinkerWentOffStillResetsTheCount() {
        // 깜빡이는 훨씬 전에 켰고 차선 변경 직후 껐다. 차선 카드는 ~9초 뒤에 나오지만, 그 전에 15초 판정을 내리면 안 된다.
        val f = Feed(); f.ready(); f.signal(4_100, Indicator.LEFT); f.wave(21_000)
        f.signal(26_000, Indicator.OFF); f.tick(48_000); f.end(60_000)
        assertTrue(f.cards.any { it.kind == ReviewKind.LANE_SIGNALLED })
        assertTrue(f.blinker.isEmpty())
    }
    @Test fun withoutAnyManeuverTheSameLongRunIsACard() {
        // 대조군: 위와 같은 깜빡이 길이인데 차선 변경이 없으면 카드가 나온다.
        val f = Feed(); f.ready(); f.signal(4_100, Indicator.LEFT); f.signal(26_000, Indicator.OFF); f.end(60_000)
        assertEquals(1, f.blinker.size)
    }

    // ---- 근거 없으면 숨김 ----
    @Test fun lostSignalReceptionInsideTheRunMeansNoCard() {
        val f = Feed(); f.ready(); f.signal(5_000, Indicator.LEFT); f.signal(22_000, null); f.end(50_000)
        assertTrue(f.blinker.isEmpty())
        val control = Feed(); control.ready(); control.signal(5_000, Indicator.LEFT); control.signal(22_000, Indicator.OFF); control.end(50_000)
        assertEquals(1, control.blinker.size)
    }
    @Test fun lateOutOfOrderSignalInvalidatesTheRun() {
        val f = Feed(); f.ready(); f.signal(5_000, Indicator.LEFT); f.tick(22_000)
        f.put(DriveInput.Signal(21_000, 1_021_000, Indicator.OFF)) // 250ms 허용 밖으로 늦게 도착
        f.end(50_000)
        assertTrue(f.blinker.isEmpty())
    }
    @Test fun clockGapInsideTheRunMeansNoCard() {
        val f = Feed(); f.ready(); f.signal(5_000, Indicator.LEFT); f.advance(8_000)
        f.put(DriveInput.Speed(14_000, 1_014_000, 15f)) // 6초 동안 아무 입력도 없었다
        f.nextTick = 14_050; f.signal(30_000, Indicator.OFF); f.end(50_000)
        assertTrue(f.blinker.isEmpty())
    }
    @Test fun missingOrDemoSpeedNeverCountsTime() {
        assertTrue(run(5_000, 25_000, speed = null).blinker.isEmpty())
        assertTrue(run(5_000, 25_000, policy = RecapPolicy(demoMissingSpeed = true)).blinker.isEmpty())
    }
    @Test fun parkedTimeDoesNotCount() {
        val f = Feed(); f.ready(); f.signal(5_000, Indicator.LEFT); f.gear(9_000, DriveGear.PARK); f.tick(40_000)
        f.signal(40_000, Indicator.OFF); f.end(60_000)
        assertTrue(f.blinker.isEmpty()) // 달린 시간은 4초뿐
    }

    // ---- 표시·집계 ----
    private fun card(): ReviewCard = run(5_000, 20_100).blinker.single()
    @Test fun wordingFollowsTheSpec() {
        val c = card()
        assertEquals("깜빡이를 켠 채 주행", recapTitle(c))
        val text = blinkerLeftOnText(c)!!
        assertTrue(text, text.contains("깜빡이를 켠 채 약 15초(약 "))
        assertTrue(text, text.contains("m) 달렸어요 — 뒤차가 진로 변경으로 오해할 수 있어요"))
        assertTrue(recapEvidence(c).contains(text))
        assertFalse((text + recapTitle(c) + recapEvidence(c)).contains("위반"))
        assertNull(blinkerLeftOnText(c.copy(kind = ReviewKind.LANE_SIGNALLED, candidate = ReviewKind.LANE_SIGNALLED)))
    }
    @Test fun cardIsVisibleAndCountsOnItsOwnRowNotAsMissed() {
        val c = card()
        assertEquals(listOf(c), visibleReviewCards(listOf(c)))
        val counts = reviewCounts(listOf(c, c.copy(id = "other")))
        assertEquals(2, counts.blinkerOn); assertEquals(0, counts.missing)
        assertEquals(2, recapGlance(counts).leftOn); assertEquals(0, recapGlance(counts).missed)
        assertTrue(recapSummary(listOf(c)).contains("켜 둔 채 주행 1"))
        assertEquals(ReviewAppearance(PleosIcon.LATE_SIGNAL, PleosTone.CAUTION), reviewAppearance(ReviewKind.BLINKER_LEFT_ON))
    }
    @Test fun cardRoundTripsThroughTheCodecAndCountCodec() {
        val day = DayRecap("2026-10-25", 1_000, 0, listOf(card()))
        assertEquals(day, RecapCodec.decode(RecapCodec.encode(day)))
        val counts = CountDay("2026-10-25", ReviewCounts(1, 2, 3, 4, 5, 6, 7, blinkerOn = 8))
        assertEquals(counts, CountCodec.decode(CountCodec.encode(counts)))
        val legacy = CountDay("2026-10-25", ReviewCounts(1, 2, 3, 4, 5, 6, 7))
        assertEquals(legacy, CountCodec.decode(CountCodec.encode(legacy)))
    }
    @Test fun cardFeedsTheAiSummaryAsAnOrdinaryItem() {
        val c = card()
        val features = summaryFeatures(DayRecap("2026-10-25", cards = listOf(c)), emptyList())!!
        assertEquals("BLINKER_LEFT_ON", features.today.single().kind)
        assertEquals("mid", features.today.single().speedBand)
    }
}
