package lab.pleos.observe

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.AtomicFile
import lab.pleos.observe.logic.recap.*
import java.io.File
import java.time.LocalDate

/** Single owner in :drive_recorder. UI uses the private provider, never opens these files. */
class RecapRepository private constructor(context: Context) {
    private val dir = File(context.filesDir, "recap-v1").apply { check(isDirectory || mkdirs()) }
    private val days = mutableMapOf<String, DayRecap>()
    private val dirty = mutableSetOf<String>()
    private val unreadable = mutableSetOf<String>()
    private val weekly = WeeklyCountStore(context)
    private val good = GoodMomentsStore(context)
    private val summary = RecapSummaryCoordinator(context) { key ->
        aiEnabled && summaryAllowed() && currentSummary()?.first == key
    }
    private var motion = DriveGear.UNKNOWN
    private val settings = context.getSharedPreferences("recap-ai-settings", Context.MODE_PRIVATE)
    val aiEnabled: Boolean get() = settings.getBoolean("enabled", false)
    val aiProxyUrl: String get() = settings.getString("proxyUrl", null)?.let(::normalizedProxyEndpoint) ?: DEFAULT_AI_PROXY_URL
    fun setAiProxyUrl(value: String) {
        val selected = requireNotNull(normalizedProxyEndpoint(value)) { "Invalid relay origin" }
        if (selected == aiProxyUrl) return
        summary.delete() // Clear before committing, so a crash cannot reuse the old server's cache.
        check(settings.edit().putString("proxyUrl", selected).commit())
        summary.configureServer(selected)
    }
    fun setAiEnabled(enabled: Boolean) {
        check(settings.edit().putBoolean("enabled", enabled).commit())
        if (!enabled) summary.cancel()
    }
    var recording = false
    private var requestedDemoNavi = false
    val demoNaviEnabled: Boolean get() = BuildConfig.DEBUG && requestedDemoNavi
    fun setDemoNaviEnabled(enabled: Boolean) {
        check(BuildConfig.DEBUG && !recording) { "Debug navigation source can change only before recording" }
        requestedDemoNavi = enabled // Process memory only, default OFF after process restart.
        navStatus = if (enabled) "시연용 모의 내비 입력 사용 중 · 기록 시작 후 공급기를 실행하세요." else "실제 NaviHelper · 미수신"
    }
    var demoMissingSpeed = false
    var navStatus = "미수신"
    var seedStatus = "초기 신호 확인 중"
    var error = ""
        private set

    init {
        summary.configureServer(aiProxyUrl)
        prune()
        val jobs = context.getSystemService(JobScheduler::class.java)
        if (jobs.getPendingJob(CLEANUP_JOB) == null) {
            val scheduled = jobs.schedule(JobInfo.Builder(CLEANUP_JOB, ComponentName(context, RecapCleanupJob::class.java))
                .setPeriodic(6 * 60 * 60 * 1_000L).setPersisted(true).build())
            if (scheduled != JobScheduler.RESULT_SUCCESS) error = "기간 정리 작업 예약 실패. 다음 접근 때는 기간 밖 요약을 삭제해요."
        }
    }

    fun today(): String = LocalDate.now().toString()
    fun prune() {
        summary.prune(today())
        weekly.prune()
        if (weekly.error.isNotEmpty()) error = weekly.error
        val today = today()
        dir.listFiles()?.forEach { file ->
            val date = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.bin(?:\\.bak|\\.new)?$").matchEntire(file.name)?.groupValues?.get(1)
            if (date != null && !retainedDate(date, today) && !file.delete()) error = "보관 기간이 지난 요약 삭제 실패"
        }
        days.keys.toList().filter { !retainedDate(it, today) }.forEach { days.remove(it); dirty.remove(it); unreadable.remove(it) }
    }

    fun day(date: String = today()): DayRecap {
        prune()
        require(retainedDate(date, today()))
        val firstLoad = date !in days
        val result = days.getOrPut(date) {
            val file = File(dir, "$date.bin")
            if (!file.exists() && !File(dir, "$date.bin.bak").exists()) DayRecap(date)
            else try {
                AtomicFile(file).openRead().use { RecapCodec.decode(it.readBytes()) }.also { require(it.date == date) }
            } catch (_: Exception) {
                unreadable += date
                error = "요약 파일 읽기 오류. 원본을 보존했어요. 기록 삭제 후 다시 시작해 주세요."
                DayRecap(date)
            }
        }
        if (date !in unreadable) {
            weekly.record(result)
            if (firstLoad) syncGood(date, result.cards)
        }
        return result
    }

    fun canRecord(): Boolean { day(); return today() !in unreadable }

    fun append(date: String, step: RecapStep) {
        val current = day(date)
        if (date in unreadable) return
        days[date] = current.copy(drivingMs = current.drivingMs + step.drivingMs,
            speedUnknownDrivingMs = current.speedUnknownDrivingMs + step.speedUnknownDrivingMs,
            cards = mergeReviewCards(current.cards, step.cards))
        weekly.record(checkNotNull(days[date]))
        if (step.cards.isNotEmpty()) syncGood(date, checkNotNull(days[date]).cards)
        if (step.drivingMs > 0 || step.cards.isNotEmpty()) dirty += date
        if (step.cards.isNotEmpty() || step.parked) flush()
        if (step.cards.isNotEmpty() && motion == DriveGear.PARK) prepareSummary()
    }

    fun feedback(id: String, value: Feedback) {
        val current = day()
        check(today() !in unreadable)
        days[today()] = applyFeedback(current, id, value)
        syncGood(today(), checkNotNull(days[today()]).cards)
        dirty += today()
        flush()
    }

    fun flush() {
        prune()
        dirty.toList().forEach { date ->
            val value = days[date] ?: return@forEach
            val atomic = AtomicFile(File(dir, "$date.bin"))
            var output: java.io.FileOutputStream? = null
            try {
                output = atomic.startWrite()
                output.write(RecapCodec.encode(value))
                atomic.finishWrite(output)
                dirty.remove(date)
            } catch (_: Exception) {
                atomic.failWrite(output)
                error = "로컬 요약 저장 실패. 화면의 내용이 파일에 저장되지 않았을 수 있어요."
            }
        }
        weekly.flush(dirty) // Counts only commit after the corresponding raw summary succeeds.
        if (weekly.error.isNotEmpty()) error = weekly.error
        good.flush(dirty)
        if (good.error.isNotEmpty()) error = good.error
    }

    /** 오늘(또는 자정을 넘기며 마무리하는 어제) 카드로만 모은 순간을 갱신한다. 지난 날 파일을 읽을 때는 건드리지 않는다. */
    private fun syncGood(date: String, cards: List<ReviewCard>) {
        if (date == today() || date == good.state.date) good.sync(date, cards)
    }
    fun goodState(): GoodState = good.state

    private fun settlePast() {
        val yesterday = LocalDate.now().minusDays(1).toString()
        if (File(dir, "$yesterday.bin").exists() || File(dir, "$yesterday.bin.bak").exists()) day(yesterday)
        weekly.flush(dirty)
    }
    fun recentCounts(): List<RecentCounts> { settlePast(); return weekly.recent() }
    /** 1.9.0 "지난 7일과 비교": 오늘을 뺀 남은 지난 날의 집계(보존 기간은 그대로 7일). */
    fun pastDays(): List<CountDay> { settlePast(); return weekly.pastDays() }

    fun onDriveGear(gear: DriveGear) {
        motion = gear
        if (gear != DriveGear.PARK) summary.cancel()
    }
    private fun summaryAllowed() = !recording || motion == DriveGear.PARK
    private fun currentSummary(): Pair<SummaryCacheKey, String>? {
        val current = day()
        val features = summaryFeatures(current, recentCounts()) ?: return null
        val key = summaryCacheKey(current, features) ?: return null
        return key to AiJson.summary(features).toString()
    }
    fun prepareSummary(request: Boolean = true, force: Boolean = false): Pair<GeneratedRecap?, Boolean> {
        val input = if (aiEnabled && summaryAllowed()) currentSummary() else null
        summary.update(input?.first, input?.second, aiEnabled && summaryAllowed(), request, force)
        return summary.result to summary.pending
    }

    fun deleteRecords() {
        summary.delete()
        days.clear(); dirty.clear(); unreadable.clear(); error = ""
        weekly.deleteAll()
        if (weekly.error.isNotEmpty()) error = weekly.error
        good.deleteAll() // 모은 잘한 순간도 함께 0으로.
        if (good.error.isNotEmpty()) error = good.error
        dir.listFiles()?.filter { it.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.bin(?:\\.bak|\\.new)?")) }?.forEach {
            if (!it.delete()) error = "기록 삭제 실패. 다시 시도해 주세요."
        }
    }

    companion object {
        private const val CLEANUP_JOB = 5005
        private var owner: RecapRepository? = null
        @Synchronized fun get(context: Context): RecapRepository = owner ?: RecapRepository(context.applicationContext).also { owner = it }
    }
}
