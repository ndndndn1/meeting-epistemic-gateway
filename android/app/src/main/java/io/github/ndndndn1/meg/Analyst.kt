package io.github.ndndndn1.meg

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Analyst(context: Context, val local: LocalLlm, val router: OpenRouter) {
    private val system =
        context.assets.open("system-prompt.txt").bufferedReader().use { it.readText() }
    @Volatile var localReady = false
    @Volatile var mode = ExecutionMode.AUTO
    val routing = AnalysisRouting()
    @Volatile var lastAttempts = JSONArray()
    @Volatile var lastWarning = ""
    @Volatile var web = true
    @Volatile var model = ""
    @Volatile var queueDepth = 0

    fun analyze(text: String, documents: JSONArray): Pair<JSONArray, JSONArray> {
        Calculation.analyze(text)?.let {
            return it
        }
        val bounded = DocumentIndex.bounded(text, documents, system)
        var evidence = bounded.first
        fun payload() =
            JSONObject()
                .put("utterance", text)
                .put("evidence", evidence)
                .put("hasEvidenceScope", evidence.length() > 0)
                .toString()
        fun messages() =
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", payload()))
        val bound = bounded.second
        val retrievalMs = bounded.third
        val selectedModel = model
        val selectedWeb = web
        val selectedMode = mode
        val attempts = JSONArray()
        lastAttempts = attempts
        lastWarning = ""
        fun cloud(): String {
            check(selectedModel.isNotBlank()) { "모델을 선택하세요." }
            val body =
                JSONObject()
                    .put("model", selectedModel)
                    .put("messages", messages())
                    .put("temperature", 0)
                    .put("max_tokens", 900)
            if (selectedWeb)
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
                            .put("basis", "WEB")
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
            return message.getString("content")
        }
        val localEvidence = JSONArray(evidence.toString())
        val raw =
            try {
                routing.run(
                    selectedMode,
                    router.connected() && selectedModel.isNotBlank(),
                    routing.predict(localReady, bound, queueDepth),
                    attempts,
                    local = {
                        check(localReady) { "로컬 모델을 준비하세요." }
                        evidence = JSONArray(localEvidence.toString())
                        check(bound <= 2800) { "로컬 입력 2,800토큰 한도 초과. 주장을 나눠 주세요." }
                        local.generate(
                            "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n${payload()}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
                        )
                    },
                    cancelLocal = { local.cancel() },
                    remote = { cloud() },
                    warning = { lastWarning = it },
                )
            } finally {
                attempts.objects().forEach {
                    it.put("inputTokenUpperBound", bound).put("retrievalMs", retrievalMs)
                }
            }
        val results = Verdicts.parse(raw, evidence)
        for (c in results.objects()) {
            c.put("attempts", JSONArray(attempts.toString()))
            if (lastWarning.isNotBlank()) c.put("reason", "$lastWarning · ${c.optString("reason")}")
        }
        return results to evidence
    }
}
