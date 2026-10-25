package lab.pleos.observe.logic

/** Monotonic time is supplied by Android at callback entry; no history is retained here. */
fun driveTrialMessage(signal: String, value: String, elapsedMs: Long, debug: Boolean, details: String = ""): String {
    if (!debug) return "event=drive.$signal"
    return "event=drive.$signal value=$value t=$elapsedMs" + if (details.isEmpty()) "" else " $details"
}
