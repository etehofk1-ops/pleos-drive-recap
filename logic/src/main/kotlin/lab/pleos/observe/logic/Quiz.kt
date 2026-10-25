package lab.pleos.observe.logic

import kotlin.math.abs
import kotlin.random.Random

enum class Seat(val label: String, val row: Int) {
    ROW_1_LEFT("1열 왼쪽", 1), ROW_1_RIGHT("1열 오른쪽", 1),
    ROW_2_LEFT("2열 왼쪽", 2), ROW_2_RIGHT("2열 오른쪽", 2),
}

enum class SignalKind(val label: String) { WINDOW("창문"), LIGHT("독서등") }
data class SignalKey(val seat: Seat, val kind: SignalKind) {
    val label: String get() = "${seat.label} · ${kind.label}"
}

data class SeatState(val window: Int, val lightOn: Boolean) {
    init { require(window in 0..10) }
}

/** Copies caller-owned maps. Only the four proven seat enums can enter a puzzle. */
@ConsistentCopyVisibility
data class VehicleSnapshot private constructor(val seats: Map<Seat, SeatState>) {
    companion object {
        fun of(seats: Map<Seat, SeatState>) = VehicleSnapshot(seats.toMap())
        val EMPTY = of(emptyMap())
    }
}

fun snapshotFromObservations(rows: List<Observation>): VehicleSnapshot {
    val readings = rows.associateBy { it.id }
    val seats = Seat.values().mapNotNull { seat ->
        val window = readings["window.${seat.name}"]
        val light = readings["light.${seat.name}"]
        if (window?.supported != true || light?.supported != true || window.error != null || light.error != null) return@mapNotNull null
        val position = window.value?.toIntOrNull()?.takeIf { it in 0..10 } ?: return@mapNotNull null
        val lit = when (light.value) { "ON" -> true; "OFF" -> false; else -> return@mapNotNull null }
        seat to SeatState(position, lit)
    }.toMap()
    return VehicleSnapshot.of(seats)
}

data class NumberProblem(val snapshot: VehicleSnapshot, val answer: Int, val options: List<Int>, val explanation: String)
data class SeatClue(val text: String, val matches: Set<Seat>)
data class SeatProblem(val snapshot: VehicleSnapshot, val answer: Seat, val clues: List<SeatClue>, val explanation: String)
data class ChangeProblem(val answer: SignalKey, val options: List<SignalKey>, val explanation: String)

private fun <T> options(answer: T, pool: List<T>, seed: Int): List<T> {
    val random = Random(seed)
    return ((pool.distinct() - answer).shuffled(random).take(3) + answer).shuffled(random)
}

private fun seatNames(seats: Collection<Seat>): String =
    Seat.values().filter { it in seats }.joinToString(", ") { it.label }.ifEmpty { "없음" }

fun numberProblem(snapshot: VehicleSnapshot, seed: Int): NumberProblem? {
    if (snapshot.seats.size < 2) return null
    val lights = snapshot.seats.filterValues { it.lightOn }.keys
    val windows = snapshot.seats.filterValues { it.window >= 5 }.keys
    val answer = lights.size + windows.size
    return NumberProblem(snapshot, answer, options(answer, (0..8).toList(), seed),
        "켜진 독서등 ${lights.size}개: ${seatNames(lights)}\n" +
            "절반 이상 열린 창문 ${windows.size}개: ${seatNames(windows)}\n" +
            "${lights.size} + ${windows.size} = $answer\n대상 좌석: ${seatNames(snapshot.seats.keys)}")
}

/** No fabricated positional clues: each predicate is computed from the snapshot. */
fun seatProblem(snapshot: VehicleSnapshot, seed: Int): SeatProblem? {
    val seats = snapshot.seats
    if (seats.size < 3) return null
    val clues = mutableListOf<SeatClue>()
    fun add(text: String, predicate: (Seat, SeatState) -> Boolean) {
        val matches = seats.filter { (seat, value) -> predicate(seat, value) }.keys.toSet()
        if (matches.isNotEmpty() && matches.size < seats.size) clues += SeatClue(text, matches)
    }
    add("독서등이 켜진 자리예요.") { _, value -> value.lightOn }
    add("독서등이 꺼진 자리예요.") { _, value -> !value.lightOn }
    add("창문이 절반 이상 열린 자리예요.") { _, value -> value.window >= 5 }
    add("창문이 절반보다 덜 열린 자리예요.") { _, value -> value.window < 5 }
    val max = seats.values.maxOf { it.window }
    val min = seats.values.minOf { it.window }
    add("대상 좌석 중 창문이 가장 많이 열린 자리예요.") { _, value -> value.window == max }
    add("대상 좌석 중 창문이 가장 적게 열린 자리예요.") { _, value -> value.window == min }
    // Comparing rows is meaningful only when both rows have both complete seats.
    if (seats.size == 4) {
        val totals = (1..2).associateWith { row -> seats.filterKeys { it.row == row }.values.sumOf { it.window } }
        if (totals[1] != totals[2]) {
            val winningRow = totals.maxBy { it.value }.key
            add("창문 열린 정도의 합이 더 큰 줄에 있어요.") { seat, _ -> seat.row == winningRow }
        }
        val lightCounts = (1..2).associateWith { row -> seats.filterKeys { it.row == row }.values.count { it.lightOn } }
        if (lightCounts[1] != lightCounts[2]) {
            val winningRow = lightCounts.maxBy { it.value }.key
            add("켜진 독서등이 더 많은 줄에 있어요.") { seat, _ -> seat.row == winningRow }
        }
    }
    // Search every 2/3-clue combination; the intersection itself proves uniqueness.
    val candidates = mutableListOf<List<SeatClue>>()
    for (i in clues.indices) for (j in i + 1 until clues.size) {
        val pair = listOf(clues[i], clues[j])
        if (pair[0].matches != pair[1].matches && (pair[0].matches intersect pair[1].matches).size == 1) candidates += pair
        for (k in j + 1 until clues.size) {
            val triple = pair + clues[k]
            if (triple.map { it.matches }.distinct().size == 3 &&
                triple.map { it.matches }.reduce { a, b -> a intersect b }.size == 1) candidates += triple
        }
    }
    val selected = candidates.shuffled(Random(seed)).firstOrNull() ?: return null
    val answer = selected.map { it.matches }.reduce { a, b -> a intersect b }.single()
    return SeatProblem(snapshot, answer, selected.shuffled(Random(seed xor 0x71)),
        "정답은 ${answer.label}이에요.\n" + selected.joinToString("\n") { "${it.text} 해당 자리: ${seatNames(it.matches)}" } +
            "\n모든 단서가 겹치는 자리는 ${answer.label} 하나예요.")
}

fun canRemember(snapshot: VehicleSnapshot): Boolean = snapshot.seats.size >= 2

/** null means an original seat is now unreadable, not that it has changed. */
fun changes(before: VehicleSnapshot, after: VehicleSnapshot): List<SignalKey>? {
    if (!after.seats.keys.containsAll(before.seats.keys)) return null
    return buildList {
        Seat.values().forEach { seat ->
            val previous = before.seats[seat] ?: return@forEach
            val current = after.seats.getValue(seat)
            if (abs(current.window - previous.window) >= 1) add(SignalKey(seat, SignalKind.WINDOW))
            if (current.lightOn != previous.lightOn) add(SignalKey(seat, SignalKind.LIGHT))
        }
    }
}

fun changeProblem(before: VehicleSnapshot, after: VehicleSnapshot, seed: Int): ChangeProblem? {
    if (!canRemember(before)) return null
    val answer = changes(before, after)?.singleOrNull() ?: return null
    val pool = Seat.values().filter { it in before.seats }.flatMap { seat -> SignalKind.values().map { SignalKey(seat, it) } }
    val previous = before.seats.getValue(answer.seat)
    val current = after.seats.getValue(answer.seat)
    val description = when (answer.kind) {
        SignalKind.WINDOW -> "창문 열림 정도가 달라졌어요: ${previous.window} → ${current.window}"
        SignalKind.LIGHT -> "독서등이 ${if (previous.lightOn) "켜짐" else "꺼짐"} → ${if (current.lightOn) "켜짐" else "꺼짐"}으로 바뀌었어요."
    }
    return ChangeProblem(answer, options(answer, pool, seed), "${answer.seat.label}\n$description\n비교한 시점에 달라진 신호는 이것 하나예요.")
}

/** Release messages contain only event names and optional correctness. */
fun eventMessage(event: String, correct: Boolean? = null, debug: Boolean, details: String = ""): String = buildString {
    append("event=$event")
    correct?.let { append(" correct=$it") }
    if (debug && details.isNotBlank()) append(" $details")
}
