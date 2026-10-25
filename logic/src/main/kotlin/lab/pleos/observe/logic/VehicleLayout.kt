package lab.pleos.observe.logic

import kotlin.math.pow

enum class VehicleWidth { NARROW, MEDIUM, WIDE }
data class VehicleLayout(val width: VehicleWidth, val twoPanes: Boolean, val verticalButtons: Boolean, val marginDp: Int)
fun vehicleLayout(widthDp: Int): VehicleLayout {
    require(widthDp >= 0)
    val width = when { widthDp < 600 -> VehicleWidth.NARROW; widthDp < 1000 -> VehicleWidth.MEDIUM; else -> VehicleWidth.WIDE }
    return VehicleLayout(width, width == VehicleWidth.WIDE, width == VehicleWidth.NARROW, if (width == VehicleWidth.NARROW) 16 else 24)
}

/** Only READY may reveal restored UI. Incomplete initial callbacks keep it hidden, and a
 * known moving/restricted signal discards the checkpoint. Never restore a cached safety state.
 */
enum class RestorationGate { WAIT, DISCARD, READY }
fun restorationGate(safety: SafetyState): RestorationGate = when {
    safety.restrictionsActive == true || (safety.gear != null && safety.gear != Gear.PARK) ||
        safety.speedMps?.let { !it.isFinite() || it != 0f } == true -> RestorationGate.DISCARD
    shouldLock(safety) -> RestorationGate.WAIT
    else -> RestorationGate.READY
}

data class ScrollAnchor(val key: String? = null, val offsetDp: Int = 0, val yDp: Int = 0)
data class PaneScrollState(val primary: ScrollAnchor = ScrollAnchor(), val cards: ScrollAnchor = ScrollAnchor(), val cardsFocused: Boolean = false)
fun singlePaneAnchor(state: PaneScrollState): ScrollAnchor = if (state.cardsFocused) state.cards else state.primary

/** Reconnect first; only a fresh snapshot can validate an in-flight question after reflow. */
fun restoreLabCheckpoint(checkpoint: LabState, safety: SafetyState, snapshot: VehicleSnapshot, settled: Boolean): LabState? {
    if (restorationGate(safety) != RestorationGate.READY) return null
    if (checkpoint.screen != LabScreen.Diagnostics && !settled && !snapshot.seats.keys.containsAll(checkpoint.current.seats.keys)) return null
    return reduceLab(checkpoint.copy(safety = safety, foreground = true), LabAction.Snapshot(snapshot)).state
}

/** WCAG sRGB relative luminance; measures code colors, not physical display brightness/mm. */
fun contrastRatio(a: Int, b: Int): Double {
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color ushr shift) and 255) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
    val x = luminance(a); val y = luminance(b)
    return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
}
