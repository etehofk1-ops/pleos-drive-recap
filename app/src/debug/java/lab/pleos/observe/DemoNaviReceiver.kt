package lab.pleos.observe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64

/** Explicit component addressing is required by the feeder. No exported release receiver. */
class DemoNaviReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accepted = runCatching {
            if (!BuildConfig.DEBUG || intent.action != "lab.pleos.observe.DEMO_NAVI") return@runCatching false
            val raw = intent.getStringExtra("json")
            val encoded = intent.getStringExtra("payloadB64")
            if ((raw == null) == (encoded == null)) return@runCatching false
            if ((raw?.length ?: encoded!!.length) > 2048) return@runCatching false
            val json = raw ?: String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
            DriveRecorderService.live?.acceptDemoNavigation(json) == true
        }.getOrDefault(false)
        resultCode = if (accepted) 0 else 1
        resultData = if (accepted) "demo_navi_accepted" else "demo_navi_rejected"
    }
}
