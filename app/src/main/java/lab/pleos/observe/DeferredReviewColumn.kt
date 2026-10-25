package lab.pleos.observe

import android.content.Context
import android.graphics.Rect
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import lab.pleos.observe.logic.nextRecapRow
import lab.pleos.observe.logic.visibleRecapRows

/** Cheap placeholders preserve logical anchors. Expensive tiles exist only near the viewport.
 * No nested scrolling or extra library; one tile per animation frame, recycled on scroll.
 * All callbacks are cancelled and all card references removed when the Activity locks/pauses.
 */
class DeferredReviewColumn(context: Context) : LinearLayout(context) {
    private data class Row(val key: String, var version: Any, val slot: FrameLayout,
        var builtVersion: Any? = null)
    private val rows = mutableListOf<Row>()
    private var viewport: ScrollView? = null
    private var createTile: ((Int) -> View)? = null
    private var preserveAnchor: (() -> (() -> Unit))? = null
    private var queued = false
    private val frame = Runnable { queued = false; materializeVisible() }
    private val location = IntArray(2)
    private val viewportRect = Rect()
    var onFirstCardPainted: (() -> Unit)? = null
    private var firstPaintReported = false

    init { orientation = VERTICAL; isSaveEnabled = false; isSaveFromParentEnabled = false }

    fun submit(keys: List<String>, versions: List<Any>, scroller: ScrollView,
        preserve: () -> (() -> Unit), factory: (Int) -> View) {
        require(keys.size == versions.size)
        viewport = scroller; createTile = factory; preserveAnchor = preserve
        if (rows.map { it.key } != keys) {
            removeCallbacks(frame); queued = false; rows.clear(); removeAllViews()
            firstPaintReported = false
            keys.forEachIndexed { i, key ->
                val slot = FrameLayout(context).apply {
                    tag = key; isSaveEnabled = false; isSaveFromParentEnabled = false
                }
                rows += Row(key, versions[i], slot)
                addView(slot, LayoutParams(-1, estimateHeight()))
            }
        } else rows.forEachIndexed { i, row -> row.version = versions[i] }
        schedule()
    }

    fun schedule() {
        if (!queued && rows.isNotEmpty() && isAttachedToWindow) {
            queued = true; postOnAnimation(frame)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); schedule() }
    override fun onDetachedFromWindow() { removeCallbacks(frame); queued = false; super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); schedule()
    }

    fun clearCards() {
        removeCallbacks(frame); queued = false; rows.clear(); removeAllViews()
        viewport = null; createTile = null; preserveAnchor = null
        firstPaintReported = false
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!firstPaintReported && rows.any { row -> row.builtVersion != null && row.slot.getGlobalVisibleRect(Rect()) }) {
            firstPaintReported = true; onFirstCardPainted?.invoke()
        }
    }

    private fun materializeVisible() {
        val scroller = viewport ?: return
        if (!isAttachedToWindow || !scroller.getGlobalVisibleRect(viewportRect)) return
        if (width == 0 || height == 0 || isLayoutRequested) { schedule(); return }
        getLocationOnScreen(location)
        val visible = visibleRecapRows(rows.map { it.slot.top }, rows.map { it.slot.bottom },
            viewportRect.top - location[1], viewportRect.bottom - location[1])
        // Keep measured height when evicting; this avoids scroll jumps and bounds live view count.
        rows.forEachIndexed { i, row ->
            if (i !in visible && row.builtVersion != null) {
                row.slot.layoutParams = row.slot.layoutParams.apply { height = row.slot.height.coerceAtLeast(1) }
                row.slot.removeAllViews(); row.builtVersion = null
            }
        }
        val index = nextRecapRow(visible, rows.indices.filter { rows[it].builtVersion != rows[it].version }.toSet()) ?: return
        val restore = preserveAnchor?.invoke()
        val row = rows[index]
        val beganMs = if (BuildConfig.DEBUG) android.os.SystemClock.elapsedRealtime() else 0L
        row.slot.removeAllViews()
        row.slot.addView(createTile?.invoke(index) ?: return, FrameLayout.LayoutParams(-1, -2))
        if (BuildConfig.DEBUG) {
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - beganMs
            if (elapsedMs >= 100) android.util.Log.w("ObserveLab", "event=ui.recap.slowTile elapsedMs=$elapsedMs")
        }
        row.slot.layoutParams = row.slot.layoutParams.apply { height = -2 }
        row.builtVersion = row.version
        restore?.invoke()
        schedule() // Never construct the rest of the page in this frame.
    }

    private fun estimateHeight() = (400 * resources.displayMetrics.density).toInt()
}
