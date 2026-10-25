package lab.pleos.observe.logic.recap

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * 실제 주행 CAN을 해독한 CSV(t_ms,type,value)를 판정 엔진에 그대로 흘려 보내 나온 카드를 적는다.
 * `RECAP_REAL_CSV=<경로>`일 때만 돈다. 데이터는 이미 주행 중에서 시작하므로 첫 입력 전에 D를 넣는다.
 */
class RealDataReplayTest {
    private val expectedG = listOf(ReviewKind.LANE_SIGNALLED, ReviewKind.LANE_NO_SIGNAL,
        ReviewKind.LANE_LATE_SIGNAL, ReviewKind.TURN, ReviewKind.TURN, ReviewKind.HARD_BRAKE)

    @Test fun avdRunOneIsAnAlwaysOnRegression() = assertEquals(expectedG, fixture("avd_g_run1.csv").map { it.kind })
    @Test fun avdRunTwoIsAnAlwaysOnRegression() = assertEquals(expectedG, fixture("avd_g_run2.csv").map { it.kind })
    @Test fun realMinuteDoesNotProduceVisibleReviewCards() {
        assertTrue(visibleReviewCards(fixture("comma2k19_example1.csv")).isEmpty())
    }
    @Test fun realisticTwoLapsKeepThreeLaneKindsAndOneBrakePerLap() {
        val cards=fixture("avd_g_realistic_2laps.csv")
        val lanes=setOf(ReviewKind.LANE_SIGNALLED,ReviewKind.LANE_NO_SIGNAL,ReviewKind.LANE_LATE_SIGNAL)
        assertEquals(List(2){listOf(ReviewKind.LANE_SIGNALLED,ReviewKind.LANE_NO_SIGNAL,ReviewKind.LANE_LATE_SIGNAL)}.flatten(),
            cards.filter{it.kind in lanes}.map{it.kind})
        assertEquals(2,cards.count{it.kind==ReviewKind.HARD_BRAKE})
    }
    private fun fixture(name: String): List<ReviewCard> {
        val stream = javaClass.getResourceAsStream("/replay/$name") ?: error("Missing replay fixture: $name")
        return replay(stream.bufferedReader().use { it.readLines() })
    }
    private fun replay(lines: List<String>): List<ReviewCard> {
        var state = RecapEngineState(); var cards = emptyList<ReviewCard>(); val base = 1_000_000L
        fun put(input: DriveInput) { val next = recapStep(state,input); state=next.state; cards=mergeReviewCards(cards,next.cards) }
        put(DriveInput.Gear(0,base,DriveGear.DRIVE))
        var last = 0L
        lines.drop(1).filter { it.isNotBlank() }.forEach { line ->
            val (time,type,value)=line.split(','); val at=time.toLong(); last=at
            put(when(type) {
                "Speed" -> DriveInput.Speed(at,base+at,value.toFloat())
                "Steering" -> DriveInput.Steering(at,base+at,value.toFloat())
                "Signal" -> DriveInput.Signal(at,base+at,Indicator.valueOf(value))
                "Brake" -> DriveInput.Brake(at,base+at,value.toBooleanStrict())
                else -> error("Unknown replay type: $type")
            })
        }
        put(DriveInput.Finish(last+50,base+last+50))
        return cards.sortedBy { it.summary.startMs }
    }
    @Test fun replayRealDrive() {
        val path = System.getenv("RECAP_REAL_CSV"); assumeTrue(path != null)
        var s = RecapEngineState(); val cards = mutableListOf<ReviewCard>(); val base = 1_000_000L
        fun put(i: DriveInput) { val st = recapStep(s, i); s = st.state; cards += st.cards }
        put(DriveInput.Gear(0, base, DriveGear.DRIVE))
        var last = 0L
        File(path).readLines().drop(1).forEach { line ->
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
        val visible = visibleReviewCards(unique)
        val out = StringBuilder("# 실주행 재생 ($path)\n\n전체 카드 ${unique.size} · 화면 카드 ${visible.size}\n\n")
        unique.sortedBy { it.summary.startMs }.forEach { c ->
            out.append("- ${"%.1f".format(c.summary.startMs / 1000.0)}~${"%.1f".format(c.summary.endMs / 1000.0)}s ${c.kind}(${c.candidate}) " +
                "각 ${c.summary.minAngle}~${c.summary.maxAngle} 반전 ${c.summary.reversals} 감속 ${c.decelerationMps2} ${c.uncertainty}" +
                (c.summary.lateral?.let { m -> " | 방향 끝 %.1f° 최대 %.1f° 누적 %.1f° 횡이동 %.2fm 가속 %.2f~%.2f 길이 %dms".format(Math.toDegrees(m.headingRad), Math.toDegrees(m.peakHeadingRad), Math.toDegrees(m.absoluteYawRad), m.displacementM, m.minAcceleration, m.maxAcceleration, c.summary.durationMs) } ?: "") +
                (if (c in visible) " [화면]" else " [숨김]") + "\n")
        }
        File(System.getenv("RECAP_REAL_OUT") ?: "build/reports/sim/real_replay.md").apply { parentFile.mkdirs() }.writeText(out.toString())
        println(out)
    }
}
