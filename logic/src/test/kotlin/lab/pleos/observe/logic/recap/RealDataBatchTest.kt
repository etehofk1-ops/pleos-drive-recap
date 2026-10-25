package lab.pleos.observe.logic.recap

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 폴더 안의 실주행 CSV(t_ms,type,value)를 하나씩 판정 엔진에 흘려 카드 목록을 JSON Lines로 쓴다.
 * `RECAP_REAL_DIR=<폴더>`일 때만 돈다. 결과: `RECAP_REAL_OUT`(기본 build/reports/sim/real_batch.jsonl).
 */
class RealDataBatchTest {
    @Test fun replayFolder() {
        val dir = System.getenv("RECAP_REAL_DIR"); assumeTrue(dir != null)
        val out = File(System.getenv("RECAP_REAL_OUT") ?: "build/reports/sim/real_batch.jsonl").apply { parentFile.mkdirs() }
        val sb = StringBuilder()
        val prefix = System.getenv("RECAP_REAL_PREFIX") ?: ""
        File(dir).listFiles { f -> f.name.endsWith(".csv") && f.name.startsWith(prefix) }!!.sortedBy { it.name }.forEach { csv ->
            var s = RecapEngineState(); val cards = mutableListOf<ReviewCard>(); val base = 1_000_000L
            fun put(i: DriveInput) { val st = recapStep(s, i); s = st.state; cards += st.cards }
            put(DriveInput.Gear(0, base, DriveGear.DRIVE))
            var last = 0L
            csv.readLines().drop(1).forEach { line ->
                val (t, type, value) = line.split(","); val at = t.toLong(); last = at
                when (type) {
                    "Speed" -> put(DriveInput.Speed(at, base + at, value.toFloat()))
                    "Steering" -> put(DriveInput.Steering(at, base + at, value.toFloat()))
                    "Signal" -> put(DriveInput.Signal(at, base + at, Indicator.valueOf(value)))
                    "Brake" -> put(DriveInput.Brake(at, base + at, value.toBoolean()))
                }
            }
            put(DriveInput.Finish(last + 50, base + last + 50))
            val unique = cards.groupBy { it.id }.map { it.value.last() }
            val visible = visibleReviewCards(unique).map { it.id }.toSet()
            val items = unique.sortedBy { it.summary.startMs }.joinToString(",") { c ->
                "{\"start_ms\":${c.summary.startMs},\"end_ms\":${c.summary.endMs},\"kind\":\"${c.kind}\",\"candidate\":\"${c.candidate}\"," +
                    "\"visible\":${c.id in visible},\"lat_m\":${c.summary.lateral?.displacementM ?: "null"}," +
                    "\"peak_rad\":${c.summary.lateral?.peakHeadingRad ?: "null"}}"
            }
            sb.append("{\"segment\":\"${csv.nameWithoutExtension}\",\"cards\":[").append(items).append("]}\n")
        }
        out.writeText(sb.toString())
        println("wrote ${out.path}")
    }
}
