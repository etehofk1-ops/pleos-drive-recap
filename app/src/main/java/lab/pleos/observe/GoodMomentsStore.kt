package lab.pleos.observe

import android.content.Context
import android.util.AtomicFile
import lab.pleos.observe.logic.recap.*
import java.io.File

/** 1.9.0 잘한 순간 모으기: 누적 숫자 몇 개만 앱 파일에 남긴다(owner process 전용, [WeeklyCountStore]와 같은 원자적 쓰기).
 * 보존 기간 제한은 없지만 카드·시각·위치는 없고, 기록 삭제([deleteAll])가 파일째 지운다. */
class GoodMomentsStore(context: Context) {
    private val dir = File(context.filesDir, "recap-good-v1").apply { check(isDirectory || mkdirs()) }
    private val file = File(dir, "state.bin")
    var state = GoodState()
        private set
    private var dirty = false
    private var bad = false
    var error = ""
        private set

    init {
        if (file.exists() || File(dir, "state.bin.bak").exists()) {
            try { state = AtomicFile(file).openRead().use { GoodCodec.decode(it.readBytes()) } }
            catch (_: Exception) { bad = true; error = "모은 순간 읽기 오류. 원본을 보존했어요." }
        }
    }

    /** [date]의 카드로 상태를 다시 계산한다. 읽기 오류가 있으면 원본을 덮어쓰지 않도록 건드리지 않는다. */
    fun sync(date: String, cards: List<ReviewCard>) {
        if (bad) return
        val next = updateGoodMoments(state, date, cards)
        if (next != state) { state = next; dirty = true }
    }

    /** 대응하는 원본 요약이 아직 저장 전이면(excludeDates) 숫자도 기다린다. */
    fun flush(excludeDates: Set<String>) {
        if (!dirty || bad || state.date in excludeDates) return
        if (state == GoodState()) { clearFiles(); dirty = false; return }
        val atomic = AtomicFile(file); var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite(); stream.write(GoodCodec.encode(state)); atomic.finishWrite(stream); dirty = false
        } catch (_: Exception) { atomic.failWrite(stream); error = "모은 순간 저장 실패" }
    }

    fun deleteAll() {
        state = GoodState(); dirty = false; bad = false; error = ""
        clearFiles()
    }

    private fun clearFiles() {
        dir.listFiles()?.filter { it.name in setOf("state.bin", "state.bin.bak", "state.bin.new") }?.forEach {
            if (!it.delete()) error = "모은 순간 삭제 실패"
        }
    }
}
