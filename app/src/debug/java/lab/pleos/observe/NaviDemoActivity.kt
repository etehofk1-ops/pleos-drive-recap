package lab.pleos.observe

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteInfo
import lab.pleos.observe.logic.*

/** Destination fields survive configuration recreation in memory only, never Bundle/files/logs.
 * https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations
 */
class NaviDemoActivity : Activity() {
    private data class Draft(val values: List<String>, val cursors: List<Int>, val focus: Int,
        val scroll: ScrollAnchor, val message: String)
    private var retained: Draft? = null
    private var pending: Draft? = null
    private var safety = SafetyState()
    private var safetyClient: CarSafetyClient? = null
    private var helper: NaviHelper? = null
    private var connected = false
    private var active = false
    private var generation = 0
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private val fields = mutableListOf<EditText>()
    private var message = "내비 연결 중"
    private val unlocked get() = active && !shouldLock(safety)
    private val listener = object : NaviHelperEventListener {
        override fun onRoutingCompleted() { runOnUiThread { if (active) { message = "경로 탐색 완료 이벤트 수신. 지도에서 안내 시작을 확인하세요."; render() } } }
        override fun onError(errorCode: NaviErrorCode) { runOnUiThread { if (active) { message = "요청 오류: ${errorCode.name}"; render() } } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pending = lastNonConfigurationInstance as? Draft
        pending?.let { message = it.message }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isSaveEnabled = false; isSaveFromParentEnabled = false }
        scroll = ScrollView(this).apply {
            isSaveEnabled = false; isSaveFromParentEnabled = false; isFillViewport = true
            setBackgroundColor(vehicleColors().background); addView(body)
            setOnApplyWindowInsetsListener { view, insets ->
                val margin = dp(vehicleLayout(resources.configuration.screenWidthDp).marginDp)
                view.setPadding(margin + insets.systemWindowInsetLeft, dp(16) + insets.systemWindowInsetTop,
                    margin + insets.systemWindowInsetRight, dp(16) + insets.systemWindowInsetBottom); insets
            }
        }
        setContentView(scroll)
    }
    override fun onResume() {
        super.onResume(); active = true; safety = SafetyState(); val ticket = ++generation
        safetyClient = CarSafetyClient(this) { value, _ ->
            val wasUnlocked = unlocked
            safety = value
            if (restorationGate(value) == RestorationGate.DISCARD || (wasUnlocked && shouldLock(value))) {
                pending = null; retained = null; fields.clear()
            }
            if (wasUnlocked != unlocked) render()
        }.also { it.start() }
        try {
            helper = NaviHelper(applicationContext).also { client -> client.initialize(onConnected = { runOnUiThread {
                if (active && ticket == generation) {
                    try { connected = true; client.addListener(listener); message = "내비 연결됨 · 공개 시연 목적지를 입력하세요." }
                    catch (_: Exception) { connected = false; message = "내비 리스너 등록 실패" }
                    render()
                }
            } }, onFailed = { runOnUiThread {
                if (active && ticket == generation) { message = "내비 연결 실패. 지도 앱에서 직접 안내를 시작해도 됩니다."; render() }
            } }) }
        } catch (_: Exception) { message = "내비 초기화 실패" }
        render()
    }
    private fun capture(): Draft = Draft(fields.map { it.text.toString() }, fields.map { it.selectionStart.coerceAtLeast(0) },
        fields.indexOfFirst { it.hasFocus() }, scroll.captureAnchor(), message)
    private fun render() {
        if (!::body.isInitialized) return
        val draft = pending ?: if (unlocked && fields.isNotEmpty()) capture() else null
        fields.clear(); body.removeAllViews()
        val colors = vehicleColors()
        fun text(value: String, core: Boolean = false) {
            body.addView(VehicleParagraph(this, value, if (core) 28f else 20f, colors.foreground, core).apply { tag = value.take(30) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }
        if (!unlocked) { text("정차 후 사용", true); return }
        text("내비 시연 설정", true); text(message)
        text("입력은 지도 앱의 경로 요청에만 사용합니다. 크기를 바꿀 때만 메모리에 유지하며 화면을 떠나거나 주행하면 지워집니다.")
        fun field(hintText: String) = EditText(this).apply {
            val index = fields.size
            hint = hintText; pleosText(26f, colors.foreground); minHeight = dp(64); isSingleLine = true
            isSaveEnabled = false; isSaveFromParentEnabled = false
            setTextColor(colors.foreground); setHintTextColor(colors.muted)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(draft?.values?.getOrNull(index) ?: "")
            setSelection((draft?.cursors?.getOrNull(index) ?: length()).coerceIn(0, length()))
            tag = "input:$index"; fields += this
            body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }
        val latitude = field("목적지 위도 (-90~90)")
        val longitude = field("목적지 경도 (-180~180)")
        val poiId = field("지도 제공자 POI ID (필요시)")
        fun button(label: String, action: () -> Unit) { body.addView(Button(this).apply {
            text = label; pleosText(30f, colors.foreground, 700); isAllCaps = false; minHeight = dp(64); isEnabled = connected
            isSaveEnabled = false; setTextColor(if (connected) colors.foreground else colors.muted)
            background = vehicleSurface(colors.button, 24); setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { if (unlocked) action() }; installPleosPress()
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }) }
        button("목적지 요청 (NaviHelper)") {
            val lat = latitude.text.toString().toDoubleOrNull(); val lon = longitude.text.toString().toDoubleOrNull()
            if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                Toast.makeText(this, "위도·경도를 확인하세요.", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    helper?.requestRoute(RouteInfo(latitude = lat, longitude = lon, poiName = "시연 목적지",
                        poiId = poiId.text.toString(), address = "", poiSubId = "0"))
                    message = "요청 전송됨 · 안내 시작 여부는 지도/이벤트로 확인하세요."
                } catch (_: Exception) { message = "목적지 요청 실패" }
                fields.forEach { it.text.clear() }; render()
            }
        }
        button("안내 취소 (NaviHelper)") {
            try { helper?.cancelRoute(); message = "안내 취소 요청됨" } catch (_: Exception) { message = "안내 취소 실패" }
            render()
        }
        pending = null
        scroll.post { if (unlocked) {
            draft?.let { fields.getOrNull(it.focus)?.requestFocus(); scroll.restoreAnchor(it.scroll) }
        } }
    }
    override fun onRetainNonConfigurationInstance(): Any? = pending ?: retained
    override fun onPause() {
        if (unlocked && pending == null) retained = capture()
        active = false; ++generation; connected = false; safety = SafetyState(); fields.clear(); render()
        safetyClient?.stop(); safetyClient = null
        runCatching { helper?.removeListener(listener) }; runCatching { helper?.release() }; helper = null
        super.onPause()
    }
}
