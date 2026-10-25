package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test

class DemoNavigationTest {
    private val run = "11111111-1111-1111-1111-111111111111"
    private fun json(action: String = "TURN_RIGHT", distance: String = "50", seq: Long = 1) =
        """{"guiding":true,"action":"$action","distanceMeters":$distance,"runId":"$run","sequence":$seq}"""
    private fun frame() = checkNotNull(parseDemoNavigation(json()))
    private fun lane() = checkNotNull(classifySegment(SegmentSummary(10_000, 12_500, 1_000_000, -14f, 11f, -1, 1,
        35f / 3.6f, 35f / 3.6f, false, SignalTiming.NONE, true)))

    @Test fun parsesOnlyClosedScalarJsonAndExactSdkNames() {
        val f = frame(); assertEquals("TURN_RIGHT", f.action); assertEquals(50.0, f.distanceMeters!!, 0.0)
        assertEquals(1L, f.sequence)
        assertNotNull(parseDemoNavigation(" \n" + json("TURN_STRAIGHT", "2e2") + "\t"))
        for (name in DEMO_TURN_TYPES) assertNotNull(parseDemoNavigation(json(name)))
    }
    @Test fun inactiveAndUnknownAreNotInventedStraightInstructions() {
        val inactive = json().replace("true", "false").replace("\"TURN_RIGHT\"", "null").replace(":50", ":null")
        assertFalse(parseDemoNavigation(inactive)!!.guiding)
        assertNotNull(parseDemoNavigation(json("TURN_UNKNOWN", "null")))
        assertNull(parseDemoNavigation(json().replace("true", "false")))
        assertNull(parseDemoNavigation(json().replace("\"TURN_RIGHT\"", "null")))
    }
    @Test fun rejectsMalformedDuplicateNestedEscapedTrailingAndOversizedJson() {
        for (bad in listOf("{}", json().dropLast(1), json() + "x", json().dropLast(1) + ",}",
            json().replace("true", "\"true\""), json().replace(":50", ":[50]"),
            json().replace("TURN_RIGHT", "TURN_\\u0052IGHT"), json().replace("\"sequence\":1", "\"sequence\":1,\"sequence\":2"),
            " ".repeat(1025) + json())) assertNull(bad, parseDemoNavigation(bad))
    }
    @Test fun rejectsLocationVinRouteAndUntrustedExternalTimestamps() {
        for (key in listOf("latitude", "longitude", "VIN", "route", "receivedAtMs", "passageAtMs", "origin"))
            assertNull(parseDemoNavigation(json().dropLast(1) + ",\"$key\":0}"))
    }
    @Test fun rejectsBadEnumsNumbersRunsAndIncompleteFields() {
        for (bad in listOf(json("RIGHT"), json("TURN_RIGHT", "-1"), json(distance = "NaN"),
            json(distance = "1e999"), json(distance = "10000001"), json(distance = "\"50\""),
            json().replace(run, "private-free-text"), json().replace("\"sequence\":1", "\"sequence\":1.5"),
            json().replace("\"sequence\":1", "\"sequence\":9007199254740992"),
            json().replace("\"guiding\":true,", ""), json().replace(",\"sequence\":1", "")))
            assertNull(bad, parseDemoNavigation(bad))
    }
    @Test fun snapshotUsesReceiverMonotonicTimeAndExpiresStrictlyAfterThreeSeconds() {
        val state = acceptDemoNavigation(DemoNavigationState(), frame(), 1000, true)!!
        assertEquals(1000L, demoNavigationSnapshot(state, 4000)!!.receivedAtMs)
        assertEquals(NavigationOrigin.DEMO, demoNavigationSnapshot(state, 1000)!!.origin)
        assertNull(demoNavigationSnapshot(state, 4001)); assertNull(demoNavigationSnapshot(state, 999))
        assertTrue(expireDemoNavigation(state, 4001).expired)
        assertEquals(expireDemoNavigation(state, 4001), expireDemoNavigation(expireDemoNavigation(state, 4001), 5000))
    }
    @Test fun noInputOrNewSessionHasNoBorrowedSnapshot() {
        assertNull(demoNavigationSnapshot(DemoNavigationState(), 0))
        assertNull(demoNavigationSnapshot(DemoNavigationState(), 100_000))
    }
    @Test fun duplicateOutOfOrderAndOldClockCannotRenewLease() {
        val state = acceptDemoNavigation(DemoNavigationState(), frame(), 1000, true)!!
        assertNull(acceptDemoNavigation(state, frame(), 3500, true))
        assertNull(acceptDemoNavigation(state, frame().copy(sequence = 0), 3500, true))
        assertNull(acceptDemoNavigation(state, frame().copy(sequence = 2), 999, true))
        assertNull(demoNavigationSnapshot(state, 4001))
    }
    @Test fun heartbeatRenewsButExpiryAndGuidanceChangesSplitEvidenceEpochs() {
        val first = acceptDemoNavigation(DemoNavigationState(), frame(), 0, true)!!
        val next = acceptDemoNavigation(first, frame().copy(sequence = 2), 2000, false)!!
        assertEquals(first.epoch, next.epoch); assertNotNull(demoNavigationSnapshot(next, 5000))
        val expired = expireDemoNavigation(next, 5001)
        val renewed = acceptDemoNavigation(expired, frame().copy(sequence = 3), 5002, false)!!
        assertTrue(renewed.epoch > first.epoch)
        val ended = acceptDemoNavigation(renewed, frame().copy(sequence = 4, guiding = false, action = null, distanceMeters = null), 6000, false)!!
        assertTrue(ended.epoch > renewed.epoch)
    }
    @Test fun differentReplayRunRequiresPark() {
        val first = acceptDemoNavigation(DemoNavigationState(), frame(), 0, true)!!
        val other = frame().copy(runId = "22222222-2222-2222-2222-222222222222", sequence = 0)
        assertNull(acceptDemoNavigation(first, other, 1000, false))
        assertNotNull(acceptDemoNavigation(first, other, 1000, true))
    }
    @Test fun labelsCannotPassDemoAsActualAfterToggleOff() {
        val c = lane().copy(demoNavigation = true, navigation = NavigationLabel.ASSISTED)
        assertEquals("내비 보조(모의)", navigationLabel(c))
        assertEquals("내비 확인(모의)", navigationLabel(c.copy(navigation = NavigationLabel.CONFIRMED)))
        assertEquals("내비 확인", navigationLabel(c.copy(navigation = NavigationLabel.CONFIRMED, demoNavigation = false)))
        assertTrue(aiCardTitle(lane().copy(demoNavigation = true), null).contains("모의"))
        assertEquals("시연용 모의 내비 입력 사용 중", demoNavigationBadge(true, emptyList()))
        assertEquals("시연용 모의 내비 판정 포함", demoNavigationBadge(false, listOf(c)))
        assertNull(demoNavigationBadge(false, listOf(lane())))
    }
    @Test fun persistedCardContainsOnlyProvenanceNotInputSnapshotAndReadsVersionTwo() {
        val day = DayRecap("2026-10-23", cards = listOf(lane().copy(navigation = NavigationLabel.ASSISTED, demoNavigation = true)))
        val bytes = RecapCodec.encode(day)
        assertEquals(day, RecapCodec.decode(bytes))
        for (key in listOf("runId", "sequence", "distanceMeters", "TURN_RIGHT", "receivedAtMs"))
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(key))
        val v2 = bytes.copyOf(bytes.size - 2); v2[7] = 2 // v3 provenance + v4 absent geometry, one-card fixture
        assertFalse(RecapCodec.decode(v2).cards.single().demoNavigation)
    }
    @Test fun realAndDemoObservationsCannotBeCombinedToPromoteConfidence() {
        val a = NavigationSnapshot(9500, true, "TURN_STRAIGHT", 200.0, epoch = 1)
        val b = a.copy(receivedAtMs = 12000, origin = NavigationOrigin.DEMO)
        assertEquals(NavigationLabel.ESTIMATED, fuseNavigation(lane(), listOf(a, b)).navigation)
    }
    @Test fun expiryInvalidationPreventsOldTurnHintFromConfirmingALaterSegment() {
        val c = lane().let { classifySegment(it.summary.copy(endMs = 14_000, minAngle = 0f, maxAngle = 90f, reversals = 0,
            minSpeedMps = 12f / 3.6f, maxSpeedMps = 12f / 3.6f, rightSignal = DirectionalTiming(SignalTiming.NONE)))!! }
        val state = acceptDemoNavigation(DemoNavigationState(), frame(), 10_000, true)!!
        val valid = demoNavigationSnapshot(state, 10_000)!!
        val expired = expireDemoNavigation(state, 13_001)
        val invalidation = NavigationSnapshot(13_001, null, null, null, epoch = expired.epoch, origin = NavigationOrigin.DEMO)
        assertEquals(NavigationLabel.ESTIMATED, fuseNavigation(c, listOf(valid, invalidation)).navigation)
        assertTrue(fuseNavigation(c, listOf(valid, invalidation)).demoNavigation)
    }
    @Test fun deterministicFourScenesPassThroughTheActualReducerAndFusion() {
        var engine = RecapEngineState(); var fusion = NavigationFusionState(); var demo = DemoNavigationState()
        var cards = emptyList<ReviewCard>(); var sequence = 0L
        val angles = mutableMapOf<Long, Float>()
        fun pattern(start: Long, every: Long, values: List<Int>) = values.forEachIndexed { i, v -> angles[start + i * every] = -v.toFloat() }
        pattern(10_000, 400, listOf(20,50,80,90,90,90,90,70,40,10,0))
        pattern(24_000, 400, listOf(30,65,100,120,120,120,120,90,50,15,0))
        pattern(40_000, 350, listOf(8,15,12,4,-6,-10,-4,0))
        pattern(53_000, 350, listOf(-8,-14,-10,-3,6,11,5,0))
        fun apply(input: DriveInput) {
            val s = recapStep(engine, input); engine = s.state
            val f = navigationFusionStep(fusion, input.atMs, s.cards.map { it.copy(demoNavigation = true) })
            fusion = f.state; cards = mergeReviewCards(cards, f.cards)
        }
        for (at in (0L..73_000L step 50)) {
            if (at % 1000 == 0L) {
                val active = at in 6000..47_999
                val action = when { at < 6000 || at >= 48000 -> null; at < 20000 -> "TURN_RIGHT"; at < 33000 -> "TURN_UTURN"; else -> "TURN_STRAIGHT" }
                val distance = when { !active -> null; at < 8000 || at in 20000..21999 || at >= 33000 -> 200.0; else -> 50.0 }
                demo = acceptDemoNavigation(demo, DemoNaviFrame(active, action, distance, run, sequence++), at, at == 0L)!!
                val f = navigationFusionStep(fusion, at, snapshot = demoNavigationSnapshot(demo, at))
                fusion = f.state; cards = mergeReviewCards(cards, f.cards)
            }
            val wall = 1_000_000 + at
            if (at == 0L) { apply(DriveInput.Gear(at, wall, DriveGear.PARK)); apply(DriveInput.Signal(at, wall, Indicator.OFF)); apply(DriveInput.Brake(at, wall, false)); apply(DriveInput.Steering(at, wall, 0f)) }
            if (at == 1000L) apply(DriveInput.Gear(at, wall, DriveGear.DRIVE))
            apply(DriveInput.Speed(at, wall, if (at < 33000) 12f / 3.6f else 35f / 3.6f))
            if (at == 8000L) apply(DriveInput.Signal(at, wall, Indicator.RIGHT))
            if (at == 16000L) apply(DriveInput.Signal(at, wall, Indicator.OFF))
            angles[at]?.let { apply(DriveInput.Steering(at, wall, it)) }
            apply(DriveInput.Tick(at, wall))
        }
        // W16c: the two 35km/h +/-8..15deg waveforms last <2.5s and move <2m laterally, so the end-point
        // window detector yields no card for them (the old settling path used to emit hidden UNSURE
        // steering cards). Both turns and their navigation labels are unchanged.
        assertEquals(listOf(ReviewKind.TURN_SIGNALLED, ReviewKind.TURN_NO_SIGNAL), cards.map { it.kind })
        assertEquals(listOf(NavigationLabel.CONFIRMED, NavigationLabel.CONFIRMED), cards.map { it.navigation })
        assertEquals(listOf(NavigationTurn.RIGHT, NavigationTurn.UTURN), cards.map { it.navigationTurn })
        assertTrue(cards.all { it.demoNavigation }); assertTrue(cards.all { it.uncertainty.isEmpty() })
    }
}
