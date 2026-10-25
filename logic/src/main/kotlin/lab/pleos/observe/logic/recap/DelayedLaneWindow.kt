package lab.pleos.observe.logic.recap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** W16c: end-point-baseline delayed window detector. Mirrors spikes/w16c/lanewin.py detect().
 * Constants are modelling features chosen on the supplied reference, not a measured lane
 * position or a vehicle safety standard. */
object LaneWindowPolicy {
    const val SAMPLE_MS = 100L
    const val CAPACITY = 160 // 16s ring: 0.5s past edge + 8s window + 0.5s future edge + scan lag
    const val RATIO = 20.0 // effective steering ratio (median of per-vehicle estimates)
    const val WHEELBASE_M = 2.7
    const val EDGE = 5 // 0.5s end-point mean outside the window, on both sides
    const val MIN_SAMPLES = 25 // 2.5s
    const val MAX_SAMPLES = 80 // 8.0s
    const val LENGTH_STEP = 5 // 0.5s
    const val SCAN_STEP = 2 // 0.2s; start sequence numbers are odd (5 + 2k), like the reference
    const val FIRST_SCAN = 31L // first odd start at or after the 3s warm-up (30 samples)
    const val MIN_MPS = 25.0 / 3.6
    const val MEAN_MIN_MPS = 30.0 / 3.6
    const val CURVE_DELTA = 0.012 // rad/s, end-point yaw-rate difference (curve entry/exit)
    const val PEAK_RAD = 1.8 * PI / 180
    const val END_RAD = 1.5 * PI / 180
    const val END_FRACTION = 0.4
    const val STRONG_END_FRACTION = 0.8
    const val STRONG_ANGLE_DEG = 5f
    const val MIN_METERS = 2.5
    const val STRONG_MIN_METERS = 2.0
    const val MAX_METERS = 6.0
    const val LOBE_RATIO = 0.3
    const val STRONG_LOBE_RATIO = 0.12
    const val LOBE_PAIR_FRACTION = 0.8
    const val CORR_MIN = 0.7
    const val ONSET_FRACTION = 0.2
    /** 1.7.1: the manoeuvre onset used ONLY to end the blinker lead distance (SegmentSummary.maneuverOnsetMs): first
     * sample of the window with |residual| above this fraction of the peak residual. ONSET_FRACTION (the card start)
     * lags the real steering start by ~0.25s (median, synthetic lane waves and gyro-verified real lane changes);
     * 10% lags ~0.1s. Chosen with spikes/w17b/onset_eval.py (docs/WEEK17B_LEAD_ONSET_REPORT.md). Looking back before the
     * window start was tested and rejected: real pre-window steering wander made most windows worse, not better. */
    const val LEAD_ONSET_FRACTION = 0.10
    /** Spec value for "missed indicator is a visible warning". NOT wired into visibleReviewCards:
     * it would hide the AVD scenario G no-indicator lane (2.1~2.9m) that the required regression
     * expects. Apply it from card.summary.lateral.displacementM when scoring (see W16c report). */
    const val NO_SIGNAL_VISIBLE_METERS = 3.5
    const val SIGNAL_CAPACITY = 256
    const val SIGNAL_KEEP_MS = 20_000L
    const val NAV_CAPACITY = 64
    const val NAV_KEEP_MS = 30_000L
    const val CANDIDATE_CAPACITY = 512
}

data class LanePoint(val seq: Long, val atMs: Long, val angle: Float, val speed: Float, val yaw: Double)
data class LaneSignal(val atMs: Long, val value: Indicator?)
data class LaneSignalProof(val leftBefore: Boolean, val rightBefore: Boolean,
    val leftDuring: Long?, val rightDuring: Long?, val covered: Boolean)
data class LaneWindowGroup(val startMs: Long, val endMs: Long, val wallStartMs: Long,
    val best: SegmentSummary, val score: Double, val signal: LaneSignalProof,
    val windowStart: Long = startMs, val windowEnd: Long = endMs)
data class DelayedLaneState(val points: List<LanePoint> = emptyList(),
    val signals: List<LaneSignal> = emptyList(), val firstMs: Long? = null,
    val nextMs: Long? = null, val nextSeq: Long = 0, val nextScan: Long = LaneWindowPolicy.FIRST_SCAN,
    val feedAt: Long? = null, val heldAngle: Float? = null, val heldSpeed: Float? = null,
    val pending: LaneWindowGroup? = null, val candidates: List<LaneWindowGroup> = emptyList(),
    /** Receipt times of fresh "turn ahead within 80m" navigation hints (bounded, last 30s). */
    val nearTurns: List<Long> = emptyList())
data class DelayedLaneStep(val state: DelayedLaneState, val cards: List<ReviewCard>)

internal fun signalProof(history: List<LaneSignal>, start: Long, end: Long): LaneSignalProof {
    val lookback = start - RecapThresholds.SIGNAL_LOOKBACK_MS
    val atStart = history.lastOrNull { it.atMs <= lookback }
    fun before(kind: Indicator): Boolean = history.withIndex().any { (i, mark) ->
        mark.value == kind && mark.atMs < start &&
            (history.getOrNull(i + 1)?.atMs ?: Long.MAX_VALUE) >= lookback
    }
    fun during(kind: Indicator) = history.firstOrNull { it.value == kind && it.atMs in start..end }?.atMs
    val covered = atStart?.value != null && history.none { it.atMs in lookback..end && it.value == null }
    return LaneSignalProof(before(Indicator.LEFT), before(Indicator.RIGHT),
        during(Indicator.LEFT), during(Indicator.RIGHT), covered)
}

private fun overlaps(a: LaneWindowGroup, b: LaneWindowGroup) = a.windowStart < b.windowEnd && b.windowStart < a.windowEnd

/** Greedy score-ranked nonmaximum suppression. Never merge a transitive chain. */
internal fun selectLaneWindows(candidates: List<LaneWindowGroup>): List<LaneWindowGroup> {
    val selected = mutableListOf<LaneWindowGroup>()
    for (c in candidates.sortedWith(compareByDescending<LaneWindowGroup> { it.score }
        .thenBy { it.windowStart }.thenBy { it.windowEnd })) {
        if (selected.none { overlaps(it, c) }) selected += c
    }
    return selected.sortedBy { it.startMs }
}

private fun windowCard(group: LaneWindowGroup, policy: RecapPolicy): ReviewCard {
    val first = group.best.firstSign
    val proof = group.signal
    fun directional(before: Boolean, during: Long?) = DirectionalTiming(when {
        before -> SignalTiming.BEFORE; during != null -> SignalTiming.DURING
        proof.covered -> SignalTiming.NONE; else -> SignalTiming.UNKNOWN
    }, if (before) null else during?.minus(group.startMs))
    val left = directional(proof.leftBefore, proof.leftDuring)
    val right = directional(proof.rightBefore, proof.rightDuring)
    val any = directional(proof.leftBefore || proof.rightBefore, listOfNotNull(proof.leftDuring,proof.rightDuring).minOrNull())
    val timing = when(maneuverDirection(first,policy.convention)) {
        ManeuverDirection.LEFT -> left; ManeuverDirection.RIGHT -> right; else -> any
    }
    val summary = group.best.copy(startMs=group.startMs,endMs=group.endMs,wallStartMs=group.wallStartMs,
        timing=timing.timing,lateSignalMs=timing.lateMs,signalCoverage=proof.covered,leftSignal=left,rightSignal=right)
    // Unknown signal coverage never becomes an invented absence (classifySegment -> LANE_CHANGE,
    // hidden). A missing-indicator card additionally needs |lateral| >= 3.5m to be visible
    // (see visibleReviewCards); the stored card keeps its kind.
    return classifySegment(summary,policy)!!
}

private fun signum(x: Double) = if (x > 0) 1 else if (x < 0) -1 else 0

/** Checks every 0.5s length (2.5..8s) for one start sequence number [a]. Pure geometry
 * identical to lanewin.py detect(); [point] returns the ring-buffer sample or null.
 * At most 12 lengths x <=80 samples, a handful of passes each; <=15 KFLOP per start. */
private fun evaluateStart(a: Long, point: (Long) -> LanePoint?, signals: List<LaneSignal>,
    nearTurns: List<Long>, input: DriveInput, out: MutableList<LaneWindowGroup>) {
    val edge = LaneWindowPolicy.EDGE
    val startPoint = point(a) ?: return
    var y0 = 0.0
    for (k in 1..edge) y0 += (point(a - k) ?: return).yaw
    y0 /= edge
    var n = LaneWindowPolicy.MIN_SAMPLES
    while (n <= LaneWindowPolicy.MAX_SAMPLES) {
        val len = n; n += LaneWindowPolicy.LENGTH_STEP
        val b = a + len
        if (point(b) == null) break // no end-point sample (longer windows cannot have one either)
        val endPoint = point(b - 1) ?: continue
        // End-point mean over the samples that exist (all 5 unless the recording ended).
        var y1 = 0.0; var ec = 0
        for (k in 0 until edge) point(b + k)?.let { y1 += it.yaw; ec++ }
        y1 /= ec
        if (abs(y1 - y0) > LaneWindowPolicy.CURVE_DELTA) continue
        val win = ArrayList<LanePoint>(len)
        var sumSpeed = 0.0; var minSpeed = Float.MAX_VALUE; var maxSpeed = 0f
        var minAngle = Float.MAX_VALUE; var maxAngle = -Float.MAX_VALUE
        var ok = true
        for (i in 0 until len) {
            val p = point(a + i); if (p == null) { ok = false; break }
            win += p; sumSpeed += p.speed
            minSpeed = minOf(minSpeed, p.speed); maxSpeed = maxOf(maxSpeed, p.speed)
            minAngle = minOf(minAngle, p.angle); maxAngle = maxOf(maxAngle, p.angle)
        }
        if (!ok) continue
        if (sumSpeed / len < LaneWindowPolicy.MEAN_MIN_MPS || minSpeed < LaneWindowPolicy.MIN_MPS) continue
        val r = DoubleArray(len)
        val step = (y1 - y0) / (len - 1)
        for (i in 0 until len) r[i] = win[i].yaw - (y0 + step * i)
        var cum = 0.0; var peak = 0.0
        val psi = DoubleArray(len)
        for (i in 0 until len) { cum += r[i]; psi[i] = cum * 0.1; peak = max(peak, abs(psi[i])) }
        if (peak < LaneWindowPolicy.PEAK_RAD) continue
        val strong = minAngle <= -LaneWindowPolicy.STRONG_ANGLE_DEG && maxAngle >= LaneWindowPolicy.STRONG_ANGLE_DEG
        val psiEnd = psi[len - 1]
        if (abs(psiEnd) > max(LaneWindowPolicy.END_RAD,
                (if (strong) LaneWindowPolicy.STRONG_END_FRACTION else LaneWindowPolicy.END_FRACTION) * peak)) continue
        var lat = 0.0
        for (i in 0 until len) lat += win[i].speed * sin(psi[i]) * 0.1
        if (abs(lat) !in (if (strong) LaneWindowPolicy.STRONG_MIN_METERS else LaneWindowPolicy.MIN_METERS)..LaneWindowPolicy.MAX_METERS) continue
        // Two opposite lobes of the 0.5s-smoothed residual (zero padded like np.convolve 'same').
        val rs = DoubleArray(len) { i ->
            var s = 0.0
            for (j in i - 2..i + 2) if (j in 0 until len) s += r[j]
            s / edge
        }
        val lobes = ArrayList<Double>(); var s0 = 0
        for (i in 1..len) {
            if (i == len || signum(rs[i]) != signum(rs[s0])) {
                var area = 0.0
                for (j in s0 until i) area += rs[j]
                area *= 0.1
                if (abs(area) > 0) lobes += area
                s0 = i
            }
        }
        if (lobes.size < 2) continue
        val top = lobes.sortedByDescending { abs(it) }
        if (signum(top[0]) == signum(top[1])) continue
        if (abs(top[1]) < (if (strong) LaneWindowPolicy.STRONG_LOBE_RATIO else LaneWindowPolicy.LOBE_RATIO) * abs(top[0])) continue
        if (abs(top[0]) + abs(top[1]) < LaneWindowPolicy.LOBE_PAIR_FRACTION * lobes.sumOf { abs(it) }) continue
        // One-period sine correlation.
        var dot = 0.0; var rr = 0.0; var tt = 0.0
        for (i in 0 until len) {
            val t = sin(2 * PI * i / len)
            dot += r[i] * t; rr += r[i] * r[i]; tt += t * t
        }
        val corr = abs(dot) / (sqrt(rr) * sqrt(tt) + 1e-12)
        if (corr < LaneWindowPolicy.CORR_MIN) continue
        val score = corr * (1 - abs(psiEnd) / peak)
        val rMax = r.maxOf { abs(it) }
        var on = 0
        for (i in 0 until len) if (abs(r[i]) > LaneWindowPolicy.ONSET_FRACTION * rMax) { on = i; break }
        val coreStart = win[on].atMs; val coreEnd = endPoint.atMs
        // Lead-only onset: same residual, lower fraction. Always at or before [on] (0.10 < 0.2 of the same peak).
        var lead = 0
        for (i in 0 until len) if (abs(r[i]) > LaneWindowPolicy.LEAD_ONSET_FRACTION * rMax) { lead = i; break }
        var absolute = 0.0; for (i in 0 until len) absolute += abs(r[i]) * 0.1
        var travel = 0.0; for (p in win) travel += p.speed * 0.1
        val summary = SegmentSummary(coreStart, coreEnd, input.wallMs - (input.atMs - coreStart), minAngle, maxAngle,
            if (r[on] >= 0) 1 else -1, lobes.size - 1, minSpeed, maxSpeed, false, SignalTiming.UNKNOWN, false,
            // Same rule as the reducer's nearTurn(): a hint is fresh for 3s after receipt.
            nearNavigationTurn = nearTurns.any { it <= coreEnd && it + RecapThresholds.NAV_FRESH_MS >= coreStart },
            lateral = LateralSummary(psiEnd, peak, absolute, 0.0, lat, travel, windowDurationMs = len * 100),
            maneuverOnsetMs = win[lead].atMs)
        out += LaneWindowGroup(coreStart, coreEnd, summary.wallStartMs, summary, score,
            signalProof(signals, coreStart, coreEnd), startPoint.atMs, startPoint.atMs + len * 100L)
    }
}

/** Fixed 10Hz ring buffer, score-ranked non-overlapping windows.
 * A start is checked once its longest (8s) window has its 0.5s end-point mean:
 * 8.4s lookahead; a selected window is final once its end precedes the next unchecked
 * start (and no higher-scored overlapping window can still be displaced): <= ~9s delay.
 * At Finish the end-point mean uses the samples that exist (never zero padded).
 * Per 10Hz point: <=160 list entries copied; every second point one start = 12 lengths x
 * <=80 samples x ~8 linear passes (<=8 KFLOP) and, only while candidates exist, one NMS
 * over <=512 candidates (sort + <=512^2/2 overlap tests worst case, usually a few).
 * No loop scales with drive duration. Catch-up is capped by the 5s clock-gap rule.
 * Live storage <=160 motion points +256 signal transitions +512 candidate summaries.
 */
fun delayedLaneStep(previous: DelayedLaneState, input: DriveInput, angle: Float?, speed: Float?,
    gear: DriveGear, policy: RecapPolicy = RecapPolicy(), hint: TurnHint? = null): DelayedLaneStep {
    val at = max(input.atMs, previous.feedAt ?: input.atMs)
    val signals = previous.signals.toMutableList()
    if (input is DriveInput.Signal) {
        val value = input.value
        if (signals.lastOrNull()?.value != value || signals.isEmpty()) {
            signals += LaneSignal(input.atMs,value)
            signals.sortBy { it.atMs }
        }
    }
    val prior = signals.indexOfLast { it.atMs < at-LaneWindowPolicy.SIGNAL_KEEP_MS }
    if (prior > 0) signals.subList(0,prior).clear()
    if (signals.size > LaneWindowPolicy.SIGNAL_CAPACITY) signals.subList(0,signals.size-LaneWindowPolicy.SIGNAL_CAPACITY).clear()
    val nearTurns = previous.nearTurns.toMutableList()
    if (hint != null && hint.isTurn == true && hint.distanceMeters?.let { it.isFinite() && it in 0f..RecapThresholds.NAV_NEAR_METERS } == true &&
        nearTurns.lastOrNull() != hint.receivedAtMs) nearTurns += hint.receivedAtMs
    nearTurns.removeAll { it < at - LaneWindowPolicy.NAV_KEEP_MS }
    if (nearTurns.size > LaneWindowPolicy.NAV_CAPACITY) nearTurns.subList(0, nearTurns.size - LaneWindowPolicy.NAV_CAPACITY).clear()
    val valid = !policy.demoMissingSpeed && gear == DriveGear.DRIVE && angle != null && speed != null &&
        angle.isFinite() && speed.isFinite() && speed >= LaneWindowPolicy.MIN_MPS && speed <= 80f
    val gap = previous.feedAt?.let { at-it > RecapThresholds.MAX_CLOCK_GAP_MS } == true
    val finish = input is DriveInput.Finish || !valid || gap
    if (previous.firstMs == null && !valid) return DelayedLaneStep(DelayedLaneState(signals=signals,feedAt=at,nearTurns=nearTurns),emptyList())
    val first = previous.firstMs ?: at // grid origin = first valid sample, like lanewin.py resample()
    val points = previous.points.toMutableList()
    var seq = previous.nextSeq
    var next = previous.nextMs ?: first
    fun yawOf(v: Float, a: Float) = v.toDouble()*a*PI/180/(LaneWindowPolicy.RATIO*LaneWindowPolicy.WHEELBASE_M)
    if (!gap) while (next <= at) {
        // Held state is deliberate for ON_CHANGE SDK callbacks (steps, never interpolated);
        // exact grid-time input replaces the last grid point so callback order at one timestamp is harmless.
        val a = if (next == at && valid) angle else previous.heldAngle
        val v = if (next == at && valid) speed else previous.heldSpeed
        if (a != null && v != null) {
            points += LanePoint(seq++,next,a,v,yawOf(v,a))
            if (points.size > LaneWindowPolicy.CAPACITY) points.removeAt(0)
        }
        next += LaneWindowPolicy.SAMPLE_MS
    }
    if (valid && points.lastOrNull()?.atMs == at) {
        val old=points.last(); points[points.lastIndex]=old.copy(angle=angle!!,speed=speed!!,yaw=yawOf(speed,angle))
    }
    val last = points.lastOrNull()?.seq ?: -1
    val lower = points.firstOrNull()?.seq ?: 0
    fun point(index: Long): LanePoint? = points.getOrNull((index-lower).toInt())
    var scan = previous.nextScan
    val candidates = previous.candidates.toMutableList()
    previous.pending?.let { candidates += it } // compatibility for pure unit-state builders
    val cards = mutableListOf<ReviewCard>()
    val maxStart = if (finish) last else last - (LaneWindowPolicy.MAX_SAMPLES + LaneWindowPolicy.EDGE - 1)
    if (!gap) while (scan <= maxStart) {
        evaluateStart(scan, ::point, signals, nearTurns, input, candidates)
        scan += LaneWindowPolicy.SCAN_STEP
        if (candidates.size > LaneWindowPolicy.CANDIDATE_CAPACITY) {
            val keep = candidates.sortedByDescending { it.score }.take(LaneWindowPolicy.CANDIDATE_CAPACITY)
            candidates.clear(); candidates.addAll(keep)
        }
        if (finish || candidates.isEmpty()) continue
        val frontier = first + scan * LaneWindowPolicy.SAMPLE_MS // time of the next unchecked start
        if (candidates.none { it.windowEnd <= frontier }) continue
        for (c in selectLaneWindows(candidates)) {
            if (c.windowEnd > frontier) continue
            // A higher-scored overlapping window that is still open could yet be displaced by a
            // future candidate, which would free this one to be blocked by it. Wait for it.
            if (candidates.any { it !== c && it.score > c.score && overlaps(it, c) && it.windowEnd > frontier }) continue
            cards += windowCard(c, policy)
            candidates.removeAll { overlaps(it, c) }
        }
        candidates.removeAll { it.windowEnd < frontier - 20_000 }
    }
    if (finish) {
        // Gap: nothing more is scanned, but fully checked windows are real evidence and are kept.
        cards += selectLaneWindows(candidates).map { windowCard(it, policy) }
        return DelayedLaneStep(DelayedLaneState(signals=signals,feedAt=at,nearTurns=nearTurns),cards)
    }
    return DelayedLaneStep(DelayedLaneState(points,signals,first,next,seq,scan,at,angle,speed,
        candidates=candidates,nearTurns=nearTurns),cards)
}
