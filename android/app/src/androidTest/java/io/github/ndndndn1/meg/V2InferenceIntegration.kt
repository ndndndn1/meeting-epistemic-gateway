package io.github.ndndndn1.meg

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V2InferenceIntegration {
    @Test
    fun nativeModelCasesAndRealCancellation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val llm = LocalLlm()
        val analyst = Analyst(context, llm, OpenRouter(context))
        analyst.mode = ExecutionMode.LOCAL_ONLY
        try {
            llm.load(ModelStore(context).path("llm-1.7b"))
            analyst.localReady = true
            val m = emptyMeeting()
            addEvidence(m, "시험 장비의 최대 입력 전압은 24V입니다.", "고정 테스트 매뉴얼")
            for ((text, docs) in
                listOf(
                    "1+1은 2입니다" to JSONArray(),
                    "지구는 태양 주위를 공전합니다" to JSONArray(),
                    "시험 장비의 최대 입력 전압은 24V입니다" to m.getJSONArray("evidence"),
                )) {
                val start = System.nanoTime()
                try {
                    val results = analyst.analyze(text, docs).first
                    println(
                        "MEG_METRIC " +
                            JSONObject()
                                .put("case", text)
                                .put("elapsedMs", (System.nanoTime() - start) / 1000000)
                                .put(
                                    "statuses",
                                    JSONArray(results.objects().map { it.getString("status") }),
                                )
                                .put("attempts", analyst.lastAttempts)
                    )
                } catch (e: Exception) {
                    println(
                        "MEG_METRIC " +
                            JSONObject()
                                .put("case", text)
                                .put("elapsedMs", (System.nanoTime() - start) / 1000000)
                                .put("error", e.message)
                    )
                }
            }
            val r = AnalysisRouting()
            var cancelled = false
            var calls = 0
            val trace = JSONArray()
            try {
                val answer =
                    r.run(
                        ExecutionMode.AUTO,
                        true,
                        1,
                        trace,
                        local = {
                            llm.generate(
                                "<|im_start|>user\n한국어 문장으로 태양계에 관한 500개 문장을 쓰세요. /no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
                            )
                        },
                        cancelLocal = {
                            cancelled = true
                            llm.cancel()
                        },
                        remote = {
                            calls++
                            "fake-cloud"
                        },
                        warning = {},
                    )
                assertEquals("fake-cloud", answer)
                assertTrue(cancelled)
                assertEquals(1, calls)
                println(
                    "MEG_METRIC " +
                        JSONObject().put("actualNativeCancel", true).put("attempts", trace)
                )
            } finally {
                r.close()
            }
        } finally {
            analyst.routing.close()
            llm.cancel()
            llm.close()
        }
    }
}
