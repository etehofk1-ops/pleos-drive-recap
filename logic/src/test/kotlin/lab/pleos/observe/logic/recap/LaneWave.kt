package lab.pleos.observe.logic.recap

import kotlin.math.PI
import kotlin.math.sin

/**
 * W16c test fixture: steering angle of a physical lane change under the detector's own
 * model (yaw = v * theta / (RATIO * WHEELBASE)). The lateral acceleration is one sine period,
 * a(t) = (2*pi*W/T^2) * sin(2*pi*t/T) with W lateral metres and period T seconds.
 */
internal object LaneWave {
    const val PERIOD_MS = 4_500L
    fun angleDeg(elapsedMs: Long, kph: Float, direction: Int = 1, periodMs: Long = PERIOD_MS, widthM: Double = 3.4): Float {
        if (elapsedMs < 0 || elapsedMs > periodMs) return 0f
        val period = periodMs / 1000.0; val v = kph / 3.6
        val a = 2 * PI * widthM / (period * period) * sin(2 * PI * elapsedMs / periodMs)
        val rad = a * LaneWindowPolicy.RATIO * LaneWindowPolicy.WHEELBASE_M / (v * v)
        return (direction * rad * 180 / PI).toFloat()
    }
}
