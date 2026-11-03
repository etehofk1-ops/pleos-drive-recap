package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecapScenarioTest {
    /** The controlled fixtures contain flat scalar controls only; Node separately validates their full JSON. */
    private fun scenario(name: String, demo: Boolean, periodicSpeed: Boolean = true, mirrorLeftLanes: Boolean = false): List<ReviewCard> {
        val directory = listOf(File("scripts"), File("../scripts")).first { File(it, "recap-scenario-$name.json").isFile }
        val json = File(directory, "recap-scenario-$name.json").readText()
        val records = Regex("\\{\\s*\"atMs\"\\s*:\\s*(\\d+),\\s*(?:\"waitForSpeedKph\"\\s*:\\s*\\{[^}]*},\\s*)?\"controls\"\\s*:\\s*\\{([^}]*)}\\s*(?:,\\s*\"caption\"\\s*:\\s*\"[^\"\\r\\n]*\")?\\s*}").findAll(json).map {
            it.groupValues[1].toLong() to Regex("\"(\\w+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?[0-9.]+))").findAll(it.groupValues[2]).map { field ->
                field.groupValues[1] to (field.groupValues[2].ifEmpty { field.groupValues[3] })
            }.toList()
        }.toList()
        require(records.isNotEmpty())
        require(records.size == Regex("\"atMs\"\\s*:").findAll(json).count()) { "Unparsed scenario step" }
        require(records.zipWithNext().all { (a, b) -> a.first <= b.first }) { "Unordered scenario steps" }
        var state = RecapEngineState()
        val cards = mutableListOf<ReviewCard>()
        val policy = RecapPolicy(demoMissingSpeed = demo)
        var speedMps = 0f
        fun put(input: DriveInput) {
            val next = recapStep(state, input, policy); state = next.state
            val merged = mergeReviewCards(cards, next.cards); cards.clear(); cards += merged
        }
        var cursor = 0L
        records.forEach { (time, fields) ->
            while (cursor < time) {
                if (periodicSpeed) put(DriveInput.Speed(cursor, 1_000_000 + cursor, speedMps))
                put(DriveInput.Tick(cursor, 1_000_000 + cursor))
                cursor += 50
            }
            fields.forEach { (key, value) ->
                val wall = 1_000_000 + time
                when (key) {
                    "gear" -> put(DriveInput.Gear(time, wall, if (value == "P") DriveGear.PARK else DriveGear.DRIVE))
                    "steering" -> {
                        val mirror = mirrorLeftLanes && (time in 10_000L..12_450L || time in 24_000L..26_800L)
                        put(DriveInput.Steering(time, wall, -value.toFloat() * if (mirror) -1 else 1)) // Console / SDK inversion, never labelled L/R
                    }
                    "turnSignal" -> put(DriveInput.Signal(time, wall, when (value) { "Off" -> Indicator.OFF; "Blink left" -> Indicator.LEFT; else -> Indicator.RIGHT }))
                    "brake" -> put(DriveInput.Brake(time, wall, value.toFloat() > 0))
                    "speedKph" -> { speedMps = value.toFloat() / 3.6f; put(DriveInput.Speed(time, wall, speedMps)) }
                }
            }
        }
        put(DriveInput.Finish(cursor + 1_000, 1_001_000 + cursor))
        if (System.getenv("RECAP_TRACE") == "1") println("scenario=$name demo=$demo: " + cards.joinToString(";") {
            "${it.kind}/${it.candidate} ${it.summary.startMs}..${it.summary.endMs} timing=${it.summary.timing} flags=${it.uncertainty}"
        })
        return cards
    }

    @Test fun actualScenarioAProducesGoodMissingLateBrakeAndNormalTurn() {
        val cards = scenario("a", false)
        // W16c: the scripted 350ms-step shapes are 2.45s long. The first starts inside the 3.5s warm-up
        // after D, the third is shorter than the 2.5s minimum window; only the middle one is a window.
        assertEquals(3, cards.size)
        assertEquals(2, visibleReviewCards(cards).size)
        assertEquals(1, cards.count { it.kind == ReviewKind.LANE_NO_SIGNAL })
        assertEquals(1, cards.count { it.kind == ReviewKind.HARD_BRAKE })
        assertEquals(1, cards.count { it.kind == ReviewKind.TURN })
    }
    @Test fun scenarioAWithoutSpeedNeverFabricatesAnActualHardBrake() {
        val cards = scenario("a", true)
        assertEquals(5, cards.size)
        assertTrue(cards.all { it.kind == ReviewKind.UNSURE })
        assertEquals(1, cards.count { it.candidate == ReviewKind.LANE_SIGNALLED })
        assertEquals(1, cards.count { it.candidate == ReviewKind.LANE_NO_SIGNAL })
        assertEquals(1, cards.count { it.candidate == ReviewKind.LANE_LATE_SIGNAL })
        assertEquals(1, cards.count { it.candidate == ReviewKind.TURN })
        assertEquals(1, cards.count { it.candidate == ReviewKind.BRAKE_UNVERIFIED && it.decelerationMps2 == null })
    }
    @Test fun scenarioBSeparatesTurnAndCurveFromLaneChange() {
        val cards = scenario("b", false)
        // W16c: a held highway curve is rejected by the end-point baseline (curve entry/exit) instead of
        // becoming a stored hidden CURVE/STEERING card; the slow turn is unchanged.
        assertEquals(listOf(ReviewKind.TURN), cards.map { it.kind })
        assertTrue(visibleReviewCards(cards).isEmpty()) // W14: curve summaries remain stored, no review tile
        assertTrue(cards.all { it.direction == ManeuverDirection.UNKNOWN })
    }
    @Test fun scenarioBWithoutSpeedKeepsBothHypothesesUncertain() {
        val cards = scenario("b", true)
        assertEquals(listOf(ReviewKind.TURN, ReviewKind.CURVE), cards.map { it.candidate })
        assertTrue(cards.all { it.kind == ReviewKind.UNSURE })
    }
    @Test fun demoDProducesFiveVisibleKindsAndPreservesTheHiddenTurn() {
        // Left-on episodes finalize after steering/brake windows; the UI orders by occurrence time.
        val cards = chronologicalCards(scenario("d-demo", false))
        assertEquals(listOf(ReviewKind.LANE_SIGNALLED, ReviewKind.LANE_NO_SIGNAL,
            ReviewKind.LANE_LATE_SIGNAL, ReviewKind.TURN, ReviewKind.BLINKER_LEFT_ON, ReviewKind.HARD_BRAKE), cards.map { it.kind })
        assertEquals(5, visibleReviewCards(cards).size)
        assertFalse(cards.any { it.kind == ReviewKind.UNSURE })
        assertEquals("오늘 차선 변경 3번 · 깜빡이 놓침 1 · 늦게 켬 1 · 급제동 1 · 켜 둔 채 주행 1", recapSummary(cards))
        assertTrue(visibleReviewCards(cards).all { aiCardTitle(it, null).endsWith("규칙 판정") })
        assertEquals(18_000L, cards.single { it.kind == ReviewKind.BLINKER_LEFT_ON }.summary.blinkerOnMs)
        val deceleration = cards.single { it.kind == ReviewKind.HARD_BRAKE }.decelerationMps2!!
        assertTrue("Expected near 9m/s², got $deceleration", deceleration in 8f..10f)
    }
    @Test fun correctingLeftLaneSteeringSignsPreservesCardKindsAndTimes() {
        val corrected = scenario("d-demo", false)
        val oldSigns = scenario("d-demo", false, mirrorLeftLanes = true)
        assertEquals(oldSigns.map { it.kind to it.summary.startMs }, corrected.map { it.kind to it.summary.startMs })
    }
    @Test fun demoDAlsoProducesOneNearNineBrakeWithoutPeriodicSpeedNotifications() {
        val cards = scenario("d-demo", false, periodicSpeed = false)
        // Speed expires after 3s without a packet: missing-lane and long-cruise evidence is absent.
        // The extended late-signal lane now completes before expiry; brake measurement is unchanged.
        assertEquals(listOf(ReviewKind.LANE_SIGNALLED, ReviewKind.LANE_LATE_SIGNAL, ReviewKind.HARD_BRAKE), visibleReviewCards(cards).map { it.kind })
        assertFalse(cards.any { it.kind == ReviewKind.UNSURE })
        val brake = cards.single { it.kind == ReviewKind.HARD_BRAKE }
        assertTrue(brake.decelerationMps2!! in 8f..10f)
    }
}
