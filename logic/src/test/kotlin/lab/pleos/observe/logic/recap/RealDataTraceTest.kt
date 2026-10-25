package lab.pleos.observe.logic.recap

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 진단용: 실주행 CSV를 흘리며 지정 구간의 내부 상태를 200ms마다 TSV로 쓴다.
 * `RECAP_TRACE_SPEC=<명세 파일>`일 때만 돈다. 명세 한 줄: `<csv 경로>\t<from_ms>\t<to_ms>\t<출력 tsv 경로>`.
 */
class RealDataTraceTest {
    @Test fun trace() {
        val spec = System.getenv("RECAP_TRACE_SPEC"); assumeTrue(!spec.isNullOrEmpty())
        File(spec).readLines().filter { it.isNotBlank() }.forEach { line ->
            val (path, fromText, toText, outPath) = line.split("\t")
            traceOne(File(path), fromText.toLong(), toText.toLong(), File(outPath))
        }
    }

    private fun traceOne(csv: File, from: Long, to: Long, out: File) {
        val sb = StringBuilder("t_ms\tangle\tspeed\tbaseline\taccel\tactive_start\treversals\tlat_m\theading\tcards\n")
        var s = RecapEngineState(); val base = 1_000_000L; var lastRow = -1_000_000L
        fun put(i: DriveInput, at: Long) {
            val st = recapStep(s, i); s = st.state
            if (at in from..to && (at - lastRow >= 200 || st.cards.isNotEmpty())) {
                lastRow = at; val f = s.lateralFilter; val a = s.active
                sb.append("$at\t${s.angle}\t${s.speed}\t${"%.2f".format(f.baselineDeg)}\t${"%.3f".format(f.acceleration)}\t")
                    .append("${a?.summary?.startMs ?: ""}\t${a?.summary?.reversals ?: ""}\t")
                    .append("${a?.summary?.lateral?.displacementM?.let { "%.2f".format(it) } ?: ""}\t")
                    .append("${a?.summary?.lateral?.headingRad?.let { "%.3f".format(it) } ?: ""}\t")
                    .append(st.cards.joinToString(";") { "${it.kind}/${it.candidate}@${it.summary.startMs}-${it.summary.endMs}" +
                        " lat=${it.summary.lateral?.displacementM?.let { d -> "%.2f".format(d) }} flags=${it.uncertainty}" }).append('\n')
            }
        }
        put(DriveInput.Gear(0, base, DriveGear.DRIVE), 0)
        var last = 0L
        csv.readLines().drop(1).forEach { line ->
            val (t, type, value) = line.split(","); val at = t.toLong(); last = at
            when (type) {
                "Speed" -> put(DriveInput.Speed(at, base + at, value.toFloat()), at)
                "Steering" -> put(DriveInput.Steering(at, base + at, value.toFloat()), at)
                "Signal" -> put(DriveInput.Signal(at, base + at, Indicator.valueOf(value)), at)
                "Brake" -> put(DriveInput.Brake(at, base + at, value.toBoolean()), at)
            }
        }
        put(DriveInput.Finish(last + 50, base + last + 50), last + 50)
        out.parentFile.mkdirs(); out.writeText(sb.toString())
        println("wrote ${out.path}")
    }
}
