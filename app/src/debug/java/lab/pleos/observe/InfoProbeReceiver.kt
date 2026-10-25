package lab.pleos.observe

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Debug-only adb probe: logs AAOS INFO_* values to see whether model/wheelbase are exposed. */
class InfoProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val car = Car.createCar(context.applicationContext) ?: run { Log.w(TAG, "no car"); return }
        val pm = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        listOf(
            "INFO_MAKE" to VehiclePropertyIds.INFO_MAKE,
            "INFO_MODEL" to VehiclePropertyIds.INFO_MODEL,
            "INFO_MODEL_YEAR" to VehiclePropertyIds.INFO_MODEL_YEAR,
            "INFO_FUEL_TYPE" to VehiclePropertyIds.INFO_FUEL_TYPE,
            "INFO_EXTERIOR_DIMENSIONS" to VehiclePropertyIds.INFO_EXTERIOR_DIMENSIONS,
        ).forEach { (name, id) ->
            val text = runCatching {
                val v = pm.getProperty<Any>(id, 0).value
                if (v is Array<*>) v.joinToString(",") else v.toString()
            }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }
            Log.i(TAG, "$name=$text")
        }
        runCatching { pm.getCarPropertyConfig(VehiclePropertyIds.PERF_STEERING_ANGLE) }.getOrNull().let { c ->
            Log.i(TAG, "PERF_STEERING_ANGLE config=" + (c?.let { "min=${it.getMinValue(0)} max=${it.getMaxValue(0)} rate=${it.minSampleRate}..${it.maxSampleRate}" } ?: "null"))
        }
        car.disconnect()
    }
    private companion object { const val TAG = "InfoProbe" }
}
