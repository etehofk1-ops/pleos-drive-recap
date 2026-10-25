package lab.pleos.observe

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import lab.pleos.observe.logic.Gear
import lab.pleos.observe.logic.SafetyState
import lab.pleos.observe.logic.shouldLogUiProperty

/** API-34-compatible callbacks; newer subscribePropertyEvents APIs are intentionally unused.
 * https://developer.android.com/reference/android/car/Car
 * https://developer.android.com/reference/android/car/hardware/property/CarPropertyManager
 * https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictionsManager
 * https://developer.android.com/reference/android/car/VehiclePropertyIds#GEAR_SELECTION
 */
class CarSafetyClient(
    private val context: Context,
    private val onState: (SafetyState, String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var running = false
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var propertyListener: CarPropertyManager.CarPropertyEventCallback? = null
    private var state = SafetyState()
    private var selectedGear: Gear? = null
    private var currentGear: Gear? = null
    private var connectionGeneration = 0
    private var uxDetail = "미수신"
    private var lastPropertyLogMs: Long? = null
    private var lastPublished: Pair<SafetyState, String>? = null

    fun start() {
        if (running) return
        running = true
        val session = ++generation
        state = SafetyState()
        selectedGear = null; currentGear = null
        publish()
        try {
            // No infinite wait on the main thread. Callback readiness is actual Car connectivity.
            car = Car.createCar(context.applicationContext, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (!running || generation != session) return@createCar
                EvidenceLog.event("car.lifecycle", "ready=$ready")
                detachManagers()
                state = SafetyState(carConnected = ready)
                selectedGear = null; currentGear = null
                uxDetail = "미수신"
                publish()
                if (ready) attachManagers(connectedCar, session, connectionGeneration)
            }
        } catch (e: Exception) {
            EvidenceLog.error("car.connect.failed", e)
            state = SafetyState()
            uxDetail = "연결 오류: ${e.javaClass.simpleName}"
            publish()
        }
    }

    private fun attachManagers(connectedCar: Car, session: Int, connection: Int) {
        fun valid() = running && generation == session && connectionGeneration == connection
        try {
            val manager = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxManager = manager
            manager.registerListener { restriction ->
                val at = EvidenceLog.now()
                EvidenceLog.event("ux.callback", "requiresDO=${restriction.isRequiresDistractionOptimization} active=${restriction.activeRestrictions}", at)
                if (valid()) acceptUx(restriction)
            }
            manager.currentCarUxRestrictions?.let {
                EvidenceLog.event("ux.current", "requiresDO=${it.isRequiresDistractionOptimization} active=${it.activeRestrictions}")
                if (valid()) acceptUx(it)
            }
        } catch (e: Exception) {
            EvidenceLog.error("ux.failed", e)
            state = state.copy(restrictionsActive = null)
            uxDetail = "미수신 / ${e.javaClass.simpleName}"
            publish()
        }

        try {
            val manager = connectedCar.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            properties = manager
            val listener = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    if (!valid() || value.areaId != 0) return
                    val available = value.status == CarPropertyValue.STATUS_AVAILABLE
                    val received = if (available) value.value else null
                    val now = SystemClock.elapsedRealtime()
                    if (shouldLogUiProperty(BuildConfig.DEBUG, now, lastPropertyLogMs)) {
                        lastPropertyLogMs = now
                        EvidenceLog.event("car.property.callback", "propertyId=${value.propertyId} areaId=${value.areaId} status=${value.status} value=$received vehicleTimestampNs=${value.timestamp}")
                    }
                    when (value.propertyId) {
                        VehiclePropertyIds.CURRENT_GEAR -> {
                            currentGear = (received as? Int)?.let(::gear)
                            state = state.copy(gear = selectedGear ?: currentGear)
                        }
                        VehiclePropertyIds.GEAR_SELECTION -> {
                            selectedGear = (received as? Int)?.let(::gear)
                            state = state.copy(gear = selectedGear ?: currentGear)
                        }
                        VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                            state = state.copy(speedMps = received as? Float)
                        }
                    }
                    publish()
                }

                override fun onErrorEvent(propertyId: Int, areaId: Int) {
                    EvidenceLog.event("car.property.error", "propertyId=$propertyId areaId=$areaId")
                    if (valid()) invalidate(propertyId)
                }
            }
            propertyListener = listener
            // registerCallback delivers initial status/value as well as subsequent changes.
            // Failed subscriptions leave that signal unknown; never assume P or speed=0.
            listOf(VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.CURRENT_GEAR, VehiclePropertyIds.PERF_VEHICLE_SPEED).forEach { property ->
                try {
                    // NORMAL is 1 Hz. Gear is ON_CHANGE. This is the UI safety client only;
                    // the independent recorder retains its original input rates and judgement.
                    val rate = if (property == VehiclePropertyIds.PERF_VEHICLE_SPEED) CarPropertyManager.SENSOR_RATE_NORMAL
                        else CarPropertyManager.SENSOR_RATE_ONCHANGE
                    val accepted = manager.registerCallback(listener, property, rate)
                    EvidenceLog.event("car.property.register", "propertyId=$property accepted=$accepted")
                    if (!accepted) invalidate(property)
                } catch (e: Exception) {
                    EvidenceLog.error("car.property.register.failed", e, "propertyId=$property")
                    invalidate(property)
                }
            }
        } catch (e: Exception) {
            EvidenceLog.error("car.property.manager.failed", e)
            state = state.copy(gear = null, speedMps = null)
            selectedGear = null; currentGear = null
            publish()
        }
    }

    private fun acceptUx(value: CarUxRestrictions) {
        state = state.copy(restrictionsActive = value.isRequiresDistractionOptimization ||
            value.activeRestrictions != CarUxRestrictions.UX_RESTRICTIONS_BASELINE)
        uxDetail = "requiresDO=${value.isRequiresDistractionOptimization}, active=${value.activeRestrictions}"
        publish()
    }

    private fun invalidate(property: Int) {
        state = when (property) {
            VehiclePropertyIds.CURRENT_GEAR -> { currentGear = null; state.copy(gear = selectedGear) }
            VehiclePropertyIds.GEAR_SELECTION -> { selectedGear = null; state.copy(gear = currentGear) }
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> state.copy(speedMps = null)
            else -> state
        }
        publish()
    }

    private fun gear(raw: Int): Gear? = when (raw) {
        VehicleGear.GEAR_UNKNOWN -> null
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_DRIVE -> Gear.DRIVE
        else -> Gear.OTHER
    }

    private fun publish() {
        val value = state to uxDetail
        if (value != lastPublished) { lastPublished = value; onState(state, uxDetail) }
    }

    private fun detachManagers() {
        ++connectionGeneration // Ignore callbacks queued by the previous connection.
        try { uxManager?.unregisterListener() } catch (e: Exception) { EvidenceLog.error("ux.unregister.failed", e) }
        try { propertyListener?.let { properties?.unregisterCallback(it) } }
        catch (e: Exception) { EvidenceLog.error("car.property.unregister.failed", e) }
        uxManager = null
        properties = null
        propertyListener = null
    }

    fun stop() {
        running = false
        ++generation
        detachManagers()
        try { car?.disconnect() } catch (e: Exception) { EvidenceLog.error("car.disconnect.failed", e) }
        car = null
        state = SafetyState()
        selectedGear = null; currentGear = null
        uxDetail = "미수신"
        publish()
    }
}
