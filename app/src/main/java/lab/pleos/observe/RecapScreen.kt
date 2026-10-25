package lab.pleos.observe

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import lab.pleos.observe.logic.*
import lab.pleos.observe.logic.recap.*
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 1.8.0 복기 화면 C안 "한 장씩"의 그림 부품. 문장·숫자·종류는 logic 모듈의 순수 함수가 정하고 여기는 그리기만 한다. */

private fun Context.spPx(size: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, pleosType(size).sizeSp, resources.displayMetrics)

private fun Context.textPaint(color: Int, size: Float, weight: Int = 400) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color; textSize = spPx(size)
    typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false)
}

/** 깜빡이 켬 → 진입 막대(또는 켜 둔 시간 막대). 점선 눈금이 권장 거리(또는 15초)다. */
class MomentBarView(context: Context, private val bar: MomentBar, private val color: Int) : View(context) {
    private val p = context.vehicleColors()
    private val d = resources.displayMetrics.density
    private val label = context.textPaint(p.textSecondary, 24f)
    private val strong = context.textPaint(p.textPrimary, 24f, 700)
    private val faint = context.textPaint(p.textTertiary, 24f)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 10 * d }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * d; this.color = p.textTertiary
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        isSaveEnabled = false
        contentDescription = "${bar.startLabel}부터 ${bar.valueLabel}, ${bar.referenceLabel} 눈금"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (128 * d).toInt())
    }
    override fun onDraw(canvas: Canvas) {
        val pad = 24 * d; val barW = min(width - 2 * pad, 900 * d); val y = 70 * d; val r = 16 * d
        val x0 = pad; val xv = x0 + bar.value / bar.scaleMax * barW; val xr = x0 + bar.reference / bar.scaleMax * barW
        track.color = p.dividerSecondary; canvas.drawLine(x0, y, x0 + barW, y, track)
        track.color = color; canvas.drawLine(x0, y, xv, y, track)
        canvas.drawLine(xr, y - 22 * d, xr, y + 22 * d, tick)
        faint.textAlign = Paint.Align.CENTER; canvas.drawText(bar.referenceLabel, xr, y + 56 * d, faint)
        fill.style = Paint.Style.FILL; fill.color = p.surfaceBasic; canvas.drawCircle(x0, y, r, fill)
        fill.style = Paint.Style.STROKE; fill.strokeWidth = 6 * d; fill.color = color; canvas.drawCircle(x0, y, r - 3 * d, fill)
        fill.style = Paint.Style.FILL; canvas.drawCircle(xv, y, r, fill)
        label.textAlign = Paint.Align.LEFT; canvas.drawText(bar.startLabel, x0 - r, 28 * d, label)
        val startEnd = x0 - r + label.measureText(bar.startLabel) + 16 * d
        val half = strong.measureText(bar.valueLabel) / 2
        val center = min(max(xv, startEnd + half), width - half)
        strong.textAlign = Paint.Align.CENTER; canvas.drawText(bar.valueLabel, center, 28 * d, strong)
    }
}

/** 오늘 주행 중 위치: 첫 순간~마지막 순간 위에 종류 색 점. 속도는 저장하지 않아 곡선은 그리지 않는다. */
class MomentTimelineView(context: Context, private val dots: List<TimelineDot>, private val ticks: List<TimelineTick>,
    private val colorOf: (MomentGroup) -> Int, private val onSelect: (Int) -> Unit) : View(context) {
    private val p = context.vehicleColors()
    private val d = resources.displayMetrics.density
    private val tickText = context.textPaint(p.textTertiary, 20f).apply { textAlign = Paint.Align.CENTER }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 4 * d }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        isSaveEnabled = false; isClickable = true
        contentDescription = "오늘 주행 중 순간 ${dots.size}개의 위치. 점을 누르면 그 순간으로 이동해요"
    }
    private fun x(fraction: Float) = 44 * d + fraction * (width - 88 * d)
    private val trackY get() = 44 * d
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (112 * d).toInt())
    }
    override fun onDraw(canvas: Canvas) {
        line.color = p.dividerTertiary; canvas.drawLine(x(0f), trackY, x(1f), trackY, line)
        ticks.forEach { canvas.drawText(it.label, x(it.fraction), height - 8 * d, tickText) }
        (dots.filter { !it.selected } + dots.filter { it.selected }).forEach { m ->
            val c = colorOf(m.group); val cx = x(m.fraction)
            if (m.selected) { dot.style = Paint.Style.FILL; dot.color = (c and 0x00ffffff) or (0x24 shl 24); canvas.drawCircle(cx, trackY, 30 * d, dot) }
            val r = (if (m.selected) 18 else 12) * d
            dot.style = Paint.Style.FILL; dot.color = p.surfaceBasic; canvas.drawCircle(cx, trackY, r + 4 * d, dot)
            dot.color = c; canvas.drawCircle(cx, trackY, r, dot)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val best = dots.indices.filter { abs(x(dots[it].fraction) - event.x) <= 36 * d && abs(trackY - event.y) <= 56 * d }
                    .minByOrNull { abs(x(dots[it].fraction) - event.x) }
                if (best != null) { performClick(); onSelect(best) }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean = super.performClick()
}

/** 줄이 모자라면 아래로 내려가는 칩 줄. */
class ChipFlow(context: Context) : ViewGroup(context) {
    private val gap = context.dp(10)
    init { isSaveEnabled = false; isSaveFromParentEnabled = false }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += c.measuredWidth + gap; rowH = max(rowH, c.measuredHeight)
        }
        setMeasuredDimension(maxW, y + rowH)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap; rowH = max(rowH, c.measuredHeight)
        }
    }
}

/** 한 장씩 화면의 카드·막대·열을 만든다. 상태(어느 카드, 피드백 중인지)는 MainActivity가 넘긴다. */
class RecapPainter(private val ctx: Context, private val width: VehicleWidth, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val p = ctx.vehicleColors()
    private val wide get() = width == VehicleWidth.WIDE
    private fun dp(v: Int) = ctx.dp(v)

    private fun text(value: String, size: Float, color: Int, weight: Int = 400) =
        TextView(ctx).apply { text = value; pleosText(size, color, weight) }

    /** 흰 카드. 야간에는 배경과 같은 색이라 옅은 테두리로 경계를 준다. */
    private fun cardBox(padH: Int, padV: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; isSaveEnabled = false; isSaveFromParentEnabled = false
        background = GradientDrawable().apply {
            setColor(p.surfaceBasic); cornerRadius = dp(24).toFloat()
            if (p.night) setStroke(dp(1), p.dividerSecondary)
        }
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    /** 순간 개수만큼 칸, 지금 보는 칸만 진하게. */
    fun segments(count: Int, selected: Int): List<View> = (0 until count).map { i ->
        View(ctx).apply {
            background = ctx.vehicleSurface(if (i == selected) p.textPrimary else p.dividerSecondary, 4)
            layoutParams = LinearLayout.LayoutParams(0, dp(8), 1f).apply { marginEnd = if (i == count - 1) 0 else dp(if (count > 24) 3 else 10) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun chip(value: String, outlined: Boolean = false) = text(value, 24f, p.textSecondary).apply {
        setPadding(dp(18), dp(8), dp(18), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(40).toFloat()
            if (outlined) setStroke(dp(1), p.dividerTertiary) else setColor(p.basicEnabled)
        }
    }

    private fun bigNumber(item: BigNumber) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; background = ctx.vehicleSurface(p.basicEnabled, 16)
        setPadding(dp(24), dp(16), dp(28), dp(16)); isSaveEnabled = false; isSaveFromParentEnabled = false
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        row.addView(text(item.value, if (wide) 56f else 48f, p.textPrimary, 700))
        row.addView(text(item.unit, 24f, p.textSecondary).apply { setPadding(dp(6), 0, 0, dp(10)) })
        addView(row); addView(text(item.label, 24f, p.textSecondary))
    }

    private fun navButton(label: String, enabled: Boolean, selected: Boolean = false, description: String? = null, onClick: () -> Unit) =
        Button(ctx).apply {
            text = label; isAllCaps = false; isEnabled = enabled; isSelected = selected; minHeight = dp(80); minimumHeight = dp(80)
            stateListAnimator = null; elevation = 0f; isSaveEnabled = false
            pleosText(30f, if (selected) p.textInverse else p.textPrimary, 700)
            background = ctx.pleosStates(if (selected) p.selection else p.basicEnabled, if (selected) p.filledPressed else p.basicPressed, 16)
            alpha = if (enabled) 1f else .38f
            contentDescription = description
            setPadding(dp(12), dp(16), dp(12), dp(16)); setOnClickListener { onClick() }; installPleosPress()
        }

    fun momentCard(cards: List<ReviewCard>, index: Int, decision: AiDecision?, aiEnabled: Boolean, busy: Boolean,
        debugEvidence: Boolean, fillHeight: Boolean, onMove: (Int) -> Unit, onFeedback: (Feedback) -> Unit): View {
        val c = cards[index]; val group = momentGroup(c)
        val color = momentColor(p, group)
        val padH = if (wide) 56 else 32; val padV = if (wide) 48 else 28
        val box = cardBox(padH, padV).apply { tag = "card:${c.id}" }
        box.addView(text(momentCaption(c, index, cards.size, zone), 28f, legibleTint(color, p.surfaceBasic, p.textPrimary), 700))
        val aiLabel = decision?.takeIf { it.accepted && aiEnabled }?.label
        box.addView(VehicleParagraph(ctx, momentHeadline(c, aiLabel), when (width) { VehicleWidth.WIDE -> 60f; VehicleWidth.MEDIUM -> 48f; else -> 40f },
            p.textPrimary, true, wordBreaks = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        if (aiLabel != null) box.addView(text("규칙 결과: ${momentHeadline(c)}", 24f, p.textSecondary),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val bar = momentBar(c)
        val numbers = if (bar == null) momentBigNumbers(c) else emptyList()
        if (bar != null) box.addView(MomentBarView(ctx, bar, color), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (wide) 40 else 24) })
        else if (numbers.isNotEmpty()) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; isSaveEnabled = false }
            numbers.forEachIndexed { i, n -> row.addView(bigNumber(n), LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(16) }) }
            box.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(if (wide) 40 else 24) })
        }
        val chips = ChipFlow(ctx)
        momentEvidenceChips(c, numbersShown = numbers.isNotEmpty()).forEach { chips.addView(chip(it)) }
        chips.addView(chip(trustBadge(c, decision, aiEnabled), outlined = true))
        box.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (wide) 32 else 20) })
        if (c.demoNavigation && c.navigation == NavigationLabel.ESTIMATED) box.addView(text("모의 내비 미수신/안내 없음", 24f, p.textSecondary),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        if (debugEvidence) box.addView(VehicleParagraph(ctx, "Debug · ${recapEvidence(c, debug = true)}", 20f, p.textTertiary),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        box.addView(text("오늘 주행 중 위치", 24f, p.textSecondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (wide) 32 else 24) })
        val dots = timelineDots(cards, index)
        val from = cards.minOf { it.wallTimeMs }; val to = cards.maxOf { it.wallTimeMs }
        box.addView(MomentTimelineView(ctx, dots, timelineTicks(from, to, zone), { momentColor(p, it) }, onMove),
            LinearLayout.LayoutParams(-1, -2))
        if (fillHeight) box.addView(View(ctx), LinearLayout.LayoutParams(-1, 0, 1f))
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; isSaveEnabled = false }
        buttons.addView(navButton("‹", index > 0, description = "이전 순간") { onMove(index - 1) }, LinearLayout.LayoutParams(dp(if (wide) 96 else 72), -2))
        buttons.addView(navButton("맞아요", !busy, c.feedback == Feedback.YES) { onFeedback(Feedback.YES) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        buttons.addView(navButton("아니에요", !busy, c.feedback == Feedback.NO) { onFeedback(Feedback.NO) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        buttons.addView(navButton("›", index < cards.size - 1, description = "다음 순간") { onMove(index + 1) },
            LinearLayout.LayoutParams(dp(if (wide) 96 else 72), -2).apply { marginStart = dp(16) })
        box.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (wide) 28 else 24) })
        return box
    }

    fun emptyCard(): View = cardBox(if (wide) 56 else 32, if (wide) 48 else 28).apply {
        addView(text("아직 복기 후보가 없어요", 32f, p.textPrimary, 700))
        addView(VehicleParagraph(ctx, "기록을 시작한 뒤 정차해서 확인해 주세요.", 26f, p.textSecondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    fun glanceCard(glance: RecapGlance, total: Int, unsure: Int): View = cardBox(32, 28).apply {
        tag = "summary"
        addView(text("오늘 한눈에", 24f, p.textSecondary))
        val rows = glanceRows(glance)
        if (rows.isEmpty()) addView(text("아직 순간이 없어요", 26f, p.textSecondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        rows.forEachIndexed { i, row ->
            addView(View(ctx).apply { setBackgroundColor(p.dividerSecondary) }, LinearLayout.LayoutParams(-1, dp(2)).apply { topMargin = dp(if (i == 0) 12 else 0) })
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isSaveEnabled = false
                setPadding(0, dp(16), 0, dp(16)) }
            line.addView(View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(momentColor(p, row.group)) } },
                LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(14) })
            line.addView(text(momentGroupLabel(row.group), 28f, p.textPrimary), LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(text(row.count.toString(), 28f, p.textPrimary, 700))
            addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        addView(text("후보 ${total}개 · 규칙 애매함 ${unsure}개", 24f, p.textSecondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    fun messageCard(message: String, patterns: List<String>, status: String, pending: Boolean, fill: Boolean, onRetry: (() -> Unit)?): View =
        cardBox(32, 28).apply {
            tag = "daily-message"
            addView(text("오늘의 한마디 · AI", 24f, p.textSecondary))
            addView(VehicleParagraph(ctx, message, 28f, p.textPrimary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            patterns.forEach { addView(VehicleParagraph(ctx, it, 26f, p.textSecondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }) }
            addView(text(status, 24f, p.textSecondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            if (pending) addView(ProgressBar(ctx).apply { contentDescription = "AI 문장 준비 중" }, LinearLayout.LayoutParams(dp(48), dp(48)))
            if (fill) addView(View(ctx), LinearLayout.LayoutParams(-1, 0, 1f))
            onRetry?.let { retry -> addView(PleosSecondaryButton(ctx, "AI 다시 확인", retry), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }) }
        }
}
