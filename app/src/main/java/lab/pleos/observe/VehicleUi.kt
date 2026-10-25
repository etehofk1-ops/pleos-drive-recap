package lab.pleos.observe

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.Gravity
import android.view.ViewTreeObserver
import android.widget.*
import lab.pleos.observe.logic.*

fun Context.vehicleColors() = vehiclePalette(resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
fun Context.dp(value: Int) = (resources.displayMetrics.density * value).toInt()
fun Context.vehicleSurface(color: Int, radius: Int = 0) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

fun TextView.pleosText(size: Float, color: Int, weight: Int = 400) {
    val token = pleosType(size)
    textSize = token.sizeSp
    typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false)
    setLineSpacing(0f, 1f)
    setLineHeight(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, token.lineSp, resources.displayMetrics).toInt())
    setTextColor(color); isSaveEnabled = false; isSaveFromParentEnabled = false
}

/** Logical child/offset anchors survive a density change and reflow better than raw scroll px. */
fun ScrollView.captureAnchor(): ScrollAnchor {
    val column = getChildAt(0) as? LinearLayout ?: return ScrollAnchor()
    fun firstTagged(view: View, top: Int): Pair<View, Int>? {
        if (top + view.height <= scrollY) return null
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            firstTagged(child, top + child.top)?.let { return it }
        }
        return if (view.tag is String) view to top else null
    }
    val first = firstTagged(column, 0)
    val density = resources.displayMetrics.density
    return ScrollAnchor(first?.first?.tag as? String, (((first?.second ?: 0) - scrollY) / density).toInt(), (scrollY / density).toInt())
}
fun ScrollView.restoreAnchor(anchor: ScrollAnchor) {
    val column = getChildAt(0) as? LinearLayout ?: return
    if (isLayoutRequested || column.isLayoutRequested) {
        val firstChild = column.getChildAt(0)
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalLayoutListener(this)
                // A lock or newer render may have replaced the body before this traversal.
                if (isAttachedToWindow && getChildAt(0) === column && column.getChildAt(0) === firstChild) restoreAnchor(anchor)
            }
        }); return
    }
    fun find(view: View, top: Int): Int? {
        if (anchor.key != null && view.tag == anchor.key) return top
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), top + view.getChildAt(i).top)?.let { return it }
        return null
    }
    val top = find(column, 0)
    scrollTo(0, if (top == null) context.dp(anchor.yDp) else (top - context.dp(anchor.offsetDp)).coerceAtLeast(0))
}

/** Full text, split into paragraphs of <=3 measured lines. No ellipsis or lost evidence.
 * Re-measures on window/font/density change. All font sizes are sp, spacing is dp.
 */
class VehicleParagraph(context: Context, private val value: String, private val sizeSp: Float,
    private val color: Int, private val bold: Boolean = false) : LinearLayout(context) {
    private var measuredFor = -1
    init { orientation = VERTICAL; isSaveEnabled = false; isSaveFromParentEnabled = false }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(context.dp(48))
        if (measuredFor != width) {
            measuredFor = width
            removeAllViews()
            val paint = TextPaint().apply {
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, pleosType(sizeSp).sizeSp, resources.displayMetrics)
                typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), if (bold) 700 else 400, false)
            }
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).setIncludePad(true).build()
            var start = 0
            for (line in 0 until layout.lineCount step 3) {
                val end = layout.getLineEnd(minOf(line + 2, layout.lineCount - 1))
                val part = value.substring(start, end).trimEnd(); start = end
                addView(TextView(context).apply {
                    text = part; pleosText(sizeSp, color, if (bold) 700 else 400)
                    breakStrategy = Layout.BREAK_STRATEGY_SIMPLE; hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                    isSaveEnabled = false; isSaveFromParentEnabled = false
                }, LayoutParams(-1, -2).apply { if (line > 0) topMargin = context.dp(8) })
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

fun Context.styleVehicleDialog(dialog: AlertDialog) {
    val colors = vehicleColors()
    dialog.window?.setBackgroundDrawable(vehicleSurface(colors.popup, 48))
    dialog.window?.setDimAmount(if (colors.night) .5f else .2f)
    val available = resources.configuration.screenWidthDp - 48
    if (available > 0) dialog.window?.setLayout(dp(available.coerceAtMost(808)), ViewGroup.LayoutParams.WRAP_CONTENT)
    fun style(view: View) {
        // Paragraph children already have exact token sizes/weights (including font-scale conversion).
        if (view is TextView && view.parent !is VehicleParagraph) view.pleosText(26f, colors.textPrimary)
        if (view is ViewGroup) (0 until view.childCount).forEach { style(view.getChildAt(it)) }
    }
    dialog.window?.decorView?.let(::style)
    dialog.findViewById<TextView>(android.R.id.message)?.let { message ->
        val parent = message.parent as? ViewGroup ?: return@let
        val index = parent.indexOfChild(message); val params = message.layoutParams
        val paragraphs = VehicleParagraph(this, message.text.toString(), 26f, colors.textPrimary)
        parent.removeView(message); parent.addView(paragraphs, index, params)
    }
    listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { id ->
        dialog.getButton(id)?.apply {
            minHeight = dp(80); pleosText(26f, colors.readable(colors.positive, colors.popup), 700)
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(colors.readable(colors.positive, colors.basicPressed), colors.readable(colors.positive, colors.popup))))
            background = vehicleSurface(colors.popup)
            setPadding(dp(24), dp(16), dp(24), dp(16)); installPleosPress()
        }
    }
}

/** Native click/long-click/accessibility remain intact. Leaving the bounds sends CANCEL once. */
fun View.installPleosPress() {
    stateListAnimator = null; elevation = 0f
    if (background !is StateListDrawable) {
        val p = context.vehicleColors()
        val normal = background ?: context.vehicleSurface(p.backgroundPrimary)
        val pressed = (normal.constantState?.newDrawable()?.mutate() as? GradientDrawable)
            ?.apply { setColor(p.basicPressed) } ?: context.vehicleSurface(p.basicPressed)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }
    var cancelled = false
    setOnTouchListener { view, event ->
        fun reset() { view.animate().cancel(); view.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }
        if (!view.isEnabled) return@setOnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { cancelled = false; view.animate().cancel(); view.animate().scaleX(.95f).scaleY(.95f).setDuration(80).start() }
            MotionEvent.ACTION_MOVE -> if (!cancelled && (event.x < 0 || event.y < 0 || event.x >= view.width || event.y >= view.height)) {
                cancelled = true; reset()
                val cancel = MotionEvent.obtain(event); cancel.action = MotionEvent.ACTION_CANCEL
                view.onTouchEvent(cancel); cancel.recycle()
            }
            MotionEvent.ACTION_UP -> { reset(); if (cancelled) return@setOnTouchListener true }
            MotionEvent.ACTION_CANCEL -> { cancelled = true; reset() }
        }
        cancelled && event.actionMasked != MotionEvent.ACTION_CANCEL
    }
}

private fun Context.pleosStates(normal: Int, pressed: Int, radius: Int = 0, outlined: Boolean = false, disabled: Int = normal): StateListDrawable {
    val p = vehicleColors()
    fun shape(color: Int, focus: Int? = null) = vehicleSurface(color, radius).apply {
        if (focus != null) setStroke(dp(3), focus) else if (outlined) setStroke(1, p.dividerTertiary)
    }
    return StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), shape(disabled))
        addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_focused), shape(pressed, p.tokens.getValue("color_hmc/stroke/focused_pressed")))
        addState(intArrayOf(android.R.attr.state_pressed), shape(pressed))
        addState(intArrayOf(android.R.attr.state_focused), shape(normal, p.focused))
        addState(intArrayOf(), shape(normal))
    }
}

open class PleosSecondaryButton(context: Context, value: String, action: () -> Unit, primary: Boolean = false) : Button(context) {
    init {
        val p = context.vehicleColors()
        text = value; isAllCaps = false; minHeight = context.dp(80); minimumHeight = context.dp(80)
        stateListAnimator = null; elevation = 0f
        pleosText(pleosBody(vehicleLayout(resources.configuration.screenWidthDp).width), if (primary) p.textInverse else p.textPrimary, 700)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(p.disabledText, if (primary) p.textInverse else p.textPrimary)))
        background = context.pleosStates(if (primary) p.filledEnabled else p.surfaceBasic,
            if (primary) p.filledPressed else p.basicPressed, 24, !primary, p.basicEnabled)
        setPadding(context.dp(32), context.dp(16), context.dp(32), context.dp(16))
        setOnClickListener { action() }; installPleosPress()
    }
}
class PleosPrimaryButton(context: Context, value: String, action: () -> Unit) : PleosSecondaryButton(context, value, action, true)

class PleosDivider(context: Context, private val vertical: Boolean = false) : View(context) {
    init { setBackgroundColor(context.vehicleColors().dividerTertiary); isSaveEnabled = false
        layoutParams = LinearLayout.LayoutParams(if (vertical) 1 else -1, if (vertical) -1 else 1) }
    // A plain View takes the whole AT_MOST size on its long axis; under a fill-viewport ScrollView that
    // stretched a tile row to the screen height. Claim only an EXACT size and let the row's uniform pass fill it.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun along(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(if (vertical) 1 else along(widthMeasureSpec), if (vertical) along(heightMeasureSpec) else 1)
    }
}

fun Context.pleosIcon(icon: PleosIcon, tint: Int) = ImageView(this).apply {
    val id = when (icon) {
        PleosIcon.RECORD -> R.drawable.ic_record; PleosIcon.RECAP -> R.drawable.ic_recap
        PleosIcon.AI_SUMMARY -> R.drawable.ic_ai_summary; PleosIcon.LANE_CHANGE -> R.drawable.ic_lane_change
        PleosIcon.LANE_LEFT -> R.drawable.ic_lane_left; PleosIcon.LANE_RIGHT -> R.drawable.ic_lane_right
        PleosIcon.TURN_LEFT -> R.drawable.ic_turn_left; PleosIcon.TURN_RIGHT -> R.drawable.ic_turn_right
        PleosIcon.UTURN -> R.drawable.ic_uturn; PleosIcon.HARD_BRAKE -> R.drawable.ic_hard_brake
        PleosIcon.LATE_SIGNAL -> R.drawable.ic_late_signal; PleosIcon.GOOD -> R.drawable.ic_good
        PleosIcon.NAVIGATION -> R.drawable.ic_navigation; PleosIcon.ARROW_FORWARD -> R.drawable.ic_arrow_forward
    }
    setImageResource(id); imageTintList = ColorStateList.valueOf(tint)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)); isSaveEnabled = false
}

/** Flat tile, no elevation or arbitrary rounded card. The whole target is >=112dp high. */
class PleosTile(context: Context, icon: PleosIcon, heading: String, state: String,
    tone: PleosTone = PleosTone.NEUTRAL, selected: Boolean = false, forward: Boolean = false,
    trailing: View? = null, number: String? = null, action: (() -> Unit)? = null) : LinearLayout(context) {
    init {
        val p = context.vehicleColors(); val bg = if (selected) p.selection else p.surfaceBasic
        val ink = if (selected) p.textInverse else p.textPrimary
        orientation = VERTICAL; isSelected = selected; isSaveEnabled = false; isSaveFromParentEnabled = false
        minimumHeight = context.dp(192); setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(24))
        background = context.pleosStates(bg, if (selected) p.filledPressed else p.basicPressed)
        val top = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(context.pleosIcon(icon, if (selected) ink else p.tone(tone)))
        top.addView(View(context), LayoutParams(0, 1, 1f))
        if (trailing != null) top.addView(trailing)
        else if (forward) top.addView(context.pleosIcon(PleosIcon.ARROW_FORWARD, ink))
        addView(top, LayoutParams(-1, -2).apply { bottomMargin = context.dp(24) })
        val width = vehicleLayout(resources.configuration.screenWidthDp).width
        number?.let { addView(TextView(context).apply { text = it; pleosText(if (width == VehicleWidth.WIDE) 60f else 48f, ink, 700) }) }
        addView(VehicleParagraph(context, heading, pleosBody(width), ink, true), LayoutParams(-1,-2))
        if (state.isNotEmpty()) {
            if (tone == PleosTone.CAUTION || tone == PleosTone.WARNING) {
                // Semantic fill is exact token; its text uses guaranteed contrasting black/white.
                addView(VehicleParagraph(context, state, 24f, p.inkOn(p.tone(tone))).apply {
                    background = context.vehicleSurface(p.tone(tone), 16)
                    setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
                }, LayoutParams(-1,-2).apply { topMargin = context.dp(12) })
            } else addView(VehicleParagraph(context, state, 26f, if (selected) ink else p.readable(p.tone(tone), p.basicPressed)),
                LayoutParams(-1,-2).apply { topMargin = context.dp(8) })
        }
        if (action != null) { setOnClickListener { action() }; isFocusable = true; installPleosPress() }
    }
}

class PleosTabs(context: Context, labels: List<String>, selected: Int?, enabled: Boolean = true, onSelect: (Int) -> Unit) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL; isSaveEnabled = false; isSaveFromParentEnabled = false
        val p = context.vehicleColors()
        labels.forEachIndexed { index, label ->
            addView(Button(context).apply {
                text = label; isAllCaps = false; isEnabled = enabled; minHeight = context.dp(80)
                stateListAnimator = null; elevation = 0f
                isSelected = selected == index; isSaveEnabled = false
                pleosText(26f, if (isSelected) p.textInverse else p.textPrimary, 700)
                background = context.pleosStates(if (isSelected) p.selection else p.surfaceBasic,
                    if (isSelected) p.filledPressed else p.basicPressed, outlined = true)
                setPadding(context.dp(12), context.dp(16), context.dp(12), context.dp(16))
                setOnClickListener { onSelect(index) }; installPleosPress()
            }, LayoutParams(0, -2, 1f))
        }
    }
}

/** App-panel toast overlay: no SYSTEM_ALERT_WINDOW or file/history. Cleared by Activity pause. */
class PleosToast(private val root: FrameLayout) {
    private val handler = Handler(Looper.getMainLooper())
    private var notice: View? = null
    private val remove = Runnable { clear() }
    fun show(value: String) {
        clear()
        val context = root.context; val p = context.vehicleColors()
        val inset = root.rootWindowInsets
        val left = inset?.systemWindowInsetLeft ?: 0; val right = inset?.systemWindowInsetRight ?: 0
        val pixels = if (root.width > 0) root.width else context.dp(context.resources.configuration.screenWidthDp)
        val widthDp = (((pixels - left - right) / context.resources.displayMetrics.density).toInt() - 48).coerceAtLeast(48).coerceAtMost(792)
        notice = VehicleParagraph(context, value, 26f, p.textInverse).apply {
            background = context.vehicleSurface(p.filledEnabled, 16)
            setPadding(context.dp(40), context.dp(24), context.dp(40), context.dp(24))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            translationX = (left - right) / 2f
        }.also { view -> root.addView(view, FrameLayout.LayoutParams(context.dp(widthDp), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = context.dp(24) + (root.rootWindowInsets?.systemWindowInsetBottom ?: 0)
        }) }
        handler.postDelayed(remove, 3_500)
    }
    fun clear() { handler.removeCallbacks(remove); notice?.let(root::removeView); notice = null }
}
