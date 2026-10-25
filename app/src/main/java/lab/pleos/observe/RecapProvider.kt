package lab.pleos.observe

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import lab.pleos.observe.logic.recap.*
import lab.pleos.observe.logic.recapTileCounts
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Private IPC: all repository + engine mutations serialized on the recorder process main thread. */
class RecapProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()
        val done = CountDownLatch(1)
        val action = Runnable {
            try {
                val repo = RecapRepository.get(checkNotNull(context))
                when (method) {
                    "read" -> Unit
                    "summaryRetry" -> Unit
                    "feedback" -> repo.feedback(checkNotNull(arg), Feedback.valueOf(checkNotNull(extras?.getString("feedback"))))
                    "aiSetting" -> { require(arg in listOf("on", "off")); repo.setAiEnabled(arg == "on") }
                    "aiServer" -> repo.setAiProxyUrl(checkNotNull(arg))
                    "demoNaviSetting" -> { require(arg in listOf("on", "off")); repo.setDemoNaviEnabled(arg == "on") }
                    "delete" -> {
                        val live = DriveRecorderService.live
                        if (live != null) live.deleteUserRecords() else repo.deleteRecords()
                    }
                    else -> error("Unknown recap operation")
                }
                val stored = repo.day()
                val day = stored.copy(cards = visibleReviewCards(stored.cards))
                val offset = (extras?.getInt("offset", 0) ?: 0).coerceIn(0, day.cards.size)
                val newest = day.cards.asReversed()
                result.putByteArray("day", RecapCodec.encode(day.copy(cards = newest.drop(offset).take(100))))
                result.putInt("total", day.cards.size)
                result.putString("recapSummary", recapSummary(stored.cards))
                // Presentation only: whole-day counters, never the paginated 100-card subset.
                val tiles = recapTileCounts(reviewCounts(stored.cards))
                result.putInt("tileMissing", tiles.missing); result.putInt("tileLate", tiles.late)
                result.putInt("tileHardBrake", tiles.hardBrake); result.putInt("tileGood", tiles.good)
                result.putInt("unsure", day.cards.count { it.kind == ReviewKind.UNSURE })
                result.putBoolean("recording", repo.recording)
                result.putBoolean("demo", repo.demoMissingSpeed)
                result.putBoolean("demoNaviEnabled", repo.demoNaviEnabled)
                result.putBoolean("hasDemoNaviCards", stored.cards.any { it.demoNavigation })
                result.putBoolean("aiEnabled", repo.aiEnabled)
                result.putString("aiProxyUrl", repo.aiProxyUrl)
                val (generated, preparing) = repo.prepareSummary(
                    request = method == "summaryRetry" || extras?.getBoolean("prepareSummary", false) == true,
                    force = method == "summaryRetry")
                result.putString("summaryResult", generated?.let { AiJson.generated(it).toString() })
                result.putBoolean("summaryPending", preparing)
                result.putString("navi", repo.navStatus)
                result.putString("seed", repo.seedStatus)
                result.putString("error", repo.error)
            } catch (error: Exception) { result.putString("error", "복기 처리 실패: ${error.javaClass.simpleName}") }
            finally { done.countDown() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else Handler(Looper.getMainLooper()).post(action)
        // First call can queue behind the recorder process/Vehicle service startup.
        // Waiting happens on the caller's IO thread; never replay mutating commands.
        return if (done.await(8, TimeUnit.SECONDS)) result else Bundle().apply { putString("error", "복기 서비스 응답 지연. 다시 시도해 주세요.") }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

class RecapCleanupJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        RecapRepository.get(this).prune()
        return false
    }
    override fun onStopJob(params: JobParameters?): Boolean = true
}
