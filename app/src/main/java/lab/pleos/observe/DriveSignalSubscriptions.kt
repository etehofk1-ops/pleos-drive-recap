package lab.pleos.observe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import lab.pleos.observe.logic.recap.InitialReadTracker
import java.util.concurrent.TimeoutException

/** Own Vehicle and Car connections; never borrows or stops the Activity's clients.
 * Typed signatures/permissions verified with javap on Vehicle 2.3.2, SHA-256
 * e11fdfe498cad3ecc0d5a2a6992b0c4a4d135195f1455018127bc069fd5ef937.
 * Full evidence: docs/WEEK4_DRIVE_RECORDER.md and scripts/inspect-drive-aar.ps1.
 * AAOS callbacks: https://developer.android.com/reference/android/car/hardware/property/CarPropertyManager
 */
class DriveSignalSubscriptions(
    private val context: Context,
    private val record: (signal: String, value: Any?, source: String, details: String, at: Long) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var generation = 0
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var carGeneration = 0
    private var properties: CarPropertyManager? = null
    private var propertyListener: CarPropertyManager.CarPropertyEventCallback? = null
    private var ux: CarUxRestrictionsManager? = null
    private val unregister = mutableListOf<() -> Unit>()
    private val seedToken = Any()
    private val initialReads = mutableMapOf<String, InitialReadTracker>()

    fun start() {
        if (running) return
        running = true
        val epoch = ++generation
        attempt("sdk", "initialize") {
            val sdk = Vehicle(context.applicationContext)
            vehicle = sdk
            sdk.initialize()
            emit(epoch, "sdk.initialize", "returned", "framework_return")
            // initialize() return is not evidence of connection or of a received signal.
            attempt("turnSignal", "setup") { turnSignal(sdk, epoch) }
            attempt("steeringAngle", "setup") { steering(sdk, epoch) }
            attempt("brakePedal", "setup") { brake(sdk, epoch) }
        }
        attempt("car", "connect") {
            car = Car.createCar(context.applicationContext, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (!running || generation != epoch) return@createCar
                emit(epoch, "car.lifecycle", ready, "lifecycle")
                detachCar()
                if (ready) attachCar(connected, epoch, carGeneration)
            }
        }
    }

    private fun turnSignal(sdk: Vehicle, epoch: Int) {
        val api = sdk.getTurnSignal()
        attempt("turnSignal", "cap") {
            api.checkTurnSignalStateCapabilities(
                { config -> emit(epoch, "turnSignal.cap", "accessType=${config.accessType.name}", "cap") },
                { error -> failed(epoch, "turnSignal", "cap", error) })
        }
        val listener = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = emit(epoch, "turnSignal", state, "notify")
            override fun onFailed(e: Exception) = failed(epoch, "turnSignal", "notify", e)
        }
        arm("turnSignal", { api.registerTurnSignalState(listener) }, { api.unregisterTurnSignalState(listener) })
        initialRead<TurnSignalValue>("turnSignal", epoch, { it != null }) { ok, fail -> api.getCurrentTurnSignalState(ok, fail) }
    }

    private fun steering(sdk: Vehicle, epoch: Int) {
        val api = sdk.getSteeringWheel()
        attempt("steeringAngle", "cap") {
            api.checkSteeringWheelAngleCapabilities(
                { config, bounds -> emit(epoch, "steeringAngle.cap",
                    "accessType=${config.accessType.name},minValue=${bounds.minValue},maxValue=${bounds.maxValue}", "cap") },
                { failed(epoch, "steeringAngle", "cap", it) })
        }
        val listener = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) = emit(epoch, "steeringAngle", angle, "notify")
            override fun onFailed(e: Exception) = failed(epoch, "steeringAngle", "notify", e)
        }
        arm("steeringAngle", { api.registerSteeringWheelAngle(listener) }, { api.unregisterSteeringWheelAngle(listener) })
        initialRead<Float>("steeringAngle", epoch, { it?.isFinite() == true }) { ok, fail -> api.getCurrentSteeringWheelAngle(ok, fail) }
    }

    private fun brake(sdk: Vehicle, epoch: Int) {
        val api = sdk.getBrake()
        attempt("brakePedal", "cap") {
            api.checkBrakePedalStatusCapabilities(
                { config -> emit(epoch, "brakePedal.cap", "accessType=${config.accessType.name}", "cap") },
                { failed(epoch, "brakePedal", "cap", it) })
        }
        val listener = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) = emit(epoch, "brakePedal", status, "notify")
            override fun onFailed(e: Exception) = failed(epoch, "brakePedal", "notify", e)
        }
        arm("brakePedal", { api.registerBrakePedalStatus(listener) }, { api.unregisterBrakePedalStatus(listener) })
        initialRead<Boolean>("brakePedal", epoch, { it != null }) { ok, fail -> api.getCurrentBrakePedalStatus(ok, fail) }
    }

    /** Initial GET is retried on null/error/timeout, independently of change notifications.
     * Snapshot response must not overwrite any notify delivered since its request began.
     * Existing AAR-verified getters only; no defaults pretending OFF/false/zero were received.
     */
    private fun <T> initialRead(signal: String, epoch: Int, usable: (T?) -> Boolean,
        read: ((T?) -> Unit, (Exception) -> Unit) -> Unit) {
        val tracker = initialReads.getOrPut(signal) { InitialReadTracker() }
        var attempts = 0
        var finished = false
        lateinit var request: Runnable
        request = Runnable {
            if (!running || generation != epoch || finished) return@Runnable
            attempts++
            val ticket = tracker.begin()
            var answered = false
            emit(epoch, "$signal.initialGet.request", attempts, "seed")
            fun answer(value: T?, error: Exception?) = onMain {
                if (!running || generation != epoch || finished || answered) return@onMain
                answered = true
                if (error == null && tracker.complete(ticket, usable(value))) {
                    finished = true
                    emit(epoch, signal, value, "get", "initial=true attempt=$attempts")
                    emit(epoch, "$signal.initialGet.ready", "received", "seed")
                } else {
                    // A failed GET is not a failed live subscription. Preserve a known notify.
                    if (error != null && !tracker.known) failed(epoch, signal, "get", error)
                    emit(epoch, "$signal.initialGet.retry", error?.javaClass?.simpleName ?: "null_or_newer_notify", "seed")
                    if (attempts < 12) main.postAtTime(request, seedToken, SystemClock.uptimeMillis() + 500)
                    else emit(epoch, "$signal.initialGet.unavailable", "no_confirmed_get", "seed")
                }
            }
            try { read({ answer(it, null) }, { answer(null, it) }) }
            catch (error: Exception) { answer(null, error) }
            main.postAtTime({ answer(null, TimeoutException("initial_get")) }, seedToken, SystemClock.uptimeMillis() + 2_000)
        }
        main.postAtTime(request, seedToken, SystemClock.uptimeMillis())
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private fun attachCar(connected: Car, epoch: Int, connection: Int) {
        fun valid() = running && generation == epoch && carGeneration == connection
        attempt("ux", "setup") {
            val manager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            ux = manager
            fun accept(value: CarUxRestrictions, source: String) {
                val at = SystemClock.elapsedRealtime()
                if (valid()) {
                    emit(epoch, "ux", value.activeRestrictions, source,
                        "requiresDO=${value.isRequiresDistractionOptimization}", at)
                    // Typed boolean for the recording-state resolver; do not parse debug log text.
                    emit(epoch, "uxRequiresDo", value.isRequiresDistractionOptimization, source, "", at)
                }
            }
            manager.registerListener { accept(it, "notify") }
            emit(epoch, "ux.register", "returned", "framework_return")
            manager.currentCarUxRestrictions?.let { accept(it, "get") }
        }
        attempt("car.properties", "setup") {
            val manager = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            properties = manager
            val listener = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    val at = SystemClock.elapsedRealtime()
                    if (!valid() || value.areaId != 0) return
                    val signal = propertyName(value.propertyId)
                    emit(epoch, signal, if (value.status == CarPropertyValue.STATUS_AVAILABLE) value.value else null,
                        "notify", "propertyId=${value.propertyId} areaId=${value.areaId} status=${value.status} vehicleTimestampNs=${value.timestamp}", at)
                }
                override fun onErrorEvent(propertyId: Int, areaId: Int) {
                    if (valid()) emit(epoch, "${propertyName(propertyId)}.error", "property_error", "notify",
                        "propertyId=$propertyId areaId=$areaId")
                }
            }
            propertyListener = listener
            // Both gear properties require the already declared CAR_POWERTRAIN read permission.
            // https://developer.android.com/reference/android/car/VehiclePropertyIds#GEAR_SELECTION
            listOf(VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.CURRENT_GEAR, VehiclePropertyIds.PERF_VEHICLE_SPEED).forEach { id ->
                attempt(propertyName(id), "register") {
                    val accepted = manager.registerCallback(listener, id, CarPropertyManager.SENSOR_RATE_UI)
                    emit(epoch, "${propertyName(id)}.register", accepted, "framework_return")
                    if (!accepted) emit(epoch, "${propertyName(id)}.error", "subscription_rejected", "register")
                }
            }
        }
    }

    private fun propertyName(id: Int) = when (id) {
        VehiclePropertyIds.CURRENT_GEAR -> "gear"
        VehiclePropertyIds.GEAR_SELECTION -> "gearSelection"
        VehiclePropertyIds.PERF_VEHICLE_SPEED -> "speed"
        else -> "property.$id"
    }

    private fun emit(epoch: Int, signal: String, value: Any?, source: String, details: String = "",
        at: Long = SystemClock.elapsedRealtime()) {
        onMain {
            if (running && generation == epoch) {
                val base = signal.substringBefore('.')
                if (source == "notify" && base in listOf("turnSignal", "brakePedal", "steeringAngle"))
                    initialReads.getOrPut(base) { InitialReadTracker() }
                        .notified(!signal.endsWith(".error") && value != null && (value !is Float || value.isFinite()))
                record(signal, value, source, details, at)
            }
        }
    }

    private fun failed(epoch: Int, signal: String, source: String, error: Exception) =
        emit(epoch, "$signal.error", error.javaClass.simpleName, source)

    private fun attempt(signal: String, source: String, action: () -> Unit) {
        try { action() } catch (error: Exception) {
            record("$signal.error", error.javaClass.simpleName, source, "", SystemClock.elapsedRealtime())
        }
    }

    private fun arm(signal: String, register: () -> Unit, cancel: () -> Unit) {
        unregister += cancel
        attempt(signal, "register") {
            register()
            emit(generation, "$signal.register", "returned", "framework_return")
        }
    }

    private fun detachCar() {
        ++carGeneration
        attempt("ux", "unregister") { ux?.unregisterListener() }
        attempt("car.properties", "unregister") { propertyListener?.let { properties?.unregisterCallback(it) } }
        ux = null
        properties = null
        propertyListener = null
    }

    fun stop() {
        running = false
        ++generation
        main.removeCallbacksAndMessages(seedToken)
        initialReads.clear()
        detachCar()
        unregister.asReversed().forEach { cancel -> attempt("sdk", "unregister", cancel) }
        unregister.clear()
        attempt("sdk", "release") { vehicle?.release() }
        vehicle = null
        attempt("car", "disconnect") { car?.disconnect() }
        car = null
    }
}
