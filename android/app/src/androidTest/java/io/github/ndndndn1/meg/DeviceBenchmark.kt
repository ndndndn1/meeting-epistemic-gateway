package io.github.ndndndn1.meg

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceBenchmark {
    @Test
    fun localModelComparison() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelStore(context)
        val out = JSONArray()
        val cases =
            listOf(
                "이 장비의 최대 입력 전압은 24V입니다." to "VERIFIED",
                "이 장비의 최대 입력 전압은 48V입니다." to "CONTRADICTED",
                "제 생각에는 광고 때문인 것 같습니다." to "HYPOTHESIS",
                "지난 프로젝트에서 이 문제가 있었습니다." to "EXPERIENCE",
                "혹시 메모리 문제 아닐까요?" to "QUESTION",
            )
        for (which in listOf("llm-1.7b", "llm-4b")) {
            if (!File(models.path(which)).exists()) continue
            val llm = LocalLlm()
            val analyst = Analyst(context, llm, OpenRouter(context))
            val start = System.nanoTime()
            llm.load(models.path(which))
            analyst.localReady = true
            val audio =
                AudioEngine(models, { false }, {}, { _, _, _, _ -> }, { throw AssertionError(it) })
            audio.prepare()
            val loadMs = (System.nanoTime() - start) / 1000000
            val m = emptyMeeting()
            addEvidence(
                m,
                "제조사 매뉴얼 v1.0: 이 장비의 최대 입력 전압은 24V입니다. 48V 입력은 허용되지 않습니다.",
                "제조사 매뉴얼 v1.0",
            )
            for ((text, expected) in cases) {
                val t = System.nanoTime()
                try {
                    val r = analyst.analyze(text, m.getJSONArray("evidence"))
                    val verdict = r.first.getJSONObject(0).getString("status")
                    out.put(
                        JSONObject()
                            .put("model", which)
                            .put("expected", expected)
                            .put("actual", verdict)
                            .put("latencyMs", (System.nanoTime() - t) / 1000000)
                            .put("loadWithSpeechMs", loadMs)
                    )
                    Log.i(
                        "MEG",
                        "benchmark model=$which expected=$expected actual=$verdict ms=${(System.nanoTime()-t)/1000000}",
                    )
                } catch (e: Exception) {
                    out.put(
                        JSONObject()
                            .put("model", which)
                            .put("expected", expected)
                            .put("error", e.javaClass.simpleName)
                            .put("latencyMs", (System.nanoTime() - t) / 1000000)
                    )
                    Log.i("MEG", "benchmark model=$which failed=${e.javaClass.simpleName}")
                }
            }
            audio.release()
            llm.close()
        }
        File(context.filesDir, "benchmark.json")
            .writeText(
                JSONObject()
                    .put("kind", "synthetic-text-device-integration")
                    .put("representativeMeeting", false)
                    .put("results", out)
                    .toString(2)
            )
        assertTrue("models must be staged", out.length() > 0)
    }
}
