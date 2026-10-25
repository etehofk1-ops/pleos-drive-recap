package lab.pleos.observe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import lab.pleos.observe.logic.Observation

/** Typed public SDK only. Full signatures and URLs: docs/API_EVIDENCE.md.
 * https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object
 * https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/entry
 * https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/entry
 */
class PleosObservationClient(
    context: Context,
    private val onState: (String, List<Observation>) -> Unit,
) {
    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var running = false
    private var status = "초기화 전"
    private val rows = linkedMapOf<String, Observation>()
    private val revisions = mutableMapOf<String, Int>()
    private val subscriptionErrors = mutableMapOf<String, String>()
    private var refreshSequence = 0
    private var cancelRefresh: (() -> Unit)? = null
    private val windowListeners = mutableListOf<Pair<WindowArea, WindowPositionListener>>()
    private val lightListeners = mutableListOf<Pair<SeatArea, LightStateListener>>()
    private val windows = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT,
        WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)
    private val seats = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT,
        SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)

    fun start() {
        if (running) return
        running = true
        val session = ++generation
        rows.clear()
        revisions.clear()
        subscriptionErrors.clear()
        windows.forEach { rows["window.$it"] = Observation("창문 ${seatLabel(it.name)} ($it)", id = "window.$it") }
        seats.forEach { rows["light.$it"] = Observation("독서등 ${seatLabel(it.name)} ($it)", id = "light.$it") }
        status = "초기화 호출 중"
        publish()
        try {
            vehicle.initialize()
            status = "초기화 호출됨 / 응답 대기"
            publish()
            checkWindows(session)
            checkLights(session)
        } catch (e: Exception) {
            EvidenceLog.error("pleos.initialize.failed", e)
            status = "초기화 오류: ${e.javaClass.simpleName}"
            publish()
        }
    }

    // checkWindowPositionCapabilities((CapabilityConfig, Map<WindowArea, AreaBounds<Int>>) -> Unit, (Exception) -> Unit)
    // https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/check-window-position-capabilities
    private fun checkWindows(session: Int) {
        try {
            vehicle.getWindow().checkWindowPositionCapabilities(
                onComplete = { config, areas ->
                    val at = EvidenceLog.now()
                    EvidenceLog.event("window.capability", "access=${config.accessType} areas=${areas.entries.joinToString { (area, bounds) -> "${area.values.joinToString()}:${bounds.minValue}..${bounds.maxValue}" }}", at)
                    dispatch(session) {
                        status = "Vehicle SDK 응답 확인"
                        windows.forEach { value ->
                            val id = "window.$value"
                            val bounds = areas.entries.firstOrNull { it.key.values.contains(value) }?.value
                            val readable = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
                            val supported = readable && bounds != null
                            rows[id] = rows.getValue(id).copy(supported = supported,
                                capability = if (supported) "원시 범위 ${bounds?.minValue}..${bounds?.maxValue}, ${config.accessType}" else "조회 지원 영역 아님")
                            if (supported) subscribeWindow(session, id, WindowArea(value))
                        }
                        publish()
                    }
                },
                onFailed = { e -> capabilityFailure(session, "window", e) },
            )
        } catch (e: Exception) { capabilityFailure(session, "window", e) }
    }

    // getCurrentWindowPosition(area, (Int?) -> Unit, (Exception) -> Unit)
    // registerWindowPosition(area, WindowPositionListener), unregister has the SAME area/listener.
    // https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/register-window-position
    private fun subscribeWindow(session: Int, id: String, area: WindowArea) {
        val window = vehicle.getWindow()
        val listener = object : WindowPositionListener {
            override fun onWindowPositionUpdated(position: Int?) =
                reading(session, id, "notify", position?.toString(), live = true)
            override fun onFailed(e: Exception) = readingFailure(session, id, "notify", e, live = true)
        }
        // Preserve the exact instance for unregister, even if registration partially fails.
        windowListeners += area to listener
        try { window.registerWindowPosition(area, listener) }
        catch (e: Exception) { readingFailure(session, id, "register", e) }
        val before = revisions[id] ?: 0
        try {
            window.getCurrentWindowPosition(area,
                onComplete = { value -> reading(session, id, "get", value?.toString(), expectedRevision = before) },
                onFailed = { e -> readingFailure(session, id, "get", e, expectedRevision = before) })
        } catch (e: Exception) { readingFailure(session, id, "get", e, expectedRevision = before) }
    }

    // checkReadingLightStateCapabilities((CapabilityConfig, Map<SeatArea, List<LightState>>) -> Unit, ...)
    // https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-reading-light-state-capabilities
    private fun checkLights(session: Int) {
        try {
            vehicle.getLight().checkReadingLightStateCapabilities(
                onComplete = { config, areas ->
                    val at = EvidenceLog.now()
                    EvidenceLog.event("light.capability", "access=${config.accessType} areas=${areas.entries.joinToString { (area, states) -> "${area.values.joinToString()}:$states" }}", at)
                    dispatch(session) {
                        status = "Vehicle SDK 응답 확인"
                        seats.forEach { value ->
                            val id = "light.$value"
                            val states = areas.entries.firstOrNull { it.key.values.contains(value) }?.value
                            val readable = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
                            val supported = readable && !states.isNullOrEmpty()
                            rows[id] = rows.getValue(id).copy(supported = supported,
                                capability = if (supported) "$states, ${config.accessType}" else "조회 지원 영역 아님")
                            if (supported) subscribeLight(session, id, SeatArea(value))
                        }
                        publish()
                    }
                },
                onFailed = { e -> capabilityFailure(session, "light", e) },
            )
        } catch (e: Exception) { capabilityFailure(session, "light", e) }
    }

    // getCurrentReadingLightState(area, (LightState?) -> Unit, (Exception) -> Unit)
    // LightStateListener.onLightStateUpdated(LightState?), inherited onFailed(Exception).
    // https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/register-reading-light-state
    // https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.listener/light-state-listener/entry
    private fun subscribeLight(session: Int, id: String, area: SeatArea) {
        val light = vehicle.getLight()
        val listener = object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) =
                reading(session, id, "notify", state?.name, live = true)
            override fun onFailed(e: Exception) = readingFailure(session, id, "notify", e, live = true)
        }
        lightListeners += area to listener
        try { light.registerReadingLightState(area, listener) }
        catch (e: Exception) { readingFailure(session, id, "register", e) }
        val before = revisions[id] ?: 0
        try {
            light.getCurrentReadingLightState(area,
                onComplete = { value -> reading(session, id, "get", value?.name, expectedRevision = before) },
                onFailed = { e -> readingFailure(session, id, "get", e, expectedRevision = before) })
        } catch (e: Exception) { readingFailure(session, id, "get", e, expectedRevision = before) }
    }

    private fun reading(session: Int, id: String, source: String, value: String?,
        live: Boolean = false, expectedRevision: Int? = null) {
        val at = EvidenceLog.now()
        EvidenceLog.event("${id.substringBefore('.')}.$source", "signal=$id value=${value ?: "null"} session=$session", at)
        dispatch(session) {
            if (live) revisions[id] = (revisions[id] ?: 0) + 1
            // A delayed initial GET must not overwrite a newer notification.
            if (expectedRevision != null && expectedRevision != (revisions[id] ?: 0)) return@dispatch
            rows[id] = rows.getValue(id).copy(value = value, receivedAt = at, error = subscriptionErrors[id])
            publish()
        }
    }

    private fun readingFailure(session: Int, id: String, source: String, e: Exception,
        live: Boolean = false, expectedRevision: Int? = null) {
        val at = EvidenceLog.now()
        EvidenceLog.error("${id.substringBefore('.')}.$source.failed", e, "signal=$id value=null session=$session", at)
        dispatch(session) {
            if (source == "register") subscriptionErrors[id] = e.javaClass.simpleName
            if (live) revisions[id] = (revisions[id] ?: 0) + 1
            if (expectedRevision != null && expectedRevision != (revisions[id] ?: 0)) return@dispatch
            rows[id] = rows.getValue(id).copy(value = null, receivedAt = at, error = e.javaClass.simpleName)
            publish()
        }
    }

    private fun capabilityFailure(session: Int, domain: String, e: Exception) {
        EvidenceLog.error("$domain.capability.failed", e)
        dispatch(session) {
            status = "SDK 오류 있음 / 로그 확인"
            rows.keys.filter { it.startsWith("$domain.") }.forEach { id ->
                rows[id] = rows.getValue(id).copy(error = e.javaClass.simpleName)
            }
            publish()
        }
    }

    private fun dispatch(session: Int, action: () -> Unit) {
        main.post { if (running && generation == session) action() }
    }

    private fun publish() = onState(status, rows.values.toList())

    /** Borrow the existing SDK session; probe close must not release the game's Vehicle. */
    fun createSignalProbe(onState: (List<lab.pleos.observe.logic.ProbeSignalState>) -> Unit): SignalProbeClient {
        check(running) { "Vehicle session is not running" }
        return SignalProbeClient(vehicle, onState)
    }

    /** A fresh GET batch for memory/compare. No cached row is passed off as a fresh read.
     * Notifications newer than a GET take precedence, using the same revisions as initial GET.
     * An error or 4-second timeout rejects the whole batch; callers retain no new baseline.
     */
    fun readFresh(onComplete: (Boolean, List<Observation>) -> Unit) {
        if (!running) { onComplete(false, emptyList()); return }
        cancelRefresh?.invoke()
        val session = generation
        val request = ++refreshSequence
        val pending = rows.filterValues { it.supported == true }.keys.toMutableSet()
        if (pending.isEmpty()) { onComplete(false, rows.values.toList()); return }
        var failed = false
        var completed = false
        lateinit var timeout: Runnable
        fun valid() = running && generation == session && refreshSequence == request && !completed
        fun finish(success: Boolean) {
            if (!valid()) return
            completed = true
            ++refreshSequence
            main.removeCallbacks(timeout)
            cancelRefresh = null
            onComplete(success, rows.values.toList())
        }
        timeout = Runnable {
            if (valid()) {
                pending.forEach { id -> rows[id] = rows.getValue(id).copy(value = null,
                    receivedAt = EvidenceLog.now(), error = "RefreshTimeout")
                    revisions[id] = (revisions[id] ?: 0) + 1
                }
                publish()
                EvidenceLog.event("observation.refresh.timeout")
                finish(false)
            }
        }
        cancelRefresh = { main.removeCallbacks(timeout); finish(false) }
        main.postDelayed(timeout, 4_000)
        fun received(id: String, before: Int, value: String?, error: Exception?) {
            val at = EvidenceLog.now()
            val event = "${id.substringBefore('.')}.refresh"
            if (error == null) EvidenceLog.event(event, "signal=$id value=$value", at)
            else EvidenceLog.error("$event.failed", error, "signal=$id", at)
            main.post {
                if (!valid() || !pending.remove(id)) return@post
                failed = failed || error != null
                if ((revisions[id] ?: 0) == before) {
                    // Also invalidate a delayed initial GET that began before this fresh batch.
                    revisions[id] = before + 1
                    rows[id] = rows.getValue(id).copy(value = value, receivedAt = at,
                        error = error?.javaClass?.simpleName ?: subscriptionErrors[id])
                    publish()
                }
                if (pending.isEmpty()) finish(!failed)
            }
        }
        windows.forEach { value ->
            val id = "window.$value"
            if (id in pending) {
                val before = revisions[id] ?: 0
                try {
                    vehicle.getWindow().getCurrentWindowPosition(WindowArea(value),
                        onComplete = { received(id, before, it?.toString(), null) },
                        onFailed = { received(id, before, null, it) })
                } catch (e: Exception) { received(id, before, null, e) }
            }
        }
        seats.forEach { value ->
            val id = "light.$value"
            if (id in pending) {
                val before = revisions[id] ?: 0
                try {
                    vehicle.getLight().getCurrentReadingLightState(SeatArea(value),
                        onComplete = { received(id, before, it?.name, null) },
                        onFailed = { received(id, before, null, it) })
                } catch (e: Exception) { received(id, before, null, e) }
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        ++generation
        cancelRefresh?.invoke()
        cancelRefresh = null
        windowListeners.forEach { (area, listener) ->
            try { vehicle.getWindow().unregisterWindowPosition(area, listener) }
            catch (e: Exception) { EvidenceLog.error("window.unregister.failed", e) }
        }
        lightListeners.forEach { (area, listener) ->
            try { vehicle.getLight().unregisterReadingLightState(area, listener) }
            catch (e: Exception) { EvidenceLog.error("light.unregister.failed", e) }
        }
        windowListeners.clear()
        lightListeners.clear()
        // Late capability/get callbacks are ignored by session generation above.
        try { vehicle.release() } catch (e: Exception) { EvidenceLog.error("pleos.release.failed", e) }
        rows.clear()
        EvidenceLog.event("pleos.stopped", "cleanupRequested=true")
    }

    private fun seatLabel(name: String): String = when (name) {
        "ROW_1_LEFT" -> "1열 왼쪽"
        "ROW_1_RIGHT" -> "1열 오른쪽"
        "ROW_2_LEFT" -> "2열 왼쪽"
        "ROW_2_RIGHT" -> "2열 오른쪽"
        else -> name
    }
}
