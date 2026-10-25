package lab.pleos.observe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import lab.pleos.observe.logic.recap.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Recorder-process owner, independent of Activity lifetime. No raw features in the cache. */
class RecapSummaryCoordinator(context: Context, private val mayApply: (SummaryCacheKey) -> Boolean) {
    private val dir = File(context.filesDir, "recap-ai-summary-v1").apply { mkdirs() }
    private val file = AtomicFile(File(dir, "today.bin"))
    private val state = SummaryPreparation()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val client = AiProxyClient()
    private var task: Future<*>? = null
    private var prunedDate: String? = null
    val result: GeneratedRecap? get() = state.entry?.result
    val pending: Boolean get() = state.pending

    fun configureServer(value: String) { client.setBaseUrl(value) }

    private fun readCache(): SummaryCacheEntry? = runCatching {
        file.openRead().use { input ->
            require(input.channel.size() <= 16 * 1024)
            SummaryCacheCodec.decode(input.readBytes())
        }
    }.getOrNull()

    fun prune(today: String) {
        if (state.key?.date?.let { it != today } == true) cancel()
        if (prunedDate == today) return
        prunedDate = today
        if (readCache()?.key?.date != today) file.delete()
    }

    fun update(key: SummaryCacheKey?, payload: String?, enabled: Boolean, request: Boolean, force: Boolean = false) {
        val selected = key.takeIf { enabled }
        if (selected != state.key) {
            cancel()
            state.select(selected, if (selected == null) null else readCache())
        }
        if (!request || selected == null || payload == null) return
        if (force) { client.cancel(); task?.cancel(true); file.delete() }
        val ticket = state.begin(force) ?: return
        task = worker.submit {
            val answer = client.summarize(payload)
            main.post {
                if (!state.isPending(ticket)) return@post
                // Re-check current date/cards/AI opt-in/motion before writing, not just before display.
                if (!mayApply(ticket.key)) { cancel(); return@post }
                val entry = state.finish(ticket, answer) ?: return@post
                var stream: java.io.FileOutputStream? = null
                try {
                    stream = file.startWrite(); stream.write(SummaryCacheCodec.encode(entry)); file.finishWrite(stream)
                } catch (_: Exception) { file.failWrite(stream) } // Memory result still works; optional disk cache.
            }
        }
    }

    fun cancel() { client.cancel(); task?.cancel(true); task = null; state.reset() }
    fun delete() { cancel(); file.delete() }
}
