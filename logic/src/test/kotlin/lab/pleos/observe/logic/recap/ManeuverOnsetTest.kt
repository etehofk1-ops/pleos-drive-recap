package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test

/**
 * 1.7.1: 깜빡이 켬 거리는 판정기가 정한 카드 시작(startMs, 잔차 20%)이 아니라 '조작 시작'(maneuverOnsetMs, 잔차 10%)까지 잰다.
 * startMs·카드 종류·신호 증거는 그대로이고, onset은 메모리에서만 쓴다(RecapCodec에 저장하지 않는다).
 */
class ManeuverOnsetTest {
    private class Feed(val mps: Float, val policy: RecapPolicy = RecapPolicy()) {
        var state = RecapEngineState()
        val cards = mutableListOf<ReviewCard>()
        var nextTick = 0L
        fun put(input: DriveInput): RecapStep = recapStep(state, input, policy).also {
            state = it.state
            val merged = mergeReviewCards(cards, it.cards); cards.clear(); cards += merged
        }
        fun advance(t: Long) {
            while (nextTick < t) {
                put(DriveInput.Speed(nextTick, 1_000_000 + nextTick, mps))
                put(DriveInput.Tick(nextTick, 1_000_000 + nextTick)); nextTick += 50
            }
            if (nextTick == t) nextTick += 50
        }
        fun speed(t: Long) { advance(t); put(DriveInput.Speed(t, 1_000_000 + t, mps)) }
        fun angle(t: Long, v: Float) { speed(t); put(DriveInput.Steering(t, 1_000_000 + t, v)) }
        fun signal(t: Long, v: Indicator) { advance(t); put(DriveInput.Signal(t, 1_000_000 + t, v)) }
        fun gear(t: Long, v: DriveGear) { advance(t); put(DriveInput.Gear(t, 1_000_000 + t, v)) }
        fun end(t: Long) { speed(t); put(DriveInput.Tick(t, 1_000_000 + t)); put(DriveInput.Finish(t + 50, 1_000_000 + t + 50)) }
        fun ready() { gear(0, DriveGear.PARK); signal(0, Indicator.OFF); angle(0, 0f); speed(4_000); gear(4_000, DriveGear.DRIVE) }
        /** 켠 [signalAt] 뒤 [leadMs]에 조향이 시작하는 합성 차선 변경. */
        fun laneAfterSignal(signalAt: Long, leadMs: Long): ReviewCard {
            ready(); signal(signalAt, Indicator.LEFT)
            val start = signalAt + leadMs
            val kph = mps * 3.6f
            for (e in 0L..LaneWave.PERIOD_MS step 100) angle(start + e, LaneWave.angleDeg(e, kph))
            angle(start + LaneWave.PERIOD_MS + 100, 0f)
            end(start + 6_000)
            return cards.single { it.kind == ReviewKind.LANE_SIGNALLED }
        }
    }

    private companion object { const val SIGNAL_AT = 8_100L }

    // ---- 엔진 전체: 깜빡이 2.0초 뒤 조향 시작 ----
    private fun leadError(kph: Float): Triple<Float, Float, ReviewCard> {
        val mps = kph / 3.6f
        val card = Feed(mps).laneAfterSignal(SIGNAL_AT, 2_000) // 판정창은 기록 시작 3초 워밍업 뒤에야 열리므로 신호를 늦게 켠다
        val expected = mps * 2.0f
        return Triple(card.summary.signalLeadMeters!!, expected, card)
    }

    @Test fun twoSecondsBeforeSteeringAt60KphIsAboutThirtyThreeMeters() {
        val (lead, expected, card) = leadError(60f)
        // 판정창 격자(0.1초)가 낳는 해상도 안에서 33.3m에 닿는다. 1.7.0(카드 시작까지)은 36.7m라 이 허용 오차(2m)를 넘는다.
        assertEquals("33.3m 기대", expected, lead, 2f)
        assertTrue("카드 시작까지 재던 값(${16.667f * (card.summary.startMs - SIGNAL_AT) / 1000f})보다 짧다", lead < 16.667f * (card.summary.startMs - SIGNAL_AT) / 1000f)
        assertTrue("onset은 카드 시작보다 늦지 않다", card.summary.maneuverOnsetMs!! <= card.summary.startMs)
        assertEquals(card.summary.maneuverOnsetMs!! - SIGNAL_AT, card.summary.signalLeadMs)
    }
    @Test fun leadStaysWithinOneTenthAndAHalfSecondAcrossSpeeds() {
        for (kph in listOf(40f, 60f, 80f, 100f, 120f)) {
            val (lead, expected, _) = leadError(kph)
            val tolerance = kph / 3.6f * 0.15f // ±0.15초 (카드 시작까지 재던 1.7.0은 +0.2초라 100·120km/h에서 넘는다)
            assertEquals("kph=$kph", expected, lead, tolerance)
        }
    }
    @Test fun onsetNeverMovesTheCardStartOrKind() {
        val card = Feed(60f / 3.6f).laneAfterSignal(SIGNAL_AT, 2_000)
        val s = card.summary
        assertEquals(ReviewKind.LANE_SIGNALLED, card.kind)
        assertTrue(s.maneuverOnsetMs!! <= s.startMs)
        assertTrue("onset은 판정창 안(카드 시작보다 최대 1초 앞)", s.startMs - s.maneuverOnsetMs!! <= 1_000)
    }

    // ---- attachSignalLead 단위 ----
    private fun feedWithBlinker(onAt: Long, until: Long): Feed {
        val f = Feed(15f); f.ready(); f.signal(onAt, Indicator.LEFT); f.advance(until); return f
    }
    private fun cardAt(start: Long, onset: Long?): ReviewCard {
        val s = SegmentSummary(start, start + 4_000, 1_000_000 + start, -8f, 8f, 1, 1, 15f, 15f, false, SignalTiming.BEFORE, true,
            leftSignal = DirectionalTiming(SignalTiming.BEFORE), rightSignal = DirectionalTiming(SignalTiming.NONE), maneuverOnsetMs = onset)
        return ReviewCard("onset-$start", s.wallStartMs, ReviewKind.LANE_SIGNALLED, ReviewKind.LANE_SIGNALLED, s)
    }
    @Test fun leadIsMeasuredToTheOnsetWhenPresent() {
        val f = feedWithBlinker(4_100, 10_500)
        val withOnset = attachSignalLead(cardAt(10_000, 9_000), f.state.blinker, f.policy).summary
        assertEquals(15f * 4.9f, withOnset.signalLeadMeters!!, 1f)
        assertEquals(4_900L, withOnset.signalLeadMs)
        val without = attachSignalLead(cardAt(10_000, null), f.state.blinker, f.policy).summary // 감속 후보 등 창 없는 카드: 지금 그대로
        assertEquals(15f * 5.9f, without.signalLeadMeters!!, 1f)
        assertEquals(10_000L, withOnset.startMs); assertEquals(10_000L, without.startMs)
    }
    @Test fun blinkerSwitchedOnAfterTheOnsetGivesZeroNotHidden() {
        val f = feedWithBlinker(9_800, 10_500)
        val s = attachSignalLead(cardAt(10_000, 9_500), f.state.blinker, f.policy).summary // 켠 시각(9.8초)이 onset(9.5초) 뒤, 카드 시작(10초) 앞
        assertEquals(0f, s.signalLeadMeters!!, 0.01f)
        assertEquals(0L, s.signalLeadMs)
    }
    @Test fun missingSpeedBeforeTheOnsetStillHidesTheDistance() {
        val f = Feed(15f); f.ready(); f.signal(4_100, Indicator.LEFT)
        f.put(DriveInput.Speed(6_000, 1_006_000, null)); f.advance(10_500)
        assertNull(attachSignalLead(cardAt(10_000, 9_000), f.state.blinker, f.policy).summary.signalLeadMeters)
    }
    @Test fun demoModeStillNeverProducesADistanceWithAnOnset() {
        val f = Feed(15f, RecapPolicy(demoMissingSpeed = true)); f.ready(); f.signal(4_100, Indicator.LEFT); f.advance(10_500)
        assertNull(attachSignalLead(cardAt(10_000, 9_000), f.state.blinker, f.policy).summary.signalLeadMeters)
    }

    // ---- 판정창: onset은 20% 시작과 같거나 앞서고, 창 밖으로 나가지 않는다 ----
    @Test fun windowCardOnsetIsNotLaterThanStartAndInsideTheWindow() {
        val mps = 60f / 3.6f; val kph = 60f
        var state = DelayedLaneState(); val cards = mutableListOf<ReviewCard>()
        val t0 = 10_000L
        for (t in 0L..30_000 step 100) {
            val angle = LaneWave.angleDeg(t - t0, kph)
            state = delayedLaneStep(state, DriveInput.Steering(t, t, angle), angle, mps, DriveGear.DRIVE).also { cards += it.cards }.state
        }
        cards += delayedLaneStep(state, DriveInput.Finish(30_050, 30_050), 0f, mps, DriveGear.DRIVE).cards
        val c = cards.single()
        val onset = c.summary.maneuverOnsetMs!!
        assertTrue("onset=$onset start=${c.summary.startMs}", onset <= c.summary.startMs)
        assertTrue(c.summary.startMs - onset <= 1_000)
        assertTrue("실제 조향 시작(10초)보다 약 0.5초 이상 늦지 않다 onset=$onset", onset in 9_900L..10_500L)
    }

    // ---- 저장: onset은 메모리 전용 ----
    @Test fun onsetIsNotPersistedButTheLeadIs() {
        val base = cardAt(10_000, 9_000)
        val card = base.copy(summary = base.summary.copy(signalLeadMeters = 70f, signalLeadMs = 4_900))
        val back = RecapCodec.decode(RecapCodec.encode(DayRecap("2026-10-26", cards = listOf(card)))).cards.single()
        assertEquals(70f, back.summary.signalLeadMeters)
        assertEquals(4_900L, back.summary.signalLeadMs)
        assertNull(back.summary.maneuverOnsetMs)
        assertEquals(10_000L, back.summary.startMs)
    }
}
