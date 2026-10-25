package lab.pleos.observe.logic.recap

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import java.util.Locale

/**
 * 실차 물리로 만든 합성 주행 신호를 판정 엔진에 넣어 속도대별 탐지율과 오탐을 잰다.
 * 정답 판정 시험이 아니라 측정용이라 실패시키지 않고 리포트만 쓴다. `RECAP_SIM=1`일 때만 돈다.
 *
 * 핸들 각도 = 조향비 × (축간거리·횡가속/속도² + 언더스티어·횡가속) [rad→deg] (단순 자전거 모델).
 * 차선 변경 횡가속 a(t) = (2πW/T²)·sin(2πt/T), W = 3.3~3.6m, T = 3.5~6.5s.
 */
class RealisticDrivingSimTest {
    private enum class Truth(val label: String) {
        LANE_BEFORE("차선 변경·깜빡이 먼저"), LANE_TAP("차선 변경·깜빡이 짧게(3번)"),
        LANE_NONE("차선 변경·깜빡이 없음"), LANE_LATE("차선 변경·깜빡이 늦게"),
        CURVE("고속도로 곡선(깜빡이 없음)"), STRAIGHT("직진 60초(손 보정만)"),
        BRAKE_COMFORT("편한 감속 1.5~2.5m/s²"), BRAKE_FIRM("단호한 감속 2.8~3.8m/s²"), BRAKE_HARD("급제동 5~8m/s²"),
    }
    private class Car(r: Random, stress: Boolean, kph: Int) {
        val ratio = if (stress) 12.0 + r.nextDouble() * 7.0 else 13.5 + r.nextDouble() * 3.0
        val wheelbase = if (stress) 2.5 + r.nextDouble() * 0.6 else 2.6 + r.nextDouble() * 0.35
        val understeer = 0.0003 + r.nextDouble() * 0.0004
        // RECAP_SIM_NOISE=real: comma2k19 실주행(62~66km/h)에서 잰 손 보정 잔차 σ 0.46~0.53°·τ 0.7~0.8s·기준선 0.5° 이내에 맞춤
        private val real = System.getenv("RECAP_SIM_NOISE") == "real"
        // RECAP_SIM_NOISE=ccs: commaCarSegments(현대·기아) 핸들 잔차 실측. 중앙값 σ(2s 이동평균 제거 잔차)는 속도대별
        // <60km/h 1.25°, <100 0.51°, 그 외 0.28°이고 AR(1) τ0.8의 잔차는 σ의 0.6배라 σ = 잔차/0.6, 10~90%는 그 0.4~1.9배.
        private val ccs = System.getenv("RECAP_SIM_NOISE") == "ccs"
        val noiseSd = if (ccs) (if (kph < 60) 1.25 else if (kph < 100) 0.51 else 0.28) / 0.6 * (0.4 + r.nextDouble() * 1.5)
            else if (real) 0.3 + r.nextDouble() * 0.5 else 0.4 + r.nextDouble() * if (stress) 1.6 else 1.1
        val crown = (r.nextDouble() - 0.5) * if (real || ccs) 1.0 else 2.0
        fun wheelDeg(aLat: Double, v: Double) = ratio * (wheelbase * aLat / (v * v) + understeer * aLat) * 180.0 / PI
    }
    private class Feed {
        var s = RecapEngineState(); val cards = mutableListOf<ReviewCard>()
        fun put(i: DriveInput) { val st = recapStep(s, i); s = st.state; cards += st.cards }
    }
    private data class Result(val truth: Truth, val kph: Int, val outcome: String, val peakDeg: Double,
        val diagnostic: String, val storedCount: Int)

    private fun episode(truth: Truth, kph: Int, r: Random, stress: Boolean): Result {
        val car = Car(r, stress, kph); val f = Feed(); val dt = 50L; val base = 1_000_000L
        val v0 = kph / 3.6
        val t0 = 12_000L + r.nextLong(0, 2_000)
        // event parameters
        val dir = if (r.nextBoolean()) 1.0 else -1.0
        val T = (if (stress) 3_000 + r.nextLong(0, 5_001) else 3_500 + r.nextLong(0, 3_000)).toDouble()
        val W = 3.3 + r.nextDouble() * 0.3
        val A = 2 * PI * W / ((T / 1000) * (T / 1000))
        val asym = System.getenv("RECAP_SIM_ASYM") == "1"
        val asymR = if (asym) 0.75 + r.nextDouble() * 0.58 else 1.0 // 비대칭일 때만 난수 사용(기존 seed 결과 보존)
        val curveR = 250.0 + r.nextDouble() * 950.0
        val curveHold = 5_000 + r.nextLong(0, 15_000)
        val ramp = 2_500L
        val brakeDecel = when (truth) { Truth.BRAKE_COMFORT -> 1.5 + r.nextDouble(); Truth.BRAKE_FIRM -> 2.8 + r.nextDouble(); else -> 5.0 + r.nextDouble() * 3 }
        val brakeDrop = if (r.nextDouble() < 0.4) v0 else (15 + r.nextDouble() * 25) / 3.6
        val signalOn: Long?; val signalOff: Long?
        when (truth) {
            Truth.LANE_BEFORE -> { signalOn = t0 - (500 + r.nextLong(0, 2_500)); signalOff = t0 + (T * (0.6 + r.nextDouble() * 0.5)).toLong() }
            Truth.LANE_TAP -> { signalOn = t0 - (300 + r.nextLong(0, 1_700)); signalOff = signalOn + 1_500 }
            Truth.LANE_LATE -> { signalOn = t0 + 300 + r.nextLong(0, 1_200); signalOff = t0 + T.toLong() + 500 }
            else -> { signalOn = null; signalOff = null }
        }
        val eventEnd = when (truth) {
            Truth.CURVE -> t0 + 2 * ramp + curveHold
            Truth.STRAIGHT -> t0 + 60_000
            Truth.BRAKE_COMFORT, Truth.BRAKE_FIRM, Truth.BRAKE_HARD -> t0 + (brakeDrop / brakeDecel * 1000).toLong() + 1_500
            else -> t0 + T.toLong()
        }
        val end = eventEnd + 8_000
        f.put(DriveInput.Gear(0, base, DriveGear.PARK)); f.put(DriveInput.Signal(0, base, Indicator.OFF))
        f.put(DriveInput.Steering(0, base, 0f)); f.put(DriveInput.Speed(0, base, 0f)); f.put(DriveInput.Brake(0, base, false))
        f.put(DriveInput.Gear(500, base + 500, DriveGear.DRIVE))
        val phi = exp(-dt / 800.0)
        var noise = 0.0; var peak = 0.0; var speed = v0; var signal = Indicator.OFF; var brake = false
        var t = 550L
        while (t <= end) {
            // 횡가속(차선 변경·곡선)
            val aLat = when (truth) {
                Truth.LANE_BEFORE, Truth.LANE_TAP, Truth.LANE_NONE, Truth.LANE_LATE -> if (asym) {
                    // RECAP_SIM_ASYM=1: 앞·뒤 꺾음 시간이 다름(r 0.75~1.33, 같은 진폭) → 남은 방향을 2.5초 동안 작은 반대 조향으로 보정.
                    val t1 = T / 2 * asymR; val t2 = T / 2 / asymR; val e = t - t0
                    val residual = A * (t1 - t2) * 2 / PI / 1000.0 // 남는 횡속도(m/s) ≈ 두 반사인 면적 차
                    when {
                        e < 0 -> 0.0
                        e < t1 -> dir * A * sin(PI * e / t1)
                        e < t1 + t2 -> -dir * A * sin(PI * (e - t1) / t2)
                        e < t1 + t2 + 2_500 -> -dir * residual / 2.5 * 1.0
                        else -> 0.0
                    }
                } else if (t in t0..(t0 + T.toLong())) dir * A * sin(2 * PI * (t - t0) / T) else 0.0
                Truth.CURVE -> {
                    val ss = dir * v0 * v0 / curveR
                    when {
                        t < t0 || t > eventEnd -> 0.0
                        t < t0 + ramp -> ss * (t - t0) / ramp
                        t > eventEnd - ramp -> ss * (eventEnd - t) / ramp
                        else -> ss
                    }
                }
                else -> 0.0
            }
            noise = phi * noise + sqrt(1 - phi * phi) * car.noiseSd * r.nextDouble(-1.73, 1.73)
            val clean = car.wheelDeg(aLat, max(speed, 1.0))
            peak = max(peak, abs(clean))
            val angle = Math.round((clean + noise + car.crown) * 10) / 10.0
            // 속도(감속)
            val braking = truth.name.startsWith("BRAKE") && t >= t0 && t < eventEnd - 1_500 && speed > v0 - brakeDrop + 0.05
            if (braking) {
                val ramped = brakeDecel * minOf(1.0, (t - t0) / 400.0)
                speed = max(0.0, max(v0 - brakeDrop, speed - ramped * dt / 1000))
            }
            if (braking != brake) { brake = braking; f.put(DriveInput.Brake(t, base + t, brake)) }
            // 깜빡이
            val want = if (signalOn != null && t >= signalOn && t < (signalOff ?: Long.MAX_VALUE))
                (if (dir > 0) Indicator.LEFT else Indicator.RIGHT) else Indicator.OFF
            if (want != signal) { signal = want; f.put(DriveInput.Signal(t, base + t, signal)) }
            val speedNoise = if (stress) 0.08 else 0.04
            val measured = max(0.0, speed + r.nextDouble(-speedNoise, speedNoise))
            f.put(DriveInput.Speed(t, base + t, measured.toFloat()))
            f.put(DriveInput.Steering(t, base + t, angle.toFloat()))
            f.put(DriveInput.Tick(t, base + t))
            t += dt
        }
        f.put(DriveInput.Finish(end + dt, base + end + dt))
        val all = f.cards.groupBy { it.id }.map { it.value.last() }
        val visible = visibleReviewCards(all)
        val outcome = if (visible.isEmpty()) "카드 없음" else visible.map { it.kind.name }.sorted().joinToString("+")
        val detail = all.joinToString(";") { c ->
            val s = c.summary
            // Reflection only in this optional harness: same source can measure the frozen pre-W14 JAR.
            val m = s.javaClass.methods.firstOrNull { it.name == "getLateral" }?.invoke(s)
            "${c.kind}/${c.candidate}:t=${s.startMs-t0}..${s.endMs-t0},d=${s.durationMs}," +
                "motion=$m,flags=${c.uncertainty}"
        }
        return Result(truth, kph, outcome, peak, detail, all.size)
    }

    @Test fun realisticDrivingReport() {
        assumeTrue(System.getenv("RECAP_SIM") == "1")
        val n = (System.getenv("RECAP_SIM_N") ?: "200").toInt()
        require(n in 1..5_000)
        val seed = (System.getenv("RECAP_SIM_SEED") ?: "20261024").toInt()
        val stress = System.getenv("RECAP_SIM_STRESS") == "1"
        val r = Random(seed)
        val cells = listOf(
            Truth.LANE_BEFORE to listOf(40, 60, 80, 100, 120), Truth.LANE_TAP to listOf(60, 100),
            Truth.LANE_NONE to listOf(40, 60, 80, 100, 120), Truth.LANE_LATE to listOf(40, 60, 80, 100, 120),
            Truth.CURVE to listOf(60, 80, 100, 120), Truth.STRAIGHT to listOf(60, 100),
            Truth.BRAKE_COMFORT to listOf(50, 80), Truth.BRAKE_FIRM to listOf(50, 80), Truth.BRAKE_HARD to listOf(50, 80),
        )
        val results = cells.flatMap { (truth, speeds) -> speeds.flatMap { kph -> List(n) { episode(truth, kph, r, stress) } } }
        val expected = mapOf(Truth.LANE_BEFORE to "LANE_SIGNALLED", Truth.LANE_TAP to "LANE_SIGNALLED",
            Truth.LANE_NONE to "LANE_NO_SIGNAL", Truth.LANE_LATE to "LANE_LATE_SIGNAL",
            Truth.CURVE to "카드 없음", Truth.STRAIGHT to "카드 없음", Truth.BRAKE_COMFORT to "카드 없음",
            Truth.BRAKE_HARD to "HARD_BRAKE")
        val out = StringBuilder("# 실차 물리 합성 주행 시뮬레이션 (seed $seed, stress=$stress, 칸당 ${n}회)\n\n" +
            "noise=${System.getenv("RECAP_SIM_NOISE") ?: "default"}, asym=${System.getenv("RECAP_SIM_ASYM") == "1"}\n\n")
        fun percent(count: Int, total: Int) = "%.1f%%".format(Locale.ROOT, count * 100.0 / total)
        out.append("| 정답 | 속도 | 기대 판정 | 기대대로 | 차선 변경 핸들 최대각(중앙값) | 주요 결과 |\n|---|---|---|---|---|---|\n")
        for ((key, group) in results.groupBy { it.truth to it.kph }) {
            val (truth, kph) = key
            val exp = expected[truth]
            val ok = exp?.let { e -> percent(group.count { it.outcome == e }, group.size) }
            val peaks = group.map { it.peakDeg }.sorted()
            val peakText = if (truth.name.startsWith("LANE") || truth == Truth.CURVE) "%.1f°".format(peaks[peaks.size / 2]) else "-"
            val top = group.groupingBy { it.outcome }.eachCount().entries.sortedByDescending { it.value }.take(3)
                .joinToString(", ") { "${it.key} ${percent(it.value, group.size)}" }
            out.append("| ${truth.label} | $kph | ${exp ?: "(참고)"} | ${ok ?: "-"} | $peakText | $top |\n")
        }
        val file = File(System.getenv("RECAP_SIM_OUTPUT") ?: "build/reports/sim/realistic.md")
        file.parentFile.mkdirs(); file.writeText(out.toString())
        File(file.path + ".diagnostics.tsv").writeText(results.groupBy { it.truth to it.kph }.flatMap { (_, group) ->
            group.filter { it.outcome != expected[it.truth] }.take(8)
        }.joinToString("\n") { "${it.truth}\t${it.kph}\t${it.outcome}\t${it.diagnostic}" })
        val metrics = StringBuilder("truth\tkph\tn\texact\tvisible\thardBrake\tunsure\tstored\n")
        results.groupBy { it.truth to it.kph }.forEach { (key, group) ->
            fun has(kind: String, result: Result) = kind in result.outcome.split("+")
            metrics.append(listOf(key.first.name, key.second, group.size, group.count { it.outcome == expected[it.truth] },
                group.count { it.outcome != "카드 없음" }, group.count { has("HARD_BRAKE", it) },
                group.count { has("UNSURE", it) }, group.sumOf { it.storedCount }).joinToString("\t") + "\n")
        }
        File(file.path + ".metrics.tsv").writeText(metrics.toString())
        println(out)
    }
}
