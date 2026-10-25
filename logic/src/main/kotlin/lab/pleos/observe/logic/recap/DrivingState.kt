package lab.pleos.observe.logic.recap

import kotlin.math.abs

/** Recording state, not permission to show the parked-only UI. Positive evidence wins conflicts.
 * GEAR_SELECTION is the user's selection; CURRENT_GEAR may describe a different transmission gear.
 * https://developer.android.com/reference/android/car/VehiclePropertyIds#GEAR_SELECTION
 * PERF_VEHICLE_SPEED is signed m/s. Only speed is continuous and expires; gear/UX are ON_CHANGE.
 */
data class DrivingSignals(
    val selectedGear: DriveGear = DriveGear.UNKNOWN,
    val currentGear: DriveGear = DriveGear.UNKNOWN,
    val requiresDo: Boolean? = null,
    val speedMps: Float? = null,
    val speedAtMs: Long? = null,
)

enum class DrivingSource { GEAR_SELECTION, CURRENT_GEAR_FALLBACK, UX_REQUIRES_DO, SPEED, ALL_STOPPED, INCOMPLETE }
data class DrivingDecision(val gear: DriveGear, val sources: List<DrivingSource>)

fun drivingDecision(s: DrivingSignals, nowMs: Long): DrivingDecision {
    val selected = s.selectedGear != DriveGear.UNKNOWN
    val gear = if (selected) s.selectedGear else s.currentGear
    val speed = s.speedMps?.takeIf { it.isFinite() && s.speedAtMs?.let { t -> nowMs - t in 0..RecapThresholds.SPEED_FRESH_MS } == true }
    val sources = buildList {
        if (gear == DriveGear.DRIVE || gear == DriveGear.OTHER)
            add(if (selected) DrivingSource.GEAR_SELECTION else DrivingSource.CURRENT_GEAR_FALLBACK)
        if (s.requiresDo == true) add(DrivingSource.UX_REQUIRES_DO)
        if (speed != null && abs(speed) > 0f) add(DrivingSource.SPEED)
    }
    return when {
        sources.isNotEmpty() -> DrivingDecision(DriveGear.DRIVE, sources)
        gear == DriveGear.PARK && s.requiresDo == false && speed == 0f ->
            DrivingDecision(DriveGear.PARK, listOf(DrivingSource.ALL_STOPPED))
        else -> DrivingDecision(DriveGear.UNKNOWN, listOf(DrivingSource.INCOMPLETE))
    }
}

fun mpsToKph(value: Float): Float = value * 3.6f
fun kphToMps(value: Float): Float = value / 3.6f

/** Presentation only: preserve stored summaries. Never turn road curvature into a coaching card.
 * Low-speed/nav conflicts remain reviewable; only high-speed, low-confidence STEERING is hidden.
 */
fun visibleReviewCards(cards: List<ReviewCard>): List<ReviewCard> = cards.filter {
    !(it.candidate == ReviewKind.LANE_CHANGE ||
        (it.candidate in setOf(ReviewKind.LANE_SIGNALLED, ReviewKind.LANE_NO_SIGNAL, ReviewKind.LANE_LATE_SIGNAL) &&
            Uncertainty.SIGNAL_GAP in it.uncertainty)) &&
    it.kind != ReviewKind.TURN && it.candidate != ReviewKind.CURVE && it.kind != ReviewKind.CURVE &&
        !(it.candidate == ReviewKind.STEERING && it.summary.minSpeedMps?.let { speed ->
            speed >= LateralThresholds.MIN_SPEED_MPS } == true && !it.summary.speedMissing && it.navigation == NavigationLabel.ESTIMATED)
}
