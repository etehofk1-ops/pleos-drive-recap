package lab.pleos.observe

import lab.pleos.observe.logic.recap.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private object RecapAiSession { val id: String = UUID.randomUUID().toString() }

/** No keys on device. A single total deadline includes connection, upload and body read. */
class AiProxyClient : JevReviewAdapter {
    @Volatile private var endpoint = normalizedProxyEndpoint(BuildConfig.AI_PROXY_BASE_URL) ?: DEFAULT_AI_PROXY_URL
    val baseUrl: String get() = endpoint
    fun setBaseUrl(value: String) {
        val selected = requireNotNull(normalizedProxyEndpoint(value)) { "Invalid relay origin" }
        if (endpoint != selected) { cancel(); endpoint = selected }
    }
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    fun cancel() { connections.toList().forEach { it.disconnect() }; connections.clear() }
    fun close() { cancel(); deadlines.shutdownNow() }
    private fun post(path: String, body: String, timeoutMs: Long): JSONObject? {
        if (baseUrl.isEmpty() || Thread.currentThread().isInterrupted) return null
        val url = URI(baseUrl + path).toURL()
        val connection = url.openConnection() as HttpURLConnection
        connections += connection
        val expiry = deadlines.schedule({ connection.disconnect() }, timeoutMs, TimeUnit.MILLISECONDS)
        return try {
            connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 1000; connection.readTimeout = timeoutMs.toInt()
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Recap-Session", RecapAiSession.id)
            connection.doOutput = true
            val bytes = body.toByteArray(Charsets.UTF_8); require(bytes.size <= 64 * 1024)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            if (connection.responseCode != 200) return null
            val data = connection.inputStream.use { it.readBytesLimited(16 * 1024) }
            JSONObject(data.toString(Charsets.UTF_8))
        } catch (_: Exception) { null } // Quiet rule/template fallback; never log body or exceptions.
        finally { expiry.cancel(false); connections -= connection; connection.disconnect() }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(2048)
        while (true) { val count = read(buffer); if (count < 0) break; require(out.size() + count <= limit); out.write(buffer, 0, count) }
        return out.toByteArray()
    }
    override fun review(input: ClassifyFeatures): JevAnswer? =
        post("/classify", AiJson.classify(input).toString(), CLASSIFY_TIMEOUT_MS)?.let(AiJson::answer)
    fun classify(card: ReviewCard): AiDecision = assistedDecision(card, true, this)
    fun summarize(payload: String): GeneratedRecap? = runCatching {
        val json = post("/summarize", payload, SUMMARY_TIMEOUT_MS) ?: return null
        AiJson.generated(json).takeIf(::validGeneratedRecap)
    }.getOrNull()
    companion object {
        const val CLASSIFY_TIMEOUT_MS = 3_000L
        const val SUMMARY_TIMEOUT_MS = 20_000L
    }
}
