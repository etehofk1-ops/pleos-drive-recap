package lab.pleos.observe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.*
import ai.pleos.playground.navi.data.*
import lab.pleos.observe.logic.recap.NavigationSnapshot
import lab.pleos.observe.logic.recap.NavigationCue
import lab.pleos.observe.logic.recap.NavigationOrigin

/** javap: NaviHelper 3.0.5 + navi-data 3.0.4; docs/WEEK9_NAVI_FUSION.md.
 * https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners
 * Never query location/destination, stringify SDK payloads, retain description/route objects,
 * or manufacture TBT passage from type changes. The SDK has no TBT passage callback.
 */
class PleosNaviSource(private val context: Context, private val status: (String) -> Unit,
    private val hint: (NavigationSnapshot) -> Unit) : NavigationSource {
    override val origin = NavigationOrigin.PLEOS
    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var listener: NaviHelperEventListener? = null
    private var running = false
    private var connected = false
    private var generation = 0L
    private var routeEpoch = 0L
    private var guiding: Boolean? = null
    private var guidanceAt = 0L
    private var lastReply = 0L
    private var began = 0L
    private var retryMs = 2_000L
    private val reconnect = Runnable { if (running) connect() }
    private fun now() = SystemClock.elapsedRealtime()
    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            if (connected && now() - maxOf(began, lastReply) > 15_000) { failed(); main.postDelayed(this, 1_000); return }
            if (connected) runCatching { helper?.getRouteStateInfo(); helper?.getTBTInfo() }.onFailure { failed() }
            if (running) main.postDelayed(this, 1_000)
        }
    }
    override fun start() {
        if (running) return
        running = true; connect(); main.post(poll)
    }
    private fun connect() {
        dispose()
        val ticket = ++generation
        began = now(); lastReply = 0; guidanceAt = 0; guiding = null; ++routeEpoch
        status("내비 연결 중 · TBT 미수신")
        try {
            val client = NaviHelper(context.applicationContext)
            helper = client
            val events = object : NaviHelperEventListener {
                private fun dispatch(action: () -> Unit) { main.post {
                    if (running && ticket == generation && helper === client) { lastReply = now(); runCatching(action).onFailure { failed() } }
                } }
                override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
                    // Copy the enum only; do not retain RouteStateInfo (contains traffic/route data).
                    val mode = routeStateInfo.naviMode
                    dispatch { guidance(mode == NaviMode.ROUTE_GUIDING || mode == NaviMode.SIMULATION_GUIDING, mode.name) }
                }
                override fun onNavigationStatus(status: NavigationStatus) = dispatch {
                    guidance(when (status) {
                        NavigationStatus.ROUTE_DRIVE -> true
                        NavigationStatus.FREE_DRIVE, NavigationStatus.INACTIVE -> false
                        else -> null
                    }, status.name)
                }
                // onRouteStarted is route SEARCH started, not confirmed guidance.
                override fun onRouteStarted(routeStartInfo: RouteStartInfo) = dispatch { resetGuidance("경로 탐색 중") }
                override fun onRoutingCompleted() = dispatch { client.getRouteStateInfo(); client.getTBTInfo() }
                override fun onReRouteEnded() = dispatch { resetGuidance("재탐색 · 안내 확인 중"); client.getRouteStateInfo(); client.getTBTInfo() }
                override fun onDestinationChanged() = dispatch { resetGuidance("목적지 변경 · 안내 확인 중"); client.getRouteStateInfo(); client.getTBTInfo() }
                override fun onRouteOptionChanged(routeOption: RouteOption) = dispatch { resetGuidance("경로 옵션 변경 · 안내 확인 중"); client.getRouteStateInfo(); client.getTBTInfo() }
                override fun onRouteCancelled() = dispatch { resetGuidance("안내 취소", false) }
                override fun onRouteEnded() = dispatch { resetGuidance("안내 종료", false) }
                override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = dispatch {
                    // Destination arrival does not mean passage of a turn instruction.
                    resetGuidance("목적지 도착", false)
                }
                override fun onError(errorCode: NaviErrorCode) = dispatch {
                    DriveTrialLog.write("navi.error", errorCode, "navi"); failed()
                }
                override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
                    // Reduce immediately. No SDK object is captured by the posted lambda.
                    val at = now()
                    val next = tbtInfoList.filter { it.distance.isFinite() && it.distance >= 0.0 }.minByOrNull { it.distance }
                    val type = next?.type?.name
                    val rawDistance = next?.distance
                    // Unknown/oversized/invalid lists block promotion instead of silently dropping a near turn.
                    val complete = tbtInfoList.size <= 32 && tbtInfoList.all { it.distance.isFinite() && it.distance >= 0.0 }
                    val following = if (complete) tbtInfoList.filter { it !== next }.map {
                        NavigationCue(it.type.name, if (BuildConfig.NAVI_DISTANCE_METERS) it.distance else null)
                    } else emptyList()
                    main.post {
                        if (!running || ticket != generation || helper !== client) return@post
                        lastReply = now()
                        val active = guiding.takeIf { at - guidanceAt in 0..3_000 }
                        val meters = if (BuildConfig.NAVI_DISTANCE_METERS) rawDistance else null
                        hint(NavigationSnapshot(at, active, if (complete) type else null, if (complete) meters else null,
                            epoch = routeEpoch, following = following))
                        status("내비 ${if (active == true) "안내 중" else if (active == false) "안내 없음" else "상태 미수신"} · " +
                            "${type ?: "TBT 미수신"} · 거리 ${rawDistance ?: "미수신"}" +
                            if (BuildConfig.NAVI_DISTANCE_METERS) " m" else " (단위 미확인 · 거리 판정 보류)")
                        DriveTrialLog.write("navi.tbt", type ?: "missing", "navi",
                            "distanceRaw=$rawDistance unitVerified=${BuildConfig.NAVI_DISTANCE_METERS}", at)
                    }
                }
            }
            listener = events
            client.initialize(onConnected = { main.post {
                if (running && ticket == generation && helper === client) {
                    try {
                        connected = true; retryMs = 2_000; began = now()
                        client.addListener(events); client.getRouteStateInfo(); client.getTBTInfo()
                        status("내비 연결됨 · TBT 미수신")
                    } catch (_: Exception) { failed() }
                }
            } }, onFailed = { main.post { if (running && ticket == generation) failed() } })
            main.postDelayed({ if (running && ticket == generation && !connected) failed() }, 20_000)
        } catch (_: Exception) { failed() }
    }
    private fun guidance(value: Boolean?, raw: String) {
        if (guiding != value) ++routeEpoch
        guiding = value; guidanceAt = now()
        if (value != true) hint(NavigationSnapshot(guidanceAt, value, null, null, epoch = routeEpoch))
        DriveTrialLog.write("navi.guidance", raw, "navi")
    }
    private fun resetGuidance(text: String, value: Boolean? = null) {
        ++routeEpoch; guiding = value; guidanceAt = now()
        hint(NavigationSnapshot(guidanceAt, value, null, null, epoch = routeEpoch)); status(text)
    }
    private fun failed() {
        ++generation; resetGuidance("내비 미수신 · 재연결 대기"); dispose()
        main.removeCallbacks(reconnect)
        if (running) { main.postDelayed(reconnect, retryMs); retryMs = (retryMs * 2).coerceAtMost(30_000) }
    }
    private fun dispose() {
        connected = false
        val old = helper; helper = null
        listener?.let { runCatching { old?.removeListener(it) } }; listener = null
        runCatching { old?.release() }
    }
    override fun stop() {
        running = false; ++generation; ++routeEpoch
        main.removeCallbacks(poll); main.removeCallbacks(reconnect)
        dispose(); guiding = null; guidanceAt = 0; lastReply = 0
    }
}
