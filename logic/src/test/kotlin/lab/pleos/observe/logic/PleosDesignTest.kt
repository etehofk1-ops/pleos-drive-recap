package lab.pleos.observe.logic

import lab.pleos.observe.logic.recap.*
import org.junit.Assert.*
import org.junit.Test

class PleosDesignTest {
    @Test fun exactLatestColorTokensAreNotTheOlderSummaryValues() {
        val day = pleosColorTokens(false); val night = pleosColorTokens(true)
        assertEquals(0xfff7f8fa.toInt(), day["background/primary"])
        assertEquals(0xff1d1e21.toInt(), night["background/primary"])
        assertEquals(0xff0064ff.toInt(), day["informative/positive"])
        assertEquals(0xff0082ff.toInt(), night["informative/positive"])
        assertEquals(0xff02c265.toInt(), day["informative/active"])
        assertEquals(0xff00e074.toInt(), night["informative/active"])
        assertEquals(0xfffe3d16.toInt(), day["informative/negative"])
        assertEquals(0xffff4c28.toInt(), night["informative/negative"])
    }
    @Test fun opacityTokensStayExactAndAreCompositedBeforeContrast() {
        val p = vehiclePalette(false)
        assertEquals(0x1a131417, p.dividerSecondary); assertEquals(0x33131417, p.dividerTertiary)
        assertEquals(0xff000000.toInt(), compositeColor(0xff000000.toInt(), 0xffffffff.toInt()))
        assertEquals(0xffffffff.toInt(), compositeColor(0x00000000, 0xffffffff.toInt()))
        assertEquals(0xff7f7f7f.toInt(), compositeColor(0x80000000.toInt(), 0xffffffff.toInt()))
    }
    @Test fun tokenBrandColorsAreNeverPartOfAppPalette() {
        listOf(false, true).forEach { night ->
            assertTrue(pleosColorTokens(night).values.none { (it and 0xffffff) in setOf(0x5a46fa, 0x6a57ff, 0x596bff) })
        }
    }
    @Test fun everyEnabledPressedDisabledPopupToastAndBadgeInkPasses() {
        listOf(false, true).forEach { night ->
            val pairs = pleosTextPairs(vehiclePalette(night))
            assertTrue(pairs.size >= 60)
            pairs.forEach { (text, bg) -> assertTrue("$night ${contrastRatio(text, bg)}", contrastRatio(text, bg) >= 4.5) }
        }
    }
    @Test fun unsafeSemanticTextFallsBackWithoutChangingRawToken() {
        val p = vehiclePalette(false)
        assertTrue(contrastRatio(p.caution, p.surfaceBasic) < 4.5)
        assertEquals(p.caution, p.tone(PleosTone.CAUTION))
        assertTrue(contrastRatio(p.readable(p.caution, p.surfaceBasic), p.surfaceBasic) >= 4.5)
        assertTrue(contrastRatio(p.inkOn(p.caution), p.caution) >= 4.5)
        assertTrue(contrastRatio(p.disabledText, p.basicEnabled) >= 4.5)
    }
    @Test fun typographyUsesOnlySourceSizesAndLineHeights() {
        assertEquals(PleosType(30f, 38f), pleosType(30f))
        assertEquals(PleosType(20f, 24f), pleosType(14f))
        assertEquals(PleosType(32f, 40f), pleosType(33f))
        assertEquals(40f, pleosTitle(VehicleWidth.WIDE), 0f)
        assertEquals(32f, pleosTitle(VehicleWidth.NARROW), 0f)
        assertEquals(26f, pleosBody(VehicleWidth.MEDIUM), 0f)
    }
    @Test fun laneMissingUsesWarningWithNoUnverifiedDirection() {
        assertEquals(ReviewAppearance(PleosIcon.LANE_CHANGE, PleosTone.WARNING), reviewAppearance(ReviewKind.LANE_NO_SIGNAL))
        assertEquals(PleosIcon.LANE_LEFT, reviewAppearance(ReviewKind.LANE_NO_SIGNAL, direction = ManeuverDirection.LEFT).icon)
        assertEquals(PleosIcon.LANE_RIGHT, reviewAppearance(ReviewKind.LANE_NO_SIGNAL, direction = ManeuverDirection.RIGHT).icon)
    }
    @Test fun missingTurnsUseVerifiedNavigationTurnOnly() {
        assertEquals(PleosIcon.TURN_RIGHT, reviewAppearance(ReviewKind.TURN_NO_SIGNAL, NavigationTurn.RIGHT).icon)
        assertEquals(PleosIcon.TURN_LEFT, reviewAppearance(ReviewKind.TURN_NO_SIGNAL, NavigationTurn.LEFT).icon)
        assertEquals(PleosIcon.UTURN, reviewAppearance(ReviewKind.TURN_NO_SIGNAL, NavigationTurn.UTURN).icon)
        assertEquals(PleosIcon.NAVIGATION, reviewAppearance(ReviewKind.TURN_NO_SIGNAL).icon)
    }
    @Test fun hardBrakeAndLateHaveDistinctNonColorSymbols() {
        assertEquals(ReviewAppearance(PleosIcon.HARD_BRAKE, PleosTone.WARNING), reviewAppearance(ReviewKind.HARD_BRAKE))
        listOf(ReviewKind.LANE_LATE_SIGNAL, ReviewKind.TURN_LATE_SIGNAL).forEach {
            assertEquals(ReviewAppearance(PleosIcon.LATE_SIGNAL, PleosTone.CAUTION), reviewAppearance(it))
        }
    }
    @Test fun goodMomentsUseBlueCheckRatherThanGreenSafetyClaim() {
        listOf(ReviewKind.LANE_SIGNALLED, ReviewKind.TURN_SIGNALLED).forEach {
            assertEquals(ReviewAppearance(PleosIcon.GOOD, PleosTone.INFORMATION), reviewAppearance(it))
        }
    }
    @Test fun uncertainOrUnverifiedBrakingDoesNotLookLikeConfirmedWarning() {
        listOf(ReviewKind.UNSURE, ReviewKind.STEERING, ReviewKind.BRAKE_UNVERIFIED, ReviewKind.CURVE).forEach {
            assertEquals(PleosTone.NEUTRAL, reviewAppearance(it).tone)
        }
        ReviewKind.values().forEach { assertNotNull(reviewAppearance(it)) }
    }
    @Test fun momentColorsFollowTheSixToneSpecAndCaptionsStayReadableInDayAndNight() {
        val day = vehiclePalette(false); val night = vehiclePalette(true)
        assertEquals(day.positive, momentColor(day, MomentGroup.GOOD))
        assertEquals(0xff8a5cf6.toInt(), momentColor(day, MomentGroup.LEFT_ON))
        assertEquals(day.textPrimary, momentColor(day, MomentGroup.HARD_BRAKE))
        assertEquals(night.textPrimary, momentColor(night, MomentGroup.HARD_BRAKE))
        for (p in listOf(day, night)) for (group in MomentGroup.values()) {
            val tint = legibleTint(momentColor(p, group), p.surfaceBasic, p.textPrimary)
            assertTrue("$group ${p.night}", contrastRatio(tint, p.surfaceBasic) >= 4.5)
        }
    }
    @Test fun legibleTintKeepsAReadableColorAndDarkensOnlyAsMuchAsNeeded() {
        val white = 0xffffffff.toInt(); val ink = 0xff131417.toInt()
        assertEquals(0xff0064ff.toInt(), legibleTint(0xff0064ff.toInt(), white, ink))
        val red = 0xffe5482c.toInt(); val tinted = legibleTint(red, white, ink)
        assertNotEquals(red, tinted); assertTrue(contrastRatio(tinted, white) >= 4.5)
        assertTrue(((tinted ushr 16) and 255) > ((tinted ushr 8) and 255)) // 아직 붉은 계열
        assertTrue(contrastRatio(legibleTint(0xffffa202.toInt(), white, ink), white) >= 4.5)
    }
}
