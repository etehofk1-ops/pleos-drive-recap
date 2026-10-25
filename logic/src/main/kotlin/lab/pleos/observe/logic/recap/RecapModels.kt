package lab.pleos.observe.logic.recap

/** Prototype heuristics, not validated vehicle/safety standards. Tune only after labelled feedback.
 * Angle units/sign are not specified by Vehicle 2.3.2 metadata: use SDK raw magnitude, no L/R claim.
 * Speed is AAOS PERF_VEHICLE_SPEED m/s; 30 / 3.6 is the requested initial lane-candidate threshold.
 */
object RecapThresholds {
    const val ANGLE_ENTER = 6f
    const val ANGLE_NEUTRAL = 3f
    const val NEUTRAL_HOLD_MS = 700L
    const val MIN_SEGMENT_MS = 600L
    const val LANE_MAX_MS = 6_000L
    const val LANE_MAX_ANGLE = 35f
    const val LANE_MIN_MPS = 30f / 3.6f
    const val TURN_MAX_MPS = 25f / 3.6f
    const val TURN_MIN_ANGLE = 55f
    const val TURN_MIN_MS = 2_500L
    const val CURVE_MIN_MS = 8_000L
    const val MAX_SEGMENT_MS = 60_000L
    const val SIGNAL_LOOKBACK_MS = 3_000L
    const val SPEED_FRESH_MS = 3_000L
    const val MAX_CLOCK_GAP_MS = 5_000L
    // Different callback threads can enqueue just after a main-thread tick. Not sensor silence.
    const val CALLBACK_REORDER_MS = 250L
    const val MOVING_MIN_MPS = 0.5f
    const val BRAKE_MIN_DECEL = 3f
    const val BRAKE_MIN_DT_MS = 400L // average deceleration over >=400ms, not a 100ms noisy derivative
    const val BRAKE_MAX_DT_MS = 2_000L
    const val BRAKE_COOLDOWN_MS = 6_000L
    // Prototype association window around a press/release, including a measured 41ms pulse.
    const val BRAKE_ASSOCIATION_MS = 1_500L
    const val NAV_FRESH_MS = 3_000L
    const val NAV_NEAR_METERS = 80f
}

enum class DriveGear { UNKNOWN, PARK, DRIVE, OTHER }
enum class Indicator { OFF, LEFT, RIGHT }
enum class SteeringConvention { UNKNOWN, POSITIVE_LEFT, POSITIVE_RIGHT }
enum class ManeuverDirection { UNKNOWN, LEFT, RIGHT }
enum class ReviewKind { STEERING, LANE_CHANGE, LANE_SIGNALLED, LANE_NO_SIGNAL, LANE_LATE_SIGNAL, TURN, CURVE, HARD_BRAKE, BRAKE_UNVERIFIED, UNSURE,
    TURN_NO_SIGNAL, TURN_LATE_SIGNAL, TURN_SIGNALLED }
enum class SignalTiming { BEFORE, DURING, NONE, UNKNOWN }
enum class Uncertainty { SPEED_MISSING, DEMO_SPEED_MISSING, SIGNAL_GAP, INPUT_GAP, INCOMPLETE, SHAPE_AMBIGUOUS, NAV_CONFLICT, WEAK_LANE_EVIDENCE }
enum class Feedback { NONE, YES, NO }

data class RecapPolicy(
    val allowMissingSpeed: Boolean = true,
    val convention: SteeringConvention = SteeringConvention.UNKNOWN,
    val demoMissingSpeed: Boolean = false,
)

/** Only verified manoeuvre semantics + distance, never coordinates, road/destination names or route objects. */
data class TurnHint(val isTurn: Boolean?, val distanceMeters: Float?, val receivedAtMs: Long)

/** Reduced per-direction signal evidence. Needed because steering sign is still unverified. */
data class DirectionalTiming(val timing: SignalTiming, val lateMs: Long? = null)

data class SegmentSummary(
    val startMs: Long, val endMs: Long, val wallStartMs: Long,
    val minAngle: Float, val maxAngle: Float, val firstSign: Int, val reversals: Int,
    val minSpeedMps: Float?, val maxSpeedMps: Float?, val speedMissing: Boolean,
    val timing: SignalTiming, val signalCoverage: Boolean,
    val lateSignalMs: Long? = null, val nearNavigationTurn: Boolean = false,
    val incomplete: Boolean = false, val inputGap: Boolean = false,
    val leftSignal: DirectionalTiming? = null, val rightSignal: DirectionalTiming? = null,
    val lateral: LateralSummary? = null,
) { val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0) }

data class ReviewCard(
    val id: String, val wallTimeMs: Long, val kind: ReviewKind, val candidate: ReviewKind,
    val summary: SegmentSummary, val direction: ManeuverDirection = ManeuverDirection.UNKNOWN,
    val uncertainty: Set<Uncertainty> = emptySet(), val decelerationMps2: Float? = null,
    val feedback: Feedback = Feedback.NONE,
    val navigation: NavigationLabel = NavigationLabel.ESTIMATED,
    val navigationTurn: NavigationTurn? = null,
    val demoNavigation: Boolean = false,
)

data class DayRecap(val date: String, val drivingMs: Long = 0, val speedUnknownDrivingMs: Long = 0,
    val cards: List<ReviewCard> = emptyList())

/** Maximum-deceleration updates replace the same episode, preserving user feedback. */
fun mergeReviewCards(existing: List<ReviewCard>, updates: List<ReviewCard>): List<ReviewCard> {
    val merged = existing.associateByTo(linkedMapOf()) { it.id }
    for (card in updates) merged[card.id] = card.copy(feedback = merged[card.id]?.feedback ?: card.feedback)
    return merged.values.toList()
}
