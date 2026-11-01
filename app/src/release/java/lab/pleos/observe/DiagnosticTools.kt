package lab.pleos.observe

import android.content.Context
import android.content.Intent

/** Release builds contain no signal probes or observation game; the debug source set provides them. */
object DiagnosticTools {
    class Entry(val label: String, val intent: (Context, String?) -> Intent)

    val entries: List<Entry> = emptyList()
}
