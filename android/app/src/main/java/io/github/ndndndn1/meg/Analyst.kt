package io.github.ndndndn1.meg

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Analyst(context: Context, val local: LocalLlm, val router: OpenRouter) {
    private val system =
        context.assets.open("system-prompt.txt").bufferedReader().use { it.readText() }
    @Volatile var localReady = false
    @Volatile var useRemote = false
    @Volatile var web = true
    @Volatile var model = ""

    fun analyze(text: String, documents: JSONArray): Pair<JSONArray, JSONArray> {
        val tokens = Regex("[가-힣a-zA-Z0-9]{2,}").findAll(text).map { it.value.lowercase() }.toList()
        var evidence =
            JSONArray(
                documents
                    .objects()
                    .map { e -> e to tokens.count { e.getString("text").lowercase().contains(it) } }
                    .filter { it.second > 0 }
                    .sortedByDescending { it.second }
                    .take(5)
                    .map { it.first }
            )
        fun payload() =
            JSONObject()
                .put("utterance", text)
                .put("evidence", evidence)
                .put("hasEvidenceScope", documents.length() > 0 || evidence.length() > 0)
                .toString()
        fun messages() =
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", payload()))
        var raw: String? = null
        var failure: String? = null
        if (useRemote && router.connected())
            try {
                check(model.isNotBlank()) { "모델을 선택하세요." }
                val body =
                    JSONObject()
                        .put("model", model)
                        .put("messages", messages())
                        .put("temperature", 0)
                        .put("max_tokens", 900)
                if (web)
                    body.put(
                        "plugins",
                        JSONArray().put(JSONObject().put("id", "web").put("max_results", 3)),
                    )
                var message =
                    router
                        .request("chat/completions", body)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                var added = false
                message.optJSONArray("annotations")?.objects()?.forEach { a ->
                    val c = a.optJSONObject("url_citation")
                    if (
                        c != null &&
                            c.optString("content").isNotBlank() &&
                            c.optString("url").startsWith("https://")
                    ) {
                        evidence.put(
                            JSONObject()
                                .put("id", id())
                                .put("text", c.getString("content"))
                                .put("title", c.optString("title"))
                                .put("source", c.getString("url"))
                                .put("version", "웹 검색")
                                .put("checkedAt", System.currentTimeMillis())
                        )
                        added = true
                    }
                }
                if (added) {
                    body.remove("plugins")
                    body.put("messages", messages())
                    message =
                        router
                            .request("chat/completions", body)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                }
                raw = message.getString("content")
            } catch (e: Exception) {
                failure = e.message
            }
        if (raw == null) {
            check(localReady) { failure ?: "로컬 모델을 준비하세요." }
            raw =
                local.generate(
                    "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n${payload()}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
                )
        }
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
                    (c.getString("status") == "UNSUPPORTED ASSERTION" &&
                        documents.length() == 0 &&
                        evidence.length() == 0)
            )
                c.put("status", "UNVERIFIABLE").put("reason", "확인 가능한 근거가 부족합니다.")
            if (failure != null) c.put("reason", "$failure · 로컬 검증: ${c.optString("reason")}")
        }
        return results to evidence
    }
}
