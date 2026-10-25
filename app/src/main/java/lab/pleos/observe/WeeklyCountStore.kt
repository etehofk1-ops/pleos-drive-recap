package lab.pleos.observe

import android.content.Context
import android.util.AtomicFile
import lab.pleos.observe.logic.recap.*
import java.io.File
import java.time.LocalDate

/** Owner-process only. Raw summaries still expire after two days; four counts (plus the 1.9.0 lead-distance sum) survive seven. */
class WeeklyCountStore(context: Context) {
    private val dir = File(context.filesDir, "recap-counts-v1").apply { check(isDirectory || mkdirs()) }
    private val cache = mutableMapOf<String, CountDay>()
    private val dirty = mutableSetOf<String>()
    private val bad = mutableSetOf<String>()
    var error = ""
        private set
    fun prune() {
        val today = LocalDate.now().toString()
        dir.listFiles()?.forEach { file ->
            val date = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.bin(?:\\.bak|\\.new)?$").matchEntire(file.name)?.groupValues?.get(1)
            if (date != null && !retainedCountDate(date, today) && !file.delete()) error = "기간 밖 집계 삭제 실패"
        }
        cache.keys.toList().filter { !retainedCountDate(it, today) }.forEach { cache.remove(it); dirty.remove(it); bad.remove(it) }
        bad.removeAll { !retainedCountDate(it, today) }
    }
    private fun load(date: String): CountDay? {
        cache[date]?.let { return it }
        if (date in bad) return null
        val file = File(dir, "$date.bin")
        if (!file.exists() && !File(dir, "$date.bin.bak").exists()) return null
        return try { AtomicFile(file).openRead().use { CountCodec.decode(it.readBytes()) }.also {
            require(it.date == date); cache[date] = it
        } } catch (_: Exception) { bad += date; error = "집계 읽기 오류. 원본을 보존했어요."; null }
    }
    fun record(day: DayRecap) {
        if (day.drivingMs == 0L && day.cards.isEmpty()) return
        val next = countDay(day)
        if (load(day.date) != next && day.date !in bad) { cache[day.date] = next; dirty += day.date }
    }
    /** 오늘을 뺀 남은 지난 날(최대 6일, 기록이 있는 날만). */
    fun pastDays(): List<CountDay> {
        prune(); val today = LocalDate.now()
        return (1..6).mapNotNull { load(today.minusDays(it.toLong()).toString()) }
    }
    fun recent(): List<RecentCounts> = relativeCounts(pastDays(), LocalDate.now().toString())
    fun flush(excludeDates: Set<String>) {
        prune()
        dirty.toList().filter { it !in excludeDates }.forEach { date ->
            val value = cache[date] ?: return@forEach
            val atomic = AtomicFile(File(dir, "$date.bin")); var stream: java.io.FileOutputStream? = null
            try {
                stream = atomic.startWrite(); stream.write(CountCodec.encode(value)); atomic.finishWrite(stream); dirty.remove(date)
            } catch (_: Exception) { atomic.failWrite(stream); error = "집계 저장 실패" }
        }
    }
    fun deleteAll() {
        cache.clear(); dirty.clear(); bad.clear(); error = ""
        dir.listFiles()?.filter { it.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.bin(?:\\.bak|\\.new)?")) }?.forEach {
            if (!it.delete()) error = "집계 삭제 실패"
        }
    }
}
