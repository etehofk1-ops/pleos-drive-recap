package lab.pleos.observe.logic.recap

/** Closed debug transport; times are the receiver's elapsedRealtime, never host wall clock. */
data class DemoNaviFrame(val guiding: Boolean, val action: String?, val distanceMeters: Double?, val runId: String, val sequence: Long)

// Exact TurnType names inspected in navi-data 3.0.4; no numeric/substring inference.
val DEMO_TURN_TYPES = setOf("TURN_UNKNOWN", "TURN_STRAIGHT", "TURN_RIGHT", "TURN_LEFT", "TURN_KEEP_R", "TURN_KEEP_L",
    "TURN_SHARP_R", "TURN_SHARP_L", "TURN_UTURN", "TURN_ENTER_IC_R", "TURN_ENTER_IC_L", "TURN_ENTER_IC_C",
    "TURN_EXIT_IC_R", "TURN_EXIT_IC_L", "TURN_EXIT_IC_C", "TURN_ENTER_UNDER_R", "TURN_ENTER_UNDER_L", "TURN_ENTER_UNDER_C",
    "TURN_AVOID_UNDER_R", "TURN_AVOID_UNDER_L", "TURN_AVOID_UNDER_C", "TURN_ENTER_OVER_R", "TURN_ENTER_OVER_L", "TURN_ENTER_OVER_C",
    "TURN_AVOID_OVER_R", "TURN_AVOID_OVER_L", "TURN_AVOID_OVER_C", "TURN_ENTER_ROUNDABOUT_02", "TURN_ENTER_ROUNDABOUT_03",
    "TURN_ENTER_ROUNDABOUT_04", "TURN_ENTER_ROUNDABOUT_06", "TURN_ENTER_ROUNDABOUT_08", "TURN_ENTER_ROUNDABOUT_09",
    "TURN_ENTER_ROUNDABOUT_10", "TURN_ENTER_ROUNDABOUT_12", "TURN_EXIT_ROUNDABOUT", "TURN_WAY", "TURN_ARRIVE", "TURN_TUNNEL",
    "TURN_DEVIDE_MERGE", "TURN_TOLL", "TURN_ENTER_SERVICEAREA", "TURN_RESTAREA")

/** Small fixed-schema JSON decoder. Nested values, escapes, duplicate/unknown fields are
 * intentionally rejected. Only ASCII protocol strings are needed, no user text or coordinates.
 * Keeping parsing here permits real malformed-input tests on JVM without android/org.json stubs.
 */
fun parseDemoNavigation(json: String): DemoNaviFrame? = runCatching {
    require(json.length in 2..1024)
    val input = json.trim(); require(input.startsWith('{') && input.endsWith('}'))
    val entry = Regex("\\s*\"([A-Za-z][A-Za-z0-9]*)\"\\s*:\\s*(?:\"([^\"\\\\\\p{Cntrl}]*)\"|(true|false|null|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?))\\s*")
    val values = linkedMapOf<String, Pair<Boolean, String>>()
    var cursor = 1
    while (cursor < input.lastIndex) {
        val m = requireNotNull(entry.matchAt(input, cursor))
        val key = m.groupValues[1]; require(key !in values)
        values[key] = (m.groups[2] != null) to (m.groups[2]?.value ?: m.groupValues[3])
        cursor = m.range.last + 1
        if (cursor == input.lastIndex) break
        require(input[cursor] == ','); cursor++
        require(input.substring(cursor, input.lastIndex).isNotBlank())
    }
    require(cursor == input.lastIndex && values.keys == setOf("guiding", "action", "distanceMeters", "runId", "sequence"))
    val (gQuoted, g) = values.getValue("guiding"); require(!gQuoted && g in setOf("true", "false"))
    val (aQuoted, a) = values.getValue("action")
    val action = if (!aQuoted && a == "null") null else a.also { require(aQuoted && it in DEMO_TURN_TYPES) }
    val (dQuoted, d) = values.getValue("distanceMeters"); require(!dQuoted)
    val distance = if (d == "null") null else d.toDouble().also { require(it.isFinite() && it in 0.0..10_000_000.0) }
    val (rQuoted, run) = values.getValue("runId")
    require(rQuoted && run.matches(Regex("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")))
    val (sQuoted, rawSequence) = values.getValue("sequence"); require(!sQuoted)
    val sequence = rawSequence.toLong().also { require(it in 0..9_007_199_254_740_991L) }
    val guiding = g == "true"
    require(if (guiding) action != null else action == null && distance == null)
    DemoNaviFrame(guiding, action, distance, run.lowercase(), sequence)
}.getOrNull()

data class DemoNavigationState(val frame: DemoNaviFrame? = null, val receivedAtMs: Long? = null,
    val epoch: Long = 0, val expired: Boolean = true)

fun acceptDemoNavigation(state: DemoNavigationState, frame: DemoNaviFrame, nowMs: Long, allowNewRun: Boolean): DemoNavigationState? {
    require(nowMs >= 0)
    val old = state.frame
    if (old != null && old.runId != frame.runId && !allowNewRun) return null
    if (old?.runId == frame.runId && frame.sequence <= old.sequence) return null
    if (state.receivedAtMs?.let { nowMs < it } == true) return null
    val changed = state.expired || old?.runId != frame.runId || old.guiding != frame.guiding
    return DemoNavigationState(frame, nowMs, state.epoch + if (changed) 1 else 0, expired = false)
}

fun demoNavigationSnapshot(state: DemoNavigationState, nowMs: Long): NavigationSnapshot? {
    val at = state.receivedAtMs ?: return null
    val frame = state.frame ?: return null
    if (state.expired || nowMs - at !in 0..NavigationThresholds.FRESH_MS) return null
    return NavigationSnapshot(at, frame.guiding, frame.action, frame.distanceMeters, epoch = state.epoch, origin = NavigationOrigin.DEMO)
}

fun expireDemoNavigation(state: DemoNavigationState, nowMs: Long): DemoNavigationState =
    if (!state.expired && demoNavigationSnapshot(state, nowMs) == null) state.copy(expired = true, epoch = state.epoch + 1) else state
