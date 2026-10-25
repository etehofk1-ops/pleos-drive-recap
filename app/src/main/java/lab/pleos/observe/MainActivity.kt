package lab.pleos.observe

import android.app.Activity
import android.app.AlertDialog
import android.car.Car
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.*
import lab.pleos.observe.logic.*
import lab.pleos.observe.logic.recap.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Parked-only UI. Recorder lives independently; no raw signal history or location in this screen. */
class MainActivity : Activity() {
    private enum class DialogKind { NONE, MENU, SERVER, DEMO, DELETE }
    private data class Checkpoint(val recap: Boolean, val offset: Int, val scroll: PaneScrollState,
        val dialog: DialogKind, val address: String, val addressCursor: Int, val addressError: String?,
        val demoDraft: Boolean, val speedDemo: Boolean, val askedSpeed: Boolean, val askedNotification: Boolean,
        val startAt: Long?, val aiEnabled: Boolean, val aiChoice: Boolean?, val proxyUrl: String,
        val decisions: Map<String, AiDecision>, val attempts: Map<String, ClassifyFeatures>, val capturedAtMs: Long)
    private var frozenUi: Checkpoint? = null
    private var pendingUi: Checkpoint? = null
    private var pendingRoute: RecapUiRoute? = null
    private var pendingScroll: PaneScrollState? = null
    private var dialogKind = DialogKind.NONE
    private var suspendingDialog = false
    private var addressDraft = ""
    private var addressCursor = 0
    private var addressError: String? = null
    private var addressInput: EditText? = null
    private var demoDraft = false
    private var startingAt: Long? = null
    private var pendingOperation = false
    private var cardsFocused = false
    private var restoringScroll = false
    private var layoutSpec = vehicleLayout(0)
    private lateinit var paneHost: LinearLayout
    private lateinit var cardsContent: LinearLayout
    private lateinit var cardsScroll: ScrollView
    private lateinit var cardColumn: DeferredReviewColumn
    private var recapLoading = false
    private var dataEpoch = 0
    private var drawListener: ViewTreeObserver.OnDrawListener? = null
    private var recapOpenedAtMs: Long? = null
    private val colors get() = vehicleColors()
    private val BG get() = colors.background
    private val SURFACE get() = colors.surface
    private val FG get() = colors.foreground
    private val MUTED get() = colors.muted
    private val ACCENT get() = colors.accent
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val aiIo = Executors.newFixedThreadPool(3)
    private val aiClient = AiProxyClient()
    private val aiTasks = mutableListOf<Future<*>>()
    private val aiDecisions = mutableMapOf<String, AiDecision>()
    private val aiAttempts = mutableMapOf<String, ClassifyFeatures>()
    private var aiEnabled = true
    private var aiProxyUrl = DEFAULT_AI_PROXY_URL
    private var serverDialog: AlertDialog? = null
    private var userAiChoice: Boolean? = null
    private var aiEpoch = 0
    private var aiSettingVersion = 0
    private var summaryPending = false
    private var generated: GeneratedRecap? = null
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var notices: PleosToast
    private var safety = SafetyState()
    private var safetyClient: CarSafetyClient? = null
    private var foreground = false
    private var generation = 0
    private var reading = false
    private var speedAsked = false
    private var notificationAsked = false
    private var showRecap = false
    private var openRequested = false
    private var demo = false
    private var recording = false
    private var startingRecorder = false
    private var recordingDemo = false
    private var demoNaviEnabled = false
    private var hasDemoNaviCards = false
    private var day: DayRecap? = null
    private var total = 0
    private var unsure = 0
    private var todaySummary = recapSummary(emptyList())
    private var tileCounts = RecapTileCounts(0, 0, 0, 0)
    private var offset = 0
    private var navi = "미수신"
    private var seed = "초기 신호 확인 중"
    private var error = ""
    private var lastFingerprint: String? = null
    private val unlocked get() = foreground && !shouldLock(safety)
    private val startDeadline = Runnable {
        if (startingRecorder) {
            startingRecorder = false
            error = "기록 시작 상태를 확인하지 못했어요. 실행 상태를 다시 확인해 주세요."
            render()
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!foreground) return
            if (unlocked) requestData()
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingUi = lastNonConfigurationInstance as? Checkpoint
        pendingRoute = pendingUi?.route() ?: savedInstanceState?.takeIf { it.containsKey("uiCapturedAt") }?.let {
            fun anchor(prefix: String) = ScrollAnchor(it.getString(prefix + "Key"),
                it.getInt(prefix + "OffsetDp"), it.getInt(prefix + "YDp"))
            RecapUiRoute(it.getBoolean("recap"), recapPageOffset(it.getInt("offset")),
                PaneScrollState(anchor("primary"), anchor("cards"), it.getBoolean("cardsFocused")), it.getLong("uiCapturedAt"))
        }
        layoutSpec = vehicleLayout(resources.configuration.screenWidthDp.coerceAtLeast(0))
        // Keep a pending route through WAIT and another pause/save. Reveal only after fresh READY.
        logUiState("create", savedInstanceState != null)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        openRequested = savedInstanceState == null && intent.getBooleanExtra("openRecap", false)
        val root = column().apply {
            setBackgroundColor(BG); setPadding(dp(layoutSpec.marginDp), dp(16), dp(layoutSpec.marginDp), dp(16))
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(dp(layoutSpec.marginDp) + insets.systemWindowInsetLeft, dp(16) + insets.systemWindowInsetTop,
                    dp(layoutSpec.marginDp) + insets.systemWindowInsetRight, dp(16) + insets.systemWindowInsetBottom); insets
            }
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left > 0 && right - left != oldRight - oldLeft) {
                    val next = vehicleLayout(((right - left) / resources.displayMetrics.density).toInt())
                    if (next.width != layoutSpec.width) {
                        if (unlocked) pendingScroll = captureScroll()
                        layoutSpec = next; requestApplyInsets(); render()
                    }
                }
            }
        }
        title = label("오운복", pleosTitle(layoutSpec.width), bold = true).apply {
            minHeight = dp(48)
            installPleosPress()
            setOnLongClickListener {
                if (unlocked) {
                    openDiagnosticsMenu(); true
                } else false
            }
        }
        root.addView(title)
        status = label("안전 상태 확인 중", 26f, MUTED).apply { setPadding(0, dp(10), 0, dp(20)) }
        root.addView(status)
        content = column()
        scroll = ScrollView(this).apply { isFillViewport = true; isSaveEnabled = false; addView(content) }
        cardsContent = column()
        cardsScroll = ScrollView(this).apply { isFillViewport = true; isSaveEnabled = false; addView(cardsContent) }
        cardColumn = DeferredReviewColumn(this)
        cardColumn.onFirstCardPainted = {
            recapOpenedAtMs?.let { at ->
                if (BuildConfig.DEBUG) android.util.Log.i("ObserveLab", "event=ui.recap.firstCard elapsedMs=${SystemClock.elapsedRealtime() - at} offset=$offset")
                recapOpenedAtMs = null
            }
        }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> cardColumn.schedule() }
        cardsScroll.setOnScrollChangeListener { _, _, _, _, _ -> cardColumn.schedule() }
        scroll.setOnTouchListener { _, _ -> if (!restoringScroll) cardsFocused = false; false }
        cardsScroll.setOnTouchListener { _, _ -> if (!restoringScroll) cardsFocused = true; false }
        paneHost = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isSaveEnabled = false
            addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
            addView(cardsScroll, LinearLayout.LayoutParams(0, -1, 1.5f).apply { marginStart = dp(16) }) }
        root.addView(paneHost, LinearLayout.LayoutParams(-1, 0, 1f))
        val frame = FrameLayout(this).apply { addView(root, FrameLayout.LayoutParams(-1, -1)) }
        notices = PleosToast(frame)
        setContentView(frame)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("openRecap", false)) { openRequested = true; if (unlocked) { openRequested = false; openRecap(offset) } }
    }

    override fun onResume() {
        super.onResume()
        if (pendingRoute == null) {
            pendingUi = frozenUi
            pendingRoute = frozenUi?.route()
        }
        val waiting = restoreRecapRoute(pendingRoute, RestorationGate.WAIT, SystemClock.elapsedRealtime())
        pendingRoute = waiting.pending
        if (pendingRoute == null) {
            pendingUi = null; frozenUi = null; pendingScroll = null
            dismissDialog(false); addressDraft = ""; addressError = null
            showRecap = false; offset = 0; startingAt = null
        }
        foreground = true; ++generation; safety = SafetyState(); render()
        logUiState("resume")
        safetyClient = CarSafetyClient(this) { value, _ -> onSafety(value) }.also { it.start() }
        if (!speedAsked && checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            speedAsked = true; requestPermissions(arrayOf(Car.PERMISSION_SPEED), SPEED_PERMISSION)
        }
        main.post(poll)
    }

    override fun onPause() {
        DriveTrialLog.write("activity.onPause", "called")
        notices.clear()
        if (unlocked) { frozenUi = captureUi(); pendingUi = frozenUi; pendingRoute = frozenUi?.route() }
        logUiState("pause")
        foreground = false; ++generation; main.removeCallbacks(poll)
        ++dataEpoch; reading = false; recapLoading = false; recapOpenedAtMs = null; cancelAfterDraw(); cardColumn.clearCards()
        dismissDialog(preserve = true)
        main.removeCallbacks(startDeadline); startingRecorder = false
        invalidateAi()
        day = null; total = 0; unsure = 0; showRecap = false; lastFingerprint = null
        render() // Remove the raw/card view tree before disconnecting the UI's Car connection.
        safetyClient?.stop(); safetyClient = null; safety = SafetyState()
        super.onPause()
    }
    override fun onStop() { DriveTrialLog.write("activity.onStop", "called"); super.onStop() }
    override fun onDestroy() { io.shutdown(); aiIo.shutdownNow(); aiClient.close(); super.onDestroy() }
    override fun onRetainNonConfigurationInstance(): Any? = frozenUi ?: pendingUi
    override fun onSaveInstanceState(outState: Bundle) {
        val state = (if (unlocked) captureUi().route() else pendingRoute)?.let(::persistedRecapRoute)
        state?.let {
            fun anchor(prefix: String, value: ScrollAnchor) {
                outState.putString(prefix + "Key", value.key); outState.putInt(prefix + "OffsetDp", value.offsetDp)
                outState.putInt(prefix + "YDp", value.yDp)
            }
            outState.putBoolean("recap", it.recap); outState.putInt("offset", it.offset)
            outState.putLong("uiCapturedAt", it.capturedAtMs)
            anchor("primary", it.scroll.primary); anchor("cards", it.scroll.cards)
            outState.putBoolean("cardsFocused", it.scroll.cardsFocused)
        }
        logUiState("save", state != null)
        // No diagnostic drafts, vehicle values or dialog inputs go into OS/file-backed saved state.
        super.onSaveInstanceState(outState)
    }

    private fun captureScroll(): PaneScrollState {
        if (!::scroll.isInitialized) return pendingScroll ?: PaneScrollState()
        val primary = scroll.captureAnchor()
        return if (cardsScroll.visibility == View.VISIBLE) PaneScrollState(primary, cardsScroll.captureAnchor(), cardsFocused)
        else PaneScrollState(primary, if (primary.key?.startsWith("card:") == true) primary else pendingScroll?.cards ?: ScrollAnchor(),
            primary.key?.startsWith("card:") == true)
    }

    private fun captureUi(): Checkpoint {
        addressInput?.let { addressDraft = it.text.toString(); addressCursor = it.selectionStart.coerceAtLeast(0) }
        return Checkpoint(showRecap, offset, pendingScroll ?: captureScroll(), dialogKind, addressDraft, addressCursor,
            addressError, demoDraft, demo, speedAsked, notificationAsked, startingAt, aiEnabled, userAiChoice, aiProxyUrl,
            aiDecisions.toMap(), aiAttempts.filterKeys { it in aiDecisions }, SystemClock.elapsedRealtime())
    }

    private fun Checkpoint.route() = RecapUiRoute(recap, offset, scroll, capturedAtMs)
    private fun restoreRoute(state: RecapUiRoute) {
        showRecap = state.recap; offset = recapPageOffset(state.offset); pendingScroll = state.scroll
        cardsFocused = state.scroll.cardsFocused
    }

    private fun logUiState(event: String, bundle: Boolean = false) {
        if (BuildConfig.DEBUG) android.util.Log.i("ObserveLab", "event=ui.$event bundle=$bundle task=$taskId " +
            "recap=${pendingRoute?.recap ?: showRecap} offset=${pendingRoute?.offset ?: offset} pending=${pendingRoute != null}")
    }

    private fun restoreUi(state: Checkpoint) {
        showRecap = state.recap; offset = state.offset; pendingScroll = state.scroll
        dialogKind = state.dialog; addressDraft = state.address; addressCursor = state.addressCursor; addressError = state.addressError
        demoDraft = state.demoDraft; demo = state.speedDemo && BuildConfig.DEBUG
        speedAsked = state.askedSpeed; notificationAsked = state.askedNotification; startingAt = state.startAt
        aiEnabled = state.aiEnabled; userAiChoice = state.aiChoice
        aiProxyUrl = state.proxyUrl; aiClient.setBaseUrl(aiProxyUrl)
        aiDecisions.putAll(state.decisions); aiAttempts.putAll(state.attempts)
    }

    private fun onSafety(value: SafetyState) {
        val wasUnlocked = unlocked
        val previousGate = restorationGate(safety)
        safety = value
        val routeBefore = pendingRoute
        val restored = restoreRecapRoute(pendingRoute, restorationGate(value), SystemClock.elapsedRealtime())
        pendingRoute = restored.pending
        if (routeBefore != null && restored.restore == null && pendingRoute == null) {
            pendingUi = null; frozenUi = null; pendingScroll = null
            dismissDialog(false); addressDraft = ""; addressError = null
            showRecap = false; offset = 0; startingAt = null
        }
        when (restorationGate(value)) {
            RestorationGate.DISCARD -> {
                pendingUi = null; frozenUi = null; pendingScroll = null; pendingRoute = null
                openRequested = false
                dismissDialog(false); showRecap = false; offset = 0; startingAt = null
                addressDraft = ""; addressError = null
            }
            RestorationGate.WAIT -> if (wasUnlocked) {
                pendingUi = null; frozenUi = null; pendingRoute = null; dismissDialog(false); showRecap = false; pendingScroll = null
                addressDraft = ""; addressError = null
            }
            RestorationGate.READY -> if (!wasUnlocked && foreground) {
                pendingUi?.let(::restoreUi)
                restored.restore?.let(::restoreRoute)
                pendingUi = null; frozenUi = null
                startingAt?.let { at ->
                    val remaining = 12_000 - (SystemClock.elapsedRealtime() - at)
                    startingRecorder = remaining > 0
                    if (startingRecorder) main.postDelayed(startDeadline, remaining)
                }
                if (openRequested) { showRecap = true; openRequested = false }
                if (showRecap) openRecap(offset, preserve = true) else requestData()
            }
        }
        if (!unlocked) {
            day = null; lastFingerprint = null
            if (wasUnlocked) { ++dataEpoch; reading = false; recapLoading = false; cancelAfterDraw(); cardColumn.clearCards() }
        }
        // Do not rebuild an identical locked view tree on every speed callback.
        if (wasUnlocked != unlocked || previousGate != restorationGate(value)) render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION) toast("정차 후 기록 시작을 다시 눌러 주세요.")
        if (requestCode == SPEED_PERMISSION && foreground) {
            // Permission sheets need not pause every Activity implementation; retry failed subscriptions.
            safetyClient?.stop(); safetyClient = null
            safetyClient = CarSafetyClient(this) { value, _ -> onSafety(value) }.also { it.start() }
        }
    }

    private fun requestData(method: String = "read", id: String? = null, feedback: Feedback? = null) {
        if (!unlocked || (reading && method == "read")) return
        if (method == "read" && drawListener != null) return // Let the loading shell draw before any fast cached reply.
        if (method != "read") { pendingOperation = true; render() }
        if (method == "read") {
            reading = true
            if (day == null && error.isEmpty()) render()
        }
        val epoch = generation
        val requestedDataEpoch = dataEpoch
        val requestedOffset = offset
        val settingsVersion = aiSettingVersion
        val prepareSummary = showRecap
        if (method == "delete") invalidateAi()
        io.execute {
            val result = try {
                val extras = Bundle().apply {
                    putInt("offset", requestedOffset); putBoolean("prepareSummary", prepareSummary)
                    feedback?.let { putString("feedback", it.name) }
                }
                contentResolver.call(Uri.parse("content://$packageName.recap"), method, id, extras)
                    ?: Bundle().apply { putString("error", "복기 응답 없음") }
            } catch (e: Exception) { Bundle().apply { putString("error", "복기 연결 오류: ${e.javaClass.simpleName}") } }
            // Decode off the UI thread, including cached AI JSON. Binder payloads can contain 100 cards.
            val bytes = result.getByteArray("day")
            val decoded = runCatching { bytes?.let(RecapCodec::decode) }.getOrNull()
            val summaryJson = result.getString("summaryResult")
            val decodedSummary = runCatching { summaryJson?.let { AiJson.generated(org.json.JSONObject(it)) } }.getOrNull()
            val bytesHash = bytes?.contentHashCode()
            main.post {
                if (requestedDataEpoch != dataEpoch) return@post
                if (method == "read") reading = false
                if (!currentRecapResponse(requestedDataEpoch, dataEpoch, requestedOffset, offset)) return@post
                if (method != "read") pendingOperation = false
                if (!unlocked || generation != epoch || settingsVersion != aiSettingVersion || requestedOffset != offset) return@post
                day = decoded
                recapLoading = false
                recording = result.getBoolean("recording"); recordingDemo = result.getBoolean("demo")
                demoNaviEnabled = BuildConfig.DEBUG && result.getBoolean("demoNaviEnabled")
                hasDemoNaviCards = result.getBoolean("hasDemoNaviCards")
                total = result.getInt("total"); unsure = result.getInt("unsure")
                todaySummary = result.getString("recapSummary") ?: recapSummary(emptyList())
                tileCounts = RecapTileCounts(result.getInt("tileMissing"), result.getInt("tileLate"), result.getInt("tileHardBrake"), result.getInt("tileGood"))
                navi = result.getString("navi") ?: "미수신"; error = result.getString("error") ?: ""
                if (recording || error.isNotEmpty()) {
                    startingRecorder = false; startingAt = null; main.removeCallbacks(startDeadline)
                }
                seed = result.getString("seed") ?: "초기 신호 확인 중"
                val enabled = userAiChoice ?: result.getBoolean("aiEnabled", true)
                if (enabled != aiEnabled) { invalidateAi(); aiEnabled = enabled }
                val proxyUrl = result.getString("aiProxyUrl")?.let(::normalizedProxyEndpoint) ?: aiProxyUrl
                if (proxyUrl != aiProxyUrl) { invalidateAi(); aiProxyUrl = proxyUrl; aiClient.setBaseUrl(proxyUrl) }
                generated = if (aiEnabled) decodedSummary else null
                summaryPending = aiEnabled && result.getBoolean("summaryPending")
                val fingerprint = "$bytesHash|$recording|$recordingDemo|$total|$unsure|$todaySummary|$tileCounts|$navi|$seed|$error|$showRecap|$offset|$aiEnabled|$aiProxyUrl|$summaryJson|$summaryPending|$startingRecorder|$demoNaviEnabled|$hasDemoNaviCards"
                if (fingerprint != lastFingerprint) { lastFingerprint = fingerprint; render() }
                restoreDialog()
                requestAi()
            }
        }
    }

    private fun cancelAfterDraw() {
        drawListener?.let { if (scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnDrawListener(it) }
        drawListener = null
    }

    private fun openRecap(page: Int, preserve: Boolean = false) {
        if (!unlocked) return
        cancelAfterDraw(); ++dataEpoch; reading = false; pendingOperation = false
        recapOpenedAtMs = SystemClock.elapsedRealtime()
        showRecap = true; offset = recapPageOffset(page); recapLoading = true; day = null; error = ""
        if (!preserve) pendingScroll = PaneScrollState()
        cardColumn.clearCards(); lastFingerprint = null
        render() // The click itself installs a lightweight shell and spinner, never 100 tiles.
        val epoch = generation; val requestEpoch = dataEpoch
        var posted = false
        val listener = ViewTreeObserver.OnDrawListener {
            if (!posted) {
                posted = true
                if (BuildConfig.DEBUG) android.util.Log.i("ObserveLab", "event=ui.recap.shell elapsedMs=${SystemClock.elapsedRealtime() - (recapOpenedAtMs ?: SystemClock.elapsedRealtime())}")
                main.post {
                    if (epoch == generation && requestEpoch == dataEpoch && unlocked) {
                        cancelAfterDraw(); requestData()
                    }
                }
            }
        }
        drawListener = listener; scroll.viewTreeObserver.addOnDrawListener(listener); scroll.invalidate()
    }

    private fun invalidateAi() {
        ++aiEpoch; aiClient.cancel(); aiTasks.forEach { it.cancel(true) }; aiTasks.clear()
        aiDecisions.clear(); aiAttempts.clear()
        generated = null; summaryPending = false
    }

    private fun requestAi() {
        if (!unlocked || !showRecap || !aiEnabled) return
        val epoch = aiEpoch
        aiTasks.removeAll { it.isDone }
        day?.cards?.forEach { card ->
            val features = classifyFeatures(card) ?: return@forEach
            if (aiAttempts[card.id] == features) return@forEach
            aiAttempts[card.id] = features
            aiDecisions.remove(card.id)
            aiTasks += aiIo.submit {
                if (Thread.currentThread().isInterrupted) return@submit
                val decision = aiClient.classify(card)
                main.post {
                    if (unlocked && showRecap && aiEnabled && epoch == aiEpoch && aiAttempts[card.id] == features) {
                        aiDecisions[card.id] = decision; render()
                    }
                }
            }
        }
    }

    private fun toggleRecording() {
        if (!unlocked || startingRecorder) return
        if (!recording && Build.VERSION.SDK_INT >= 33 && !notificationAsked &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationAsked = true
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
            return
        }
        try {
            val command = Intent(this, DriveRecorderService::class.java)
            if (recording) startService(command.setAction(DriveRecorderService.ACTION_STOP))
            else startForegroundService(command.setAction(DriveRecorderService.ACTION_START)
                .putExtra(DriveRecorderService.EXTRA_DEMO_MISSING_SPEED, demo && BuildConfig.DEBUG))
            if (!recording) {
                startingRecorder = true; startingAt = SystemClock.elapsedRealtime(); error = ""; render()
                main.removeCallbacks(startDeadline); main.postDelayed(startDeadline, 12_000)
            } else toast("P 상태의 기록 중지를 요청했어요.")
            main.postDelayed({ if (unlocked) requestData() }, 500)
        } catch (e: Exception) { toast("기록 요청 실패: ${e.javaClass.simpleName}") }
    }

    private fun render() {
        if (!::content.isInitialized) return
        val anchors = pendingScroll ?: captureScroll()
        val wide = unlocked && showRecap && layoutSpec.twoPanes
        cardsScroll.visibility = if (wide) View.VISIBLE else View.GONE
        title.isLongClickable = unlocked
        status.text = if (unlocked) "운전 중에는 기록만, 복기는 차를 세운 뒤" else "정차 후 사용"
        content.removeAllViews(); cardsContent.removeAllViews()
        if (!unlocked) {
            cardColumn.clearCards()
            paragraph("정차 후 사용", 40f)
            paragraph("기어 P·속도 0·주행 제한 해제가 확인되면 복기를 볼 수 있어요.", 26f, MUTED)
            scroll.scrollTo(0, 0); return
        }
        if (showRecap && (recapLoading || (day == null && error.isEmpty()))) {
            paragraph("오늘 복기", 32f)
            progress("불러오는 중")
            return
        }
        if (error.isNotEmpty()) paragraph(error, 26f)
        if (pendingOperation || startingRecorder || (day == null && reading && error.isEmpty())) progress("복기 서비스 준비 중")
        val d = day
        if (!showRecap) renderHome(d) else renderRecap(d, wide)
        if ((d?.speedUnknownDrivingMs ?: 0) > 0) paragraph("이 중 ${d!!.speedUnknownDrivingMs / 1_000}초는 속도 미수신 경로예요.", 24f, MUTED)
        if (recordingDemo) paragraph("시연 기록: 실제 속도를 사용하지 않았어요. 모든 후보를 애매함으로 표시해요.", 24f, MUTED)
        paragraph("$navi\n내비가 없으면 기존 추정 판정을 사용해요. 조향 부호로 좌우를 정하지 않으며, 내비 확인도 법률 판단이 아니에요.", 20f, MUTED)
        action("기록 삭제") { confirmDelete() }
        if (BuildConfig.DEBUG) paragraph("창 폭 ${resources.configuration.screenWidthDp}dp · ${layoutSpec.width} · 시스템 주간/야간", 20f, MUTED)
        if (d != null) {
            pendingScroll = null
            val epoch = generation; val requestEpoch = dataEpoch
            scroll.post { if (unlocked && epoch == generation && requestEpoch == dataEpoch) {
                restoringScroll = true
                if (wide) { scroll.restoreAnchor(anchors.primary); cardsScroll.restoreAnchor(anchors.cards) }
                else scroll.restoreAnchor(singlePaneAnchor(anchors))
                if (serverDialog == null) {
                    val focusedPane = if (wide && anchors.cardsFocused) cardsScroll else scroll
                    focusedPane.isFocusableInTouchMode = true; focusedPane.requestFocus()
                }
                cardsFocused = anchors.cardsFocused; restoringScroll = false
            } }
        }
    }

    private fun renderHome(d: DayRecap?) {
        // Pleos 차량 설정 타일처럼 상태 글자만 두고 타일 전체를 눌러 켜고 끈다(작은 스위치 제거).
        val tiles = mutableListOf<View>(
            PleosTile(this, PleosIcon.RECORD, "기록", when {
                d == null -> "기록 상태 확인 중"
                recording -> "기록 중 · 주행 ${d.drivingMs / 1_000}초"
                else -> "대기 · 주행 ${d.drivingMs / 1_000}초"
            }, tone = if (recording) PleosTone.INFORMATION else PleosTone.NEUTRAL),
            PleosTile(this, PleosIcon.RECAP, "오늘 복기", "짚어볼 순간 ${if (d == null) "확인 중" else "${total}개"}",
                forward = true, action = { openRecap(0) }),
            PleosTile(this, PleosIcon.AI_SUMMARY, "AI 보조", if (aiEnabled) "켜짐" else "꺼짐",
                tone = if (aiEnabled) PleosTone.INFORMATION else PleosTone.NEUTRAL,
                action = { changeAi(!aiEnabled) }))
        if (demoNaviEnabled) tiles += PleosTile(this, PleosIcon.NAVIGATION, "모의 내비", "시연용 입력", PleosTone.CAUTION)
        content.addView(tileGrid(tiles, layoutSpec.width != VehicleWidth.NARROW).apply { tag = "home-tiles" }, params())
        if (recording) paragraph(seed, 24f, MUTED)
        content.addView(PleosPrimaryButton(this, if (startingRecorder) "준비 중…" else if (recording) "기록 중지 (P에서만)" else "기록 시작 (P에서만)") {
            if (unlocked) toggleRecording()
        }.apply { isEnabled = !startingRecorder && !pendingOperation }, params())
        if (hasDemoNaviCards && !demoNaviEnabled) paragraph("시연용 모의 내비 판정 포함", 24f, MUTED)
        // AI 서버 설정·속도 미수신 시연은 숨은 진단 메뉴(제목 길게 누르기)로 옮김: 운전자 화면에는 핵심 동작만.
    }

    private fun changeAi(checked: Boolean) {
        if (!unlocked || checked == aiEnabled) return
        ++aiSettingVersion; invalidateAi(); aiEnabled = checked; userAiChoice = checked
        requestData("aiSetting", if (checked) "on" else "off"); render()
    }

    private fun renderRecap(d: DayRecap?, wide: Boolean) {
        if (demoNaviEnabled || hasDemoNaviCards) paragraph(if (demoNaviEnabled) "시연용 모의 내비 입력 사용 중" else "시연용 모의 내비 판정 포함", 26f)
        val counts = listOf(
            Triple("깜빡이 놓침", tileCounts.missing, PleosIcon.LANE_CHANGE),
            Triple("늦게 켬", tileCounts.late, PleosIcon.LATE_SIGNAL),
            Triple("급제동", tileCounts.hardBrake, PleosIcon.HARD_BRAKE),
            Triple("잘한 순간", tileCounts.good, PleosIcon.GOOD))
        val tones = listOf(PleosTone.WARNING, PleosTone.CAUTION, PleosTone.WARNING, PleosTone.INFORMATION)
        content.addView(tileGrid(counts.mapIndexed { i, item ->
            PleosTile(this, item.third, item.first, "", tones[i], number = if (d == null) "—" else item.second.toString())
        }, layoutSpec.width != VehicleWidth.NARROW).apply { tag = "summary" }, params()) // 세로 1칸(628dp↑)도 2×2
        paragraph(todaySummary, 24f, MUTED)
        paragraph("${d?.date ?: "오늘"} · 주행 ${(d?.drivingMs ?: 0) / 1_000}초 · 후보 $total 개 · 규칙 애매함 $unsure 개", 24f, MUTED)
        val message = column().apply {
            tag = "daily-message"; background = vehicleSurface(colors.listGroup, 32)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(pleosIcon(PleosIcon.AI_SUMMARY, FG))
        header.addView(label("오늘의 한마디", 32f, FG, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        message.addView(header, params())
        message.addView(label("AI 한마디", 24f, MUTED), params())
        message.addView(VehicleParagraph(this, generatedOrTemplate(generated, total > 0), 28f, FG), params())
        message.addView(label(when {
            summaryPending -> "AI 문장 준비 중"
            generated != null -> "AI 문장 · 정차 후 읽는 복기"
            else -> "기본 문구"
        }, 24f, MUTED))
        if (summaryPending) message.addView(ProgressBar(this).apply { contentDescription = "AI 문장 준비 중" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        generated?.patterns?.forEach { message.addView(VehicleParagraph(this, it, 26f, MUTED), params()) }
        content.addView(message, params())
        val cardTarget = if (wide) cardsContent else content
        if (d?.cards.isNullOrEmpty()) cardTarget.addView(VehicleParagraph(this, "아직 복기 후보가 없어요. 기록을 시작한 뒤 정차해서 확인해 주세요.", 26f, MUTED), params())
        val cards = d?.cards.orEmpty()
        (cardColumn.parent as? ViewGroup)?.removeView(cardColumn)
        cardTarget.addView(cardColumn, LinearLayout.LayoutParams(-1, -2))
        val viewport = if (wide) cardsScroll else scroll
        cardColumn.submit(cards.map { "card:${it.id}" }, cards.map {
            listOf(it, aiDecisions[it.id], aiEnabled, pendingOperation, layoutSpec.width)
        }, viewport, preserve = {
            val anchor = viewport.captureAnchor(); val y = viewport.scrollY; val epoch = generation; val requestEpoch = dataEpoch
            val restore: () -> Unit = {
                viewport.post { if (unlocked && epoch == generation && requestEpoch == dataEpoch && viewport.scrollY == y) viewport.restoreAnchor(anchor) }
            }; restore
        }) { index -> column().apply {
            addView(reviewTile(cards[index]), LinearLayout.LayoutParams(-1, -2)); addView(PleosDivider(this@MainActivity))
        } }
        if (offset > 0) action("최근 카드로") { openRecap(0) }
        nextRecapPage(offset, cards.size, total)?.let { next -> action("다음 카드 100개") { openRecap(next) } }
        if (aiEnabled) action("AI 다시 확인") { invalidateAi(); summaryPending = true; requestData("summaryRetry"); render(); requestAi() }
        action("처음으로") {
            ++dataEpoch; reading = false; cancelAfterDraw(); cardColumn.clearCards()
            showRecap = false; recapLoading = false; invalidateAi(); pendingScroll = PaneScrollState(); render()
        }
    }

    private fun reviewTile(c: ReviewCard): View {
        val appearance = reviewAppearance(c.kind, c.navigationTurn, c.direction)
        val decision = aiDecisions[c.id].takeIf { aiEnabled }
        val box = column().apply {
            tag = "card:${c.id}"; setBackgroundColor(SURFACE); setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pleosIcon(appearance.icon, colors.tone(appearance.tone)))
        val text = column()
        val titleText = if (decision?.accepted == true) decision.label ?: recapTitle(c) else recapTitle(c)
        text.addView(VehicleParagraph(this, titleText, 32f, FG, true), params())
        val aiLabel = aiCardTitle(c, decision, aiEnabled).removePrefix("${recapTitle(c)} · ")
        val badge = if (decision?.accepted == true) "AI 추정 · ${navigationLabel(c)}"
            else aiLabel + if (c.navigation == NavigationLabel.ESTIMATED) " · 추정" else ""
        text.addView(VehicleParagraph(this, badge, 24f, MUTED).apply {
            background = vehicleSurface(colors.listGroup, 16); setPadding(dp(12), dp(8), dp(12), dp(8))
        }, params())
        if (c.demoNavigation && c.navigation == NavigationLabel.ESTIMATED) text.addView(label("모의 내비 미수신/안내 없음", 24f, MUTED), params())
        if (decision?.accepted == true) text.addView(VehicleParagraph(this, "규칙 결과: ${recapTitle(c)}", 24f, MUTED), params())
        val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(c.wallTimeMs))
        text.addView(VehicleParagraph(this, "$time · ${recapEvidence(c, debug = BuildConfig.DEBUG)}", 26f, MUTED), params())
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        box.addView(row)
        box.addView(PleosTabs(this, listOf("맞아요", "아니에요"), when (c.feedback) { Feedback.YES -> 0; Feedback.NO -> 1; else -> null }, !pendingOperation) { index ->
            if (unlocked) { cardsFocused = true; requestData("feedback", c.id, if (index == 0) Feedback.YES else Feedback.NO) }
        })
        return box
    }

    private fun tileGrid(tiles: List<View>, twoColumns: Boolean): View {
        tiles.forEachIndexed { index, tile -> tile.tag = "tile:${if (showRecap) "recap" else "home"}:$index" }
        val grid = column()
        tiles.chunked(if (twoColumns) 2 else 1).forEachIndexed { i, group ->
            if (i > 0) grid.addView(PleosDivider(this))
            // weightSum keeps a lone tile at half width without a filler child; a fixed-height filler
            // would become the wrap-height row's only non-fill child and collapse the row to 1px.
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; if (twoColumns) weightSum = 2f }
            group.forEachIndexed { index, tile ->
                if (index > 0) row.addView(PleosDivider(this, true))
                row.addView(tile, LinearLayout.LayoutParams(0, -1, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        return grid
    }

    private fun progress(value: String) {
        val row = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(ProgressBar(this).apply { contentDescription = value }, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(label(value, 26f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        content.addView(row, params())
    }
    private fun dialogTitle(value: String) = VehicleParagraph(this, value, pleosTitle(layoutSpec.width), FG, true).apply {
        setPadding(dp(24), dp(24), dp(24), dp(16))
    }

    private fun dismissDialog(preserve: Boolean) {
        suspendingDialog = preserve
        serverDialog?.dismiss(); serverDialog = null; addressInput = null
        suspendingDialog = false
        if (!preserve) dialogKind = DialogKind.NONE
    }

    private fun present(kind: DialogKind, dialog: AlertDialog) {
        dismissDialog(false); dialogKind = kind; serverDialog = dialog
        dialog.setOnDismissListener {
            if (serverDialog === dialog) {
                serverDialog = null; addressInput = null
                if (!suspendingDialog) dialogKind = DialogKind.NONE
            }
        }
        dialog.show(); styleVehicleDialog(dialog)
    }

    private fun restoreDialog() {
        if (!unlocked || serverDialog != null) return
        when (dialogKind) {
            DialogKind.MENU -> openDiagnosticsMenu()
            DialogKind.SERVER -> editAiServer(true)
            DialogKind.DEMO -> editDemoNavigation(true)
            DialogKind.DELETE -> confirmDelete()
            DialogKind.NONE -> Unit
        }
    }

    private fun openDiagnosticsMenu() {
        if (!unlocked) return
        val choices = mutableListOf("신호 탐침·기존 게임", "내비 경로 요청 (입력 저장 안 함)", "AI 서버 설정")
        if (BuildConfig.DEBUG) choices += "시연용 모의 내비"
        if (BuildConfig.DEBUG && !recording) choices += if (demo) "속도 미수신 시연 끄기 (Debug)" else "속도 미수신 시연 켜기 (Debug)"
        val menu = column()
        val dialog = AlertDialog.Builder(this).setCustomTitle(dialogTitle("숨은 진단 메뉴")).setView(ScrollView(this).apply { addView(menu) })
            .setNegativeButton("닫기", null).create()
        choices.forEachIndexed { which, name -> menu.addView(button(name) {
            if (unlocked) {
                dialog.dismiss()
                when (which) {
                    0 -> startActivity(Intent(this, LegacyLabActivity::class.java).putExtra("recapNaviStatus", navi))
                    1 -> startActivity(Intent(this, NaviDemoActivity::class.java))
                    2 -> editAiServer()
                    3 -> editDemoNavigation()
                    else -> { demo = !demo; toast(if (demo) "속도 미수신 시연 켜짐 (Debug)" else "속도 미수신 시연 꺼짐"); render() }
                }
            }
        }, params()) }
        present(DialogKind.MENU, dialog)
    }

    private fun confirmDelete() {
        if (!unlocked) return
        present(DialogKind.DELETE, AlertDialog.Builder(this).setCustomTitle(dialogTitle("기록과 집계 삭제"))
            .setMessage("오늘·어제 요약과 피드백, 최근 집계, 저장된 AI 문장을 삭제합니다. 기록 중이면 집계도 새로 시작합니다.")
            .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                if (unlocked) { offset = 0; requestData("delete") }
            }.create())
    }

    private fun editDemoNavigation(restoring: Boolean = false) {
        if (!unlocked || !BuildConfig.DEBUG) return
        if (recording || startingRecorder) { dialogKind = DialogKind.NONE; toast("기록을 중지한 뒤 내비 원천을 바꿔 주세요."); return }
        if (!restoring) demoDraft = demoNaviEnabled
        val toggle = CheckBox(this).apply {
            text = "시연용 모의 내비"; textSize = 26f; setTextColor(FG); minHeight = dp(64)
            isChecked = demoDraft; isSaveEnabled = false
            setPadding(dp(24), dp(18), dp(24), dp(18))
            setOnCheckedChangeListener { _, checked -> demoDraft = checked }
        }
        present(DialogKind.DEMO, AlertDialog.Builder(this).setCustomTitle(dialogTitle("시연용 모의 내비 (Debug)"))
            .setMessage("실제 내비 안내가 아닌 모의 입력으로 판정합니다. 기록 시작 전에만 변경하며 프로세스를 다시 시작하면 꺼집니다.")
            .setView(toggle).setNegativeButton("취소", null).setPositiveButton("적용") { _, _ ->
                if (unlocked && !recording && !startingRecorder) requestData("demoNaviSetting", if (toggle.isChecked) "on" else "off")
            }.create())
    }

    private fun editAiServer(restoring: Boolean = false) {
        if (!unlocked) return
        if (!restoring) { addressDraft = aiProxyUrl; addressCursor = addressDraft.length; addressError = null }
        val address = EditText(this).apply {
            setText(addressDraft); setSelection(addressCursor.coerceIn(0, length())); textSize = 24f
            minHeight = dp(64); isSingleLine = true; isSaveEnabled = false; isSaveFromParentEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            error = addressError
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { addressDraft = s.toString() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        val dialog = AlertDialog.Builder(this).setCustomTitle(dialogTitle("AI 서버 설정"))
            .setMessage("기본 주소: $DEFAULT_AI_PROXY_URL\nHTTPS 또는 10.0.2.2 HTTP 주소만 입력하세요. 키는 입력하지 않아요.")
            .setView(address).setNegativeButton("취소", null).setPositiveButton("저장", null).create()
        present(DialogKind.SERVER, dialog); addressInput = address
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!unlocked) { dialog.dismiss(); return@setOnClickListener }
            val selected = normalizedProxyEndpoint(address.text.toString())
            if (selected == null) {
                addressError = "주소 형식을 확인해 주세요. 인증정보·쿼리·경로는 넣을 수 없어요."
                address.error = addressError
            } else {
                ++aiSettingVersion; invalidateAi(); requestData("aiServer", selected); dialog.dismiss()
            }
        }
    }

    private fun toast(value: String) { if (unlocked) notices.show(value) }
    private fun paragraph(value: String, size: Float, color: Int = FG) { content.addView(VehicleParagraph(this, value, size, color).apply { tag = "text:" + value.take(40) }, params()) }
    private fun action(text: String, action: () -> Unit) { content.addView(button(text) { if (unlocked) action() }, params()) }
    private fun button(value: String, action: () -> Unit) = PleosSecondaryButton(this, value, action)
    private fun label(value: String, size: Float, color: Int = FG, bold: Boolean = false) = TextView(this).apply {
        text = value; pleosText(size, color, if (bold) 700 else 400)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isSaveEnabled = false; isSaveFromParentEnabled = false }
    private fun params() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    companion object {
        private const val SPEED_PERMISSION = 10
        private const val NOTIFICATION_PERMISSION = 11
    }
}
