package lab.pleos.observe

import lab.pleos.observe.logic.recap.NavigationOrigin

/** Recorder owns one source for its entire session; no mixing during a maneuver. */
interface NavigationSource {
    val origin: NavigationOrigin
    fun start()
    fun stop()
}
