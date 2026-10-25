package lab.pleos.observe

import android.content.Context
import android.car.VehicleGear
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import lab.pleos.observe.logic.recap.*
import java.time.Instant
import java.time.ZoneId

/** Main-thread adapter; only reduced summaries reach the repository. */
class RecapRecordingSession(context: Context, demo: Boolean, private val parked: () -> Unit,
    private val gearChanged: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val repo = RecapRepository.get(context)
    private val policy = RecapPolicy(demoMissingSpeed = demo)
    private var state = RecapEngineState()
    private var driving = DrivingSignals()
    private var lastDecision: DrivingDecision? = null
    private val drivingTimes = mutableMapOf<String, Long>()
    private val notified = mutableSetOf<String>()
    private val failedSubscriptions = mutableSetOf<String>()
    private val initialGetUnavailable = mutableSetOf<String>()
    private var day = repo.today()
    private var running = false
    private var awaitingPark = false
    private var lastFlush = 0L
    private var navigation = NavigationFusionState()
    private val usingDemoNavi = BuildConfig.DEBUG && repo.demoNaviEnabled
    private val navi: NavigationSource = if (usingDemoNavi) DemoNaviSource(::onNaviStatus, ::onNavigation)
        else PleosNaviSource(context, ::onNaviStatus, ::onNavigation)
    private fun onNaviStatus(status: String) { main.post { if (running) repo.navStatus = status } }
    private fun onNavigation(snapshot: NavigationSnapshot) { main.post { if (running) {
            val step = navigationFusionStep(navigation, SystemClock.elapsedRealtime(), snapshot = snapshot)
            navigation = step.state
            publish(RecapStep(state, step.cards))
    } } }
    fun acceptDemoNavigation(json: String): Boolean = running && usingDemoNavi && repo.demoNaviEnabled &&
        (navi as? DemoNaviSource)?.accept(json, gear == DriveGear.PARK) == true
    private fun markSource(cards: List<ReviewCard>) = cards.map {
        if (usingDemoNavi && it.summary.firstSign != 0) it.copy(demoNavigation = true) else it
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val at = SystemClock.elapsedRealtime()
            refreshDriving(at)
            accept(DriveInput.Tick(at, wall(at)))
            if (at - lastFlush >= 5_000) { repo.flush(); lastFlush = at }
            main.postDelayed(this, 500)
        }
    }
    val gear: DriveGear get() = drivingDecision(driving, SystemClock.elapsedRealtime()).gear

    fun start() {
        check(repo.canRecord()) { "Unreadable recap file" }
        running = true
        repo.recording = true
        repo.onDriveGear(DriveGear.UNKNOWN)
        repo.demoMissingSpeed = policy.demoMissingSpeed
        repo.seedStatus = "초기 신호 확인 중"
        navi.start()
        main.post(ticker)
    }

    fun raw(signal: String, value: Any?, source: String, at: Long) {
        val apply = apply@{
            if (!running) return@apply
            val base = signal.substringBefore('.')
            if (source == "seed") {
                if (signal.endsWith(".initialGet.ready")) initialGetUnavailable -= base
                if (signal.endsWith(".initialGet.unavailable")) initialGetUnavailable += base
                publishSeedStatus()
                return@apply
            }
            if (signal.endsWith(".error") && source in listOf("register", "setup")) failedSubscriptions += base
            if (source == "notify" && !signal.endsWith(".error")) failedSubscriptions -= base
            val seeded = base in listOf("steeringAngle", "turnSignal", "brakePedal")
            if (source == "get" && ((!seeded && base in notified) || base in failedSubscriptions)) return@apply
            if (source == "notify" && !signal.endsWith(".error") && value != null) notified += base
            val wall = wall(at)
            if (signal == "car.lifecycle" && value == false) {
                driving = DrivingSignals()
                drivingTimes.clear()
                listOf("gear", "gearSelection", "speed", "uxRequiresDo").forEach { notified.remove(it); failedSubscriptions.remove(it) }
                refreshDriving(at)
                accept(DriveInput.Speed(at, wall, null))
            }
            val failed = signal.endsWith(".error")
            if (source !in listOf("notify", "get") && !failed) return@apply
            val raw = if (failed) null else value
            if (base in listOf("gear", "gearSelection", "uxRequiresDo", "speed") || (failed && base in listOf("ux", "car"))) {
                // ON_CHANGE gear/UX are held until changed/error/disconnect; speed expires after 3s.
                if (at < (drivingTimes[base] ?: Long.MIN_VALUE)) return@apply
                drivingTimes[base] = at
                driving = when (base) {
                    "gear" -> driving.copy(currentGear = decodeGear(raw))
                    "gearSelection" -> driving.copy(selectedGear = decodeGear(raw))
                    "uxRequiresDo", "ux" -> driving.copy(requiresDo = raw as? Boolean)
                    "speed" -> driving.copy(speedMps = (raw as? Float)?.takeIf { it.isFinite() }, speedAtMs = at)
                    else -> DrivingSignals()
                }
                // For the final speed->0 callback, let the engine see the deceleration while
                // still DRIVE, then close the episode. For motion onset do the reverse.
                if (base != "speed" || state.gear != DriveGear.DRIVE || drivingDecision(driving, at).gear == DriveGear.DRIVE)
                    refreshDriving(at)
            }
            val input = when (signal.substringBefore('.')) {
                "steeringAngle" -> DriveInput.Steering(at, wall, raw as? Float)
                "turnSignal" -> DriveInput.Signal(at, wall, when ((raw as? Enum<*>)?.name) {
                    "OFF" -> Indicator.OFF; "LEFT" -> Indicator.LEFT; "RIGHT" -> Indicator.RIGHT; else -> null
                })
                "brakePedal" -> DriveInput.Brake(at, wall, raw as? Boolean)
                "speed" -> DriveInput.Speed(at, wall, raw as? Float)
                else -> null
            }
            if (input != null) accept(input)
            if (base == "speed") refreshDriving(at)
            publishSeedStatus()
        }
        // Most Car callbacks already run on main; a second post let a newer timer overtake them.
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else main.post { apply() }
    }

    private fun publishSeedStatus() {
        repo.seedStatus = recorderReadiness(state.indicator, state.brake, state.angle, initialGetUnavailable)
    }

    private fun decodeGear(raw: Any?): DriveGear = when (raw as? Int) {
        null, VehicleGear.GEAR_UNKNOWN -> DriveGear.UNKNOWN
        VehicleGear.GEAR_PARK -> DriveGear.PARK
        VehicleGear.GEAR_DRIVE -> DriveGear.DRIVE
        else -> DriveGear.OTHER
    }

    private fun refreshDriving(receivedAt: Long) {
        val at = maxOf(receivedAt, state.lastMs ?: receivedAt)
        val decision = drivingDecision(driving, at)
        if (decision != lastDecision) {
            DriveTrialLog.write("recap.motion.${decision.sources.joinToString(".") { it.name.lowercase() }}", decision.gear, "rule",
                "sources=${decision.sources.joinToString(",")} selected=${driving.selectedGear} current=${driving.currentGear} " +
                    "requiresDO=${driving.requiresDo} speedMps=${driving.speedMps} speedKph=${driving.speedMps?.let(::mpsToKph)}", at)
            lastDecision = decision
        }
        if (decision.gear != state.gear) accept(DriveInput.Gear(at, wall(at), decision.gear))
    }

    private fun accept(input: DriveInput) {
        val date = Instant.ofEpochMilli(input.wallMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if (date != day) {
            val finish = recapStep(state, DriveInput.Finish(input.atMs, input.wallMs), policy)
            val fused = navigationFusionStep(navigation, input.atMs, markSource(finish.cards), finish = true)
            navigation = fused.state
            if (retainedDate(day, repo.today())) repo.append(day, finish.copy(cards = fused.cards))
            repo.flush()
            state = finish.state.copy(active = null, missingBrakeStart = null)
            day = date
        }
        if (!retainedDate(day, repo.today())) return
        val priorGear = state.gear
        val priorActive = state.active
        val rawStep = recapStep(state, input, policy)
        val fused = navigationFusionStep(navigation, input.atMs, markSource(rawStep.cards), finish = input is DriveInput.Finish)
        navigation = fused.state
        val step = rawStep.copy(cards = fused.cards)
        state = step.state
        publish(step)
        if (priorActive == null && state.active != null) DriveTrialLog.write("recap.segment.start", state.active!!.summary.startMs, "rule",
            "wallStartMs=${state.active!!.summary.wallStartMs} signalKnownSince=${state.indicatorKnownSince}", input.atMs)
        if (state.gear != priorGear) { repo.onDriveGear(state.gear); gearChanged() }
        if (state.gear == DriveGear.DRIVE) awaitingPark = true
        if (state.gear == DriveGear.PARK && awaitingPark) {
            awaitingPark = false; repo.flush(); repo.prepareSummary(); parked()
        }
    }

    private fun publish(step: RecapStep) {
        if (!retainedDate(day, repo.today())) return
        repo.append(day, step)
        for (card in step.cards) DriveTrialLog.write("recap.card", card.kind, "rule",
            "id=${card.id} wallStartMs=${card.wallTimeMs} startMs=${card.summary.startMs} endMs=${card.summary.endMs} " +
                "signalTiming=${card.summary.timing} lateMs=${card.summary.lateSignalMs} flags=${card.uncertainty} " +
                "navigation=${card.navigation} turn=${card.navigationTurn}")
    }

    fun clearRecords() {
        check(gear == DriveGear.PARK) { "Park required" }
        repo.deleteRecords()
        navigation = NavigationFusionState()
        if (usingDemoNavi) { navi.stop(); navi.start() } // Forget the input lease along with deleted evidence.
        awaitingPark = false
        val now = SystemClock.elapsedRealtime()
        state = state.copy(lastMs = now, active = null, missingBrakeStart = null, lastHardBrakeMs = null,
            brakeWindow = null, recentDecelerations = emptyList(), decelerationAnchor = null, inputTimes = emptyMap(),
            lateralFilter = LateralFilter(), lastSpeedDropMs = null, delayedLane = DelayedLaneState(),
            indicatorKnownSince = if (state.indicator == null) null else now,
            left = if (state.indicator == Indicator.LEFT) IndicatorWindow(now) else IndicatorWindow(),
            right = if (state.indicator == Indicator.RIGHT) IndicatorWindow(now) else IndicatorWindow(), hint = null)
        day = repo.today()
    }

    fun stop() {
        if (!running) return
        main.removeCallbacks(ticker)
        val at = SystemClock.elapsedRealtime()
        accept(DriveInput.Finish(at, wall(at)))
        running = false
        navi.stop()
        repo.recording = false
        repo.flush()
    }
    private fun wall(at: Long) = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - at)
}
