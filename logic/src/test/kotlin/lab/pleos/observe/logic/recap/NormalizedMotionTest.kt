package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class NormalizedMotionTest {
    private fun physicalLane(kph: Float, signal: SignalTiming): List<ReviewCard> {
        var s = RecapEngineState(); var cards = emptyList<ReviewCard>()
        fun put(i: DriveInput) { val next = recapStep(s, i); s = next.state; cards = mergeReviewCards(cards, next.cards) }
        put(DriveInput.Gear(0,0,DriveGear.DRIVE)); put(DriveInput.Signal(0,0,Indicator.OFF)); put(DriveInput.Steering(0,0,0f))
        val v = kph / 3.6
        for (t in 0L..18_000 step 50) {
            if (t == 8_000L && signal == SignalTiming.BEFORE) put(DriveInput.Signal(t,t,Indicator.LEFT))
            if (t == 10_500L && signal == SignalTiming.DURING) put(DriveInput.Signal(t,t,Indicator.RIGHT))
            val a = if (t in 10_000L..14_000L) 2 * PI * 3.5 / 16 * sin(2 * PI * (t - 10_000) / 4_000) else 0.0
            val angle = a * LaneWindowPolicy.RATIO * LaneWindowPolicy.WHEELBASE_M / (v*v) * 180 / PI
            put(DriveInput.Speed(t,t,v.toFloat())); put(DriveInput.Steering(t,t,angle.toFloat())); put(DriveInput.Tick(t,t))
        }
        put(DriveInput.Finish(18_050,18_050)) // W16c: the delayed window is confirmed at Finish
        return cards
    }
    @Test fun cleanPhysicalLanesAtEveryRequestedSpeedUseGeometryAndPreserveSignalTiming() {
        for (kph in listOf(40f,60f,80f,100f,120f)) for (signal in listOf(SignalTiming.NONE, SignalTiming.BEFORE, SignalTiming.DURING)) {
            val cards = physicalLane(kph, signal)
            assertEquals("$kph $signal $cards", 1, cards.size)
            val card = cards.single()
            assertEquals("$kph $signal", when(signal) { SignalTiming.NONE -> ReviewKind.LANE_NO_SIGNAL;
                SignalTiming.BEFORE -> ReviewKind.LANE_SIGNALLED; else -> ReviewKind.LANE_LATE_SIGNAL }, card.kind)
            assertNotNull(card.summary.lateral); assertEquals(signal, card.summary.timing)
            if (signal == SignalTiming.DURING) assertEquals(10_500L - card.summary.startMs, card.summary.lateSignalMs)
            if (kph >= 100f) assertTrue(max(abs(card.summary.minAngle), abs(card.summary.maxAngle)) < 6)
        }
    }
    @Test fun sameLateralAccelerationRequiresInverseSquaredSteering() {
        val a = estimatedLateralAcceleration(8.0, 60.0 / 3.6)
        assertEquals(a, estimatedLateralAcceleration(2.0, 120.0 / 3.6), 1e-8)
    }
    @Test fun baselineFreezesDuringSegmentAndFilterHoldsUnchangedInput() {
        var s = LateralFilter(0, heldAngle = 1f, heldSpeed = 20f)
        s = advanceLateral(s, 1f, 20f, 8_000, true).state // clock gap resets, no artificial integration
        repeat(80) { s = advanceLateral(s, 1f, 20f, 8_100L + it * 100, true).state }
        assertTrue(s.baselineDeg > 0.6)
        val bias = s.baselineDeg
        s = advanceLateral(s, 15f, 20f, 16_100, false).state
        val held = advanceLateral(s, 15f, 20f, 17_100, false)
        assertEquals(bias, held.state.baselineDeg, 0.0); assertTrue(held.impulse > 0)
    }
    @Test fun exactHeldIntegrationDoesNotDependOnPacketRate() {
        val initial = LateralFilter(0, heldAngle = 12f, heldSpeed = 20f)
        val whole = advanceLateral(initial, 12f, 20f, 1_000, false)
        var filter = initial; var m = LateralSummary()
        for (t in 50L..1_000L step 50) { val next = advanceLateral(filter, 12f, 20f, t, false); filter = next.state; m = integrateLateral(m, next) }
        assertEquals(whole.state.acceleration, filter.acceleration, 1e-8)
        assertEquals(whole.displacement, m.displacementM, 1e-8)
    }
    @Test fun geometryRejectsCurvesRepeatedWeavingAndInsufficientDisplacement() {
        val m = LateralSummary(0.01, 0.1, 0.2, 0.2, 3.5 + 0.01 * 80 / 2, 80.0, -1.0, 1.0)
        val s = SegmentSummary(0, 4_000, 0, -3f, 3f, 1, 4, 30f, 30f, false, SignalTiming.NONE, true, lateral = m)
        assertTrue(normalizedLaneShape(s)) // raw-angle 6°/single reversal no longer gate normalized shape
        assertFalse(normalizedLaneShape(s.copy(lateral = m.copy(headingRad = 0.2))))
        assertFalse(normalizedLaneShape(s.copy(lateral = m.copy(absoluteYawRad = 0.8))))
        assertFalse(normalizedLaneShape(s.copy(lateral = m.copy(displacementM = 0.1))))
        assertFalse(normalizedLaneShape(s.copy(endMs = 2_499)))
        assertFalse(normalizedLaneShape(s.copy(endMs = 8_001)))
    }
    @Test fun distanceHeadingSpeedAndDurationPolicyBoundariesAreInclusive() {
        val m = LateralSummary(0.0,0.1,0.2,0.0,1.75,80.0,-1.0,1.0)
        val s = SegmentSummary(0,2_500,0,-12f,12f,1,1,LateralThresholds.MIN_SPEED_MPS,
            LateralThresholds.MIN_SPEED_MPS,false,SignalTiming.NONE,true,lateral=m)
        assertEquals(ReviewKind.LANE_NO_SIGNAL,classifySegment(s)!!.kind)
        assertFalse(normalizedLaneShape(s.copy(lateral=m.copy(displacementM=1.74999))))
        assertTrue(normalizedLaneShape(s.copy(endMs=8_000,lateral=m.copy(displacementM=5.5))))
        assertFalse(normalizedLaneShape(s.copy(lateral=m.copy(displacementM=5.50001))))
        assertTrue(returnedHeading(m.copy(headingRad=0.06)))
        assertFalse(returnedHeading(m.copy(headingRad=0.06001)))
        assertEquals(ReviewKind.UNSURE,classifySegment(s.copy(minSpeedMps=LateralThresholds.MIN_SPEED_MPS-0.001f))!!.kind)
        val weak=s.copy(minAngle=-3f,maxAngle=3f,lateral=m.copy(displacementM=2.5))
        assertTrue(normalizedLaneShape(weak)); assertFalse(normalizedLaneShape(weak.copy(lateral=weak.lateral!!.copy(displacementM=2.49999))))
    }
    @Test fun aTenMinuteStreamKeepsTransientStateBounded() {
        var s=RecapEngineState()
        fun put(i:DriveInput){s=recapStep(s,i).state;assertTrue(s.recentDecelerations.size<=32);assertTrue(s.inputTimes.size<=9)}
        put(DriveInput.Gear(0,0,DriveGear.DRIVE));put(DriveInput.Signal(0,0,Indicator.OFF));put(DriveInput.Brake(0,0,true))
        put(DriveInput.Steering(0,0,0f))
        for(t in 0L..600_000 step 50){put(DriveInput.Speed(t,t,20f-(t%1_000)/200f));put(DriveInput.Tick(t,t))}
        // W16c: >=25km/h motion lives in the delayed-lane ring buffer, not in the old lateral filter.
        assertTrue(s.delayedLane.points.size <= LaneWindowPolicy.CAPACITY)
        assertTrue(s.delayedLane.signals.size <= LaneWindowPolicy.SIGNAL_CAPACITY)
        assertTrue(s.delayedLane.candidates.size <= LaneWindowPolicy.CANDIDATE_CAPACITY)
        assertEquals(600_000L, s.delayedLane.feedAt)
    }
    @Test fun geometryAndFeedbackRoundTripWithoutRawHistory() {
        val m = LateralSummary(0.01, 0.1, 0.2, 0.2, 4.0, 80.0, -1.0, 1.0)
        val s = SegmentSummary(0, 4_000, 0, -3f, 3f, 1, 1, 30f, 30f, false, SignalTiming.NONE, true, lateral = m)
        val day = DayRecap("2026-10-24", cards = listOf(classifySegment(s)!!.copy(feedback = Feedback.YES)))
        assertEquals(day, RecapCodec.decode(RecapCodec.encode(day)))
    }
    @Test fun navigationCanAssistSmallRawAnglesWithoutChangingTurnRules() {
        val c = physicalLane(120f, SignalTiming.NONE).single()
        val observations = (8_000L..17_000L step 1_000).map {
            NavigationSnapshot(it, true, "TURN_STRAIGHT", 200.0)
        }
        assertEquals(NavigationLabel.ASSISTED, fuseNavigation(c, observations).navigation)
        val conflict = fuseNavigation(c, listOf(NavigationSnapshot(c.summary.startMs, true, "TURN_RIGHT", 20.0)))
        assertEquals(NavigationLabel.CONFLICT, conflict.navigation); assertEquals(ReviewKind.UNSURE, conflict.kind)
    }
    @Test fun aRealClockGapDoesNotProduceConfidentIntegratedGeometry() {
        var s = RecapEngineState(); var cards = emptyList<ReviewCard>()
        fun put(i:DriveInput){val next=recapStep(s,i);s=next.state;cards=mergeReviewCards(cards,next.cards)}
        put(DriveInput.Gear(0,0,DriveGear.DRIVE)); put(DriveInput.Signal(0,0,Indicator.OFF)); put(DriveInput.Steering(0,0,0f))
        for(t in 0L..5_000 step 100) { put(DriveInput.Speed(t,t,20f)); put(DriveInput.Tick(t,t)) }
        put(DriveInput.Steering(5_000,5_000,12f)); put(DriveInput.Tick(5_500,5_500))
        put(DriveInput.Speed(11_500,11_500,20f)); put(DriveInput.Finish(11_600,11_600))
        // W16c: a half-recorded manoeuvre across a >5s clock gap is dropped; it never becomes a confident lane card.
        assertTrue(cards.none { it.kind in setOf(ReviewKind.LANE_NO_SIGNAL, ReviewKind.LANE_SIGNALLED, ReviewKind.LANE_LATE_SIGNAL) })
    }
    @Test fun populatedVersionThreeFileStillReadsExactly() {
        val s = SegmentSummary(0,4_000,0,-14f,14f,1,1,15f,15f,false,SignalTiming.NONE,true)
        val day = DayRecap("2026-10-24", cards = listOf(classifySegment(s)!!.copy(demoNavigation=true)))
        val bytes = RecapCodec.encode(day).dropLast(1 + 4).toByteArray().apply { this[7] = 3 } // + v7 four absent nullable fields
        assertEquals(day, RecapCodec.decode(bytes))
    }
    @Test fun shortSpeedSpikeDoesNotPassFourHundredMillisecondAverage() {
        var state = RecapEngineState(); var cards = emptyList<ReviewCard>()
        fun put(i: DriveInput) { val next = recapStep(state,i); state=next.state; cards=mergeReviewCards(cards,next.cards) }
        put(DriveInput.Gear(0,0,DriveGear.DRIVE)); put(DriveInput.Brake(0,0,true))
        for(t in 0L..1_000 step 50) put(DriveInput.Speed(t,t, if(t==100L) 10f else 20f))
        assertTrue(cards.isEmpty())
    }
    @Test fun decelerationNeedsFourHundredMillisecondsAndKeepsThreeAsPolicyThreshold() {
        assertEquals(3f, RecapThresholds.BRAKE_MIN_DECEL, 0f)
        for(rate in listOf(2.8f,3f,3.8f,6f)) {
            var state=RecapEngineState(); var cards=emptyList<ReviewCard>()
            fun put(i:DriveInput){val next=recapStep(state,i);state=next.state;cards=mergeReviewCards(cards,next.cards)}
            put(DriveInput.Gear(0,0,DriveGear.DRIVE));put(DriveInput.Brake(0,0,true))
            for(t in 0L..1_000 step 50){
                put(DriveInput.Speed(t,t,20f-rate*t/1_000))
                if(t<400)assertTrue(cards.isEmpty())
            }
            assertEquals("rate=$rate",rate>=3f,cards.any{it.kind==ReviewKind.HARD_BRAKE})
        }
    }
    @Test fun presentationHidesCurveAndHighSpeedSteeringWithoutMutatingStoredCounts() {
        val s = SegmentSummary(0, 4_000, 0, -3f, 3f, 1, 4, 30f, 30f, false, SignalTiming.NONE, true)
        val steering = classifySegment(s)!!
        val curve = steering.copy(kind = ReviewKind.CURVE, candidate = ReviewKind.CURVE)
        val stored = listOf(steering, curve)
        assertTrue(visibleReviewCards(stored).isEmpty()); assertEquals(2, stored.size)
        assertEquals(ReviewCounts(), reviewCounts(stored))
        assertEquals(listOf(steering), visibleReviewCards(listOf(steering.copy(summary = s.copy(speedMissing = true)))).map { it.copy(summary = s) })
    }

    @Test fun scenarioGKeepsSixVisibleCardsThroughReducerAndFusion() {
        // W16c: scenario G (full-size lane changes). g-soak keeps the old 350ms-step shapes, whose third
        // (late-indicator) lane change is shorter than the 2.5s minimum window, so it no longer fits here.
        val dir = listOf(File("scripts"), File("../scripts")).first { File(it, "recap-scenario-g.json").isFile }
        val json = File(dir, "recap-scenario-g.json").readText()
        val blocks = Regex("\"atMs\"\\s*:\\s*(\\d+)([\\s\\S]*?)(?=\"atMs\"|$)").findAll(json).toList()
        var engine = RecapEngineState(); var fusion = NavigationFusionState(); var cards = emptyList<ReviewCard>()
        var speed = 0f; var cursor = 0L; var snapshot: NavigationSnapshot? = null
        fun put(input: DriveInput) {
            val step = recapStep(engine, input); engine = step.state
            val nav = navigationFusionStep(fusion, input.atMs, step.cards.map { it.copy(demoNavigation = true) })
            fusion = nav.state; cards = mergeReviewCards(cards, nav.cards)
        }
        fun tick(t: Long) {
            snapshot?.let { n -> if (t % 1_000 == 0L) {
                val nav = navigationFusionStep(fusion, t, snapshot = n.copy(receivedAtMs = t))
                fusion = nav.state; cards = mergeReviewCards(cards, nav.cards)
            } }
            put(DriveInput.Speed(t, 1_000_000 + t, speed)); put(DriveInput.Tick(t, 1_000_000 + t))
        }
        for (block in blocks) {
            val t = block.groupValues[1].toLong(); val body = block.groupValues[2]
            while (cursor < t) { tick(cursor); cursor += 50 }
            val controls = Regex("\"controls\"\\s*:\\s*\\{([^}]*)}").find(body)!!.groupValues[1]
            for (field in Regex("\"(\\w+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?[0-9.]+))").findAll(controls)) {
                val value = field.groupValues[2].ifEmpty { field.groupValues[3] }; val wall = 1_000_000 + t
                when (field.groupValues[1]) {
                    "gear" -> put(DriveInput.Gear(t, wall, if (value == "P") DriveGear.PARK else DriveGear.DRIVE))
                    "speedKph" -> { speed = value.toFloat() / 3.6f; put(DriveInput.Speed(t, wall, speed)) }
                    "steering" -> put(DriveInput.Steering(t, wall, -value.toFloat()))
                    "brake" -> put(DriveInput.Brake(t, wall, value.toFloat() > 0))
                    "turnSignal" -> put(DriveInput.Signal(t, wall, when (value) { "Off" -> Indicator.OFF; "Blink left" -> Indicator.LEFT; else -> Indicator.RIGHT }))
                }
            }
            Regex("\"navi\"\\s*:\\s*\\{([^}]*)}").find(body)?.groupValues?.get(1)?.let { n ->
                val action = Regex("\"action\"\\s*:\\s*\"([^\"]+)\"").find(n)?.groupValues?.get(1)
                val distance = Regex("\"distanceMeters\"\\s*:\\s*([0-9.]+)").find(n)?.groupValues?.get(1)?.toDouble()
                snapshot = NavigationSnapshot(t, n.contains(Regex("\"guiding\"\\s*:\\s*true")), action, distance, origin = NavigationOrigin.DEMO)
                val nav = navigationFusionStep(fusion, t, snapshot = snapshot); fusion = nav.state; cards = mergeReviewCards(cards, nav.cards)
            }
        }
        for (t in cursor..cursor + 5_000 step 50) tick(t)
        put(DriveInput.Finish(cursor + 5_050, 1_000_000 + cursor + 5_050)) // delayed windows are confirmed at Finish
        if (System.getenv("RECAP_TRACE") == "1") println(cards.joinToString("\n") { "${it.kind} ${it.summary.durationMs} ${it.summary.lateral} y=${it.summary.lateral?.let(::lateralDisplacement)}" })
        assertEquals(listOf(ReviewKind.LANE_SIGNALLED, ReviewKind.LANE_NO_SIGNAL, ReviewKind.LANE_LATE_SIGNAL,
            ReviewKind.TURN_SIGNALLED, ReviewKind.TURN_NO_SIGNAL, ReviewKind.HARD_BRAKE), visibleReviewCards(cards).map { it.kind })
    }
}
