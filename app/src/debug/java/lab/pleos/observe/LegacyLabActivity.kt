package lab.pleos.observe

import android.app.Activity
import android.car.Car
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import lab.pleos.observe.logic.*
import java.util.Locale

/** Vehicle values stay in diagnostics or a revealed explanation, never a live question. */
class LegacyLabActivity : Activity() {
    private data class Checkpoint(val model: LabState, val scroll: ScrollAnchor, val probe: Boolean,
        val permission: Boolean, val notification: Boolean)
    private var frozenUi: Checkpoint? = null
    private var pendingUi: Checkpoint? = null
    private var pendingScroll: ScrollAnchor? = null
    private var probeScroll: ScrollAnchor? = null
    private var layoutSpec = vehicleLayout(0)
    private val colors get() = vehicleColors()
    private val BACKGROUND get() = colors.background
    private val FOREGROUND get() = colors.foreground
    private val MUTED get() = colors.muted
    private val ACCENT get() = colors.accent
    private val SUCCESS get() = colors.success
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var model = LabState(seed = System.nanoTime().toInt())
    private var drawnScreen: LabScreen? = null
    private var safety = SafetyState()
    private var uxDetail = "미수신"
    private var sdkStatus = "초기화 전"
    private var observations = emptyList<Observation>()
    private var foreground = false
    private var permissionAsked = false
    private var recorderNotificationAsked = false
    private var safetyClient: CarSafetyClient? = null
    private var observationClient: PleosObservationClient? = null
    private var probeOpen = false
    private var probeClient: SignalProbeClient? = null
    private var probeSignals = emptyList<ProbeSignalState>()
    private var probeStatus = ""
    private var probeRenderPosted = false
    private var diagnosticEntryPending = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a memory object survives configuration recreation; never serialize diagnostic values.
        pendingUi = lastNonConfigurationInstance as? Checkpoint
        pendingUi?.let { permissionAsked = it.permission; recorderNotificationAsked = it.notification; diagnosticEntryPending = false }
        layoutSpec = vehicleLayout(resources.configuration.screenWidthDp.coerceAtLeast(0))
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        val root = column().apply {
            setBackgroundColor(BACKGROUND)
            setPadding(dp(layoutSpec.marginDp), dp(16), dp(layoutSpec.marginDp), dp(16))
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(dp(layoutSpec.marginDp) + insets.systemWindowInsetLeft, dp(16) + insets.systemWindowInsetTop,
                    dp(layoutSpec.marginDp) + insets.systemWindowInsetRight, dp(16) + insets.systemWindowInsetBottom)
                insets
            }
        }
        title = text("우리 차 관찰 추리실", 32f, bold = true).apply {
            minHeight = dp(48)
            setOnLongClickListener { if (model.unlocked) { send(LabAction.Diagnostics); true } else false }
        }
        root.addView(title)
        status = text("", 18f, MUTED).apply { setPadding(0, dp(10), 0, dp(18)) }
        root.addView(status)
        content = column().apply { setPadding(0, dp(8), 0, dp(20)) }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        send(LabAction.Safety(safety, foreground))
        startClients()
        if (checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED && !permissionAsked) {
            permissionAsked = true
            requestPermissions(arrayOf(Car.PERMISSION_SPEED), SPEED_PERMISSION)
        }
    }

    private fun startClients() {
        if (!foreground || safetyClient != null) return
        safety = SafetyState()
        observations = emptyList()
        send(LabAction.Safety(safety, foreground))
        safetyClient = CarSafetyClient(this) { value, detail ->
            val changed = safety != value || uxDetail != detail
            val wasUnlocked = model.unlocked
            safety = value
            uxDetail = detail
            if (restorationGate(value) == RestorationGate.DISCARD || (wasUnlocked && shouldLock(value))) {
                pendingUi = null; frozenUi = null; pendingScroll = null; probeScroll = null
            }
            send(LabAction.Safety(value, foreground))
            if (pendingUi != null) tryRestore()
            else if (model.unlocked) send(LabAction.Snapshot(snapshotFromObservations(observations)))
            if (changed && model.screen == LabScreen.Diagnostics) render(force = true)
        }.also { it.start() }
        try {
            observationClient = PleosObservationClient(this) { sdk, rows ->
                sdkStatus = sdk
                observations = rows
                if (pendingUi != null) tryRestore() else send(LabAction.Snapshot(snapshotFromObservations(rows)))
                if (model.screen == LabScreen.Diagnostics) render(force = true)
            }.also { it.start() }
        } catch (e: Exception) {
            EvidenceLog.error("pleos.client.failed", e)
            sdkStatus = "SDK 생성 오류: ${e.javaClass.simpleName}"
            render(force = model.screen == LabScreen.Diagnostics)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SPEED_PERMISSION) {
            EvidenceLog.event("permission.speed", "granted=${checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED}")
            stopClients()
            startClients()
        }
        if (requestCode == RECORDER_NOTIFICATION_PERMISSION) {
            DriveTrialLog.write("activity.notificationPermission",
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED, "permission")
            Toast.makeText(this, "정차 후 제목을 길게 눌러 진단으로 돌아가 기록 시험 시작을 다시 눌러 주세요.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        DriveTrialLog.write("activity.onPause", "called")
        if (model.unlocked && pendingUi == null) frozenUi = Checkpoint(model, scroll.captureAnchor(), probeOpen, permissionAsked, recorderNotificationAsked)
        foreground = false
        // Synchronously remove question/answer views and discard the memory baseline.
        send(LabAction.Safety(safety, false))
        stopClients()
        super.onPause()
    }

    override fun onRetainNonConfigurationInstance(): Any? = pendingUi ?: frozenUi

    private fun tryRestore() {
        val checkpoint = pendingUi ?: return
        val fresh = snapshotFromObservations(observations)
        val settled = observations.isNotEmpty() && observations.all { it.supported == false || it.value != null || it.error != null }
        val restored = restoreLabCheckpoint(checkpoint.model, safety, fresh, settled) ?: return
        model = restored; pendingUi = null; pendingScroll = checkpoint.scroll
        drawnScreen = null; render(force = true)
        scroll.post {
            if (!model.unlocked) return@post
            if (checkpoint.probe && model.screen == LabScreen.Diagnostics) {
                probeScroll = checkpoint.scroll; openProbe()
            }
            (model.screen as? LabScreen.MemoryReading)?.let { reading ->
                observationClient?.readFresh { success, rows ->
                    send(LabAction.FreshRead(reading.id, if (success) snapshotFromObservations(rows) else null))
                } ?: send(LabAction.FreshRead(reading.id, null))
            }
        }
    }

    override fun onStop() {
        DriveTrialLog.write("activity.onStop", "called")
        super.onStop()
    }

    private fun stopClients() {
        safety = SafetyState()
        send(LabAction.Safety(safety, foreground))
        safetyClient?.stop()
        observationClient?.stop()
        safetyClient = null
        observationClient = null
        observations = emptyList()
        sdkStatus = "관찰 중지"
    }

    private fun send(action: LabAction) {
        val previousSeats = model.current.seats.keys
        val transition = reduceLab(model, action)
        model = transition.state
        if (diagnosticEntryPending && model.unlocked) {
            diagnosticEntryPending = false
            model = reduceLab(model, LabAction.Diagnostics).state
        }
        if (!model.unlocked || model.screen != LabScreen.Diagnostics) {
            if (probeOpen) {
                probeOpen = false
                // Remove raw views before calling any SDK unregister operation.
                render(force = true)
            }
            closeProbe()
        }
        transition.events.forEach { event ->
            val correct = event.correct
            if (correct == null) EvidenceLog.event(event.name) else EvidenceLog.answer(event.name, correct)
        }
        render(force = model.screen is LabScreen.MemoryReady && previousSeats != model.current.seats.keys)
        transition.refresh?.let { request ->
            val client = observationClient
            if (client == null) send(LabAction.FreshRead(request.id, null))
            else client.readFresh { success, rows ->
                send(LabAction.FreshRead(request.id, if (success) snapshotFromObservations(rows) else null))
            }
        }
    }

    private fun render(force: Boolean = false) {
        val unlocked = model.unlocked
        title.isLongClickable = unlocked
        status.text = if (unlocked) "정차 확인 · 진행 기록은 저장하지 않아요" else
            "정차 후 사용 · " + if (foreground) lockReasons(model.safety).joinToString(" · ") else "관찰 중지"
        val screen = model.screen
        if (!force && drawnScreen == screen) return
        val anchor = probeScroll ?: pendingScroll ?: if (drawnScreen == screen) scroll.captureAnchor() else ScrollAnchor()
        drawnScreen = screen
        // Remove the view tree, not just an overlay. No stale text survives lock.
        content.removeAllViews()
        scroll.scrollTo(0, 0)
        if (pendingUi != null && model.unlocked) {
            heading("화면 상태 복원 중")
            paragraph("차량 값을 새로 확인하고 있어요.")
            return
        }
        when (screen) {
            LabScreen.Locked -> {
                heading("정차 후 사용", ACCENT)
                paragraph("차가 멈추고 기어가 P인지 확인해 주세요.\n안전 신호가 확인되면 처음 화면으로 돌아가요.")
            }
            LabScreen.Home -> {
                heading("차를 세우고, 둘러보고, 맞혀 보세요")
                paragraph("눈으로 살펴보고 함께 추리하는 세 가지 문제", MUTED)
                gap(16)
                choice("문제 1 숫자 관찰") { send(LabAction.Open(ProblemKind.NUMBER)) }
                choice("문제 2 자리 추리") { send(LabAction.Open(ProblemKind.SEAT)) }
                choice("문제 3 무엇이 바뀌었나") { send(LabAction.Open(ProblemKind.CHANGE)) }
            }
            is LabScreen.Unavailable -> {
                notice(screen.notice)
                heading("아직 문제를 만들 수 없어요")
                paragraph(screen.reason)
                if (model.current.seats.size < if (screen.kind == ProblemKind.SEAT) 3 else 2) {
                    paragraph("숫자·변경 문제는 확인된 좌석 2곳 이상, 자리 추리는 3곳 이상이 필요해요.", MUTED, 21f)
                }
                choice("다시 확인하기") { send(LabAction.Open(screen.kind)) }
                homeButton()
            }
            is LabScreen.Number -> {
                notice(screen.notice)
                heading("문제 1 · 숫자 관찰")
                paragraph("켜진 독서등 수와\n절반 이상 열린 창문 수를 더하면?", size = 32f)
                targets(screen.problem.snapshot)
                horizontalChoices(screen.problem.options.map { value -> value.toString() to { send(LabAction.NumberAnswer(screen.id, value)) } })
                homeButton()
            }
            is LabScreen.SeatQuestion -> {
                notice(screen.notice)
                heading("문제 2 · 자리 추리")
                screen.problem.clues.forEachIndexed { i, clue -> paragraph("${i + 1}. ${clue.text}", size = 28f) }
                seatGrid(screen.problem.snapshot.seats.keys) { seat -> send(LabAction.SeatAnswer(screen.id, seat)) }
                homeButton()
            }
            is LabScreen.MemoryReady -> {
                notice(screen.notice)
                heading("문제 3 · 무엇이 바뀌었나")
                paragraph("창문과 독서등을 둘러보고 기억해 주세요.\n준비되면 아래 버튼을 누르세요.", size = 30f)
                targets(model.current)
                choice("기억하기", accent = true) { send(LabAction.Remember) }
                homeButton()
            }
            is LabScreen.MemoryReading -> {
                heading("차의 값을 다시 확인하고 있어요")
                paragraph("잠시만 기다려 주세요. 읽기가 끝나기 전에는 차를 그대로 두세요.")
                homeButton()
            }
            is LabScreen.MemoryHidden -> {
                notice(screen.notice)
                heading("화면을 가렸어요", ACCENT)
                paragraph("한 사람이 창문이나 독서등 하나만 바꾸세요.\n다 되면 [바뀌었어요]를 누르세요.", size = 32f)
                gap(24)
                choice("바뀌었어요", accent = true) { send(LabAction.Changed) }
                homeButton()
            }
            is LabScreen.ChangeQuestion -> {
                heading("문제 3 · 무엇이 바뀌었나")
                paragraph("어느 자리의 무엇이 달라졌을까요?", size = 32f)
                screen.problem.options.chunked(2).forEach { pair ->
                    horizontalChoices(pair.map { key -> key.label to { send(LabAction.ChangeAnswer(screen.id, key)) } })
                }
                homeButton()
            }
            is LabScreen.Result -> {
                heading(if (screen.correct) "맞았어요!" else "틀렸어요", if (screen.correct) SUCCESS else ACCENT)
                paragraph("왜 그런지 살펴볼까요?", MUTED, 24f)
                paragraph(screen.explanation, size = 27f)
                val nextKind = ProblemKind.values()[(screen.kind.ordinal + 1) % ProblemKind.values().size]
                horizontalChoices(listOf("다른 문제" to { send(LabAction.Open(nextKind)) }, "처음으로" to { send(LabAction.Home) }))
            }
            LabScreen.Diagnostics -> {
                if (probeOpen) {
                    renderProbe()
                } else {
                    heading("관찰 진단 · 1주차")
                    val restriction = when (safety.restrictionsActive) { true -> "활성"; false -> "비활성"; null -> "미수신" }
                    val speed = safety.speedMps?.let { String.format(Locale.KOREA, "%.3f m/s (%.2f km/h)", it, it * 3.6f) } ?: "미수신"
                    paragraph("Car 서비스: ${if (safety.carConnected) "연결됨" else "안 됨"} | $sdkStatus\n" +
                        "주행 제한: $restriction ($uxDetail)\n기어: ${safety.gear?.label ?: "미수신"} | 속도: $speed", size = 21f)
                    paragraph(observationBody(safety, observations), size = 22f)
                    paragraph("복기 NaviHelper 탐침: ${intent.getStringExtra("recapNaviStatus") ?: "미수신(복기 메인에서 진입해 주세요)"}", MUTED, 21f)
                    paragraph("주행 기록 시험: 정차 상태에서 시작한 뒤 화면이 가려져도 신호 수신이 이어지는지 확인합니다. 시험 후 중지하세요.", MUTED, 21f)
                    horizontalChoices(listOf("주행 기록 시험 시작" to { startDriveTrial() }, "중지" to { stopDriveTrial() }))
                    horizontalChoices(listOf("신호 탐침" to { openProbe() }, "기존 게임" to { send(LabAction.Home) }, "복기로 돌아가기" to { finish() }))
                }
            }
        }
        if (model.unlocked) {
            pendingScroll = null
            if (probeOpen && probeSignals.isNotEmpty()) probeScroll = null
            scroll.post { if (model.unlocked && model.screen == screen) scroll.restoreAnchor(anchor) }
        }
    }

    private fun startDriveTrial() {
        if (!foreground || !model.unlocked || model.screen != LabScreen.Diagnostics || probeOpen) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !recorderNotificationAsked) {
            recorderNotificationAsked = true
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), RECORDER_NOTIFICATION_PERMISSION)
            return
        }
        try {
            startForegroundService(Intent(this, DriveRecorderService::class.java).setAction(DriveRecorderService.ACTION_START))
            DriveTrialLog.write("activity.startRequested", "submitted", "ui")
            Toast.makeText(this, "기록 서비스 시작 요청. 실제 실행·수신은 알림과 ObserveLab 로그로 확인하세요.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            DriveTrialLog.write("activity.startError", error.javaClass.simpleName, "ui")
            Toast.makeText(this, "시작 요청 실패: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopDriveTrial() {
        if (!foreground || !model.unlocked) return
        try {
            startService(Intent(this, DriveRecorderService::class.java).setAction(DriveRecorderService.ACTION_STOP))
            DriveTrialLog.write("activity.stopRequested", "submitted", "ui")
            Toast.makeText(this, "P 상태의 기록 서비스 중지 요청", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            DriveTrialLog.write("activity.stopError", error.javaClass.simpleName, "ui")
        }
    }

    private fun openProbe() {
        if (!model.unlocked || model.screen != LabScreen.Diagnostics) return
        closeProbe(clearScroll = false)
        probeOpen = true
        probeStatus = "SDK 연결 중"
        if (probeScroll == null) scroll.scrollTo(0, 0)
        try {
            val observer = checkNotNull(observationClient) { "Vehicle client is not running" }
            val client = observer.createSignalProbe { rows ->
                if (probeOpen && model.unlocked && model.screen == LabScreen.Diagnostics) {
                    probeSignals = rows
                    // Capability + initial GET can arrive in a burst across many areas.
                    if (!probeRenderPosted) {
                        probeRenderPosted = true
                        scroll.postOnAnimation {
                            probeRenderPosted = false
                            if (probeOpen && model.unlocked && model.screen == LabScreen.Diagnostics) render(force = true)
                        }
                    }
                }
            }
            probeClient = client
            probeStatus = "탐침 활성 · 원시값 / GET과 notify는 별도 표시"
            client.start()
        } catch (error: Exception) {
            probeClient?.stop()
            probeClient = null
            probeStatus = "탐침 시작 오류: ${error.javaClass.simpleName}"
            EvidenceLog.error("probe.start.failed", error)
        }
        render(force = true)
    }

    private fun closeProbe(clearScroll: Boolean = true) {
        if (clearScroll) probeScroll = null
        probeOpen = false
        probeClient?.stop()
        probeClient = null
        probeSignals = emptyList()
        probeStatus = ""
    }

    private fun renderProbe() {
        heading("신호 탐침 · 3주차")
        paragraph(probeStatus, MUTED, 21f)
        paragraph("숫자는 SDK 원시값 그대로입니다. 단위 메타데이터가 없으면 단위를 붙이지 않습니다.\nSDK에 없음 ≠ 차량 미지원 ≠ 콜백 미수신. Debug 탐침 원시값은 ObserveLab 로그에도 남습니다.", MUTED, 20f)
        horizontalChoices(listOf("현재값 다시 읽기" to { probeClient?.readCurrent() },
            "탐침 다시 시작" to { openProbe() }, "관찰 진단으로" to {
                closeProbe()
                scroll.scrollTo(0, 0)
                render(force = true)
            }))
        probeSignals.forEach { signal ->
            gap(14)
            paragraph(signal.title, ACCENT, 28f)
            paragraph("타입: ${signal.rawType}\ncap: ${if (signal.capMissing) "SDK에 없음" else probeSampleText(signal.capability)}", size = 21f)
            if (signal.notifyMissing) paragraph("notify: SDK에 없음 · 현재값 다시 읽기로 확인", MUTED, 21f)
            signal.areas.forEach { area ->
                paragraph("${area.area}\nget: ${probeSampleText(area.get)}\n조회: ${area.getStatus}\n" +
                    if (signal.notifyMissing) "notify: SDK에 없음" else
                        "notify: ${probeSampleText(area.notify)}\n구독: ${area.subscription}", size = 21f)
            }
        }
    }

    private fun heading(value: String, color: Int = FOREGROUND) {
        content.addView(VehicleParagraph(this, value, 28f, color, true).apply { tag = "heading:$value" }, blockParams())
    }

    private fun paragraph(value: String, color: Int = FOREGROUND, size: Float = 26f) {
        content.addView(VehicleParagraph(this, value, size, color).apply { tag = "text:" + value.take(40) }, blockParams())
    }

    private fun notice(value: String?) {
        if (value != null) content.addView(VehicleParagraph(this, value, 24f, ACCENT).apply {
            background = surface(colors.surface)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }, blockParams())
    }

    private fun targets(snapshot: VehicleSnapshot) {
        val usable = Seat.values().filter { it in snapshot.seats }.joinToString(", ") { it.label }
        paragraph("살펴볼 자리: $usable", MUTED, 22f)
        if (snapshot.seats.size < 4) paragraph("미수신·미지원 자리는 이번 문제에서 제외했어요.", ACCENT, 21f)
    }

    private fun homeButton() {
        gap(12)
        choice("처음으로") { send(LabAction.Home) }
    }

    private fun seatGrid(available: Set<Seat>, answer: (Seat) -> Unit) {
        paragraph("차량 앞쪽 ↑", MUTED, 20f)
        Seat.values().toList().chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isSaveEnabled = false }
            pair.forEach { seat ->
                row.addView(button(if (seat in available) seat.label else "${seat.label}\n미수신·미지원", enabled = seat in available) { answer(seat) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
            }
            content.addView(row, blockParams())
        }
    }

    private fun horizontalChoices(choices: List<Pair<String, () -> Unit>>) {
        val row = LinearLayout(this).apply { orientation = if (layoutSpec.verticalButtons) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; isSaveEnabled = false }
        choices.forEach { (label, action) ->
            row.addView(button(label, onClick = action), if (layoutSpec.verticalButtons) blockParams() else LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        content.addView(row, blockParams())
    }

    private fun choice(label: String, accent: Boolean = false, action: () -> Unit) {
        content.addView(button(label, accent = accent, onClick = action), blockParams())
    }

    private fun button(label: String, enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        pleosText(30f, FOREGROUND, 700)
        isAllCaps = false
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        minHeight = dp(88)
        setPadding(dp(24), dp(20), dp(24), dp(20))
        setTextColor(if (enabled) FOREGROUND else MUTED)
        background = surface(colors.button)
        if (accent) setTextColor(ACCENT)
        isEnabled = enabled
        setOnClickListener { onClick() }; installPleosPress()
    }

    private fun text(value: String, size: Float, color: Int = FOREGROUND, bold: Boolean = false) = TextView(this).apply {
        text = value
        pleosText(size, color, if (bold) 700 else 400)
        setTextColor(color)


        isSaveEnabled = false
        isSaveFromParentEnabled = false
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isSaveEnabled = false
        isSaveFromParentEnabled = false
    }

    private fun surface(color: Int): GradientDrawable {
        val borderColor = colors.border
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), borderColor)
        }
    }

    private fun gap(height: Int) { content.addView(View(this), LinearLayout.LayoutParams(dp(1), dp(height))) }
    private fun blockParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    companion object {
        private const val SPEED_PERMISSION = 10
        private const val RECORDER_NOTIFICATION_PERMISSION = 11
    }
}
