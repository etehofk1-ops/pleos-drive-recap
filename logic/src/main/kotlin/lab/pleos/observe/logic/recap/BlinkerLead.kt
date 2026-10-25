package lab.pleos.observe.logic.recap

import kotlin.math.max

/** 1.7.0. Prototype heuristics, not a traffic-law judgement. The 30m / 100m reference is a recommended
 * distance only; the app cannot know whether a road is an expressway, so the 100m reference is chosen
 * from the mean speed of the lead interval alone (>= 80km/h). */
object BlinkerThresholds {
    const val REFERENCE_M = 30f
    const val HIGHWAY_REFERENCE_M = 100f
    const val HIGHWAY_MIN_MPS = 80f / 3.6f
    const val ROUND_M = 5
    /** B: time at >= 30km/h with one side left on. */
    const val LEFT_ON_MIN_MS = 15_000L
    const val LEFT_ON_MIN_MPS = 30f / 3.6f
    /** A flash gap shorter than this is the same continuous switch-on (same as the 3s signal lookback). */
    const val BRIDGE_MS = RecapThresholds.SIGNAL_LOOKBACK_MS
    /** Lane windows are confirmed up to ~9s after they end; a run is judged only after that, so a late
     * lane/turn card can still restart the count (spec: count again from the end of that card). */
    const val VERDICT_LAG_MS = 10_000L
    /** Odometer history. Reducer-path (slow turn) cards can start up to MAX_SEGMENT_MS before they are emitted. */
    const val TRACK_KEEP_MS = 70_000L
    const val POINT_STEP_MS = 100L
    const val RUN_CAPACITY = 12
}

/** One decimated sample of the integrated odometer ([odoM]) and of the "left on at speed" counter ([onMs]/[onM]).
 * [gen] changes whenever the speed series was broken, so a distance never bridges missing speed. */
data class TrackPoint(val atMs: Long, val odoM: Double, val gen: Int, val onMs: Long, val onM: Double)

/** One continuous switch-on of a side (short OFF gaps bridged). Serves A (lead distance) and B (left on). */
data class BlinkerRun(
    val side: Indicator, val onMs: Long, val onOdoM: Double?, val onGen: Int, val wallDeltaMs: Long,
    val baseAtMs: Long, val baseCumMs: Long, val baseCumM: Double,
    val lastOnMs: Long, val offMs: Long? = null,
    val closedAtMs: Long? = null, val endCumMs: Long = baseCumMs, val endCumM: Double = baseCumM,
    val gap: Boolean = false, val reported: Boolean = false,
    val minMps: Float? = null, val maxMps: Float? = null,
)

/** Bounded (<= ~700 points + 12 runs): no loop scales with drive duration. Never persisted. */
data class BlinkerState(
    val odoM: Double = 0.0, val gen: Int = 0,
    val cumMs: Long = 0, val cumM: Double = 0.0,
    val points: List<TrackPoint> = emptyList(), val runs: List<BlinkerRun> = emptyList(),
)

private val MANEUVER_KINDS = setOf(ReviewKind.LANE_CHANGE, ReviewKind.LANE_SIGNALLED, ReviewKind.LANE_NO_SIGNAL,
    ReviewKind.LANE_LATE_SIGNAL, ReviewKind.TURN, ReviewKind.TURN_NO_SIGNAL, ReviewKind.TURN_LATE_SIGNAL, ReviewKind.TURN_SIGNALLED)
private fun isManeuver(card: ReviewCard) = card.summary.firstSign != 0 && card.candidate in MANEUVER_KINDS

internal fun odoAt(points: List<TrackPoint>, t: Long): Pair<Double, Int>? {
    if (points.isEmpty() || t < points.first().atMs) return null
    var lo = 0; var hi = points.lastIndex // last index with atMs <= t
    while (lo < hi) { val mid = (lo + hi + 1) / 2; if (points[mid].atMs <= t) lo = mid else hi = mid - 1 }
    val p = points[lo]
    if (lo == points.lastIndex) return if (t - p.atMs <= BlinkerThresholds.POINT_STEP_MS) p.odoM to p.gen else null
    val n = points[lo + 1]
    if (n.gen != p.gen) return null // speed was missing in between
    val f = if (n.atMs == p.atMs) 0.0 else (t - p.atMs).toDouble() / (n.atMs - p.atMs)
    return (p.odoM + (n.odoM - p.odoM) * f) to p.gen
}

internal fun cumAt(points: List<TrackPoint>, t: Long, curMs: Long, curM: Double): Pair<Long, Double>? {
    if (points.isEmpty() || t < points.first().atMs) return null
    if (t >= points.last().atMs) return curMs to curM
    var lo = 0; var hi = points.lastIndex
    while (lo < hi) { val mid = (lo + hi + 1) / 2; if (points[mid].atMs <= t) lo = mid else hi = mid - 1 }
    val p = points[lo]; val n = points[lo + 1]
    val f = if (n.atMs == p.atMs) 0.0 else (t - p.atMs).toDouble() / (n.atMs - p.atMs)
    return (p.onMs + ((n.onMs - p.onMs) * f).toLong()) to (p.onM + (n.onM - p.onM) * f)
}

/** Distance (m) and time (ms) from switching [side] on to the manoeuvre, or null without a complete speed integral.
 * [start] (the card start) only selects which switch-on the card's BEFORE proof refers to; the lead ends at [onset]
 * (the manoeuvre onset, <= [start]). A switch-on between [onset] and [start] means "switched on as it began": 0 m. */
internal fun signalLead(b: BlinkerState, side: Indicator, start: Long, onset: Long = start): Pair<Float, Long>? {
    val run = b.runs.lastOrNull {
        it.side == side && it.onMs < start && (it.offMs == null || it.offMs >= start - RecapThresholds.SIGNAL_LOOKBACK_MS)
    } ?: return null
    val end = minOf(onset, start)
    if (end <= run.onMs) return 0f to 0L
    val onOdo = run.onOdoM ?: return null
    val (odo, gen) = odoAt(b.points, end) ?: return null
    if (gen != run.onGen) return null
    val meters = odo - onOdo
    if (!meters.isFinite() || meters < -1.0) return null
    return max(0.0, meters).toFloat() to (end - run.onMs)
}

/** Adds the lead to a signalled card (timing BEFORE). The card kind never changes. */
internal fun attachSignalLead(card: ReviewCard, b: BlinkerState, policy: RecapPolicy): ReviewCard {
    val s = card.summary
    if (policy.demoMissingSpeed || s.firstSign == 0 || s.timing != SignalTiming.BEFORE) return card
    fun lead(side: Indicator, t: DirectionalTiming?): DirectionalTiming? {
        if (t == null || t.timing != SignalTiming.BEFORE) return t
        val r = signalLead(b, side, s.startMs, s.maneuverOnsetMs ?: s.startMs) ?: return t
        return t.copy(leadMeters = r.first, leadMs = r.second)
    }
    val left = lead(Indicator.LEFT, s.leftSignal); val right = lead(Indicator.RIGHT, s.rightSignal)
    val chosen = when (maneuverDirection(s.firstSign, policy.convention)) {
        ManeuverDirection.LEFT -> left
        ManeuverDirection.RIGHT -> right
        // Unknown steering sign: whichever side was on; if both, the shorter (conservative) lead.
        ManeuverDirection.UNKNOWN -> listOfNotNull(left, right).filter { it.timing == SignalTiming.BEFORE && it.leadMeters != null }
            .minByOrNull { it.leadMeters!! }
    }?.takeIf { it.timing == SignalTiming.BEFORE }
    if (left == s.leftSignal && right == s.rightSignal && chosen?.leadMeters == null) return card
    return card.copy(summary = s.copy(leftSignal = left, rightSignal = right,
        signalLeadMeters = chosen?.leadMeters, signalLeadMs = chosen?.leadMs))
}

private fun blinkerCard(r: BlinkerRun): ReviewCard? {
    val count = max(0L, r.endCumMs - r.baseCumMs); val meters = max(0.0, r.endCumM - r.baseCumM)
    if (r.gap || count < BlinkerThresholds.LEFT_ON_MIN_MS) return null
    val start = r.baseAtMs; val end = max(start, r.offMs ?: r.lastOnMs)
    val wall = start + r.wallDeltaMs
    val summary = SegmentSummary(start, end, wall, 0f, 0f, 0, 0, r.minMps, r.maxMps, false, SignalTiming.BEFORE, true,
        blinkerOnMs = count, blinkerOnMeters = meters.toFloat())
    return ReviewCard("$wall-$start-blinkerOn", wall, ReviewKind.BLINKER_LEFT_ON, ReviewKind.BLINKER_LEFT_ON, summary,
        if (r.side == Indicator.LEFT) ManeuverDirection.LEFT else ManeuverDirection.RIGHT)
}

/** Post-processes one [recapCore] step: integrates speed into an odometer, tracks continuous blinker runs,
 * attaches the lead distance (A) and judges "left on" runs (B). Pure; no raw history beyond the bounded state. */
internal fun blinkerStep(previous: RecapEngineState, input: DriveInput, policy: RecapPolicy, core: RecapStep, dropped: Boolean): RecapStep {
    val s = core.state
    val finish = input is DriveInput.Finish
    val b = previous.blinker
    val at = s.lastMs ?: input.atMs
    val prevMs = previous.lastMs
    val dt = if (dropped || prevMs == null) 0L else (at - prevMs).coerceAtLeast(0)
    val v0 = if (dropped || prevMs == null) null else speedAt(previous, prevMs, policy)
    val v1 = if (dropped) null else speedAt(s, at, policy)
    var odo = b.odoM; var gen = b.gen; var cumMs = b.cumMs; var cumM = b.cumM
    var runs = b.runs; var points = b.points
    fun update(pred: (BlinkerRun) -> Boolean, f: (BlinkerRun) -> BlinkerRun) { runs = runs.map { if (pred(it)) f(it) else it } }
    fun close(r: BlinkerRun, gapped: Boolean = false) = r.copy(closedAtMs = at, endCumMs = cumMs, endCumM = cumM, gap = r.gap || gapped)

    if (dt > 0) {
        // Speed integral: trapezoid between two fresh speeds. Any other interval is a break (gen changes).
        if (dt <= RecapThresholds.MAX_CLOCK_GAP_MS && v0 != null && v1 != null) odo += (v0 + v1) / 2.0 * dt / 1000.0 else gen++
        val onSide = previous.indicator == Indicator.LEFT || previous.indicator == Indicator.RIGHT
        if (onSide && dt > RecapThresholds.MAX_CLOCK_GAP_MS) update({ it.closedAtMs == null }) { it.copy(gap = true) }
        else if (onSide && previous.gear == DriveGear.DRIVE && v0 != null && v0 >= BlinkerThresholds.LEFT_ON_MIN_MPS) {
            cumMs += dt; cumM += (if (v1 != null) (v0 + v1) / 2.0 else v0.toDouble()) * dt / 1000.0
            update({ it.closedAtMs == null }) { it.copy(minMps = minOf(it.minMps ?: v0, v0), maxMps = maxOf(it.maxMps ?: v0, v0)) }
        }
    }
    if (!dropped && v1 != null && (points.isEmpty() || at - points.last().atMs >= BlinkerThresholds.POINT_STEP_MS))
        points = (points + TrackPoint(at, odo, gen, cumMs, cumM)).dropWhile { at - it.atMs > BlinkerThresholds.TRACK_KEEP_MS }

    if (!dropped || input is DriveInput.Signal) {
        update({ it.closedAtMs == null && it.offMs != null && at - it.offMs > BlinkerThresholds.BRIDGE_MS }) { close(it) }
        val open = runs.lastOrNull { it.closedAtMs == null }
        when (val cur = s.indicator) {
            Indicator.LEFT, Indicator.RIGHT -> {
                if (open != null && open.side == cur) update({ it === open }) { it.copy(offMs = null, lastOnMs = at) }
                else {
                    if (open != null) update({ it === open }) { close(it) }
                    val onAt = if (input is DriveInput.Signal) input.atMs else at
                    runs = runs + BlinkerRun(cur, onAt, if (!dropped && v1 != null) odo else null, gen, input.wallMs - input.atMs,
                        onAt, cumMs, cumM, at)
                }
            }
            Indicator.OFF -> if (open != null && open.offMs == null) update({ it === open }) { it.copy(offMs = at) }
            null -> if (open != null) update({ it === open }) { close(it, gapped = true) }
        }
    }

    // A lane/turn card restarts the count at the end of that manoeuvre.
    for (c in core.cards) if (isManeuver(c)) {
        val end = c.summary.endMs
        val base = cumAt(points, end, cumMs, cumM) ?: continue
        update({ !it.reported && end > it.baseAtMs && end >= it.onMs }) {
            it.copy(baseAtMs = end, baseCumMs = max(it.baseCumMs, base.first), baseCumM = max(it.baseCumM, base.second))
        }
    }

    val force = finish || core.parked
    if (force) update({ it.closedAtMs == null }) { close(it) }
    val verdicts = mutableListOf<ReviewCard>()
    runs = runs.map { r ->
        val closedAt = r.closedAtMs
        if (r.reported || closedAt == null) r
        else if (force || r.gap || at - closedAt >= BlinkerThresholds.VERDICT_LAG_MS) { blinkerCard(r)?.let(verdicts::add); r.copy(reported = true) }
        else r
    }
    var excess = runs.size - BlinkerThresholds.RUN_CAPACITY
    if (excess > 0) runs = runs.filter { if (excess > 0 && it.reported) { excess--; false } else true }

    val next = BlinkerState(odo, gen, cumMs, cumM, points, runs)
    return core.copy(state = s.copy(blinker = if (finish) BlinkerState() else next),
        cards = core.cards.map { attachSignalLead(it, next, policy) } + verdicts)
}
