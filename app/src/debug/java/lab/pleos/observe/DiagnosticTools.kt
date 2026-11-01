package lab.pleos.observe

import android.content.Context
import android.content.Intent

/** Debug-only entries of the hidden diagnostics menu. Release builds ship an empty list instead. */
object DiagnosticTools {
    class Entry(val label: String, val intent: (Context, String?) -> Intent)

    val entries: List<Entry> = listOf(
        Entry("신호 탐침·기존 게임") { c, navi -> Intent(c, LegacyLabActivity::class.java).putExtra("recapNaviStatus", navi) },
        Entry("내비 경로 요청 (입력 저장 안 함)") { c, _ -> Intent(c, NaviDemoActivity::class.java) },
    )
}
