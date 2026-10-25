package lab.pleos.observe

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import lab.pleos.observe.logic.recap.*

/** No listener/receiver registration here. Only src/debug's manifest receiver can feed it.
 * Input/sequence/clock are memory-only. This source never renews an old snapshot itself.
 */
class DemoNaviSource(private val status: (String) -> Unit, private val hint: (NavigationSnapshot) -> Unit) : NavigationSource {
    override val origin = NavigationOrigin.DEMO
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var state = DemoNavigationState()
    private val expiry = Runnable {
        if (running) {
            val now = SystemClock.elapsedRealtime()
            val next = expireDemoNavigation(state, now)
            if (next != state) {
                state = next
                hint(NavigationSnapshot(now, null, null, null, epoch = state.epoch, origin = origin))
                status("모의 내비 미수신/만료 · 기존 추정 유지")
                DriveTrialLog.write("navi.demo.expired", "expired", "demo")
            }
        }
    }
    override fun start() {
        check(BuildConfig.DEBUG)
        running = true; state = DemoNavigationState()
        status("시연용 모의 내비 입력 사용 중 · 미수신")
    }
    fun accept(json: String, allowNewRun: Boolean): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!BuildConfig.DEBUG || !running) return false
        val frame = parseDemoNavigation(json) ?: return false
        val now = SystemClock.elapsedRealtime()
        val fresh = expireDemoNavigation(state, now)
        val next = acceptDemoNavigation(fresh, frame, now, allowNewRun) ?: return false
        state = next
        hint(checkNotNull(demoNavigationSnapshot(state, now)))
        main.removeCallbacks(expiry); main.postDelayed(expiry, NavigationThresholds.FRESH_MS + 1)
        status("시연용 모의 내비 입력 사용 중 · ${if (frame.guiding) frame.action else "안내 종료"}")
        DriveTrialLog.write("navi.demo.received", frame.action ?: "inactive", "demo", "sequence=${frame.sequence}", now)
        return true
    }
    override fun stop() {
        running = false; main.removeCallbacks(expiry); state = DemoNavigationState()
    }
}
