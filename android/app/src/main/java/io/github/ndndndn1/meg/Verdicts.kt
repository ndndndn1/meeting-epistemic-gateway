package io.github.ndndndn1.meg

import org.json.JSONArray
import org.json.JSONObject

object Verdicts {
    fun parse(raw: String, evidence: JSONArray): JSONArray {
        val clean =
            raw.replace(Regex("<think>[\\s\\S]*?</think>"), "")
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        val results = JSONObject(clean).getJSONArray("claims")
        require(results.length() <= 20)
        val known = evidence.objects().map { it.getString("id") }.toSet()
        val statuses =
            setOf(
                "GENERAL_KNOWLEDGE",
                "VERIFIED",
                "CONTRADICTED",
                "EXPERIENCE",
                "HYPOTHESIS",
                "UNSUPPORTED ASSERTION",
                "UNVERIFIABLE",
                "QUESTION",
                "VALUE JUDGMENT",
                "PROPOSAL",
            )
        for (c in results.objects()) {
            require(c.getString("status") in statuses && c.getString("text").length <= 3000)
            val ids = c.optJSONArray("evidenceIds") ?: JSONArray()
            val valid = (0 until ids.length()).map { ids.optString(it) }.filter { it in known }
            c.put("evidenceIds", JSONArray(valid))
            if (
                (c.getString("status") in setOf("VERIFIED", "CONTRADICTED") && valid.isEmpty()) ||
                    (c.getString("status") == "UNSUPPORTED ASSERTION" && evidence.length() == 0)
            )
                c.put("status", "UNVERIFIABLE").put("reason", "확인 가능한 근거가 부족합니다.")
            if (
                c.optString("basis") == "MODEL_KNOWLEDGE" &&
                    c.getString("status") in
                        setOf("VERIFIED", "CONTRADICTED", "UNSUPPORTED ASSERTION")
            )
                c.put("status", "UNVERIFIABLE")
            if (
                c.getString("status") == "GENERAL_KNOWLEDGE" &&
                    Regex(
                            "현재|지금|오늘|올해|최신|최근|버전|가격|주가|대통령|대표이사|법률|법규|규정|출시|20\\d{2}|version|latest|current|today|price|president|CEO",
                            RegexOption.IGNORE_CASE,
                        )
                        .containsMatchIn(c.getString("text"))
            )
                c.put("status", "UNVERIFIABLE").put("reason", "시점·버전에 따라 달라지는 주장은 자료 또는 검색이 필요합니다.")
            if (c.getString("status") == "GENERAL_KNOWLEDGE") {
                c.put("basis", "MODEL_KNOWLEDGE")
                    .put("evidenceIds", JSONArray())
                    .put(
                        "reason",
                        "일반 지식 판단 · 출처 미확인 · " +
                            c.optString("reason").removePrefix("일반 지식 판단 · 출처 미확인"),
                    )
            } else if (valid.isNotEmpty())
                c.put(
                    "basis",
                    if (
                        evidence.objects().any {
                            it.getString("id") in valid && it.optString("basis") == "WEB"
                        }
                    )
                        "WEB"
                    else "DOCUMENT",
                )
            else c.remove("basis")
        }
        return results
    }
}
