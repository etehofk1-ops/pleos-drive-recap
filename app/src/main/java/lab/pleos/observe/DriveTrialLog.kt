package lab.pleos.observe

import android.os.Process
import android.os.SystemClock
import android.util.Log
import lab.pleos.observe.logic.driveTrialMessage
import lab.pleos.observe.logic.shouldLogDriveEvent

/** Debug trial evidence, separate from unchanged game/probe logging. No files or personal data. */
object DriveTrialLog {
    fun write(signal: String, value: Any?, source: String = "lifecycle", details: String = "",
        at: Long = SystemClock.elapsedRealtime()) {
        if (!shouldLogDriveEvent(signal, BuildConfig.DEBUG)) return
        val raw = if (value is Enum<*>) value.name else value?.toString() ?: "null"
        Log.i("ObserveLab", driveTrialMessage(signal, raw, at, BuildConfig.DEBUG,
            "source=$source pid=${Process.myPid()} $details".trimEnd()))
    }
}
