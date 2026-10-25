package lab.pleos.observe.logic

enum class ProblemKind { NUMBER, SEAT, CHANGE }

sealed interface LabScreen {
    data object Locked : LabScreen
    data object Home : LabScreen
    data object Diagnostics : LabScreen
    data class Unavailable(val kind: ProblemKind, val reason: String, val notice: String? = null) : LabScreen
    data class Number(val id: Long, val problem: NumberProblem, val notice: String? = null) : LabScreen
    data class SeatQuestion(val id: Long, val problem: SeatProblem, val notice: String? = null) : LabScreen
    data class MemoryReady(val notice: String? = null) : LabScreen
    data class MemoryReading(val id: Long, val baseline: VehicleSnapshot?) : LabScreen
    data class MemoryHidden(val baseline: VehicleSnapshot, val notice: String? = null) : LabScreen
    data class ChangeQuestion(val id: Long, val problem: ChangeProblem) : LabScreen
    data class Result(val correct: Boolean, val explanation: String, val kind: ProblemKind) : LabScreen
}

data class LabState(
    val safety: SafetyState = SafetyState(),
    val foreground: Boolean = false,
    val current: VehicleSnapshot = VehicleSnapshot.EMPTY,
    val screen: LabScreen = LabScreen.Locked,
    val sequence: Long = 0,
    val seed: Int = 0,
) {
    val unlocked: Boolean get() = foreground && !shouldLock(safety)
}

sealed interface LabAction {
    data class Safety(val value: SafetyState, val foreground: Boolean) : LabAction
    data class Snapshot(val value: VehicleSnapshot) : LabAction
    data class Open(val kind: ProblemKind) : LabAction
    data object Home : LabAction
    data object Diagnostics : LabAction
    data class NumberAnswer(val id: Long, val value: Int) : LabAction
    data class SeatAnswer(val id: Long, val value: Seat) : LabAction
    data class ChangeAnswer(val id: Long, val value: SignalKey) : LabAction
    data object Remember : LabAction
    data object Changed : LabAction
    data class FreshRead(val id: Long, val value: VehicleSnapshot?) : LabAction
}

data class LabEvent(val name: String, val correct: Boolean? = null)
data class RefreshRequest(val id: Long)
data class LabTransition(val state: LabState, val events: List<LabEvent> = emptyList(), val refresh: RefreshRequest? = null)

private const val INVALIDATED = "차가 바뀌었어요, 새 문제를 낼게요"
private const val TOO_FEW = "창문과 독서등을 모두 확인할 수 있는 좌석이 부족해요. 값 수신과 지원 상태를 확인해 주세요."
private const val NO_UNIQUE = "유일한 답을 만들 수 없어요. 창문이나 독서등을 하나 바꿔 보세요"

private fun openProblem(state: LabState, kind: ProblemKind, notice: String? = null): LabTransition {
    val id = state.sequence + 1
    val seed = state.seed xor id.toInt()
    val screen = when (kind) {
        ProblemKind.NUMBER -> numberProblem(state.current, seed)?.let { LabScreen.Number(id, it, notice) }
            ?: LabScreen.Unavailable(kind, TOO_FEW, notice)
        ProblemKind.SEAT -> if (state.current.seats.size < 3) LabScreen.Unavailable(kind, TOO_FEW, notice)
            else seatProblem(state.current, seed)?.let { LabScreen.SeatQuestion(id, it, notice) }
                ?: LabScreen.Unavailable(kind, NO_UNIQUE, notice)
        ProblemKind.CHANGE -> if (canRemember(state.current)) LabScreen.MemoryReady(notice)
            else LabScreen.Unavailable(kind, TOO_FEW, notice)
    }
    val event = if (screen is LabScreen.Unavailable) "quiz.unavailable" else "quiz.opened"
    return LabTransition(state.copy(screen = screen, sequence = id), listOf(LabEvent(event)))
}

/** All puzzle transitions are pure. A refresh request is an effect for the Android adapter. */
fun reduceLab(state: LabState, action: LabAction): LabTransition {
    if (action is LabAction.Safety) {
        val next = state.copy(safety = action.value, foreground = action.foreground)
        if (!next.unlocked) {
            return LabTransition(next.copy(current = VehicleSnapshot.EMPTY, screen = LabScreen.Locked),
                if (state.screen != LabScreen.Locked) listOf(LabEvent("session.discarded")) else emptyList())
        }
        return if (!state.unlocked) LabTransition(next.copy(screen = LabScreen.Home, current = VehicleSnapshot.EMPTY),
            listOf(LabEvent("session.ready"))) else LabTransition(next)
    }
    // Includes late read/answer callbacks after lock or pause.
    if (!state.unlocked) return LabTransition(state)
    return when (action) {
        is LabAction.Snapshot -> {
            if (state.current == action.value) return LabTransition(state)
            val next = state.copy(current = action.value)
            val kind = when (state.screen) {
                is LabScreen.Number -> ProblemKind.NUMBER
                is LabScreen.SeatQuestion -> ProblemKind.SEAT
                else -> null
            }
            if (kind != null) {
                val renewed = openProblem(next, kind, INVALIDATED)
                renewed.copy(events = listOf(LabEvent("quiz.invalidated")) + renewed.events)
            } else if (state.screen is LabScreen.Unavailable) {
                openProblem(next, state.screen.kind, state.screen.notice)
            } else if (state.screen is LabScreen.MemoryReady && !canRemember(action.value)) {
                openProblem(next, ProblemKind.CHANGE)
            } else LabTransition(next)
        }
        is LabAction.Open -> openProblem(state, action.kind)
        LabAction.Home -> LabTransition(state.copy(screen = LabScreen.Home), listOf(LabEvent("session.home")))
        LabAction.Diagnostics -> LabTransition(state.copy(screen = LabScreen.Diagnostics), listOf(LabEvent("diagnostics.opened")))
        is LabAction.NumberAnswer -> {
            val screen = state.screen as? LabScreen.Number ?: return LabTransition(state)
            if (screen.id != action.id || action.value !in screen.problem.options) return LabTransition(state)
            val correct = action.value == screen.problem.answer
            LabTransition(state.copy(screen = LabScreen.Result(correct, screen.problem.explanation, ProblemKind.NUMBER)), listOf(LabEvent("quiz.number.answered", correct)))
        }
        is LabAction.SeatAnswer -> {
            val screen = state.screen as? LabScreen.SeatQuestion ?: return LabTransition(state)
            if (screen.id != action.id || action.value !in screen.problem.snapshot.seats) return LabTransition(state)
            val correct = action.value == screen.problem.answer
            LabTransition(state.copy(screen = LabScreen.Result(correct, screen.problem.explanation, ProblemKind.SEAT)), listOf(LabEvent("quiz.seat.answered", correct)))
        }
        is LabAction.ChangeAnswer -> {
            val screen = state.screen as? LabScreen.ChangeQuestion ?: return LabTransition(state)
            if (screen.id != action.id || action.value !in screen.problem.options) return LabTransition(state)
            val correct = action.value == screen.problem.answer
            LabTransition(state.copy(screen = LabScreen.Result(correct, screen.problem.explanation, ProblemKind.CHANGE)), listOf(LabEvent("quiz.change.answered", correct)))
        }
        LabAction.Remember -> {
            if (state.screen !is LabScreen.MemoryReady) return LabTransition(state)
            val id = state.sequence + 1
            LabTransition(state.copy(screen = LabScreen.MemoryReading(id, null), sequence = id),
                listOf(LabEvent("memory.read.requested")), RefreshRequest(id))
        }
        LabAction.Changed -> {
            val screen = state.screen as? LabScreen.MemoryHidden ?: return LabTransition(state)
            val id = state.sequence + 1
            LabTransition(state.copy(screen = LabScreen.MemoryReading(id, screen.baseline), sequence = id),
                listOf(LabEvent("memory.compare.requested")), RefreshRequest(id))
        }
        is LabAction.FreshRead -> {
            val screen = state.screen as? LabScreen.MemoryReading ?: return LabTransition(state)
            if (screen.id != action.id) return LabTransition(state)
            val fresh = action.value
            val before = screen.baseline
            if (fresh == null) {
                val notice = "값을 다시 읽지 못했어요. 잠시 후 다시 시도해 주세요."
                return LabTransition(state.copy(screen = if (before == null) LabScreen.MemoryReady(notice) else LabScreen.MemoryHidden(before, notice)), listOf(LabEvent("memory.read.failed")))
            }
            val next = state.copy(current = fresh)
            if (before == null) {
                if (!canRemember(fresh)) return LabTransition(next.copy(screen = LabScreen.Unavailable(ProblemKind.CHANGE, TOO_FEW)))
                return LabTransition(next.copy(screen = LabScreen.MemoryHidden(fresh)), listOf(LabEvent("memory.remembered")))
            }
            val changes = changes(before, fresh)
            val notice = when {
                changes == null -> "기억한 좌석의 값을 확인할 수 없어요. 값 수신 후 다시 시도해 주세요."
                changes.isEmpty() -> "아직 안 바뀌었어요"
                changes.size > 1 -> "하나만 바꿔 주세요"
                else -> null
            }
            if (notice != null) return LabTransition(next.copy(screen = LabScreen.MemoryHidden(before, notice)), listOf(LabEvent("memory.retry")))
            val problem = changeProblem(before, fresh, state.seed xor screen.id.toInt())!!
            LabTransition(next.copy(screen = LabScreen.ChangeQuestion(screen.id, problem)), listOf(LabEvent("memory.question.ready")))
        }
        is LabAction.Safety -> error("Handled above")
    }
}
