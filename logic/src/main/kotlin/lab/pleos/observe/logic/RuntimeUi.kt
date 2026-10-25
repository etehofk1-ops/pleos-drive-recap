package lab.pleos.observe.logic

/** UI route only. Never includes safety, diagnostic drafts, vehicle values or AI payloads. */
data class RecapUiRoute(val recap: Boolean = false, val offset: Int = 0,
    val scroll: PaneScrollState = PaneScrollState(), val capturedAtMs: Long)
const val UI_RESTORE_TTL_MS = 30 * 60 * 1_000L
const val RECAP_PAGE_SIZE = 100
const val UI_PROPERTY_LOG_INTERVAL_MS = 5_000L

data class UiRestoration(val pending: RecapUiRoute? = null, val restore: RecapUiRoute? = null)
/** Only stable UI keys cross the OS saved-state boundary; never copy text/value-based tags. */
fun persistedRecapRoute(route: RecapUiRoute): RecapUiRoute {
    fun anchor(a: ScrollAnchor): ScrollAnchor {
        val allowed = a.key in setOf("summary", "daily-message", "home-tiles") ||
            a.key?.matches(Regex("tile:(home|recap):[0-9]+|card:[0-9]+-[0-9]+-(steer|brake|brakeUnknown)")) == true
        return a.copy(key = a.key.takeIf { allowed })
    }
    return route.copy(scroll = route.scroll.copy(primary = anchor(route.scroll.primary), cards = anchor(route.scroll.cards)))
}

fun currentRecapResponse(requestEpoch: Int, currentEpoch: Int, requestOffset: Int, currentOffset: Int): Boolean =
    requestEpoch == currentEpoch && requestOffset == currentOffset

/** WAIT retains the original timestamp even across another pause/save/process recreation. */
fun restoreRecapRoute(route: RecapUiRoute?, gate: RestorationGate, nowMs: Long): UiRestoration {
    if (route == null || gate == RestorationGate.DISCARD || nowMs < route.capturedAtMs ||
        nowMs - route.capturedAtMs > UI_RESTORE_TTL_MS) return UiRestoration()
    return if (gate == RestorationGate.READY) UiRestoration(restore = route) else UiRestoration(pending = route)
}

fun recapPageOffset(requested: Int): Int = (requested.coerceAtLeast(0) / RECAP_PAGE_SIZE) * RECAP_PAGE_SIZE
fun nextRecapPage(offset: Int, loaded: Int, total: Int): Int? =
    if (offset.toLong() + loaded < total && loaded > 0) offset + RECAP_PAGE_SIZE else null

/** Half-open viewport range with one-row overscan. Only one expensive tile is built per frame. */
fun visibleRecapRows(tops: List<Int>, bottoms: List<Int>, viewportTop: Int, viewportBottom: Int): IntRange {
    require(tops.size == bottoms.size)
    val first = tops.indices.firstOrNull { bottoms[it] > viewportTop && tops[it] < viewportBottom } ?: return IntRange.EMPTY
    val last = tops.indices.last { bottoms[it] > viewportTop && tops[it] < viewportBottom }
    return (first - 1).coerceAtLeast(0)..(last + 1).coerceAtMost(tops.lastIndex)
}
fun nextRecapRow(visible: IntRange, stale: Set<Int>): Int? = visible.firstOrNull { it in stale }

/** Check before formatting timestamps/value strings. Release produces no per-property log. */
fun shouldLogUiProperty(debug: Boolean, nowMs: Long, lastMs: Long?): Boolean = debug &&
    (lastMs == null || nowMs < lastMs || nowMs - lastMs >= UI_PROPERTY_LOG_INTERVAL_MS)

/** Recorder's input subscriptions/rules are unchanged. Release retains decisions only. */
fun shouldLogDriveEvent(signal: String, debug: Boolean): Boolean = debug ||
    signal == "recap.card" || signal == "recap.segment.start" || signal.startsWith("recap.motion.") ||
    signal.startsWith("recap.summary.")
