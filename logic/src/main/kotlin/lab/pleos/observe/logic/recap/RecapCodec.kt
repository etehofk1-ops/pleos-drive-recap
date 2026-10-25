package lab.pleos.observe.logic.recap

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.LocalDate

fun retainedDate(date: String, today: String): Boolean = runCatching {
    val now = LocalDate.parse(today)
    val day = LocalDate.parse(date)
    day == now || day == now.minusDays(1)
}.getOrDefault(false)

fun applyFeedback(day: DayRecap, cardId: String, feedback: Feedback): DayRecap =
    day.copy(cards = day.cards.map { if (it.id == cardId) it.copy(feedback = feedback) else it })

/** Whitelisted, versioned summary schema. No raw event, location, VIN, route or arbitrary object serialization. */
object RecapCodec {
    private const val MAGIC = 0x52454350
    // 7 (1.7.0): directional lead distance, signalLeadMeters/Ms and blinkerOnMs/Meters. Older records decode with null.
    private const val VERSION = 7
    fun encode(day: DayRecap): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(day.date)
            out.writeLong(day.drivingMs); out.writeLong(day.speedUnknownDrivingMs); out.writeInt(day.cards.size)
            day.cards.forEach { c ->
                out.writeUTF(c.id); out.writeLong(c.wallTimeMs); out.writeUTF(c.kind.name); out.writeUTF(c.candidate.name)
                out.writeUTF(c.direction.name); out.writeUTF(c.feedback.name)
                out.writeInt(c.uncertainty.size); c.uncertainty.sortedBy { it.ordinal }.forEach { out.writeUTF(it.name) }
                out.nullableFloat(c.decelerationMps2)
                val s = c.summary
                out.writeLong(s.startMs); out.writeLong(s.endMs); out.writeLong(s.wallStartMs)
                out.writeFloat(s.minAngle); out.writeFloat(s.maxAngle); out.writeInt(s.firstSign); out.writeInt(s.reversals)
                out.nullableFloat(s.minSpeedMps); out.nullableFloat(s.maxSpeedMps); out.writeBoolean(s.speedMissing)
                out.writeUTF(s.timing.name); out.writeBoolean(s.signalCoverage)
                out.writeBoolean(s.lateSignalMs != null); s.lateSignalMs?.let(out::writeLong)
                out.writeBoolean(s.nearNavigationTurn); out.writeBoolean(s.incomplete); out.writeBoolean(s.inputGap)
                listOf(s.leftSignal, s.rightSignal).forEach { timing ->
                    out.writeBoolean(timing != null)
                    timing?.let {
                        out.writeUTF(it.timing.name); out.writeBoolean(it.lateMs != null); it.lateMs?.let(out::writeLong)
                        out.nullableFloat(it.leadMeters); out.nullableLong(it.leadMs)
                    }
                }
                out.writeUTF(c.navigation.name); out.writeUTF(c.navigationTurn?.name ?: "")
                out.writeBoolean(c.demoNavigation)
                out.writeBoolean(s.lateral != null)
                s.lateral?.let { m ->
                    listOf(m.headingRad, m.peakHeadingRad, m.absoluteYawRad, m.velocityMps, m.displacementM,
                        m.travelM, m.minAcceleration, m.maxAcceleration).forEach { require(it.isFinite()); out.writeDouble(it) }
                    require(m.settledMs in 0..LateralThresholds.SETTLE_MS); out.writeLong(m.settledMs)
                    require(m.windowDurationMs == 0 || m.windowDurationMs in 2_500..8_000); out.writeInt(m.windowDurationMs)
                }
                out.nullableFloat(s.signalLeadMeters); out.nullableLong(s.signalLeadMs)
                out.nullableLong(s.blinkerOnMs); out.nullableFloat(s.blinkerOnMeters)
            }
        }
        bytes.toByteArray()
    }

    /** Throws on unknown/corrupt schema; Android owner preserves the file until explicit delete. */
    fun decode(bytes: ByteArray): DayRecap = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == MAGIC) { "Unsupported recap schema" }
        val version = input.readInt(); require(version in 1..VERSION) { "Unsupported recap schema" }
        val date = input.readUTF().also { LocalDate.parse(it) }
        val drive = input.readLong(); val unknown = input.readLong()
        require(drive >= 0 && unknown in 0..drive)
        val size = input.readInt(); require(size in 0..100_000)
        val cards = List(size) {
            val id = input.readUTF(); val wall = input.readLong()
            val kind = ReviewKind.valueOf(input.readUTF()); val candidate = ReviewKind.valueOf(input.readUTF())
            val direction = ManeuverDirection.valueOf(input.readUTF()); val feedback = Feedback.valueOf(input.readUTF())
            val flagCount = input.readInt(); require(flagCount in 0..Uncertainty.values().size)
            val flags = List(flagCount) { Uncertainty.valueOf(input.readUTF()) }.toSet()
            val decel = input.nullableFloat()
            var s = SegmentSummary(input.readLong(), input.readLong(), input.readLong(), input.readFloat(), input.readFloat(),
                input.readInt(), input.readInt(), input.nullableFloat(), input.nullableFloat(), input.readBoolean(),
                SignalTiming.valueOf(input.readUTF()), input.readBoolean(), if (input.readBoolean()) input.readLong() else null,
                input.readBoolean(), input.readBoolean(), input.readBoolean())
            fun directional(): DirectionalTiming? = if (input.readBoolean()) {
                val timing = SignalTiming.valueOf(input.readUTF()); val late = if (input.readBoolean()) input.readLong() else null
                if (version >= 7) DirectionalTiming(timing, late, input.nullableFloat(), input.nullableLong()) else DirectionalTiming(timing, late)
            } else null
            if (version >= 2) s = s.copy(leftSignal = directional(), rightSignal = directional())
            val navigation = if (version >= 2) NavigationLabel.valueOf(input.readUTF()) else NavigationLabel.ESTIMATED
            val turn = if (version >= 2) input.readUTF().takeIf { it.isNotEmpty() }?.let(NavigationTurn::valueOf) else null
            val demo = version >= 3 && input.readBoolean()
            if (version >= 4 && input.readBoolean()) {
                val m = List(8) { input.readDouble().also { require(it.isFinite()) } }
                val settledMs = if (version >= 5) input.readLong().also { require(it in 0..LateralThresholds.SETTLE_MS) } else 0
                val windowMs = if (version >= 6) input.readInt().also { require(it == 0 || it in 2_500..8_000) } else 0
                s = s.copy(lateral = LateralSummary(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], settledMs, windowMs))
            }
            if (version >= 7) {
                val leadMeters = input.nullableFloat(); val leadMs = input.nullableLong()
                val onMs = input.nullableLong(); val onMeters = input.nullableFloat()
                require((leadMeters ?: 0f) >= 0f && (leadMs ?: 0L) >= 0L && (onMs ?: 0L) >= 0L && (onMeters ?: 0f) >= 0f)
                s = s.copy(signalLeadMeters = leadMeters, signalLeadMs = leadMs, blinkerOnMs = onMs, blinkerOnMeters = onMeters)
            }
            require(s.endMs >= s.startMs && s.minAngle.isFinite() && s.maxAngle.isFinite())
            ReviewCard(id, wall, kind, candidate, s, direction, flags, decel, feedback, navigation, turn, demo)
        }
        require(input.available() == 0) { "Trailing recap data" }
        DayRecap(date, drive, unknown, cards)
    }

    private fun DataOutputStream.nullableFloat(value: Float?) { writeBoolean(value != null); value?.let(::writeFloat) }
    private fun DataOutputStream.nullableLong(value: Long?) { writeBoolean(value != null); value?.let(::writeLong) }
    private fun DataInputStream.nullableLong(): Long? = if (readBoolean()) readLong() else null
    private fun DataInputStream.nullableFloat(): Float? = if (readBoolean()) readFloat().also { require(it.isFinite()) } else null
}
