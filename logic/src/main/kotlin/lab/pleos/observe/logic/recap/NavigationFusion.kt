package lab.pleos.observe.logic.recap

import kotlin.math.abs
import kotlin.math.max

/** Prototype association windows, not a safety standard. 50m requested by W9; 3s permits
 * binder/TBT delivery jitter. Never interpret an unverified SDK distance unit as metres.
 * No coordinates, description, route IDs or road names are accepted by this boundary.
 */
object NavigationThresholds {
    const val NEAR_METERS = 50.0
    const val PRE_MS = 3_000L
    const val POST_MS = 3_000L
    const val FRESH_MS = 3_000L
    // Conservative 5 km/h margin below the original 30 km/h rule, only with complete nav evidence.
    const val ASSIST_MIN_MPS = 25f / 3.6f
    const val KEEP_MS = RecapThresholds.MAX_SEGMENT_MS + PRE_MS + POST_MS
}

enum class NavigationLabel { ESTIMATED, CONFIRMED, ASSISTED, MERGE_EXIT, CONFLICT }
enum class NavigationTurn { LEFT, RIGHT, UTURN }
enum class NavigationOrigin { PLEOS, DEMO }

/** Exact navi-data 3.0.4 TurnType names are retained only in transient memory.
 * AAR: docs/WEEK9_NAVI_FUSION.md. Unknown names are never decoded by substring/numeric guess.
 */
data class NavigationCue(val action: String, val distanceMeters: Double?)
data class NavigationSnapshot(val receivedAtMs: Long, val guiding: Boolean?, val action: String?,
    val distanceMeters: Double?, val passageAtMs: Long? = null, val epoch: Long = 0,
    val following: List<NavigationCue> = emptyList(), val origin: NavigationOrigin = NavigationOrigin.PLEOS)

fun navigationTurn(action: String?): NavigationTurn? = when (action) {
    "TURN_LEFT", "TURN_SHARP_L" -> NavigationTurn.LEFT
    "TURN_RIGHT", "TURN_SHARP_R" -> NavigationTurn.RIGHT
    "TURN_UTURN" -> NavigationTurn.UTURN
    else -> null
}

private val mergeExitActions = setOf("TURN_ENTER_IC_R", "TURN_ENTER_IC_L", "TURN_ENTER_IC_C",
    "TURN_EXIT_IC_R", "TURN_EXIT_IC_L", "TURN_EXIT_IC_C", "TURN_DEVIDE_MERGE")
private fun near(n: NavigationSnapshot, s: SegmentSummary): Boolean =
    n.receivedAtMs in (s.startMs - NavigationThresholds.PRE_MS)..(s.endMs + NavigationThresholds.POST_MS) &&
        (n.distanceMeters?.let { it.isFinite() && it in 0.0..NavigationThresholds.NEAR_METERS } == true ||
            n.passageAtMs?.let { it in s.startMs..s.endMs } == true)

/** No nav / stale / no usable evidence returns the ORIGINAL rule card unchanged.
 * Absence of TBT is not evidence of absence of a turn. A concrete known straight or far
 * turn/merge must be fresh across the entire segment before lane confidence can increase.
 */
fun fuseNavigation(card: ReviewCard, observations: List<NavigationSnapshot>): ReviewCard {
    val result = fuseNavigationEvidence(card, observations)
    val demo = observations.any { it.origin == NavigationOrigin.DEMO && it.receivedAtMs in
        (card.summary.startMs - NavigationThresholds.PRE_MS)..(card.summary.endMs + NavigationThresholds.POST_MS) }
    return if (demo && card.summary.firstSign != 0) result.copy(demoNavigation = true) else result
}

private fun fuseNavigationEvidence(card: ReviewCard, observations: List<NavigationSnapshot>): ReviewCard {
    if (card.summary.firstSign == 0) return card // brake cards are independent
    val s = card.summary
    val all = observations.sortedBy { it.receivedAtMs }
    val atStart = all.lastOrNull { it.receivedAtMs <= s.startMs }
    val relevant = all.filter { it.receivedAtMs in (s.startMs - NavigationThresholds.PRE_MS)..(s.endMs + NavigationThresholds.POST_MS) }
    if (relevant.isEmpty() || relevant.any { it.guiding != true } || relevant.map { it.epoch }.distinct().size != 1 ||
        relevant.map { it.origin }.distinct().size != 1) return card
    val cues = relevant.flatMap { n -> listOf(n) + n.following.map { n.copy(action = it.action, distanceMeters = it.distanceMeters, following = emptyList()) } }
    val turns = cues.filter { navigationTurn(it.action) != null && near(it, s) }
    val peak = max(abs(s.minAngle), abs(s.maxAngle))
    val slowLarge = !s.speedMissing && s.maxSpeedMps?.let { it <= RecapThresholds.TURN_MAX_MPS } == true &&
        peak >= RecapThresholds.TURN_MIN_ANGLE && s.durationMs >= RecapThresholds.TURN_MIN_MS
    if (turns.isNotEmpty()) {
        val actions = turns.mapNotNull { navigationTurn(it.action) }.distinct()
        if (!slowLarge && s.speedMissing) return card
        if (!slowLarge || actions.size != 1) return card.copy(kind = ReviewKind.UNSURE,
            uncertainty = card.uncertainty + Uncertainty.NAV_CONFLICT, navigation = NavigationLabel.CONFLICT)
        val turn = actions.single()
        // Direction comes from navigation, never from an assumed steering sign. U-turn uses left.
        val signal = if (turn == NavigationTurn.RIGHT) s.rightSignal else s.leftSignal
        val timing = signal?.timing ?: SignalTiming.UNKNOWN
        val flags = card.uncertainty - Uncertainty.SHAPE_AMBIGUOUS - Uncertainty.SIGNAL_GAP
        val remaining = if (timing == SignalTiming.UNKNOWN) flags + Uncertainty.SIGNAL_GAP else flags
        val kind = when (timing) {
            SignalTiming.BEFORE -> ReviewKind.TURN_SIGNALLED
            SignalTiming.NONE -> ReviewKind.TURN_NO_SIGNAL
            SignalTiming.DURING -> if ((signal?.lateMs ?: 0) > 0) ReviewKind.TURN_LATE_SIGNAL else ReviewKind.TURN
            SignalTiming.UNKNOWN -> ReviewKind.TURN
        }
        val finalFlags = if (kind == ReviewKind.TURN) remaining + Uncertainty.SIGNAL_GAP else remaining
        return card.copy(kind = if (finalFlags.isEmpty()) kind else ReviewKind.UNSURE, candidate = kind,
            summary = s.copy(timing = timing, lateSignalMs = signal?.lateMs, nearNavigationTurn = true),
            direction = if (turn == NavigationTurn.RIGHT) ManeuverDirection.RIGHT else ManeuverDirection.LEFT,
            navigation = NavigationLabel.CONFIRMED, navigationTurn = turn, uncertainty = finalFlags)
    }
    val laneShape = if (s.lateral != null) normalizedLaneShape(s) else s.reversals == 1 && s.durationMs in RecapThresholds.MIN_SEGMENT_MS..RecapThresholds.LANE_MAX_MS &&
        s.minAngle <= -RecapThresholds.ANGLE_ENTER && s.maxAngle >= RecapThresholds.ANGLE_ENTER && peak <= RecapThresholds.LANE_MAX_ANGLE
    if (!laneShape) return card
    val merge = cues.any { it.action in mergeExitActions && near(it, s) }
    // Do not promote missing speed, input gaps, incomplete or signal-unknown observations.
    val removable = setOf(Uncertainty.SHAPE_AMBIGUOUS)
    if (card.uncertainty.any { it !in removable } || s.speedMissing || !s.signalCoverage ||
        s.minSpeedMps?.let { it >= NavigationThresholds.ASSIST_MIN_MPS } != true) return card
    val covered = atStart != null && s.startMs - atStart.receivedAtMs in 0..NavigationThresholds.FRESH_MS &&
        relevant.last().receivedAtMs >= s.endMs - NavigationThresholds.FRESH_MS &&
        relevant.zipWithNext().all { (a, b) -> b.receivedAtMs - a.receivedAtMs <= NavigationThresholds.FRESH_MS }
    val knownClear = covered && cues.all { n ->
        n.action == "TURN_STRAIGHT" || ((navigationTurn(n.action) != null || n.action in mergeExitActions) &&
            n.distanceMeters?.let { it.isFinite() && it > NavigationThresholds.NEAR_METERS } == true)
    }
    if (!merge && !knownClear) return card
    val kind = when (s.timing) {
        SignalTiming.BEFORE -> ReviewKind.LANE_SIGNALLED
        SignalTiming.NONE -> ReviewKind.LANE_NO_SIGNAL
        SignalTiming.DURING -> if ((s.lateSignalMs ?: 0) > 0) ReviewKind.LANE_LATE_SIGNAL else return card
        SignalTiming.UNKNOWN -> return card
    }
    return card.copy(kind = kind, candidate = kind, uncertainty = emptySet(),
        navigation = if (merge) NavigationLabel.MERGE_EXIT else NavigationLabel.ASSISTED)
}

data class PendingNavigationCard(val card: ReviewCard, val queuedAtMs: Long)
data class NavigationFusionState(val observations: List<NavigationSnapshot> = emptyList(),
    val pending: List<PendingNavigationCard> = emptyList())
data class NavigationFusionStep(val state: NavigationFusionState, val cards: List<ReviewCard>)

/** Bounded, memory-only association. Delay a steering verdict up to 3s to accept late TBT.
 * Even without nav the same rule result is emitted; stop/rollover flushes the pending queue.
 */
fun navigationFusionStep(previous: NavigationFusionState, atMs: Long, cards: List<ReviewCard> = emptyList(),
    snapshot: NavigationSnapshot? = null, finish: Boolean = false): NavigationFusionStep {
    val observations = (previous.observations + listOfNotNull(snapshot))
        .filter { atMs - it.receivedAtMs in 0..NavigationThresholds.KEEP_MS }.sortedBy { it.receivedAtMs }.takeLast(256)
    val queue = previous.pending + cards.filter { it.summary.firstSign != 0 }.map { PendingNavigationCard(it, atMs) }
    val (ready, waiting) = queue.partition { finish || atMs - it.queuedAtMs >= NavigationThresholds.POST_MS }
    val result = cards.filter { it.summary.firstSign == 0 } + ready.map { fuseNavigation(it.card, observations) }
    // Discard decided evidence, keeping only lookback for following/pending segments.
    val cutoff = ready.maxOfOrNull { it.card.summary.endMs }?.let { end ->
        minOf(end, waiting.minOfOrNull { it.card.summary.startMs } ?: end) - NavigationThresholds.PRE_MS
    }
    val retained = if (cutoff == null) observations else observations.filter { it.receivedAtMs >= cutoff }
    return NavigationFusionStep(if (finish) NavigationFusionState() else NavigationFusionState(retained, waiting), result)
}

fun navigationLabel(card: ReviewCard): String = when (card.navigation) {
    NavigationLabel.ESTIMATED -> "추정"
    NavigationLabel.CONFIRMED -> if (card.demoNavigation) "내비 확인(모의)" else "내비 확인"
    NavigationLabel.ASSISTED -> if (card.demoNavigation) "내비 보조(모의)" else "내비 보조"
    NavigationLabel.MERGE_EXIT -> (if (card.demoNavigation) "내비 보조(모의)" else "내비 보조") + " · 합류/진출 구간"
    NavigationLabel.CONFLICT -> "추정 · 내비와 핸들 불일치"
}

fun demoNavigationBadge(enabled: Boolean, cards: List<ReviewCard>): String? = when {
    enabled -> "시연용 모의 내비 입력 사용 중"
    cards.any { it.demoNavigation } -> "시연용 모의 내비 판정 포함"
    else -> null
}
