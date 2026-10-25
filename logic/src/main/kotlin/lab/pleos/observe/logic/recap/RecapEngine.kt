package lab.pleos.observe.logic.recap

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

sealed interface DriveInput {
    val atMs: Long
    val wallMs: Long
    data class Steering(override val atMs: Long, override val wallMs: Long, val value: Float?) : DriveInput
    data class Speed(override val atMs: Long, override val wallMs: Long, val value: Float?) : DriveInput
    data class Signal(override val atMs: Long, override val wallMs: Long, val value: Indicator?) : DriveInput
    data class Brake(override val atMs: Long, override val wallMs: Long, val value: Boolean?) : DriveInput
    data class Gear(override val atMs: Long, override val wallMs: Long, val value: DriveGear) : DriveInput
    data class Navigation(override val atMs: Long, override val wallMs: Long, val value: TurnHint?) : DriveInput
    data class Tick(override val atMs: Long, override val wallMs: Long) : DriveInput
    data class Finish(override val atMs: Long, override val wallMs: Long) : DriveInput
}

data class IndicatorWindow(val began: Long? = null, val ended: Long? = null)
data class ActiveSegment(
    val summary: SegmentSummary,
    val lastSign: Int,
    val neutralSince: Long? = null,
    val signaledBefore: Boolean = false,
    val signaledDuring: Long? = null,
    val leftBefore: Boolean = false, val rightBefore: Boolean = false,
    val leftDuring: Long? = null, val rightDuring: Long? = null,
    val neutralMotion: LateralSummary? = null,
    val signalEndMs: Long? = null,
    val settleSince: Long? = null,
    val settleBody: SegmentSummary? = null,
    val settleNeutralBody: Boolean = false,
)

/** Transient reduced evidence only, never a persisted raw speed series. */
data class DecelerationEvidence(val startMs: Long, val endMs: Long, val wallMs: Long,
    val fromMps: Float, val toMps: Float, val rateMps2: Float, val movementAtMs: Long = endMs)
data class BrakeWindow(val beganMs: Long, val wallMs: Long, val releasedMs: Long? = null,
    val best: DecelerationEvidence? = null, val emitted: Boolean = false)
data class SpeedAnchor(val atMs: Long, val mps: Float)

data class RecapEngineState(
    val lastMs: Long? = null,
    val gear: DriveGear = DriveGear.UNKNOWN,
    val angle: Float? = null,
    val speed: Float? = null, val speedAt: Long? = null,
    val indicator: Indicator? = null, val indicatorKnownSince: Long? = null,
    val left: IndicatorWindow = IndicatorWindow(), val right: IndicatorWindow = IndicatorWindow(),
    val brake: Boolean? = null, val missingBrakeStart: Pair<Long, Long>? = null,
    val hint: TurnHint? = null,
    val active: ActiveSegment? = null,
    val lastHardBrakeMs: Long? = null,
    val inputTimes: Map<String, Long> = emptyMap(),
    val recentDecelerations: List<DecelerationEvidence> = emptyList(),
    val brakeWindow: BrakeWindow? = null,
    val decelerationAnchor: SpeedAnchor? = null,
    val lateralFilter: LateralFilter = LateralFilter(),
    val lastSpeedDropMs: Long? = null,
    val delayedLane: DelayedLaneState = DelayedLaneState(),
)

data class RecapStep(val state: RecapEngineState, val cards: List<ReviewCard> = emptyList(),
    val drivingMs: Long = 0, val speedUnknownDrivingMs: Long = 0, val parked: Boolean = false)

fun maneuverDirection(sign: Int, convention: SteeringConvention): ManeuverDirection = when {
    convention == SteeringConvention.UNKNOWN || sign == 0 -> ManeuverDirection.UNKNOWN
    (sign > 0) == (convention == SteeringConvention.POSITIVE_LEFT) -> ManeuverDirection.LEFT
    else -> ManeuverDirection.RIGHT
}

fun classifySegment(s: SegmentSummary, policy: RecapPolicy = RecapPolicy()): ReviewCard? {
    if (s.durationMs < RecapThresholds.MIN_SEGMENT_MS) return null
    val peak = max(abs(s.minAngle), abs(s.maxAngle))
    val legacyLaneShape = s.reversals == 1 && s.durationMs <= RecapThresholds.LANE_MAX_MS &&
        s.minAngle <= -RecapThresholds.ANGLE_ENTER && s.maxAngle >= RecapThresholds.ANGLE_ENTER && peak <= RecapThresholds.LANE_MAX_ANGLE
    val laneShape = if (s.lateral != null) !s.incomplete && normalizedLaneShape(s) else legacyLaneShape
    val laneSpeed = s.speedMissing || (s.minSpeedMps ?: -1f) >=
        if (s.lateral != null) LateralThresholds.MIN_SPEED_MPS else RecapThresholds.LANE_MIN_MPS
    val slowTurn = (s.speedMissing || (s.maxSpeedMps ?: Float.MAX_VALUE) <= RecapThresholds.TURN_MAX_MPS) &&
        peak >= RecapThresholds.TURN_MIN_ANGLE && s.durationMs >= RecapThresholds.TURN_MIN_MS
    val candidate = when {
        s.nearNavigationTurn || slowTurn -> ReviewKind.TURN
        (!laneShape && s.lateral?.let { !returnedHeading(it) && s.durationMs >= RecapThresholds.CURVE_MIN_MS } == true) ||
            (s.lateral == null && s.reversals == 0 && s.durationMs >= RecapThresholds.CURVE_MIN_MS) -> ReviewKind.CURVE
        laneShape && laneSpeed -> when (s.timing) {
            SignalTiming.BEFORE -> ReviewKind.LANE_SIGNALLED
            SignalTiming.DURING -> if (s.signalCoverage && (s.lateSignalMs ?: 0) > 0) ReviewKind.LANE_LATE_SIGNAL else ReviewKind.LANE_CHANGE
            SignalTiming.NONE -> if (s.signalCoverage) ReviewKind.LANE_NO_SIGNAL else ReviewKind.LANE_CHANGE
            SignalTiming.UNKNOWN -> ReviewKind.LANE_CHANGE
        }
        else -> ReviewKind.STEERING
    }
    val flags = mutableSetOf<Uncertainty>()
    if (s.speedMissing) flags += Uncertainty.SPEED_MISSING
    if (policy.demoMissingSpeed) flags += Uncertainty.DEMO_SPEED_MISSING
    if (s.incomplete) flags += Uncertainty.INCOMPLETE
    if (s.inputGap) flags += Uncertainty.INPUT_GAP
    if (candidate == ReviewKind.LANE_CHANGE) flags += Uncertainty.SIGNAL_GAP
    if (candidate == ReviewKind.STEERING) flags += Uncertainty.SHAPE_AMBIGUOUS
    if (candidate == ReviewKind.CURVE && peak >= RecapThresholds.TURN_MIN_ANGLE) flags += Uncertainty.SHAPE_AMBIGUOUS
    return ReviewCard("${s.wallStartMs}-${s.startMs}-steer", s.wallStartMs,
        if (flags.isEmpty()) candidate else ReviewKind.UNSURE, candidate, s,
        maneuverDirection(s.firstSign, policy.convention), flags)
}

private fun speedAt(s: RecapEngineState, now: Long, policy: RecapPolicy): Float? =
    if (policy.demoMissingSpeed || s.speedAt == null || now - s.speedAt !in 0..RecapThresholds.SPEED_FRESH_MS) null else s.speed

private fun sign(angle: Float) = if (angle >= RecapThresholds.ANGLE_ENTER) 1 else if (angle <= -RecapThresholds.ANGLE_ENTER) -1 else 0
private fun moving(s: RecapEngineState, now: Long, policy: RecapPolicy): Boolean {
    if (s.gear != DriveGear.DRIVE) return false
    val speed = speedAt(s, now, policy)
    return if (speed == null) policy.allowMissingSpeed else speed >= RecapThresholds.MOVING_MIN_MPS
}

private fun matching(s: RecapEngineState, firstSign: Int, policy: RecapPolicy): Boolean = when (maneuverDirection(firstSign, policy.convention)) {
    ManeuverDirection.UNKNOWN -> s.indicator != null && s.indicator != Indicator.OFF
    ManeuverDirection.LEFT -> s.indicator == Indicator.LEFT
    ManeuverDirection.RIGHT -> s.indicator == Indicator.RIGHT
}

private fun before(s: RecapEngineState, firstSign: Int, at: Long, policy: RecapPolicy): Boolean {
    fun recent(window: IndicatorWindow, active: Boolean) = window.began?.let {
        it < at && (if (active) at else window.ended ?: Long.MIN_VALUE) >= at - RecapThresholds.SIGNAL_LOOKBACK_MS
    } ?: false
    val left = recent(s.left, s.indicator == Indicator.LEFT)
    val right = recent(s.right, s.indicator == Indicator.RIGHT)
    return when (maneuverDirection(firstSign, policy.convention)) {
        ManeuverDirection.UNKNOWN -> left || right
        ManeuverDirection.LEFT -> left
        ManeuverDirection.RIGHT -> right
    }
}

private fun nearTurn(s: RecapEngineState, at: Long): Boolean = s.hint?.let {
    it.isTurn == true && it.distanceMeters?.let { d -> d.isFinite() && d in 0f..RecapThresholds.NAV_NEAR_METERS } == true &&
        at - it.receivedAtMs in 0..RecapThresholds.NAV_FRESH_MS
} ?: false

private fun beginSegment(s: RecapEngineState, start: Long, at: Long, wall: Long, angle: Float,
    speed: Float?, first: Int, normalized: Boolean, policy: RecapPolicy) =
    ActiveSegment(SegmentSummary(start, at, wall, min(0f, angle), max(0f, angle), first, 0,
        speed, speed, speed == null, SignalTiming.UNKNOWN,
        s.indicatorKnownSince?.let { it <= start - RecapThresholds.SIGNAL_LOOKBACK_MS } == true,
        nearNavigationTurn = nearTurn(s, at), lateral = if (normalized) LateralSummary() else null), first,
        signaledBefore = before(s, first, start, policy),
        leftBefore = before(s, 1, start, policy.copy(convention = SteeringConvention.POSITIVE_LEFT)),
        rightBefore = before(s, -1, start, policy.copy(convention = SteeringConvention.POSITIVE_LEFT)))

/** Immutable online reducer: no raw history, file IO, Android classes or AI calls. */
fun recapStep(previous: RecapEngineState, input: DriveInput, policy: RecapPolicy = RecapPolicy()): RecapStep {
    val eventAt = input.atMs
    val key = input.javaClass.simpleName
    val sameSignalOlder = previous.inputTimes[key]?.let { eventAt < it } == true
    if (sameSignalOlder || (previous.lastMs != null && previous.lastMs - eventAt > RecapThresholds.CALLBACK_REORDER_MS)) {
        // Never infer absence after dropping an out-of-order blinker, or a clean shape after a lost angle.
        val affected = input is DriveInput.Signal || input is DriveInput.Steering
        val active = previous.active?.let { it.copy(summary = it.summary.copy(inputGap = it.summary.inputGap || affected,
            signalCoverage = it.summary.signalCoverage && input !is DriveInput.Signal)) }
        val invalidated = if (affected) DelayedLaneState(feedAt=previous.lastMs,
            signals=(previous.delayedLane.signals+LaneSignal(previous.lastMs ?: eventAt,null)).takeLast(LaneWindowPolicy.SIGNAL_CAPACITY)) else previous.delayedLane
        return RecapStep(if (input is DriveInput.Signal) previous.copy(indicator = null, indicatorKnownSince = null, active = active, delayedLane=invalidated)
            else if (affected) previous.copy(active = active, delayedLane=invalidated) else previous)
    }
    // Small cross-signal dispatcher jitter is not an input gap. Keep the original event
    // time for window boundaries and delay calculations; only the reducer clock is clamped.
    val at = max(eventAt, previous.lastMs ?: eventAt)
    val dt = previous.lastMs?.let { at - it } ?: 0
    // Effective DRIVE is supplied by the recording-state resolver (selection OR UX OR speed).
    // Count time waiting in D too; manoeuvre entry still excludes measured stationary steering.
    val elapsed = if (dt in 0..RecapThresholds.MAX_CLOCK_GAP_MS && previous.gear == DriveGear.DRIVE) dt else 0
    val missingElapsed = if (speedAt(previous, previous.lastMs ?: at, policy) == null) elapsed else 0
    var s = previous.copy(lastMs = at, inputTimes = previous.inputTimes + (key to eventAt),
        recentDecelerations = previous.recentDecelerations.filter { at - it.movementAtMs in 0..RecapThresholds.BRAKE_ASSOCIATION_MS })
    if (s.brakeWindow?.releasedMs?.let { eventAt - it > RecapThresholds.BRAKE_ASSOCIATION_MS + RecapThresholds.BRAKE_MIN_DT_MS } == true)
        s = s.copy(brakeWindow = null)
    val cards = mutableListOf<ReviewCard>()
    var parked = false
    when (input) {
        is DriveInput.Steering -> s = s.copy(angle = input.value?.takeIf { it.isFinite() })
        is DriveInput.Speed -> {
            val value = input.value?.takeIf { it.isFinite() && it >= 0f }
            val movementAt = if (value != null && previous.speed?.let { value < it } == true) eventAt else previous.lastSpeedDropMs
            s = s.copy(lastSpeedDropMs = if (value == null) null else movementAt)
            // Aggregate >=400ms even when AAOS emits every 20~50ms. Resetting the anchor
            // on every packet would discard every fast update and miss the whole slowdown.
            val anchor = previous.decelerationAnchor
            val prior = anchor?.mps
            val delta = anchor?.let { eventAt - it.atMs } ?: 0
            val decel = if (value != null && prior != null && delta in RecapThresholds.BRAKE_MIN_DT_MS..RecapThresholds.BRAKE_MAX_DT_MS)
                ((prior - value) * 1_000f / delta).takeIf { it.isFinite() } else null
            if (!policy.demoMissingSpeed && decel != null && decel >= RecapThresholds.BRAKE_MIN_DECEL &&
                (previous.gear == DriveGear.DRIVE || s.brakeWindow != null)) {
                val evidence = DecelerationEvidence(eventAt - delta, eventAt, input.wallMs, prior!!, value!!, decel, movementAt ?: eventAt)
                s = s.copy(recentDecelerations = (s.recentDecelerations + evidence).takeLast(32))
                s.brakeWindow?.let { window ->
                    val associated = evidence.movementAtMs >= window.beganMs - RecapThresholds.BRAKE_ASSOCIATION_MS &&
                        (window.releasedMs == null || evidence.movementAtMs <= window.releasedMs + RecapThresholds.BRAKE_ASSOCIATION_MS)
                    if (associated && (window.best == null || decel > window.best.rateMps2))
                        s = s.copy(brakeWindow = window.copy(best = evidence))
                }
            }
            s = s.copy(speed = value, speedAt = eventAt, decelerationAnchor = when {
                value == null -> null
                anchor == null || delta >= RecapThresholds.BRAKE_MIN_DT_MS -> SpeedAnchor(eventAt, value)
                else -> anchor
            })
        }
        is DriveInput.Signal -> {
            val value = input.value
            fun window(kind: Indicator, old: IndicatorWindow): IndicatorWindow = when {
                value == kind && previous.indicator != kind -> IndicatorWindow(eventAt, null)
                previous.indicator == kind && value != kind -> old.copy(ended = eventAt)
                else -> old
            }
            s = s.copy(indicator = value, indicatorKnownSince = if (value == null) null else previous.indicatorKnownSince ?: eventAt,
                left = window(Indicator.LEFT, previous.left), right = window(Indicator.RIGHT, previous.right))
        }
        is DriveInput.Brake -> {
            if (input.value == true && previous.brake != true && previous.gear == DriveGear.DRIVE) {
                val old = s.brakeWindow
                val best = (s.recentDecelerations + listOfNotNull(old?.best)).maxByOrNull { it.rateMps2 }
                s = s.copy(brakeWindow = old?.copy(releasedMs = null, best = best) ?: BrakeWindow(eventAt, input.wallMs, best = best))
            }
            if (input.value == false && previous.brake == true)
                s = s.copy(brakeWindow = s.brakeWindow?.copy(releasedMs = eventAt))
            if (input.value == null) s = s.copy(brakeWindow = null)
            if (input.value == true && previous.brake != true && previous.gear == DriveGear.DRIVE && speedAt(previous, at, policy) == null && policy.allowMissingSpeed)
                s = s.copy(missingBrakeStart = at to input.wallMs)
            if (input.value == false && previous.missingBrakeStart != null) {
                val (start, wall) = previous.missingBrakeStart
                if (at - start >= RecapThresholds.BRAKE_MIN_DT_MS) {
                    val summary = SegmentSummary(start, at, wall, 0f, 0f, 0, 0, null, null, true, SignalTiming.UNKNOWN, false)
                    cards += ReviewCard("$wall-$start-brakeUnknown", wall, ReviewKind.UNSURE, ReviewKind.BRAKE_UNVERIFIED, summary,
                        uncertainty = setOf(Uncertainty.SPEED_MISSING) + if (policy.demoMissingSpeed) setOf(Uncertainty.DEMO_SPEED_MISSING) else emptySet())
                }
                s = s.copy(missingBrakeStart = null)
            }
            if (input.value == null) s = s.copy(missingBrakeStart = null)
            s = s.copy(brake = input.value)
        }
        is DriveInput.Gear -> { parked = previous.gear == DriveGear.DRIVE && input.value == DriveGear.PARK; s = s.copy(gear = input.value) }
        is DriveInput.Navigation -> s = s.copy(hint = input.value)
        is DriveInput.Tick, is DriveInput.Finish -> Unit
    }
    s.brakeWindow?.let { window ->
        val best = window.best
        val canEmit = window.emitted || s.lastHardBrakeMs?.let { at - it >= RecapThresholds.BRAKE_COOLDOWN_MS } != false
        if (best != null && canEmit && !policy.demoMissingSpeed) {
            // Emit immediately, then upsert this ID only when a higher maximum is observed.
            val priorBest = previous.brakeWindow?.takeIf { it.beganMs == window.beganMs }?.best
            if (!window.emitted || priorBest != best) {
                val summary = SegmentSummary(best.startMs, best.endMs, best.wallMs - (best.endMs - best.startMs), 0f, 0f, 0, 0,
                    best.toMps, best.fromMps, false, SignalTiming.UNKNOWN, false)
                cards += ReviewCard("${window.wallMs}-${window.beganMs}-brake", window.wallMs, ReviewKind.HARD_BRAKE,
                    ReviewKind.HARD_BRAKE, summary, decelerationMps2 = best.rateMps2)
                s = s.copy(brakeWindow = window.copy(emitted = true),
                    lastHardBrakeMs = if (window.emitted) s.lastHardBrakeMs else at)
            }
        }
    }
    val angle = s.angle
    val speed = speedAt(s, at, policy)
    val window = delayedLaneStep(s.delayedLane,input,angle,speed,s.gear,policy,s.hint)
    s = s.copy(delayedLane=window.state)
    cards += window.cards
    // W16 replaces only the measured >=25km/h path. Legacy/missing-speed/low-
    // speed turns and brake handling above retain their existing behavior.
    if (speed != null && speed >= LateralThresholds.MIN_SPEED_MPS &&
        !(speed <= RecapThresholds.TURN_MAX_MPS && angle != null && abs(angle) >= RecapThresholds.TURN_MIN_ANGLE)) {
        s=s.copy(active=null,lateralFilter=LateralFilter(),missingBrakeStart=if(s.gear!=DriveGear.DRIVE || input is DriveInput.Finish) null else s.missingBrakeStart)
        if(input is DriveInput.Finish) s=s.copy(brakeWindow=null,recentDecelerations=emptyList(),decelerationAnchor=null,lastSpeedDropMs=null)
        return RecapStep(s,cards,elapsed,missingElapsed,parked)
    }
    var active = s.active
    val lateral = advanceLateral(s.lateralFilter, angle, speed, at, learnBaseline = active == null)
    s = s.copy(lateralFilter = lateral.state)
    val normalized = speed != null && speed >= LateralThresholds.MIN_SPEED_MPS &&
        !(speed <= RecapThresholds.TURN_MAX_MPS && angle != null && abs(angle) >= RecapThresholds.TURN_MIN_ANGLE)
    if (active?.summary?.lateral != null && speed != null && !normalized) {
        // A pending high-speed wiggle must never swallow the subsequent low-speed turn.
        val ended = active.summary.copy(endMs = at, incomplete = true,
            lateral = integrateLateral(active.summary.lateral!!, lateral))
        classifySegment(ended, policy)?.let(cards::add)
        active = null
    }
    val normalizedEntry = normalized && lateral.state.calibrationMs >= LateralThresholds.CALIBRATION_MS &&
        abs(lateral.state.acceleration) >= LateralThresholds.ENTER_MPS2
    val legacyEntry = !normalized && angle != null && abs(angle) >= RecapThresholds.ANGLE_ENTER
    val entryAngle = angle ?: previous.angle
    if (active == null && entryAngle != null && (normalizedEntry || legacyEntry) && moving(s, at, policy)) {
        val first = if (normalized) if (lateral.state.acceleration > 0) 1 else -1 else sign(entryAngle)
        val start = if (normalized) maxOf(0, eventAt - LateralThresholds.MAX_ONSET_LOOKBACK_MS,
            lateral.state.onsetAtMs?.takeIf { lateral.state.onsetSign == first } ?: eventAt) else eventAt
        active = beginSegment(s, start, at, input.wallMs - (eventAt - start), entryAngle, speed, first, normalized, policy)
    }
    if (active != null) {
        var a = active
        var summary = a.summary
        val deadline = a.settleSince?.plus(LateralThresholds.SETTLE_MS)
        val geometryStep = if (deadline != null && at > deadline &&
            previous.lateralFilter.atMs?.let { it < deadline } == true)
            advanceLateral(previous.lateralFilter, angle, speed, deadline, learnBaseline = false) else lateral
        var motion = summary.lateral?.let { integrateLateral(it, geometryStep) }
        val currentSign = if (motion != null) when {
            lateral.state.acceleration >= LateralThresholds.SIGN_MPS2 -> 1
            lateral.state.acceleration <= -LateralThresholds.SIGN_MPS2 -> -1
            else -> 0
        } else angle?.let(::sign) ?: 0
        var changed = currentSign != 0 && currentSign != a.lastSign
        val firstImpulseMinimum = if (max(abs(summary.minAngle), abs(summary.maxAngle)) >= LateralThresholds.SETTLE_RAW_DEG)
            LateralThresholds.RECIPROCAL_FIRST_IMPULSE_MPS else LateralThresholds.MIN_FIRST_IMPULSE_MPS
        if (motion != null && changed && summary.reversals == 0 &&
            abs(motion.velocityMps) < firstImpulseMinimum && angle != null) {
            // Restart at the substantial lobe instead of merging a weak, earlier noise wiggle.
            val start = maxOf(0, eventAt - LateralThresholds.MAX_ONSET_LOOKBACK_MS,
                lateral.state.onsetAtMs?.takeIf { lateral.state.onsetSign == currentSign } ?: eventAt)
            a = beginSegment(s, start, at, input.wallMs - (eventAt - start), angle, speed, currentSign, true, policy)
            summary = a.summary; motion = integrateLateral(LateralSummary(), lateral); changed = false
        }
        val neutral = angle != null && if (motion != null) abs(lateral.state.acceleration) <= LateralThresholds.NEUTRAL_MPS2
            else abs(angle) <= RecapThresholds.ANGLE_NEUTRAL
        val observedOn = when (maneuverDirection(summary.firstSign, policy.convention)) {
            ManeuverDirection.LEFT -> s.left.began
            ManeuverDirection.RIGHT -> s.right.began
            ManeuverDirection.UNKNOWN -> listOfNotNull(s.left.began, s.right.began).maxOrNull()
        }
        val beforeStart = a.signaledBefore || before(s, summary.firstSign, summary.startMs, policy)
        val strongBoth = summary.minAngle <= -RecapThresholds.ANGLE_ENTER && summary.maxAngle >= RecapThresholds.ANGLE_ENTER
        val rawEnd = if (a.settleNeutralBody) a.signalEndMs ?: a.settleBody?.endMs else if (motion != null && strongBoth && angle != null && abs(angle) <= RecapThresholds.ANGLE_NEUTRAL)
            a.signalEndMs ?: eventAt else null
        a = a.copy(lastSign = if (currentSign != 0) currentSign else a.lastSign,
            signaledBefore = beforeStart,
            signalEndMs = rawEnd,
            neutralSince = if (neutral) a.neutralSince ?: eventAt else null,
            neutralMotion = if (neutral) a.neutralMotion ?: motion else null,
            signaledDuring = if (beforeStart) null else a.signaledDuring ?: observedOn?.takeIf {
                matching(s, summary.firstSign, policy) && it >= summary.startMs && it <= (rawEnd ?: a.neutralSince ?: at)
            })
        // Per-direction evidence is independent of the unknown steering-angle sign convention.
        val steeringEnd = rawEnd ?: a.neutralSince ?: at
        fun began(kind: Indicator, old: Long?, window: IndicatorWindow): Long? = old ?: window.began?.takeIf {
            s.indicator == kind && it in summary.startMs..steeringEnd
        }
        a = a.copy(leftBefore = a.leftBefore || before(s, 1, summary.startMs, policy.copy(convention = SteeringConvention.POSITIVE_LEFT)),
            rightBefore = a.rightBefore || before(s, -1, summary.startMs, policy.copy(convention = SteeringConvention.POSITIVE_LEFT)),
            leftDuring = began(Indicator.LEFT, a.leftDuring, s.left),
            rightDuring = began(Indicator.RIGHT, a.rightDuring, s.right))
        summary = summary.copy(endMs = at, minAngle = min(summary.minAngle, angle ?: summary.minAngle), maxAngle = max(summary.maxAngle, angle ?: summary.maxAngle),
            reversals = summary.reversals + if (changed) 1 else 0,
            minSpeedMps = if (speed == null) summary.minSpeedMps else min(summary.minSpeedMps ?: speed, speed),
            maxSpeedMps = if (speed == null) summary.maxSpeedMps else max(summary.maxSpeedMps ?: speed, speed),
            speedMissing = summary.speedMissing || speed == null,
            signalCoverage = summary.signalCoverage && s.indicator != null,
            nearNavigationTurn = summary.nearNavigationTurn || nearTurn(s, at),
            // ON_CHANGE silence is held state, not lost input. Explicit failure/reversal still invalidates.
            inputGap = summary.inputGap || (input is DriveInput.Steering && angle == null) ||
                (motion != null && dt > RecapThresholds.MAX_CLOCK_GAP_MS), lateral = motion)
        val bothLobes = motion?.let { it.minAcceleration <= -LateralThresholds.SIGN_MPS2 && it.maxAcceleration >= LateralThresholds.SIGN_MPS2 } == true
        val centered = a.neutralSince?.let {
            if (motion == null) at - it >= RecapThresholds.NEUTRAL_HOLD_MS else
                at - it >= LateralThresholds.NEUTRAL_HOLD_MS && (bothLobes || summary.durationMs >= RecapThresholds.CURVE_MIN_MS)
        } == true
        val rawAcceleration = if (angle != null && speed != null)
            estimatedLateralAcceleration(angle - lateral.state.baselineDeg, speed.toDouble()) else 0.0
        val secondPeak = motion?.let { if (summary.firstSign > 0) abs(it.minAcceleration) else it.maxAcceleration } ?: 0.0
        val returningLobe = bothLobes && a.lastSign != summary.firstSign && summary.durationMs >= LateralThresholds.MIN_DURATION_MS &&
            abs(lateral.state.acceleration) <= minOf(LateralThresholds.SETTLE_MAX_MPS2, secondPeak * LateralThresholds.SETTLE_PEAK_FRACTION) &&
            abs(rawAcceleration) <= LateralThresholds.SETTLE_MAX_MPS2 &&
            abs(lateral.state.acceleration) < abs(previous.lateralFilter.acceleration) - 1e-9
        val reciprocalRaw = summary.minAngle <= -LateralThresholds.SETTLE_RAW_DEG &&
            summary.maxAngle >= LateralThresholds.SETTLE_RAW_DEG
        val alreadyComplete = reciprocalRaw && centered && normalizedLaneShape(summary.copy(endMs = a.neutralSince ?: at,
            lateral = a.neutralMotion ?: motion))
        if (motion != null && bothLobes && (returningLobe || centered) && !alreadyComplete && a.settleSince == null) {
            // Preserve the manoeuvre's duration and indicator window. Geometry continues
            // integrating in constant space; a later blinker cannot excuse this manoeuvre.
            a = a.copy(settleSince = at, settleBody = summary.copy(endMs = a.neutralSince ?: at))
        }
        if (a.settleSince != null && centered && !a.settleNeutralBody &&
            at - a.settleSince!! < LateralThresholds.SETTLE_MIN_MS) {
            a = a.copy(settleNeutralBody = true, settleBody = summary.copy(endMs = a.neutralSince ?: at,
                lateral = a.neutralMotion ?: motion))
        }
        val settling = a.settleSince != null
        val newLobe = settling && abs(rawAcceleration) > LateralThresholds.SETTLE_MAX_MPS2
        val settledGeometry = a.settleBody?.lateral?.let { settledLateral(it, motion!!, at - a.settleSince!!) }
        val corrected = reciprocalRaw && settling && centered && normalizedLaneShape(a.settleBody!!.copy(lateral = settledGeometry))
        val settled = settling && (at - a.settleSince!! >= LateralThresholds.SETTLE_MS || newLobe || corrected || alreadyComplete)
        val finish = input is DriveInput.Finish || s.gear != DriveGear.DRIVE || angle == null ||
            at - summary.startMs >= if (motion == null) RecapThresholds.MAX_SEGMENT_MS else LateralThresholds.MAX_CANDIDATE_MS
        if ((!settling && centered) || settled || finish) {
            val timing = when { a.signaledBefore -> SignalTiming.BEFORE; a.signaledDuring != null -> SignalTiming.DURING
                summary.signalCoverage -> SignalTiming.NONE; else -> SignalTiming.UNKNOWN }
            // Weak high-speed signals need a quiet observation window, not a lucky
            // instantaneous return inside continuous hand corrections/noise.
            val interruptedWeak = settling && !reciprocalRaw && (newLobe || finish) && at - a.settleSince!! < LateralThresholds.SETTLE_MS
            val completeMotion = !interruptedWeak && (settling || centered || (finish && neutral && normalizedLaneShape(summary)))
            val body = if (alreadyComplete) summary.copy(endMs = a.neutralSince ?: at)
                else a.settleBody ?: summary.copy(endMs = a.neutralSince ?: at)
            summary = body.copy(endMs = body.endMs, timing = timing,
                lateSignalMs = a.signaledDuring?.let { it - summary.startMs },
                incomplete = if (motion == null) !centered && a.neutralSince == null else !completeMotion,
                inputGap = summary.inputGap, signalCoverage = summary.signalCoverage, speedMissing = summary.speedMissing,
                lateral = if (alreadyComplete) a.neutralMotion ?: motion else if (settling) settledGeometry else a.neutralMotion ?: motion)
            fun directional(before: Boolean, during: Long?) = DirectionalTiming(when {
                before -> SignalTiming.BEFORE
                during != null -> SignalTiming.DURING
                summary.signalCoverage -> SignalTiming.NONE
                else -> SignalTiming.UNKNOWN
            }, if (!before) during?.let { it - summary.startMs } else null)
            summary = summary.copy(leftSignal = directional(a.leftBefore, a.leftDuring),
                rightSignal = directional(a.rightBefore, a.rightDuring))
            classifySegment(summary, policy)?.let(cards::add)
            active = null
        } else active = a.copy(summary = summary)
    }
    s = s.copy(active = active, missingBrakeStart = if (s.gear != DriveGear.DRIVE || input is DriveInput.Finish) null else s.missingBrakeStart)
    if (input is DriveInput.Finish) s = s.copy(brakeWindow = null, recentDecelerations = emptyList(), decelerationAnchor = null,
        lastSpeedDropMs = null, lateralFilter = LateralFilter())
    return RecapStep(s, cards, elapsed, missingElapsed, parked)
}
