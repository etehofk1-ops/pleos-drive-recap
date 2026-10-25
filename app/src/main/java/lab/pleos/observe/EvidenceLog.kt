package lab.pleos.observe

import android.util.Log
import lab.pleos.observe.logic.eventMessage
import lab.pleos.observe.logic.probeMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object EvidenceLog {
    private val format = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX")
        .withZone(ZoneId.systemDefault())

    fun now(): String = format.format(Instant.now())

    fun event(name: String, fields: String = "", receivedAt: String = now()) {
        Log.i("ObserveLab", eventMessage(name, debug = BuildConfig.DEBUG, details = "receivedAt=$receivedAt $fields"))
    }

    fun answer(name: String, correct: Boolean) {
        Log.i("ObserveLab", eventMessage(name, correct, debug = BuildConfig.DEBUG))
    }

    // Probe screen only: raw diagnostic evidence follows the existing Debug-only rule.
    fun probe(signal: String, phase: String, value: String, area: String, at: String, status: String) {
        Log.i("ObserveLab", probeMessage(signal, phase, value, area, at, status, debug = BuildConfig.DEBUG))
    }

    fun error(name: String, error: Exception, fields: String = "", receivedAt: String = now()) {
        if (BuildConfig.DEBUG) {
            Log.e("ObserveLab", eventMessage(name, debug = true,
                details = "receivedAt=$receivedAt $fields error=${error.javaClass.simpleName}"), error)
        } else {
            // No exception message, stack, timestamp, identifiers or vehicle values in release.
            Log.e("ObserveLab", eventMessage(name, debug = false))
        }
    }
}
