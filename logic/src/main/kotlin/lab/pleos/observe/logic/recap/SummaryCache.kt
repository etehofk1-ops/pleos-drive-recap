package lab.pleos.observe.logic.recap

import java.io.*
import java.security.MessageDigest
import java.time.LocalDate

/** Local-only identity. Neither these hashes nor card IDs/timestamps are sent to AI. */
data class SummaryCacheKey(val date: String, val cardsHash: String, val contextHash: String)
data class SummaryCacheEntry(val key: SummaryCacheKey, val result: GeneratedRecap)
data class SummaryTicket(val key: SummaryCacheKey, val generation: Long)

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

fun summaryCacheKey(day: DayRecap, features: SummaryFeatures): SummaryCacheKey? {
    val cards = visibleReviewCards(day.cards)
    if (cards.isEmpty() || cards.size > 500) return null
    // Driving timers, list pagination/order and user feedback do not change the generated facts.
    val normalized = day.copy(drivingMs = 0, speedUnknownDrivingMs = 0,
        cards = cards.sortedBy { it.id }.map { it.copy(feedback = Feedback.NONE) })
    val context = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            // Time zone/hour and recent counts also invalidate stale prose for the same cards.
            val today = features.today.sortedWith(compareBy({ it.kind }, { it.hourBucket }, { it.speedBand }, { it.indicator }))
            out.writeInt(today.size)
            today.forEach { out.writeUTF(it.kind); out.writeInt(it.hourBucket); out.writeUTF(it.speedBand); out.writeUTF(it.indicator) }
            out.writeInt(features.recentDays.size)
            features.recentDays.sortedBy { it.date }.forEach {
                out.writeUTF(it.date); out.writeInt(it.counts.missing); out.writeInt(it.counts.late)
                out.writeInt(it.counts.good); out.writeInt(it.counts.hardBrake)
                out.writeInt(it.counts.turnMissing); out.writeInt(it.counts.turnLate); out.writeInt(it.counts.turnGood)
            }
        }; bytes.toByteArray()
    }
    return SummaryCacheKey(day.date, sha256(RecapCodec.encode(normalized)), sha256(context))
}

/** Main-thread state machine. A cancelled/deleted/replaced request can never refill the cache. */
class SummaryPreparation {
    var key: SummaryCacheKey? = null
        private set
    var entry: SummaryCacheEntry? = null
        private set
    var pending = false
        private set
    private var generation = 0L
    private var attempted = false
    fun select(next: SummaryCacheKey?, cached: SummaryCacheEntry? = null) {
        if (next == key) return
        reset(); key = next
        entry = cached?.takeIf { it.key == next && validGeneratedRecap(it.result) }
    }
    fun begin(force: Boolean = false): SummaryTicket? {
        val current = key ?: return null
        if (!force && (pending || attempted || entry != null)) return null
        ++generation; attempted = true; pending = true; entry = null
        return SummaryTicket(current, generation)
    }
    fun finish(ticket: SummaryTicket, answer: GeneratedRecap?): SummaryCacheEntry? {
        if (!isPending(ticket)) return null
        pending = false
        entry = answer?.takeIf(::validGeneratedRecap)?.let { SummaryCacheEntry(ticket.key, it) }
        return entry
    }
    fun isPending(ticket: SummaryTicket) = pending && ticket.generation == generation && ticket.key == key
    fun reset() { ++generation; key = null; entry = null; pending = false; attempted = false }
}

object SummaryCacheCodec {
    fun encode(entry: SummaryCacheEntry): ByteArray = ByteArrayOutputStream().use { bytes ->
        require(validGeneratedRecap(entry.result)); LocalDate.parse(entry.key.date)
        require(listOf(entry.key.cardsHash, entry.key.contextHash).all { it.matches(Regex("[0-9a-f]{64}")) })
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x41495331); out.writeUTF(entry.key.date); out.writeUTF(entry.key.cardsHash); out.writeUTF(entry.key.contextHash)
            out.writeUTF(entry.result.text); out.writeUTF(entry.result.model); out.writeInt(entry.result.patterns.size)
            entry.result.patterns.forEach(out::writeUTF)
        }; bytes.toByteArray()
    }
    fun decode(bytes: ByteArray): SummaryCacheEntry = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(bytes.size <= 16 * 1024 && input.readInt() == 0x41495331)
        val key = SummaryCacheKey(input.readUTF(), input.readUTF(), input.readUTF())
        LocalDate.parse(key.date); require(listOf(key.cardsHash, key.contextHash).all { it.matches(Regex("[0-9a-f]{64}")) })
        val text = input.readUTF(); val model = input.readUTF(); val size = input.readInt(); require(size in 0..4)
        val result = GeneratedRecap(text, List(size) { input.readUTF() }, model)
        require(validGeneratedRecap(result) && input.available() == 0)
        SummaryCacheEntry(key, result)
    }
}
