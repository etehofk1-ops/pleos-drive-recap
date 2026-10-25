package lab.pleos.observe.logic

import lab.pleos.observe.logic.recap.*
import kotlin.math.roundToInt

/** Official local design_tokens.json, Color collection 994235... (49421:*).
 * Keep exact source colors; semantic colors/32% disabled ink are not automatically readable text.
 */
fun pleosColorTokens(night: Boolean): Map<String, Int> = linkedMapOf(
    "background/primary" to if (night) 0xff1d1e21.toInt() else 0xfff7f8fa.toInt(),
    "surface/basic" to if (night) 0xff1d1e21.toInt() else 0xffffffff.toInt(),
    "background/popup" to if (night) 0xff313236.toInt() else 0xffffffff.toInt(),
    "dim/dark/primary" to if (night) 0x0dffffff else 0x0d131417,
    "text/dark/primary" to if (night) 0xffffffff.toInt() else 0xff131417.toInt(),
    "text/dark/secondary" to if (night) 0xd6ffffff.toInt() else 0xd6131417.toInt(),
    "text/dark/tertiary" to if (night) 0xa3ffffff.toInt() else 0xa3131417.toInt(),
    "text/dark/quaternary" to if (night) 0x52ffffff else 0x52131417,
    "text/light/primary" to if (night) 0xff131417.toInt() else 0xffffffff.toInt(),
    "divider/secondary" to if (night) 0x1affffff else 0x1a131417,
    "divider/tertiary" to if (night) 0x33ffffff else 0x33131417,
    "button/filled/enabled" to if (night) 0xffe0e1e6.toInt() else 0xff44464e.toInt(),
    "button/filled/pressed" to if (night) 0xffc7c9ce.toInt() else 0xff313236.toInt(),
    "button/basic/enabled" to if (night) 0x0dffffff else 0x0d131417,
    "button/basic/pressed" to if (night) 0x1affffff else 0x1a131417,
    "informative/positive" to if (night) 0xff0082ff.toInt() else 0xff0064ff.toInt(),
    "informative/active" to if (night) 0xff00e074.toInt() else 0xff02c265.toInt(),
    "informative/negative" to if (night) 0xffff4c28.toInt() else 0xfffe3d16.toInt(),
    // Caution comes from the Hyundai theme; no caution role in the Color collection.
    "color/informative/error" to 0xffffa202.toInt(),
    "color_hmc/stroke/focused" to 0xff6abffb.toInt(), "color_hmc/stroke/focused_pressed" to 0xff0794f8.toInt())

fun compositeColor(overlay: Int, background: Int): Int {
    val alpha = (overlay ushr 24) / 255.0
    fun channel(shift: Int) = ((((overlay ushr shift) and 255) * alpha + ((background ushr shift) and 255) * (1 - alpha)).roundToInt()).coerceIn(0, 255)
    return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

enum class PleosTone { NEUTRAL, INFORMATION, POSITIVE, WARNING, CAUTION }
class VehiclePalette(val night: Boolean) {
    val tokens = pleosColorTokens(night)
    private fun token(name: String) = tokens.getValue(name)
    val backgroundPrimary = token("background/primary")
    val surfaceBasic = token("surface/basic")
    val popup = token("background/popup")
    val listGroup = compositeColor(token("dim/dark/primary"), surfaceBasic)
    val textPrimary = token("text/dark/primary")
    val textSecondary = compositeColor(token("text/dark/secondary"), backgroundPrimary)
    val textTertiary = compositeColor(token("text/dark/tertiary"), backgroundPrimary)
    val disabledText = textSecondary // Explicit contrast override for official 32% disabled ink.
    val textInverse = token("text/light/primary")
    val dividerSecondary = token("divider/secondary")
    val dividerTertiary = token("divider/tertiary")
    val filledEnabled = token("button/filled/enabled")
    val filledPressed = token("button/filled/pressed")
    val basicEnabled = compositeColor(token("button/basic/enabled"), surfaceBasic)
    val basicPressed = compositeColor(token("button/basic/pressed"), surfaceBasic)
    val selection = textPrimary
    val positive = token("informative/positive")
    val active = token("informative/active")
    val negative = token("informative/negative")
    val caution = token("color/informative/error")
    val focused = token("color_hmc/stroke/focused")
    fun tone(tone: PleosTone) = when (tone) {
        PleosTone.NEUTRAL -> textPrimary; PleosTone.INFORMATION -> positive
        PleosTone.POSITIVE -> active; PleosTone.WARNING -> negative; PleosTone.CAUTION -> caution
    }
    fun inkOn(background: Int): Int = listOf(0xff131417.toInt(), 0xffffffff.toInt()).maxBy { contrastRatio(it, background) }
    fun readable(color: Int, background: Int) = if (contrastRatio(color, background) >= 4.5) color else inkOn(background)
    // Compatibility names for diagnostics; components use semantic token roles below.
    val background get() = backgroundPrimary
    val foreground get() = textPrimary
    val surface get() = surfaceBasic
    val button get() = basicEnabled
    val muted get() = textSecondary
    val accent get() = readable(positive, basicPressed)
    val success get() = readable(active, basicPressed)
    val border get() = dividerTertiary
}
fun vehiclePalette(night: Boolean) = VehiclePalette(night)

/** Enumerate actual allowed ink/surface pairs, including pressed/disabled/popup/toast/selection. */
fun pleosTextPairs(p: VehiclePalette): List<Pair<Int, Int>> = buildList {
    listOf(p.backgroundPrimary, p.surfaceBasic, p.popup, p.listGroup, p.basicEnabled, p.basicPressed).forEach { bg ->
        listOf(p.textPrimary, p.textSecondary, p.textTertiary, p.disabledText).forEach { add(it to bg) }
        PleosTone.values().forEach { add(p.readable(p.tone(it), bg) to bg) }
    }
    listOf(p.filledEnabled, p.filledPressed, p.selection).forEach { add(p.textInverse to it) }
    PleosTone.values().forEach { add(p.inkOn(p.tone(it)) to p.tone(it)) }
}

data class PleosType(val sizeSp: Float, val lineSp: Float)
fun pleosType(size: Float): PleosType = listOf(PleosType(20f,24f), PleosType(24f,28f), PleosType(26f,34f),
    PleosType(28f,36f), PleosType(30f,38f), PleosType(32f,40f), PleosType(36f,44f), PleosType(40f,52f),
    PleosType(48f,64f), PleosType(56f,72f), PleosType(60f,80f)).minBy { kotlin.math.abs(it.sizeSp - size) }
fun pleosTitle(width: VehicleWidth) = if (width == VehicleWidth.WIDE) 40f else 32f
fun pleosBody(width: VehicleWidth) = if (width == VehicleWidth.WIDE) 30f else 26f

enum class PleosIcon { RECORD, RECAP, AI_SUMMARY, LANE_CHANGE, LANE_LEFT, LANE_RIGHT, TURN_LEFT, TURN_RIGHT, UTURN, HARD_BRAKE, LATE_SIGNAL, GOOD, NAVIGATION, ARROW_FORWARD }
data class ReviewAppearance(val icon: PleosIcon, val tone: PleosTone)
fun reviewAppearance(kind: ReviewKind, turn: NavigationTurn? = null, direction: ManeuverDirection = ManeuverDirection.UNKNOWN): ReviewAppearance {
    val lane = when (direction) { ManeuverDirection.LEFT -> PleosIcon.LANE_LEFT; ManeuverDirection.RIGHT -> PleosIcon.LANE_RIGHT; else -> PleosIcon.LANE_CHANGE }
    val turning = when (turn) { NavigationTurn.LEFT -> PleosIcon.TURN_LEFT; NavigationTurn.RIGHT -> PleosIcon.TURN_RIGHT; NavigationTurn.UTURN -> PleosIcon.UTURN; null -> PleosIcon.NAVIGATION }
    return when (kind) {
        ReviewKind.LANE_NO_SIGNAL -> ReviewAppearance(lane, PleosTone.WARNING)
        ReviewKind.TURN_NO_SIGNAL -> ReviewAppearance(turning, PleosTone.WARNING)
        // BLINKER_LEFT_ON: a missed moment like the late signal, so the same blinker icon and caution tone (no new icon/layout).
        ReviewKind.LANE_LATE_SIGNAL, ReviewKind.TURN_LATE_SIGNAL, ReviewKind.BLINKER_LEFT_ON -> ReviewAppearance(PleosIcon.LATE_SIGNAL, PleosTone.CAUTION)
        ReviewKind.HARD_BRAKE -> ReviewAppearance(PleosIcon.HARD_BRAKE, PleosTone.WARNING)
        ReviewKind.LANE_SIGNALLED, ReviewKind.TURN_SIGNALLED -> ReviewAppearance(PleosIcon.GOOD, PleosTone.INFORMATION)
        ReviewKind.TURN -> ReviewAppearance(turning, PleosTone.NEUTRAL)
        ReviewKind.LANE_CHANGE -> ReviewAppearance(lane, PleosTone.NEUTRAL)
        else -> ReviewAppearance(PleosIcon.NAVIGATION, PleosTone.NEUTRAL)
    }
}

/** 복기 카드 종류 색(시안 C). 파랑·빨강·주황은 Pleos 토큰, 보라는 토큰이 없어 시안 값을 쓴다. */
fun momentColor(p: VehiclePalette, group: MomentGroup): Int = when (group) {
    MomentGroup.GOOD -> p.positive
    MomentGroup.MISSED -> p.negative
    MomentGroup.LATE -> p.caution
    MomentGroup.LEFT_ON -> if (p.night) 0xffa78bfa.toInt() else 0xff8a5cf6.toInt()
    MomentGroup.HARD_BRAKE -> p.textPrimary
    MomentGroup.OTHER -> p.textTertiary
}

/** 글자로 쓸 때 [background] 위에서 4.5:1이 되도록 같은 색을 [ink] 쪽으로 필요한 만큼만 섞는다. */
fun legibleTint(color: Int, background: Int, ink: Int): Int {
    if (contrastRatio(color, background) >= 4.5) return color
    for (step in 1..20) {
        val t = step / 20.0
        fun channel(shift: Int) = ((((color ushr shift) and 255) * (1 - t) + ((ink ushr shift) and 255) * t).roundToInt()).coerceIn(0, 255)
        val mixed = (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        if (contrastRatio(mixed, background) >= 4.5) return mixed
    }
    return ink
}
