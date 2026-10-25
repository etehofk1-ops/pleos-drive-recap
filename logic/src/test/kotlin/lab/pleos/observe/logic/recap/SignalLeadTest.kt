package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** 1.7.0 A: 깜빡이를 켠 시각부터 조작 시작까지 속도를 적분한 거리(권장 거리 근거). */
class SignalLeadTest {
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
        fun lane(start: Long = 8_500) { wave(start); end(start + 6_000) }
    }

    /** 판정과 무관하게 거리 적분만 검증하려는 합성 카드(조작 시작 = [start]). */
    private fun laneCardAt(start: Long, timing: SignalTiming = SignalTiming.BEFORE): ReviewCard {
        val s = SegmentSummary(start, start + 4_000, 1_000_000 + start, -8f, 8f, 1, 1, 15f, 15f, false, timing, true,
            leftSignal = DirectionalTiming(timing), rightSignal = DirectionalTiming(SignalTiming.NONE))
        return ReviewCard("lead-$start", s.wallStartMs, ReviewKind.LANE_SIGNALLED, ReviewKind.LANE_SIGNALLED, s)
    }
    private fun lead(f: Feed, start: Long): SegmentSummary =
        attachSignalLead(laneCardAt(start), f.state.blinker, f.policy).summary

    @Test fun constantSpeedLeadIsSpeedTimesElapsed() {
        val f = Feed(); f.ready(); f.signal(4_100, Indicator.LEFT); f.tick(10_500)
        val s = lead(f, 10_000)
        assertEquals(15f * 5.9f, s.signalLeadMeters!!, 1f)
        assertEquals(5_900L, s.signalLeadMs)
        assertEquals(s.signalLeadMeters, s.leftSignal!!.leadMeters)
        assertNull(s.rightSignal!!.leadMeters)
    }
    @Test fun acceleratingLeadIntegratesTheSpeedSeries() {
        val f = Feed(); f.ready(); f.signal(5_000, Indicator.LEFT)
        for (t in 5_000L..9_500 step 100) f.speed(t, 10f + (t - 5_000) / 400f) // 10 -> 20 m/s over 4 s, then 20+
        // mean 15 m/s over 4 s = 60 m, not 20 m/s * 4 s (end speed) and not 10 m/s * 4 s (start speed)
        assertEquals(60f, lead(f, 9_000).signalLeadMeters!!, 2f)
    }
    @Test fun missingSpeedBetweenOnAndStartHidesTheDistance() {
        val f = Feed(); f.ready(); f.signal(5_000, Indicator.LEFT)
        f.speed(6_000, null); f.tick(9_500); f.speed(9_500, 15f); f.tick(10_000)
        assertNull(lead(f, 9_000).signalLeadMeters)
        assertNull(lead(f, 9_000).signalLeadMs)
        // 끊김 뒤에 새로 켠 깜빡이는 다시 잰다
        f.signal(10_200, Indicator.OFF); f.signal(14_000, Indicator.LEFT); f.tick(18_500)
        assertEquals(15f * 4f, lead(f, 18_000).signalLeadMeters!!, 1f)
    }
    @Test fun speedSilenceLongerThanTheFreshWindowHidesTheDistance() {
        val f = Feed(); f.ready(); f.signal(4_100, Indicator.LEFT)
        f.advance(4_150); f.nextTick = 1_000_000 // 이후 속도 표본 없이 Tick만
        for (t in 4_150L..9_000 step 50) f.put(DriveInput.Tick(t, 1_000_000 + t))
        f.put(DriveInput.Speed(9_000, 1_009_000, 15f))
        assertNull(lead(f, 8_000).signalLeadMeters)
    }
    @Test fun unknownSpeedAtTheMomentOfSwitchingOnHidesTheDistance() {
        val f = Feed(null); f.ready(); f.signal(4_100, Indicator.LEFT); f.speed(5_000, 15f); f.tick(9_000)
        assertNull(lead(f, 8_000).signalLeadMeters)
    }
    @Test fun leadOnlyForSignalledTimingAndOnlyTheSignalledSide() {
        val f = Feed(); f.ready(); f.signal(4_100, Indicator.LEFT); f.tick(10_500)
        assertNull(attachSignalLead(laneCardAt(10_000, SignalTiming.DURING), f.state.blinker, f.policy).summary.signalLeadMeters)
        val rightOnly = attachSignalLead(laneCardAt(10_000).let { c ->
            c.copy(summary = c.summary.copy(leftSignal = DirectionalTiming(SignalTiming.NONE), rightSignal = DirectionalTiming(SignalTiming.BEFORE))) },
            f.state.blinker, f.policy).summary
        assertNull(rightOnly.signalLeadMeters) // 오른쪽 깜빡이는 켠 적이 없다
    }
    @Test fun knownConventionPicksTheSideOfTheManeuver() {
        val f = Feed(); f.ready(); f.signal(4_100, Indicator.LEFT); f.tick(10_500)
        val both = laneCardAt(10_000).let { c -> c.copy(summary = c.summary.copy(rightSignal = DirectionalTiming(SignalTiming.BEFORE))) }
        val left = attachSignalLead(both, f.state.blinker, RecapPolicy(convention = SteeringConvention.POSITIVE_LEFT)).summary
        assertEquals(15f * 5.9f, left.signalLeadMeters!!, 1f)
        val right = attachSignalLead(both, f.state.blinker, RecapPolicy(convention = SteeringConvention.POSITIVE_RIGHT)).summary
        assertNull(right.signalLeadMeters)
    }
    @Test fun demoMissingSpeedNeverProducesADistance() {
        val f = Feed(15f, RecapPolicy(demoMissingSpeed = true)); f.ready(); f.signal(4_100, Indicator.LEFT); f.tick(10_500)
        assertNull(lead(f, 10_000).signalLeadMeters)
    }
    @Test fun flickerShorterThanTheLookbackKeepsTheFirstSwitchOnTime() {
        val f = Feed(); f.ready(); f.signal(4_100, Indicator.LEFT); f.signal(5_000, Indicator.OFF)
        f.signal(5_700, Indicator.LEFT); f.tick(10_500)
        assertEquals(5_900L, lead(f, 10_000).signalLeadMs) // 점멸 사이의 짧은 꺼짐은 이어 본다
    }

    @Test fun detectedLaneChangeCardCarriesTheLeadMeasuredToTheManeuverOnset() {
        // 1.7.1: the lead ends at the manoeuvre onset (maneuverOnsetMs, 10% of the peak residual), not at the card start (20%).
        val f = Feed(); f.ready(); f.signal(4_100, Indicator.LEFT); f.lane(10_000)
        val c = f.cards.single { it.kind == ReviewKind.LANE_SIGNALLED }
        val onset = c.summary.maneuverOnsetMs!!
        assertTrue(onset <= c.summary.startMs)
        assertEquals(15f * (onset - 4_100) / 1_000f, c.summary.signalLeadMeters!!, 1.5f)
        assertEquals(onset - 4_100, c.summary.signalLeadMs)
    }
    @Test fun laneCardsWithoutEarlySignalHaveNoLead() {
        val f = Feed(); f.ready(); f.lane(10_000)
        assertNull(f.cards.single().summary.signalLeadMeters)
    }

    // ---- 기준 거리(30m / 고속 100m)와 문구 ----
    private fun textCard(meters: Float?, ms: Long?, kind: ReviewKind = ReviewKind.LANE_SIGNALLED): ReviewCard {
        val s = SegmentSummary(10_000, 14_000, 1_000_000, -8f, 8f, 1, 1, 15f, 15f, false, SignalTiming.BEFORE, true,
            signalLeadMeters = meters, signalLeadMs = ms)
        return ReviewCard("t", 1, kind, kind, s)
    }
    private fun leadAt(kph: Float, seconds: Float) = textCard(kph / 3.6f * seconds, (seconds * 1_000).toLong())

    @Test fun referenceIsThirtyMetersBelowEightyKphAndHundredAtOrAbove() {
        assertEquals(30f, signalLeadReferenceMeters(leadAt(79f, 4f).summary)!!, 0f)
        assertEquals(100f, signalLeadReferenceMeters(leadAt(80f, 4f).summary)!!, 0f)
        assertEquals(100f, signalLeadReferenceMeters(leadAt(110f, 4f).summary)!!, 0f)
        assertNull(signalLeadReferenceMeters(textCard(null, null).summary))
        assertEquals(30f, signalLeadReferenceMeters(textCard(20f, 0L).summary)!!, 0f) // 경과 시간을 모르면 기본 기준
    }
    @Test fun wordingSaysEarlyEnoughAtOrAboveTheReference() {
        val t = signalLeadText(textCard(35f, 3_000))!! // 평균 42km/h -> 기준 30m
        assertTrue(t, t.contains("약 35 m")); assertTrue(t, t.contains("충분히 일찍 켰어요"))
        assertTrue(signalLeadText(textCard(30f, 3_000))!!.contains("충분히 일찍 켰어요")) // 경계는 충분
    }
    @Test fun wordingSaysShorterThanTheRecommendedDistanceBelowTheReference() {
        val low = signalLeadText(textCard(20f, 3_000))!!
        assertTrue(low, low.contains("약 20 m")); assertTrue(low, low.contains("권장 거리(30m/고속 100m)보다 짧아요"))
        val fast = signalLeadText(leadAt(100f, 3f).let { it.copy(summary = it.summary.copy(signalLeadMeters = 80f)) })!!
        assertTrue(fast, fast.contains("권장 거리(30m/고속 100m)보다 짧아요")) // 고속 기준 100m: 80m는 짧다
        assertTrue(signalLeadText(leadAt(100f, 4f).let { it.copy(summary = it.summary.copy(signalLeadMeters = 110f)) })!!.contains("충분히"))
    }
    @Test fun metersAreRoundedToFive() {
        assertTrue(signalLeadText(textCard(87f, 6_000))!!.contains("약 85 m"))
        assertTrue(signalLeadText(textCard(88f, 6_000))!!.contains("약 90 m"))
        assertTrue(signalLeadText(textCard(1f, 6_000))!!.contains("약 0 m"))
    }
    @Test fun wordingNeverUsesLegalJudgementWordsAndAppearsInEvidence() {
        val c = textCard(20f, 3_000)
        val all = signalLeadText(c)!! + recapEvidence(c) + recapTitle(c)
        assertFalse(all.contains("위반")); assertFalse(all.contains("불법")); assertFalse(all.contains("과태료"))
        assertTrue(recapEvidence(c).contains("권장 거리"))
        assertTrue(recapEvidence(textCard(40f, 3_000, ReviewKind.TURN_SIGNALLED)).contains("충분히 일찍 켰어요"))
        // 거리가 없는 카드(옛 기록 포함)는 문구가 늘지 않는다
        assertNull(signalLeadText(textCard(null, null)))
        assertFalse(recapEvidence(textCard(null, null)).contains("깜빡이를 켜고"))
        // 먼저 켠 카드 말고는 붙이지 않는다
        assertNull(signalLeadText(textCard(20f, 3_000, ReviewKind.LANE_LATE_SIGNAL)))
    }

    @Test fun turnFusionTakesTheLeadOfTheNavigationSide() {
        val s = SegmentSummary(10_000, 14_000, 1_000_000, -80f, 80f, 1, 1, 2f, 4f, false, SignalTiming.BEFORE, true,
            leftSignal = DirectionalTiming(SignalTiming.BEFORE, null, 12f, 2_000),
            rightSignal = DirectionalTiming(SignalTiming.BEFORE, null, 40f, 6_000), signalLeadMeters = 12f, signalLeadMs = 2_000)
        val card = ReviewCard("turn", 1, ReviewKind.TURN, ReviewKind.TURN, s)
        fun fused(action: String) = fuseNavigation(card, (9_000L..15_000 step 500).map { NavigationSnapshot(it, true, action, 20.0) })
        val right = fused("TURN_RIGHT")
        assertEquals(ReviewKind.TURN_SIGNALLED, right.kind)
        assertEquals(40f, right.summary.signalLeadMeters!!, 0f); assertEquals(6_000L, right.summary.signalLeadMs)
        assertEquals(12f, fused("TURN_LEFT").summary.signalLeadMeters!!, 0f)
    }

    // ---- 저장 ----
    @Test fun leadFieldsRoundTripThroughTheCodec() {
        val s = SegmentSummary(10_000, 14_000, 1_000_000, -8f, 8f, 1, 1, 15f, 15f, false, SignalTiming.BEFORE, true,
            leftSignal = DirectionalTiming(SignalTiming.BEFORE, null, 63.5f, 4_200), rightSignal = DirectionalTiming(SignalTiming.NONE),
            signalLeadMeters = 63.5f, signalLeadMs = 4_200)
        val day = DayRecap("2026-10-25", cards = listOf(ReviewCard("a", 1, ReviewKind.LANE_SIGNALLED, ReviewKind.LANE_SIGNALLED, s)))
        assertEquals(day, RecapCodec.decode(RecapCodec.encode(day)))
    }
    @Test fun recordsSavedByVersionOnePointSixDecodeWithNullLead() {
        val day = RecapCodec.decode(legacyV6())
        val c = day.cards.single()
        assertEquals(ReviewKind.LANE_SIGNALLED, c.kind); assertEquals(SignalTiming.BEFORE, c.summary.timing)
        assertNull(c.summary.signalLeadMeters); assertNull(c.summary.signalLeadMs)
        assertNull(c.summary.blinkerOnMs); assertNull(c.summary.blinkerOnMeters)
        assertEquals(DirectionalTiming(SignalTiming.BEFORE), c.summary.leftSignal)
        assertNull(signalLeadText(c))
        // 새 버전으로 다시 저장해도 같은 카드
        assertEquals(day, RecapCodec.decode(RecapCodec.encode(day)))
    }

    /** 1.6.0이 쓰던 스키마(VERSION 6) 그대로. */
    private fun legacyV6(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x52454350); out.writeInt(6); out.writeUTF("2026-10-25"); out.writeLong(10_000); out.writeLong(0); out.writeInt(1)
            out.writeUTF("1-2-steer"); out.writeLong(1); out.writeUTF("LANE_SIGNALLED"); out.writeUTF("LANE_SIGNALLED")
            out.writeUTF("UNKNOWN"); out.writeUTF("NONE"); out.writeInt(0)
            out.writeBoolean(false)
            out.writeLong(2_000); out.writeLong(6_000); out.writeLong(1_002_000)
            out.writeFloat(-8f); out.writeFloat(8f); out.writeInt(1); out.writeInt(1)
            out.writeBoolean(true); out.writeFloat(15f); out.writeBoolean(true); out.writeFloat(15f); out.writeBoolean(false)
            out.writeUTF("BEFORE"); out.writeBoolean(true)
            out.writeBoolean(false)
            out.writeBoolean(false); out.writeBoolean(false); out.writeBoolean(false)
            repeat(2) { out.writeBoolean(true); out.writeUTF("BEFORE"); out.writeBoolean(false) }
            out.writeUTF("ESTIMATED"); out.writeUTF(""); out.writeBoolean(false)
            out.writeBoolean(false)
        }
        bytes.toByteArray()
    }
}
