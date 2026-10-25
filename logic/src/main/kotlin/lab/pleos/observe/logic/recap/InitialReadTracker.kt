package lab.pleos.observe.logic.recap

/** A GET may complete after a newer notify. Never let that old snapshot overwrite it.
 * Used on a single serialized dispatcher. A new recording owns a new tracker.
 */
class InitialReadTracker {
    private var notificationVersion = 0L
    var known = false
        private set
    fun begin(): Long = notificationVersion
    fun notified(valueKnown: Boolean) { notificationVersion++; known = valueKnown }
    fun complete(ticket: Long, valueKnown: Boolean): Boolean {
        if (ticket != notificationVersion) return false
        if (valueKnown) known = true
        return valueKnown
    }
}
