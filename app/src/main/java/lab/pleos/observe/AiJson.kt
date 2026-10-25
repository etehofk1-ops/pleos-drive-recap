package lab.pleos.observe

import lab.pleos.observe.logic.recap.*
import org.json.JSONArray
import org.json.JSONObject

/** Explicit allowlists: never serialize ReviewCard/SegmentSummary wholesale. */
object AiJson {
    fun classify(f: ClassifyFeatures) = JSONObject().put("durationMs", f.durationMs).put("peakAbsRaw", f.peakAbsRaw)
        .put("signReversals", f.signReversals).put("speedKphMin", f.speedKphMin ?: JSONObject.NULL)
        .put("speedKphMax", f.speedKphMax ?: JSONObject.NULL)
        .put("indicator", JSONObject().put("state", f.indicator.state).put("offsetMs", f.indicator.offsetMs ?: JSONObject.NULL))
        .put("naviHint", f.naviHint)
    fun summary(f: SummaryFeatures) = JSONObject().put("today", JSONArray().apply {
        f.today.forEach { put(JSONObject().put("kind", it.kind).put("hourBucket", it.hourBucket).put("speedBand", it.speedBand).put("indicator", it.indicator)) }
    }).put("recentDays", JSONArray().apply {
        f.recentDays.forEach { put(JSONObject().put("date", it.date).put("counts", JSONObject()
            .put("missing", it.counts.missing).put("late", it.counts.late).put("good", it.counts.good).put("hardBrake", it.counts.hardBrake)
            .put("turnMissing", it.counts.turnMissing).put("turnLate", it.counts.turnLate).put("turnGood", it.counts.turnGood))) }
    })
    private fun exact(obj: JSONObject, keys: Set<String>) { require(obj.keys().asSequence().toSet() == keys) }
    fun answer(obj: JSONObject): JevAnswer {
        exact(obj, setOf("label", "probabilities", "model", "latencyMs"))
        val probabilities = obj.getJSONObject("probabilities"); exact(probabilities, JEV_LABELS.toSet())
        return JevAnswer(obj.getString("label"), JEV_LABELS.associateWith { probabilities.getDouble(it) }, obj.getString("model"), obj.getLong("latencyMs"))
    }
    fun generated(obj: JSONObject): GeneratedRecap {
        exact(obj, setOf("text", "patterns", "model"))
        val p = obj.getJSONArray("patterns"); require(p.length() <= 4)
        return GeneratedRecap(obj.getString("text"), List(p.length()) { p.getString(it) }, obj.getString("model"))
    }
    fun generated(result: GeneratedRecap): JSONObject = JSONObject().put("text", result.text)
        .put("model", result.model).put("patterns", JSONArray(result.patterns))
}
