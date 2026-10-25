package lab.pleos.observe.logic.recap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/** W14 prototype policy, NOT calibrated to a particular vehicle or a safety standard.
 * Small-angle bicycle approximation: yaw rate = v * tan(roadWheelAngle) / wheelbase.
 * https://www.mathworks.com/help/robotics/ug/mobile-robot-kinematics-equations.html
 * Raw steering sign remains unverified; none of these estimates establishes left/right.
 */
object LateralThresholds {
    const val STEERING_RATIO = 15.0 // Vehicle-specific ratio/wheelbase are UNVERIFIED.
    const val WHEELBASE_M = 2.7
    const val BASELINE_TAU_S = 8.0
    const val FILTER_TAU_S = 0.5
    const val MIN_SPEED_MPS = 25f / 3.6f
    const val ENTER_MPS2 = 0.3
    const val NEUTRAL_MPS2 = 0.2
    const val SIGN_MPS2 = 0.2
    const val MIN_DURATION_MS = 2_500L
    const val MAX_DURATION_MS = 8_000L
    const val MIN_DISPLACEMENT_M = 1.75 // ~2m, allowing coarse integration / unknown vehicle gain
    const val MAX_DISPLACEMENT_M = 5.5 // ~5m, same uncalibrated model tolerance
    const val RETURN_RAD = 0.06 // ~3.4 degrees; also require return relative to the excursion
    const val RETURN_FRACTION = 0.8 // real lobes are asymmetric (AVD G: 72% residual); curves stay ~1.0, so FP unchanged in sim
    const val MAX_PATH_RATIO = 1.6 // rejects repeated weaving even if the final heading cancels
    const val NEUTRAL_HOLD_MS = 150L // after both filtered lobes, not the midpoint zero crossing
    // Two-lobe manoeuvres may finish with a small heading correction. Never widen the
    // global return-angle gates: observe the correction instead, with bounded state/time.
    const val SETTLE_MS = 2_000L
    const val SETTLE_MAX_MPS2 = 0.45 // 1.5 x entry; a new substantial lobe ends this window
    const val SETTLE_MIN_MS = 500L // observe at least one filter time constant before the guarded gate
    const val SETTLE_RAW_DEG = 5f // reciprocal raw evidence only; NEVER an entry threshold
    const val SETTLE_RETURN_RAD = 0.08 // ~4.6deg, only after measured reciprocal evidence + settling
    const val SETTLE_PATH_RATIO = 2.0 // two lobes plus a small third correction, not repeated weaving
    const val SETTLE_PEAK_FRACTION = 0.65 // require the second lobe to be receding, not its zero crossing
    const val RECIPROCAL_FIRST_IMPULSE_MPS = 0.5 // short first lobe, only with >=5deg raw evidence
    const val MAX_CANDIDATE_MS = 10_000L // bound a noise/curve candidate; never one hour-long segment
    const val CALIBRATION_MS = 3_000L // initialize offset before freezing it for a manoeuvre
    const val INITIAL_TAU_S = 1.0 // startup only, then always the slow 8s EMA outside segments
    const val MIN_FIRST_IMPULSE_MPS = 0.7 // excludes a tiny noise lobe preceding the actual manoeuvre
    // Weak wheel-angle signals amplify offset/noise at high speed. Require stricter geometry,
    // never recover the old 6deg ENTRY gate. Large reciprocal signals keep direct integration.
    const val WEAK_RETURN_RAD = 0.025
    const val WEAK_RETURN_FRACTION = 0.4
    const val WEAK_MIN_DISPLACEMENT_M = 2.5
    const val WEAK_MIN_PEAK_HEADING_RAD = 0.035
    const val RAW_ONSET_MPS2 = 0.05 // timing only; a confirmed entry STILL needs filtered |a| >=0.3
    const val MAX_ONSET_LOOKBACK_MS = 1_000L
}

/** Constant-size held input/filter state. No raw sample history. Baseline learns outside segments only. */
data class LateralFilter(val atMs: Long? = null, val baselineDeg: Double = 0.0,
    val heldAngle: Float? = null, val heldSpeed: Float? = null, val acceleration: Double = 0.0,
    val calibrationMs: Long = 0, val onsetAtMs: Long? = null, val onsetSign: Int = 0)
data class LateralAdvance(val state: LateralFilter, val dtS: Double = 0.0,
    val impulse: Double = 0.0, val displacement: Double = 0.0, val integrationSpeed: Double? = null)

fun estimatedLateralAcceleration(angleDeg: Double, speedMps: Double): Double =
    speedMps * speedMps * angleDeg * PI / 180 / (LateralThresholds.STEERING_RATIO * LateralThresholds.WHEELBASE_M)

fun advanceLateral(previous: LateralFilter, angle: Float?, speed: Float?, atMs: Long, learnBaseline: Boolean): LateralAdvance {
    val dt = previous.atMs?.let { (atMs - it) / 1_000.0 } ?: 0.0
    val heldAngle = previous.heldAngle
    val v = previous.heldSpeed?.toDouble()?.takeIf { it.isFinite() && it >= LateralThresholds.MIN_SPEED_MPS && it <= 80.0 }
    val raw = if (angle != null && speed != null && speed >= LateralThresholds.MIN_SPEED_MPS && speed <= 80f)
        estimatedLateralAcceleration(angle - previous.baselineDeg, speed.toDouble()) else 0.0
    val onsetSign = if (raw >= LateralThresholds.RAW_ONSET_MPS2) 1 else if (raw <= -LateralThresholds.RAW_ONSET_MPS2) -1 else 0
    val next = previous.copy(atMs = atMs, heldAngle = angle, heldSpeed = speed, onsetSign = onsetSign,
        onsetAtMs = if (onsetSign == 0) null else if (onsetSign == previous.onsetSign) previous.onsetAtMs ?: atMs else atMs)
    if (dt <= 0) return LateralAdvance(next)
    if (dt > RecapThresholds.MAX_CLOCK_GAP_MS / 1_000.0 || heldAngle == null)
        return LateralAdvance(next.copy(acceleration = 0.0))
    val calibrating = previous.calibrationMs < LateralThresholds.CALIBRATION_MS
    val tauBias = if (calibrating) LateralThresholds.INITIAL_TAU_S else LateralThresholds.BASELINE_TAU_S
    val bias = if (learnBaseline && abs(heldAngle) < RecapThresholds.ANGLE_ENTER)
        previous.baselineDeg + (1 - exp(-dt / tauBias)) * (heldAngle - previous.baselineDeg)
        else previous.baselineDeg
    val calibrated = next.copy(baselineDeg = bias,
        calibrationMs = (previous.calibrationMs + (dt * 1_000).toLong()).coerceAtMost(LateralThresholds.CALIBRATION_MS))
    if (v == null) return LateralAdvance(calibrated.copy(acceleration = 0.0))
    val target = estimatedLateralAcceleration(heldAngle - bias, v)
    val tau = LateralThresholds.FILTER_TAU_S
    val decay = exp(-dt / tau)
    val difference = previous.acceleration - target
    // Exact integration of a zero-order-held input through a first-order low-pass filter.
    val impulse = target * dt + difference * tau * (1 - decay)
    val displacement = target * dt * dt / 2 + difference * (tau * dt - tau * tau * (1 - decay))
    return LateralAdvance(calibrated.copy(acceleration = target + difference * decay),
        dt, impulse, displacement, v)
}

/** Reduced geometry only. heading = integral(a/v); y = double integral(a), local relative metres.
 * Local displacement is a model feature, never a geographic coordinate or a measured lane position.
 */
data class LateralSummary(val headingRad: Double = 0.0, val peakHeadingRad: Double = 0.0,
    val absoluteYawRad: Double = 0.0, val velocityMps: Double = 0.0, val displacementM: Double = 0.0,
    val travelM: Double = 0.0, val minAcceleration: Double = 0.0, val maxAcceleration: Double = 0.0,
    val settledMs: Long = 0, val windowDurationMs: Int = 0)

fun integrateLateral(previous: LateralSummary, step: LateralAdvance): LateralSummary {
    val v = step.integrationSpeed ?: return previous
    val yaw = step.impulse / v
    val heading = previous.headingRad + yaw
    return previous.copy(headingRad = heading, peakHeadingRad = max(previous.peakHeadingRad, abs(heading)),
        absoluteYawRad = previous.absoluteYawRad + abs(yaw), velocityMps = previous.velocityMps + step.impulse,
        displacementM = previous.displacementM + previous.velocityMps * step.dtS + step.displacement,
        travelM = previous.travelM + v * step.dtS,
        minAcceleration = minOf(previous.minAcceleration, step.state.acceleration),
        maxAcceleration = maxOf(previous.maxAcceleration, step.state.acceleration))
}

/** Direct double integral. No left/right or real-world lane-position claim. */
fun lateralDisplacement(s: LateralSummary): Double = s.displacementM
/** Keep the main two lobes' duration/travel. Remove only the endpoint drift actually
 * resolved by the observed correction; never force the final heading to zero. */
fun settledLateral(body: LateralSummary, observed: LateralSummary, settledMs: Long = 0): LateralSummary = observed.copy(
    displacementM = body.displacementM - (body.headingRad - observed.headingRad) * body.travelM / 2,
    travelM = body.travelM, settledMs = settledMs.coerceIn(0,LateralThresholds.SETTLE_MS))
fun returnedHeading(s: LateralSummary): Boolean = abs(s.headingRad) <= LateralThresholds.RETURN_RAD &&
    abs(s.headingRad) <= s.peakHeadingRad * LateralThresholds.RETURN_FRACTION
fun normalizedLaneShape(s: SegmentSummary): Boolean = s.lateral?.let { m ->
    if (m.windowDurationMs != 0) return@let windowLaneShape(s, m)
    val settled = m.settledMs >= LateralThresholds.SETTLE_MIN_MS &&
        s.minAngle <= -LateralThresholds.SETTLE_RAW_DEG && s.maxAngle >= LateralThresholds.SETTLE_RAW_DEG
    val strong = settled || (s.minAngle <= -RecapThresholds.ANGLE_ENTER && s.maxAngle >= RecapThresholds.ANGLE_ENTER)
    // Correct endpoint drift only for weak signals AFTER checking the uncorrected heading.
    // Never turn a monotonic curve into a lane change by forcing its heading to zero.
    val returned = if (settled) abs(m.headingRad) <= LateralThresholds.SETTLE_RETURN_RAD &&
        abs(m.headingRad) <= m.peakHeadingRad * LateralThresholds.RETURN_FRACTION
        else if (strong) returnedHeading(m) else abs(m.headingRad) <= LateralThresholds.WEAK_RETURN_RAD &&
        abs(m.headingRad) <= m.peakHeadingRad * LateralThresholds.WEAK_RETURN_FRACTION &&
        m.peakHeadingRad >= LateralThresholds.WEAK_MIN_PEAK_HEADING_RAD
    val distance = if (strong) m.displacementM else m.displacementM - m.headingRad * m.travelM / 2
    val minimum = if (strong) LateralThresholds.MIN_DISPLACEMENT_M else LateralThresholds.WEAK_MIN_DISPLACEMENT_M
    s.durationMs in LateralThresholds.MIN_DURATION_MS..LateralThresholds.MAX_DURATION_MS &&
        m.minAcceleration <= -LateralThresholds.SIGN_MPS2 && m.maxAcceleration >= LateralThresholds.SIGN_MPS2 &&
        returned && abs(distance) in minimum..LateralThresholds.MAX_DISPLACEMENT_M &&
        m.absoluteYawRad <= 2 * m.peakHeadingRad *
            if (settled) LateralThresholds.SETTLE_PATH_RATIO else LateralThresholds.MAX_PATH_RATIO
} ?: false

/** W16c window evidence: re-checks the gates detect() already applied, from the stored summary only. */
private fun windowLaneShape(s: SegmentSummary, m: LateralSummary): Boolean {
    val strong = s.minAngle <= -LaneWindowPolicy.STRONG_ANGLE_DEG && s.maxAngle >= LaneWindowPolicy.STRONG_ANGLE_DEG
    return m.windowDurationMs in 2_500..8_000 && m.peakHeadingRad >= LaneWindowPolicy.PEAK_RAD &&
        abs(m.headingRad) <= max(LaneWindowPolicy.END_RAD,
            (if (strong) LaneWindowPolicy.STRONG_END_FRACTION else LaneWindowPolicy.END_FRACTION) * m.peakHeadingRad) &&
        abs(m.displacementM) in (if (strong) LaneWindowPolicy.STRONG_MIN_METERS else LaneWindowPolicy.MIN_METERS)..LaneWindowPolicy.MAX_METERS
}
